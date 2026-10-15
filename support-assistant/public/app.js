// Support Assistant frontend — vanilla JS, no dependencies.

const chatEl = document.getElementById("chat");
const emptyStateEl = document.getElementById("empty-state");
const inputEl = document.getElementById("input");
const profileEl = document.getElementById("profile");
const sendBtn = document.getElementById("send-btn");
const stopBtn = document.getElementById("stop-btn");

// Ровно 3 профиля: первые 3 пользователя из /api/crm/users; фолбэк на случай недоступности CRM.
const FALLBACK_PROFILES = ["ivan@example.com", "maria@example.com", "olga@example.com"];

let inFlight = null; // AbortController of the active request

// --- helpers ----------------------------------------------------------------

function escapeHtml(s) {
  return s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function fmtTime() {
  return new Date().toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
}

function isAtBottom() {
  return chatEl.scrollHeight - chatEl.scrollTop - chatEl.clientHeight < 80;
}

function scrollToBottom() {
  chatEl.scrollTop = chatEl.scrollHeight;
}

// Minimal XSS-safe markdown: escape first, then build HTML.
function renderMarkdown(raw) {
  const esc = escapeHtml(raw);
  const blocks = [];

  // fenced code blocks (closed, then unclosed — unclosed happens mid-stream)
  let text = esc.replace(/```([\w+-]*)\n([\s\S]*?)```/g, (m, lang, code) => {
    blocks.push({ lang, code: code.replace(/\n$/, "") });
    return `\u0000B${blocks.length - 1}\u0000`;
  });
  text = text.replace(/```([\w+-]*)\n([\s\S]*)$/g, (m, lang, code) => {
    blocks.push({ lang, code });
    return `\u0000B${blocks.length - 1}\u0000`;
  });

  // inline code, bold, autolinks
  text = text.replace(/`([^`\n]+)`/g, '<code class="inline">$1</code>');
  text = text.replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>");
  text = text.replace(/https?:\/\/[^\s<>"']+/g, (url) => {
    const cut = url.replace(/[.,;:!?)\]]+$/, "");
    const tail = url.slice(cut.length);
    return `<a href="${cut}" target="_blank" rel="noopener noreferrer">${cut}</a>${tail}`;
  });

  // block-level: headings, tables, lists (line-based; content is already escaped)
  const isDivider = (s) => {
    const t = s.trim();
    return t.length >= 3 && t.includes("|") && t.includes("-") && /^[|\s:\-]+$/.test(t);
  };
  const splitRow = (s) =>
    s.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());

  const lines = text.split("\n");
  const out = [];
  for (let i = 0; i < lines.length; ) {
    const line = lines[i];

    // heading #..######
    const h = line.match(/^(#{1,6})\s+(.+)$/);
    if (h) {
      const lvl = h[1].length;
      out.push(`<h${lvl}>${h[2]}</h${lvl}>`);
      i += 1;
      continue;
    }

    // table: pipe row followed by |---|---| divider
    if (/^\s*\|/.test(line) && i + 1 < lines.length && isDivider(lines[i + 1])) {
      const head = splitRow(line);
      let j = i + 2;
      const rows = [];
      while (j < lines.length && /^\s*\|/.test(lines[j]) && !isDivider(lines[j])) {
        rows.push(splitRow(lines[j]));
        j += 1;
      }
      const thead = `<thead><tr>${head.map((c) => `<th>${c}</th>`).join("")}</tr></thead>`;
      const tbody = rows
        .map((r) => `<tr>${head.map((_, k) => `<td>${r[k] || ""}</td>`).join("")}</tr>`)
        .join("");
      out.push(
        `<div class="md-table-wrap"><table class="md-table">${thead}<tbody>${tbody}</tbody></table></div>`
      );
      i = j;
      continue;
    }

    // unordered list
    if (/^[-*]\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^[-*]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^[-*]\s+/, ""));
        i += 1;
      }
      out.push(`<ul class="md-list">${items.map((t) => `<li>${t}</li>`).join("")}</ul>`);
      continue;
    }

    // ordered list
    if (/^\d{1,9}[.)]\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^\d{1,9}[.)]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\d{1,9}[.)]\s+/, ""));
        i += 1;
      }
      out.push(`<ol class="md-list">${items.map((t) => `<li>${t}</li>`).join("")}</ol>`);
      continue;
    }

    out.push(line + "<br>");
    i += 1;
  }
  text = out.join("");

  // restore code blocks
  text = text.replace(/\u0000B(\d+)\u0000/g, (m, i) => {
    const b = blocks[Number(i)];
    const label = b.lang ? escapeHtml(b.lang) : "код";
    return (
      `<div class="code-block"><div class="code-head"><span>${label}</span>` +
      `<button type="button" class="copy-btn">Копировать</button></div>` +
      `<pre><code>${b.code}</code></pre></div>`
    );
  });

  return text;
}

// --- bubbles ----------------------------------------------------------------

function hideEmptyState() {
  if (emptyStateEl) emptyStateEl.classList.add("hidden");
}

function addUserBubble(text) {
  hideEmptyState();
  const wrap = document.createElement("div");
  wrap.className = "msg user";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  const body = document.createElement("div");
  body.className = "bubble-text";
  body.innerHTML = escapeHtml(text).replace(/\n/g, "<br>");
  const time = document.createElement("span");
  time.className = "time";
  time.textContent = fmtTime();
  bubble.append(body, time);
  wrap.append(bubble);
  chatEl.append(wrap);
  scrollToBottom();
}

