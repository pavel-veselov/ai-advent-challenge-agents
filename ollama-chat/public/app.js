// Ollama Chat frontend — vanilla JS, no dependencies.

const MAX_MESSAGES = 10; // keep in sync with the server default

const chatEl = document.getElementById("chat");
const emptyStateEl = document.getElementById("empty-state");
const inputEl = document.getElementById("input");
const sendBtn = document.getElementById("send-btn");
const stopBtn = document.getElementById("stop-btn");
const modelChip = document.getElementById("model-chip");
const queueBadge = document.getElementById("queue-badge");

const messages = []; // { role, content } — in-memory only, reset on reload
let inFlight = null; // AbortController of the active request
let queueTimer = null;

// --- helpers ------------------------------------------------------------------

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

// Minimal XSS-safe markdown: escape first, then build HTML on top of that.
function renderMarkdown(raw) {
  const esc = escapeHtml(raw);
  const blocks = [];

  // fenced code blocks first (closed ones, then an unclosed tail mid-stream)
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

  text = text.replace(/\n/g, "<br>");

  // restore code blocks with a header + copy button
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

// --- bubbles --------------------------------------------------------------------

function hideEmptyState() {
  emptyStateEl.classList.add("hidden");
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
}

function addAssistantBubble() {
  hideEmptyState();
  const wrap = document.createElement("div");
  wrap.className = "msg assistant";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  const body = document.createElement("div");
  body.className = "bubble-text";
  const time = document.createElement("span");
  time.className = "time";
  time.textContent = fmtTime();
  bubble.append(body, time);
  wrap.append(bubble);
  chatEl.append(wrap);
  return { wrap, bubble, body, time };
}

// `stick` is captured by the caller BEFORE the DOM changes.
function renderAssistant(part, content, streaming, stick) {
  part.body.innerHTML =
    renderMarkdown(content) + (streaming ? '<span class="cursor" aria-hidden="true"></span>' : "");
  if (stick) scrollToBottom();
}

function renderError(part, message) {
  const stick = isAtBottom();
  part.body.innerHTML = escapeHtml(message);
  part.wrap.classList.add("error");
  if (stick) scrollToBottom();
}

// --- queue badge ------------------------------------------------------------------

function startQueuePolling() {
  stopQueuePolling();
  const tick = async () => {
    try {
      const r = await fetch("/api/queue");
      const d = await r.json();
      queueBadge.textContent = `в очереди: ${d.waiting}`;
      queueBadge.classList.remove("hidden");
    } catch {
      /* transient — the next tick retries */
    }
  };
  tick();
  queueTimer = setInterval(tick, 1500);
}

function stopQueuePolling() {
  if (queueTimer) clearInterval(queueTimer);
  queueTimer = null;
  queueBadge.classList.add("hidden");
}

function setBusy(busy) {
  sendBtn.disabled = busy;
  sendBtn.classList.toggle("hidden", busy);
  stopBtn.classList.toggle("hidden", !busy);
}

// --- streaming ----------------------------------------------------------------------

async function readStream(res, onDelta) {
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
      try {
        obj = JSON.parse(line);
      } catch {
        continue;
      }
      if (obj.error) throw new Error(obj.error);
      if (obj.message && typeof obj.message.content === "string") onDelta(obj.message.content);
    }
  }
}

// --- send flow ------------------------------------------------------------------------

async function sendMessage() {
  const text = inputEl.value.trim();
  if (!text || inFlight) return;

  inputEl.value = "";
  autoGrow();

  const stick = isAtBottom();
  messages.push({ role: "user", content: text });
  addUserBubble(text);
  if (stick) scrollToBottom();

  const part = addAssistantBubble();
  setBusy(true);
  startQueuePolling();

  const controller = new AbortController();
  inFlight = controller;
  const assistant = { role: "assistant", content: "" };

  try {
    const res = await fetch("/api/chat", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ messages: messages.slice(-MAX_MESSAGES) }),
      signal: controller.signal,
    });

    if (res.status === 502) throw new TypeError("unreachable");
    if (!res.ok) {
      let msg = `Ошибка сервера (${res.status})`;
      try {
        const data = await res.json();
        if (data && data.error) msg = data.error;
      } catch {
        /* keep the default message */
      }
      throw new Error(msg);
    }

    await readStream(res, (delta) => {
      assistant.content += delta;
      renderAssistant(part, assistant.content, true, isAtBottom());
    });

    if (assistant.content === "") throw new Error("Пустой ответ от модели");
    messages.push(assistant);
    part.time.textContent = fmtTime();
    renderAssistant(part, assistant.content, false, isAtBottom());
  } catch (err) {
    if (controller.signal.aborted) {
      // user pressed stop — keep the partial answer if there is one
      if (assistant.content) {
        messages.push(assistant);
        renderAssistant(part, assistant.content, false, isAtBottom());
      } else {
        part.wrap.remove();
      }
    } else if (err instanceof TypeError) {
      renderError(part, "Ollama недоступна (ноутбук выключен?)");
    } else {
      renderError(part, err.message);
    }
  } finally {
    inFlight = null;
    setBusy(false);
    stopQueuePolling();
    inputEl.focus();
  }
}

// --- composer -------------------------------------------------------------------------

function autoGrow() {
  inputEl.style.height = "auto";
  inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + "px";
}

inputEl.addEventListener("input", autoGrow);
inputEl.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    if (!inFlight) sendMessage();
  }
});
sendBtn.addEventListener("click", sendMessage);
stopBtn.addEventListener("click", () => inFlight?.abort());

// Copy buttons are re-created on every stream tick — hence event delegation.
chatEl.addEventListener("click", async (e) => {
  const btn = e.target.closest(".copy-btn");
  if (!btn) return;
  const code = btn.closest(".code-block")?.querySelector("pre code");
  if (!code) return;
  try {
    await navigator.clipboard.writeText(code.textContent);
    btn.textContent = "Скопировано";
  } catch {
    btn.textContent = "Не удалось";
  }
  setTimeout(() => {
    btn.textContent = "Копировать";
  }, 1500);
});

document.querySelectorAll(".chip-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    inputEl.value = btn.dataset.prompt;
    sendMessage();
  });
});

// --- init -------------------------------------------------------------------------------

async function init() {
  try {
    const r = await fetch("/api/health");
    const d = await r.json();
    modelChip.textContent = d.model;
    if (!d.ollamaUp) {
      modelChip.classList.add("offline");
      modelChip.title = "Ollama недоступна";
    }
  } catch {
    modelChip.textContent = "офлайн";
    modelChip.classList.add("offline");
  }
  autoGrow();
  inputEl.focus();
}

init();
