// CRM-админка: пользователи, тикеты (фильтры), создание тикета через /api/crm/*.
const state = { users: [], tickets: [] };

const $ = (id) => document.getElementById(id);

const STATUS_LABELS = { open: "открыт", resolved: "решён" };

function showError(msg) {
  const el = $("crm-error");
  el.textContent = msg;
  el.classList.remove("hidden");
}

function hideError() {
  $("crm-error").classList.add("hidden");
}

async function fetchJson(url, opts) {
  const res = await fetch(url, opts);
  let body = {};
  try {
    body = await res.json();
  } catch {}
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

function cell(text) {
  const td = document.createElement("td");
  td.textContent = text;
  return td;
}

function statusChip(value) {
  const td = document.createElement("td");
  const span = document.createElement("span");
  span.className = `st ${value}`;
  span.textContent = STATUS_LABELS[value] || value;
  td.appendChild(span);
  return td;
}

function userById(id) {
  return state.users.find((u) => u.id === id) || null;
}

function renderUsers() {
  const tbody = $("users-body");
  tbody.replaceChildren();
  for (const u of state.users) {
    const tr = document.createElement("tr");
    tr.append(
      cell(u.id),
      cell(u.name),
      cell(u.email),
      cell(u.plan),
      statusChip(u.status),
      cell(u.blockedReason || "—")
    );
    tbody.appendChild(tr);
  }
}

function renderTickets() {
  const tbody = $("tickets-body");
  tbody.replaceChildren();
  const status = $("ticket-status").value;
  const q = $("ticket-user").value.trim().toLowerCase();
  const shown = state.tickets.filter((t) => {
    if (status !== "all" && t.status !== status) return false;
    if (!q) return true;
    const u = userById(t.userId);
    return (
      t.userId.toLowerCase().includes(q) ||
      (u && (u.email.toLowerCase().includes(q) || u.name.toLowerCase().includes(q)))
    );
  });
  if (!shown.length) {
    const tr = document.createElement("tr");
    const td = cell("Тикеты не найдены.");
    td.colSpan = 7;
    td.className = "muted";
    tr.appendChild(td);
    tbody.appendChild(tr);
    return;
  }
  for (const t of shown) {
    const u = userById(t.userId);
    const tr = document.createElement("tr");
    tr.append(
      cell(t.id),
      cell(u ? `${u.email} (${u.id})` : t.userId),
      cell(t.subject),
      statusChip(t.status),
      cell(t.priority),
      cell(t.createdAt),
      cell(t.updatedAt)
    );
    tbody.appendChild(tr);
  }
}

function fillUserSelect() {
  const sel = $("ticket-user-id");
  sel.replaceChildren();
  for (const u of state.users) {
    const opt = document.createElement("option");
    opt.value = u.id;
    opt.textContent = `${u.id} — ${u.name} (${u.email})`;
    sel.appendChild(opt);
  }
}

async function loadUsers() {
  state.users = (await fetchJson("/api/crm/users")).users || [];
  renderUsers();
  fillUserSelect();
}

async function loadTickets() {
  state.tickets = (await fetchJson("/api/crm/tickets")).tickets || [];
  renderTickets();
}

async function init() {
  try {
    hideError();
    await Promise.all([loadUsers(), loadTickets()]);
  } catch (e) {
    showError(`CRM недоступна: ${e.message}`);
  }
}

$("ticket-status").addEventListener("change", renderTickets);
$("ticket-user").addEventListener("input", renderTickets);

$("ticket-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const msg = $("form-msg");
  const btn = $("ticket-submit");
  msg.classList.add("hidden");
  btn.disabled = true;
  try {
    await fetchJson("/api/crm/tickets", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        userId: $("ticket-user-id").value,
        subject: $("ticket-subject").value.trim(),
        priority: $("ticket-priority").value,
      }),
    });
    $("ticket-subject").value = "";
    msg.textContent = "Тикет создан.";
    msg.className = "ok-text";
    await loadTickets();
  } catch (e) {
    msg.textContent = e.message;
    msg.className = "error-text";
  } finally {
    btn.disabled = false;
    msg.classList.remove("hidden");
  }
});

init();
