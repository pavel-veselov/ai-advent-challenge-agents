// support-assistant — AI-ассистент поддержки пользователей.
// HTTP-сервер: статика public/ + API (/api/ask, /api/history, /api/status, /api/health, /api/crm/*).
import { createServer } from "node:http";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { config } from "./src/config.js";
import { buildIndex, search } from "./src/rag.js";
import { chatWithTools } from "./src/llm.js";
import { McpClient } from "./src/mcp-client.js";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(ROOT, "public");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".txt": "text/plain; charset=utf-8",
};

let mcp = null;
async function ensureMcp() {
  if (!mcp) {
    mcp = new McpClient();
    await mcp.connect();
    // Если процесс MCP умер — сбрасываем клиент, следующий вызов пересоздаст его.
    mcp.proc.on("exit", () => {
      mcp = null;
    });
  }
  return mcp;
}

function sendJson(res, status, payload, extraHeaders = {}) {
  if (res.writableEnded || res.destroyed) return;
  if (res.headersSent) {
    res.end();
    return;
  }
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", ...extraHeaders });
  res.end(JSON.stringify(payload));
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) {
        const e = new Error("too large");
        e.code = "TOO_LARGE";
        reject(e);
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

async function buildContext(question, email) {
  const results = await search(question);
  const ragBlock = results.length
    ? results.map((r, i) => `[${i + 1}] (${r.rel}) ${r.text}`).join("\n\n")
    : "Подходящих фрагментов базы знаний не найдено.";

  // RAG и CRM используются для ответа, но в стрим наружу не выдаются
  // (sources/tools-события убраны — пользователь видит только ответ).
  let crmBlock;
  let user = null;
  let client = null;
  if (!email) {
    crmBlock = "Email пользователя не указан — данных CRM нет. Попросите уточнить профиль.";
  } else {
    try {
      client = await ensureMcp();
      const userRes = await client.callTool("get_user", { email });
      // Резолвим пользователя по email — его id дальше подставляется во все
      // CRM-вызовы: чужие тикеты недоступны (изоляция на сервере).
      const lu = await client.callTool("list_users", {});
      const users = lu.data ?? JSON.parse(lu.text);
      user = users.find((u) => u.email.toLowerCase() === email.toLowerCase()) || null;
      let ticketsText;
      if (user) {
        const tickets = await client.callTool("search_tickets", { userId: user.id });
        ticketsText = tickets.text;
      } else {
        ticketsText = `Пользователь с email ${email} в CRM не найден.`;
      }
      crmBlock = `### Пользователь\n${userRes.text}\n\n### Тикеты\n${ticketsText}`;
    } catch (e) {
      crmBlock = `CRM недоступна: ${e.message}`;
    }
  }

  return { ragBlock, crmBlock, user, client };
}

// --- История чатов (data/history.json: {email: [{role, text}]}, последние 10) ---

const HISTORY_FILE = path.join(ROOT, "data", "history.json");
const HISTORY_LIMIT = 10;

async function readHistory() {
  try {
    return JSON.parse(await readFile(HISTORY_FILE, "utf8"));
  } catch {
    return {};
  }
}

// Read-modify-write; обрезка до последних 10 сообщений ПОСЛЕ добавления.
async function appendHistory(email, question, answer) {
  if (!email) return;
  try {
    const all = await readHistory();
    const arr = Array.isArray(all[email]) ? all[email] : [];
    arr.push({ role: "user", text: question }, { role: "assistant", text: answer });
    all[email] = arr.slice(-HISTORY_LIMIT);
    await writeFile(HISTORY_FILE, JSON.stringify(all, null, 2) + "\n", "utf8");
  } catch (e) {
    console.warn("[support-assistant] history write failed:", e.message);
  }
}

// --- Инструменты LLM (изоляция: всё от имени resolved-пользователя) ---

const LLM_TOOLS = [
  {
    type: "function",
    function: {
      name: "search_tickets",
      description: "Тикеты текущего пользователя; опционально фильтр по статусу (open | resolved | all)",
      parameters: {
        type: "object",
        properties: { status: { type: "string", enum: ["open", "resolved", "all"] } },
        additionalProperties: false,
      },
    },
  },
  {
    type: "function",
    function: {
      name: "get_ticket",
      description: "Тикет по ID (только собственные тикеты)",
      parameters: {
        type: "object",
        properties: { id: { type: "string", description: "ID тикета, например T-1023" } },
        required: ["id"],
        additionalProperties: false,
      },
    },
  },
  {
    type: "function",
    function: {
      name: "create_ticket",
      description: "Создать тикет для текущего пользователя (userId подставляется сервером)",
      parameters: {
        type: "object",
        properties: {
          subject: { type: "string", description: "Тема тикета из вопроса пользователя" },
          priority: { type: "string", enum: ["low", "normal", "high"] },
        },
        required: ["subject"],
        additionalProperties: false,
      },
    },
  },
];

function toolExecutorFor(client, user) {
  if (!client || !user) {
    return async () => "CRM недоступна: пользователь не определён.";
  }
  return async (name, args) => {
    if (name === "search_tickets") {
      const status = ["open", "resolved", "all"].includes(args.status) ? args.status : undefined;
      const call = status ? { userId: user.id, status } : { userId: user.id };
      const r = await client.callTool("search_tickets", call);
      return r.isError ? `Ошибка CRM: ${r.text}` : r.text;
    }
    if (name === "get_ticket") {
      const r = await client.callTool("get_ticket", { id: String(args.id || "").trim() });
      if (r.isError) return `Ошибка CRM: ${r.text}`;
      // Изоляция: тикет другого пользователя выглядит как «не найден».
      const t = r.data;
      if (!t || t.userId !== user.id) return "Тикет не найден среди ваших.";
      return r.text;
    }
    if (name === "create_ticket") {
      const subject = String(args.subject || "").trim().slice(0, 200);
      if (!subject) return "Ошибка: тема тикета не указана.";
      const priority = ["low", "normal", "high"].includes(args.priority) ? args.priority : "normal";
      // userId всегда от resolved-пользователя — LLM его не поставляет.
      const r = await client.callTool("create_ticket", { userId: user.id, subject, priority });
      return r.isError ? `Ошибка CRM: ${r.text}` : r.text;
    }
    return `Неизвестный инструмент: ${name}`;
  };
}

async function handleAsk(req, res) {
  let body;
  try {
    body = JSON.parse(await readBody(req, 100_000));
  } catch {
    return sendJson(res, 400, { error: "invalid body" });
  }
  const question = (body.question || "").trim();
  if (!question) return sendJson(res, 400, { error: "question required" });
  const email = typeof body.email === "string" ? body.email.trim() : "";

  res.writeHead(200, {
    "content-type": "application/x-ndjson",
    "cache-control": "no-cache",
    "x-accel-buffering": "no",
  });

  const emit = (obj) => {
    if (res.writableEnded || res.destroyed) return;
    res.write(JSON.stringify(obj) + "\n");
  };

  let abort = null;
  req.on("close", () => {
    if (abort) abort();
  });

  try {
    const { ragBlock, crmBlock, user, client } = await buildContext(question, email);

    const system =
      "Ты ассистент поддержки для КОНЕЧНОГО пользователя продукта. " +
      "Отвечай кратко и дружелюбно, без служебных деталей (не упоминай файлы faq/*.md, внутренние инструменты, скоринги). " +
      "Опирайся на данные CRM и базу знаний. " +
      "Если не знаешь ответа — честно скажи и спроси, создать ли тикет. " +
      "Если пользователь согласился — создай тикет с темой из его вопроса и сообщи ID.";

    const userBlock = ["## Данные пользователя и тикеты (CRM)", crmBlock, "## База знаний (RAG)", ragBlock, "## Вопрос", question].join("\n\n");

    // История профиля — как предыдущие ходы ДО текущего вопроса (модель видит
    // контекст, напр. «да, заведи тикет» после её же уточняющего вопроса).
    const hist = await readHistory();
    const history = (Array.isArray(hist[email]) ? hist[email] : [])
      .slice(-HISTORY_LIMIT)
      .map((h) => ({ role: h.role, content: h.text }));

    const messages = [
      { role: "system", content: system },
      ...history,
      { role: "user", content: userBlock },
    ];

    const controller = new AbortController();
    abort = () => controller.abort();

    // Цикл вызова инструментов: LLM → tool_calls → исполнение через MCP →
    // role:"tool" → повтор (максимум 4 итерации) → стрим финального ответа.
    const full = await chatWithTools({
      messages,
      tools: LLM_TOOLS,
      executeTool: toolExecutorFor(client, user),
      signal: controller.signal,
      onDelta: (d) => emit({ type: "delta", text: d }),
    });

    // История пишется ДО события done, чтобы клиентский refresh её уже увидел.
    await appendHistory(email, question, full);
    emit({ type: "done", text: full });
  } catch (e) {
    if (e && e.name === "AbortError") emit({ type: "aborted" });
    else emit({ type: "error", message: e.message });
  } finally {
    res.end();
  }
}

async function handleStatus() {
  let crmTools = [];
  try {
    const client = await ensureMcp();
    crmTools = (await client.listTools()).map((t) => t.name);
  } catch {}
  return { model: config.model, embedModel: config.embedModel, crmTools };
}

// --- CRM-админка (/crm) ---

const CRM_CACHE_HEADERS = { "cache-control": "no-cache" };

async function crmUsersPayload() {
  const client = await ensureMcp();
  const r = await client.callTool("list_users", {});
  if (r.isError) throw new Error(r.text);
  return { users: r.data ?? JSON.parse(r.text) };
}

async function crmTicketsPayload() {
  const client = await ensureMcp();
  const r = await client.callTool("search_tickets", { status: "all" });
  if (r.isError) throw new Error(r.text);
  return { tickets: r.data ?? [] };
}

async function handleCrmCreate(req, res) {
  let body;
  try {
    body = JSON.parse(await readBody(req, 100_000));
  } catch {
    return sendJson(res, 400, { error: "invalid body" });
  }
  const userId = typeof body.userId === "string" ? body.userId.trim() : "";
  const subject = typeof body.subject === "string" ? body.subject.trim() : "";
  const priority = typeof body.priority === "string" ? body.priority.trim() : "normal";
  if (!userId || !subject) return sendJson(res, 400, { error: "userId и subject обязательны" });
  try {
    const client = await ensureMcp();
    const { users } = await crmUsersPayload();
    if (!users.some((u) => u.id === userId)) {
      return sendJson(res, 400, { error: `неизвестный userId: ${userId}` });
    }
    const r = await client.callTool("create_ticket", { userId, subject, priority });
    if (r.isError) return sendJson(res, 500, { error: r.text });
    return sendJson(res, 200, { ok: true, ticket: r.data ?? null });
  } catch (e) {
    return sendJson(res, 500, { error: e.message });
  }
}

async function serveStatic(res, pathname) {
  const rel = pathname === "/" ? "/index.html" : pathname;
  let decoded;
  try {
    decoded = decodeURIComponent(rel);
  } catch {
    return sendJson(res, 400, { error: "bad path" });
  }
  if (decoded.includes("\0") || decoded.includes("..")) return sendJson(res, 404, { error: "not found" });
  const filePath = path.resolve(PUBLIC_DIR, `.${decoded}`);
  if (filePath !== PUBLIC_DIR && !filePath.startsWith(PUBLIC_DIR + path.sep)) return sendJson(res, 404, { error: "not found" });
  const ext = path.extname(filePath).toLowerCase();
  if (!MIME[ext]) return sendJson(res, 404, { error: "not found" });
  try {
    const data = await readFile(filePath);
    if (res.writableEnded || res.destroyed) return;
    res.writeHead(200, { "content-type": MIME[ext] });
    res.end(data);
  } catch {
    sendJson(res, 404, { error: "not found" });
  }
}

async function handle(req, res) {
  const url = new URL(req.url || "/", "http://localhost");
  const pathname = url.pathname;

  if (req.method === "GET" || req.method === "HEAD") {
    if (pathname === "/api/health") return sendJson(res, 200, { ok: true, model: config.model, embedModel: config.embedModel });
    if (pathname === "/api/status") return sendJson(res, 200, await handleStatus());
    if (pathname === "/api/history") {
      const email = url.searchParams.get("email") || "";
      if (!email) return sendJson(res, 400, { error: "email required" });
      const all = await readHistory();
      const messages = Array.isArray(all[email]) ? all[email].slice(-HISTORY_LIMIT) : [];
      return sendJson(res, 200, { messages }, CRM_CACHE_HEADERS);
    }
    if (pathname === "/api/crm/users") {
      try {
        return sendJson(res, 200, await crmUsersPayload(), CRM_CACHE_HEADERS);
      } catch (e) {
        return sendJson(res, 500, { error: e.message });
      }
    }
    if (pathname === "/api/crm/tickets") {
      try {
        return sendJson(res, 200, await crmTicketsPayload(), CRM_CACHE_HEADERS);
      } catch (e) {
        return sendJson(res, 500, { error: e.message });
      }
    }
    if (pathname === "/crm") return serveStatic(res, "/crm.html");
    return serveStatic(res, pathname);
  }
  if (req.method === "POST" && pathname === "/api/ask") return handleAsk(req, res);
  if (req.method === "POST" && pathname === "/api/crm/tickets") return handleCrmCreate(req, res);
  return sendJson(res, 404, { error: "not found" });
}

const server = createServer((req, res) => {
  handle(req, res).catch((e) => {
    console.error("[support-assistant] request failed:", e);
    sendJson(res, 500, { error: "internal error" });
  });
});

server.on("clientError", (err, socket) => {
  if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\n\r\n");
});

async function main() {
  // Listen first — the server must respond immediately even while the RAG
  // index is still being built (embeddings over network can take ~a minute).
  server.listen(config.port, () => {
    console.log(`[support-assistant] listening on http://127.0.0.1:${config.port} (llm: ${config.model}, embed: ${config.embedModel})`);
  });
  try {
    await buildIndex();
    console.log("[support-assistant] RAG index ready");
  } catch (e) {
    console.warn("[support-assistant] RAG index build failed:", e.message);
  }
}

main();
