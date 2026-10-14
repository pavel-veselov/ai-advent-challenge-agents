// support-assistant — AI-ассистент поддержки пользователей.
// HTTP-сервер: статика public/ + API (/api/ask, /api/status, /api/health).
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { config } from "./src/config.js";
import { buildIndex, search } from "./src/rag.js";
import { chatComplete } from "./src/llm.js";
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
  }
  return mcp;
}

function sendJson(res, status, payload) {
  if (res.writableEnded || res.destroyed) return;
  if (res.headersSent) {
    res.end();
    return;
  }
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
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
  const sources = [...new Set(results.map((r) => r.rel))];
  const ragBlock = results.length
    ? results.map((r, i) => `[${i + 1}] (${r.rel}) ${r.text}`).join("\n\n")
    : "Подходящих фрагментов базы знаний не найдено.";

  const toolsUsed = [];
  let crmBlock;
  if (!email) {
    crmBlock = "Email пользователя не указан — данных CRM нет. Попросите у пользователя email или ID тикета.";
  } else {
    try {
      const client = await ensureMcp();
      const user = await client.callTool("get_user", { email });
      toolsUsed.push({ tool: "get_user", result: user.text });
      const tickets = await client.callTool("search_tickets", { email });
      toolsUsed.push({ tool: "search_tickets", result: tickets.text });
      crmBlock = `### Пользователь\n${user.text}\n\n### Тикеты\n${tickets.text}`;
    } catch (e) {
      crmBlock = `CRM недоступна: ${e.message}`;
    }
  }

  return { results, sources, ragBlock, crmBlock, toolsUsed };
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
    const { results, ragBlock, crmBlock, toolsUsed } = await buildContext(question, email);
    emit({
      type: "sources",
      sources: results.map((r) => ({ rel: r.rel, score: Number(r.score.toFixed(3)) })),
    });
    if (toolsUsed.length) emit({ type: "tools", tools: toolsUsed });

    const system =
      "Ты — ассистент поддержки пользователей продукта. " +
      "Используй ДАННЫЕ ПОЛЬЗОВАТЕЛЯ И ТИКЕТОВ (CRM) и БАЗУ ЗНАНИЙ (RAG, FAQ), приложенные к вопросу. " +
      "Отвечай на русском, кратко и по делу, с форматированием markdown (заголовки, списки, таблицы где уместно). " +
      "Опирайся в первую очередь на данные тикета: назови ID тикета, причину проблемы и конкретные шаги решения. " +
      "Если данных тикета нет или вопрос не связан с ними — попроси у пользователя email или ID тикета. " +
      "Если ответа нет ни в CRM, ни в базе знаний — честно скажи об этом.";

    const userBlock = ["## Данные пользователя и тикеты (CRM)", crmBlock, "## База знаний (RAG)", ragBlock, "## Вопрос", question].join("\n\n");

    const messages = [
      { role: "system", content: system },
      { role: "user", content: userBlock },
    ];

    const controller = new AbortController();
    abort = () => controller.abort();
    let full = "";
    full = await chatComplete(messages, {
      signal: controller.signal,
      onDelta: (d) => emit({ type: "delta", text: d }),
    });
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
    return serveStatic(res, pathname);
  }
  if (req.method === "POST" && pathname === "/api/ask") return handleAsk(req, res);
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
