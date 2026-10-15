// file-assistant — AI-ассистент, активно работающий с файлами проекта (день 34).
// HTTP-сервер: статика public/ + API (/api/ask NDJSON-стрим, /api/status, /api/health).
// Инструменты — через MCP (mcp/fs-server.js), все пути внутри рабочей области (WORKSPACE_ROOT).
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { config } from "./src/config.js";
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

// --- Инструменты LLM (OpenAI-style схемы поверх MCP fs-инструментов) ---------

const LLM_TOOLS = [
  {
    type: "function",
    function: {
      name: "list_files",
      description:
        "Рекурсивный список файлов проекта (относительные пути). pattern — подстрока пути или glob-lite («*.md», «src/*»).",
      parameters: {
        type: "object",
        properties: { pattern: { type: "string", description: "Подстрока или glob-lite фильтр" } },
        additionalProperties: false,
      },
    },
  },
  {
    type: "function",
    function: {
      name: "read_file",
      description:
        "Прочитать текстовый файл (по умолчанию до 400 строк / 48KB). offset — строка начала (с 1), limit — число строк.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "Путь относительно корня рабочей области" },
          offset: { type: "number", description: "Начальная строка (с 1)" },
          limit: { type: "number", description: "Сколько строк читать" },
        },
        required: ["path"],
        additionalProperties: false,
      },
    },
  },
  {
    type: "function",
    function: {
      name: "search_files",
      description:
        "Поиск подстроки по всем текстовым файлам (без учёта регистра) → «путь:строка: фрагмент». glob опционально фильтрует файлы.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Искомая подстрока" },
          glob: { type: "string", description: "Фильтр файлов, например *.js" },
        },
        required: ["query"],
        additionalProperties: false,
      },
    },
  },
  {
    type: "function",
    function: {
      name: "write_file",
      description:
        "Создать/перезаписать файл внутри проекта. Вернёт path, created и unified diff. Идентичный контент — changed:false.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "Путь относительно корня рабочей области" },
          content: { type: "string", description: "Полное новое содержимое файла" },
        },
        required: ["path", "content"],
        additionalProperties: false,
      },
    },
  },
];

const KNOWN_TOOLS = new Set(LLM_TOOLS.map((t) => t.function.name));

// Ошибки MCP превращаются в текст для контекста — запрос не падает (500 не отдаём).
function toolExecutorFor(client, emit) {
  return async (name, args) => {
    if (!KNOWN_TOOLS.has(name)) return `Неизвестный инструмент: ${name}`;
    if (!client) return "Файловый MCP-сервер недоступен.";
    let r;
    try {
      r = await client.callTool(name, args);
    } catch (e) {
      return `Ошибка инструмента «${name}»: ${e.message}`;
    }
    // file_change летит в стрим сразу после фактической записи файла.
    if (name === "write_file" && r.data && r.data.changed) {
      emit({
        type: "file_change",
        path: r.data.path,
        diff: r.data.diff || "",
        created: Boolean(r.data.created),
      });
    }
    return r.isError ? `Ошибка инструмента: ${r.text}` : r.text;
  };
}

// --- /api/ask ----------------------------------------------------------------

const SYSTEM_PROMPT = [
  "Ты — файловый AI-ассистент проекта (File Assistant). Ты активно работаешь с файлами рабочей области:",
  "читаешь, ищешь по файлам, анализируешь и создаёшь/меняешь файлы через инструменты list_files, read_file, search_files, write_file.",
  "",
  "Цели приходят на уровне намерения («найди все места, где используется X», «обнови README из кода»), а не пошаговые инструкции.",
  "Сам решай, какие файлы посмотреть, где искать и когда звать инструменты; действуй инициативно: сначала поиск/чтение, потом вывод или запись.",
  "",
  "Правила:",
  "- Все пути указывай относительно корня рабочей области. Писать можно только внутри проекта; цели с записью вне проекта — отклоняй, вежливо объяснив почему.",
  "- Опирайся на реальное содержимое файлов, а не на догадки: сначала search_files/read_file, потом ответ или write_file.",
  "- write_file отправляет ПОЛНОЕ новое содержимое файла (перезапись целиком).",
  "- Финальный ответ — краткий отчёт: где что находится (файл:строка), что изменено (файл + короткое описание диффа). Пиши списками, без сырых дампов инструментов.",
  "- Не выдумывай файлы и строки: если ничего не нашлось — так и скажи.",
  "- Отвечай на языке пользователя (обычно русский).",
].join("\n");

async function handleAsk(req, res) {
  let body;
  try {
    body = JSON.parse(await readBody(req, 200_000));
  } catch {
    return sendJson(res, 400, { error: "invalid body" });
  }
  const question = (body.question || "").trim();
  if (!question) return sendJson(res, 400, { error: "question required" });

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
    // MCP поднимаем лениво; недоступность не роняет запрос — инструменты вернут текст ошибки.
    let client = null;
    try {
      client = await ensureMcp();
    } catch (e) {
      console.warn("[file-assistant] MCP unavailable:", e.message);
    }

    const controller = new AbortController();
    abort = () => controller.abort();

    const messages = [
      { role: "system", content: SYSTEM_PROMPT },
      { role: "user", content: question },
    ];

    const full = await chatWithTools({
      messages,
      tools: LLM_TOOLS,
      executeTool: toolExecutorFor(client, emit),
      signal: controller.signal,
      onDelta: (d) => emit({ type: "delta", text: d }),
      maxIterations: 6,
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
  let fsTools = [];
  let workspaceRoot = config.workspaceRoot;
  try {
    const client = await ensureMcp();
    fsTools = (await client.listTools()).map((t) => t.name);
  } catch {}
  return { model: config.model, workspaceRoot, fsTools };
}

// --- статика (как в support-assistant) ---------------------------------------

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
    if (pathname === "/api/health") return sendJson(res, 200, { ok: true, model: config.model });
    if (pathname === "/api/status") return sendJson(res, 200, await handleStatus());
    return serveStatic(res, pathname);
  }
  if (req.method === "POST" && pathname === "/api/ask") return handleAsk(req, res);
  return sendJson(res, 404, { error: "not found" });
}

const server = createServer((req, res) => {
  handle(req, res).catch((e) => {
    console.error("[file-assistant] request failed:", e);
    sendJson(res, 500, { error: "internal error" });
  });
});

server.on("clientError", (err, socket) => {
  if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\n\r\n");
});

server.listen(config.port, () => {
  console.log(`[file-assistant] listening on http://127.0.0.1:${config.port} (llm: ${config.model}, workspace: ${config.workspaceRoot})`);
});