function addAssistantBubble() {
  hideEmptyState();
  const wrap = document.createElement("div");
  wrap.className = "msg assistant";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  const meta = document.createElement("div");
  meta.className = "bubble-meta";
  const body = document.createElement("div");
  body.className = "bubble-text";
  const time = document.createElement("span");
  time.className = "time";
  time.textContent = fmtTime();
  bubble.append(meta, body, time);
  wrap.append(bubble);
  chatEl.append(wrap);
  scrollToBottom();
  return { wrap, bubble, meta, body, time };
}

function renderAssistant(part, content, streaming) {
  const stick = isAtBottom();
  part.body.innerHTML = renderMarkdown(content) + (streaming ? '<span class="cursor" aria-hidden="true"></span>' : "");
  if (stick) scrollToBottom();
}

function renderError(part, message) {
  part.wrap.classList.add("error");
  part.body.innerHTML = escapeHtml(message);
  if (isAtBottom()) scrollToBottom();
}

// --- streaming --------------------------------------------------------------

async function readStream(res, onLine) {
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf("\n")) !== -1) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      let obj;
      try { obj = JSON.parse(line); } catch { continue; }
      onLine(obj);
    }
  }
}

// --- main flow --------------------------------------------------------------

async function sendMessage() {
  const text = inputEl.value.trim();
  if (!text || inFlight) return;

  inputEl.value = "";
  autoGrow();
  addUserBubble(text);

  const part = addAssistantBubble();
  setBusy(true);

  const controller = new AbortController();
  inFlight = controller;
  let answer = "";

  try {
    const res = await fetch("/api/ask", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ question: text, email: profileEl.value }),
      signal: controller.signal,
    });

    if (!res.ok) {
      let msg = `Ошибка сервера (${res.status})`;
      try {
        const data = await res.json();
        if (data && data.error) msg = data.error;
      } catch { /* keep default */ }
      throw new Error(msg);
    }

    await readStream(res, (obj) => {
      switch (obj.type) {
        case "delta":
          answer += obj.text;
          renderAssistant(part, answer, true);
          break;
        case "done":
          answer = obj.text;
          renderAssistant(part, answer, false);
          loadHistory(); // история на сервере уже обновлена — перерисовываем чат
          break;
        case "aborted":
          if (answer) renderAssistant(part, answer, false);
          break;
        case "error":
          renderError(part, obj.message || "Ошибка сервера");
          break;
      }
    });
  } catch (err) {
    if (controller.signal.aborted) {
      if (answer) renderAssistant(part, answer, false);
      else part.wrap.remove();
    } else {
      renderError(part, err.message);
    }
  } finally {
    inFlight = null;
    setBusy(false);
    inputEl.focus();
  }
}

// --- UI state ---------------------------------------------------------------

function setBusy(busy) {
  sendBtn.disabled = busy;
  sendBtn.classList.toggle("hidden", busy);
  stopBtn.classList.toggle("hidden", !busy);
}

function autoGrow() {
  inputEl.style.height = "auto";
  inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + "px";
}

// --- events -----------------------------------------------------------------

inputEl.addEventListener("input", autoGrow);
inputEl.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    if (!inFlight) sendMessage();
  }
});
sendBtn.addEventListener("click", sendMessage);
stopBtn.addEventListener("click", () => inFlight?.abort());

profileEl.addEventListener("change", () => {
  inFlight?.abort(); // активный ответ чужого профиля прекращаем
  loadHistory();
});

chatEl.addEventListener("click", async (e) => {
  const btn = e.target.closest(".copy-btn");
  if (!btn) return;
  const code = btn.closest(".code-block")?.querySelector("pre code");
  if (!code) return;
  try {
    await navigator.clipboard.writeText(code.textContent);
    btn.textContent = "Скопировано";
    setTimeout(() => { btn.textContent = "Копировать"; }, 1500);
  } catch {
    btn.textContent = "Не удалось";
    setTimeout(() => { btn.textContent = "Копировать"; }, 1500);
  }
});

document.querySelectorAll(".chip-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    inputEl.value = btn.dataset.prompt;
    sendMessage();
  });
});

async function loadProfiles() {
  let emails = FALLBACK_PROFILES;
  try {
    const r = await fetch("/api/crm/users");
    const d = await r.json();
    const list = (d.users || []).slice(0, 3).map((u) => u.email);
    if (list.length === 3) emails = list;
  } catch { /* оставляем fallback */ }
  profileEl.replaceChildren();
  for (const email of emails) {
    const o = document.createElement("option");
    o.value = email;
    o.textContent = email;
    profileEl.appendChild(o);
  }
}

async function loadHistory() {
  const email = profileEl.value;
  if (!email) return;
  try {
    const r = await fetch(`/api/history?email=${encodeURIComponent(email)}`);
    const d = await r.json();
    const msgs = Array.isArray(d.messages) ? d.messages : [];
    chatEl.querySelectorAll(".msg").forEach((m) => m.remove());
    if (msgs.length) {
      hideEmptyState();
      for (const m of msgs) {
        if (m.role === "user") addUserBubble(m.text);
        else {
          const part = addAssistantBubble();
          renderAssistant(part, m.text || "", false);
        }
      }
    } else {
      emptyStateEl?.classList.remove("hidden");
    }
    scrollToBottom();
  } catch { /* при ошибке оставляем текущий чат как есть */ }
}

async function init() {
  await loadProfiles();
  await loadHistory();
  autoGrow();
  inputEl.focus();
}

init();
