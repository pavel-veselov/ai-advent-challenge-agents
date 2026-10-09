// ollama-chat — zero-dependency HTTP server in front of a local Ollama.
// Serves the static UI from public/ and proxies POST /api/chat to Ollama's
// NDJSON streaming endpoint through a strict FIFO queue (Ollama generates
// sequentially, so exactly one upstream request is in flight at a time).

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(ROOT, "public");

const PORT = Number(process.env.PORT) || 8080;
const OLLAMA_URL = process.env.OLLAMA_URL || "http://127.0.0.1:11434";
const MODEL = process.env.MODEL || "qwen2.5-coder:3b";
const MAX_MESSAGES = Number(process.env.MAX_MESSAGES) || 10;
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES) || 1_000_000;

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

// --- FIFO queue: one request to Ollama at a time ----------------------------

const waiting = [];
let activeEntry = null;

function queueState() {
  return { active: activeEntry ? 1 : 0, waiting: waiting.length };
}

function pump() {
  if (activeEntry || waiting.length === 0) return;
  const entry = waiting.shift();
  activeEntry = entry;
  entry.active = true;
  runChat(entry)
    .catch((err) => console.error("[ollama-chat] chat handler failed:", err))
    .finally(() => {
      activeEntry = null;
      pump();
    });
}

async function runChat(entry) {
  const { res, messages, abort } = entry;
  try {
    // position at dequeue time: 0 — the request is about to be served
    res.setHeader("X-Queue-Position", "0");

    let upstream;
    try {
      upstream = await fetch(`${OLLAMA_URL}/api/chat`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ model: MODEL, messages, stream: true }),
        signal: abort.signal,
      });
    } catch (err) {
      if (abort.signal.aborted) return; // client is gone — nothing to report
      console.error("[ollama-chat] upstream fetch failed:", err.message);
      return sendJson(res, 502, { error: "Ollama is unreachable" });
    }

    if (!upstream.ok || !upstream.body) {
      await upstream.body?.cancel().catch(() => {});
      return sendJson(res, 502, { error: `Ollama responded with HTTP ${upstream.status}` });
    }

    res.writeHead(upstream.status, {
      "content-type": "application/x-ndjson",
      "cache-control": "no-cache",
      "x-accel-buffering": "no",
    });

    try {
      await pipeline(Readable.fromWeb(upstream.body), res);
    } catch {
      // client vanished mid-stream — stop pulling from Ollama
      abort.abort();
    }
  } finally {
    entry.finished = true;
  }
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

// --- request helpers ----------------------------------------------------------

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        const err = new Error("payload too large");
        err.code = "PAYLOAD_TOO_LARGE";
        reject(err);
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

// Returns a clean [{role, content}] array or null when the payload is invalid.
function validateMessages(payload) {
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) return null;
  if (!Array.isArray(payload.messages) || payload.messages.length === 0) return null;
  const clean = [];
  for (const m of payload.messages) {
    if (m === null || typeof m !== "object") return null;
    if (m.role !== "user" && m.role !== "assistant") return null;
    if (typeof m.content !== "string" || m.content.trim() === "") return null;
    clean.push({ role: m.role, content: m.content });
  }
  return clean;
}

async function handleChat(req, res) {
  let raw;
  try {
    raw = await readBody(req, MAX_BODY_BYTES);
  } catch (err) {
    if (err.code === "PAYLOAD_TOO_LARGE") return sendJson(res, 413, { error: "payload too large" });
    return sendJson(res, 400, { error: "failed to read request body" });
  }

  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    return sendJson(res, 400, { error: "invalid JSON" });
  }

  const messages = validateMessages(payload);
  if (!messages) {
    return sendJson(res, 400, {
      error: 'body must be {"messages":[{"role":"user"|"assistant","content":"string"}]}',
    });
  }

  const entry = {
    res,
    messages: messages.slice(-MAX_MESSAGES),
    active: false,
    finished: false,
    abort: new AbortController(),
  };

  // Idempotent: fires on req close and res close; the finished flag dedupes.
  const onClientGone = () => {
    if (entry.finished) return;
    if (entry.active) {
      entry.abort.abort(); // streaming — stop the upstream request
    } else {
      const i = waiting.indexOf(entry);
      if (i !== -1) waiting.splice(i, 1);
      entry.finished = true;
      if (!res.writableEnded) res.destroy();
    }
  };
  req.on("close", onClientGone);
  res.on("close", onClientGone);

  waiting.push(entry);
  pump();
}

async function handleHealth(res) {
  let ollamaUp = false;
  try {
    const r = await fetch(`${OLLAMA_URL}/api/tags`, { signal: AbortSignal.timeout(3000) });
    ollamaUp = r.ok;
  } catch {
    ollamaUp = false;
  }
  sendJson(res, 200, {
    status: ollamaUp ? "ok" : "degraded",
    model: MODEL,
    ollamaUp,
    queue: queueState(),
  });
}

async function serveStatic(res, pathname) {
  const rel = pathname === "/" ? "/index.html" : pathname;
  let decoded;
  try {
    decoded = decodeURIComponent(rel);
  } catch {
    return sendJson(res, 400, { error: "bad path" });
  }
  if (decoded.includes("\0") || decoded.includes("..")) {
    return sendJson(res, 404, { error: "not found" });
  }
  const filePath = path.resolve(PUBLIC_DIR, `.${decoded}`);
  if (filePath !== PUBLIC_DIR && !filePath.startsWith(PUBLIC_DIR + path.sep)) {
    return sendJson(res, 404, { error: "not found" });
  }
  const ext = path.extname(filePath).toLowerCase();
  if (!MIME[ext]) {
    return sendJson(res, 404, { error: "not found" });
  }
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
    if (pathname === "/api/health") return handleHealth(res);
    if (pathname === "/api/queue") return sendJson(res, 200, queueState());
    return serveStatic(res, pathname);
  }
  if (req.method === "POST" && pathname === "/api/chat") return handleChat(req, res);
  return sendJson(res, 404, { error: "not found" });
}

const server = createServer((req, res) => {
  const started = Date.now();
  const pathname = (req.url || "/").split("?")[0];
  let logged = false;
  const log = () => {
    if (logged) return;
    logged = true;
    console.log(
      `[ollama-chat] ${req.method} ${pathname} ${res.statusCode} ${Date.now() - started}ms queue=${queueState().waiting}`
    );
  };
  res.on("finish", log);
  res.on("close", log);

  handle(req, res).catch((err) => {
    console.error("[ollama-chat] request failed:", err);
    sendJson(res, 500, { error: "internal error" });
  });
});

server.on("clientError", (err, socket) => {
  if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\n\r\n");
});

server.listen(PORT, () => {
  console.log(
    `[ollama-chat] listening on http://127.0.0.1:${PORT} (ollama: ${OLLAMA_URL}, model: ${MODEL})`
  );
});
