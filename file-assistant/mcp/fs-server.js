// MCP (stdio) файловый сервер для file-assistant.
// JSON-RPC 2.0, newline-delimited JSON на stdin/stdout (как mcp/crm-server.js в support-assistant).
// Все пути жёстко ограничены рабочей областью (WORKSPACE_ROOT): лексическая проверка
// path.resolve + startsWith и realpath-проверка для существующих файлов.
import { createInterface } from "node:readline";
import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
// Корень рабочей области: из env или корень репозитория (fs-server лежит в file-assistant/mcp/).
const ROOT = path.resolve(process.env.WORKSPACE_ROOT || path.resolve(here, "..", ".."));

const MAX_FILE_BYTES = 2 * 1024 * 1024; // 2MB — чтение/запись крупнее запрещены
const LIST_CAP = 500; // максимум файлов в list_files
const SEARCH_CAP = 80; // максимум совпадений в search_files
const READ_DEFAULT_LINES = 400;
const READ_MAX_LINES = 1000;
const READ_MAX_BYTES = 48 * 1024; // 48KB на выдачу read_file
const DIFF_MAX_LINES = 120; // дифф обрезается до этого числа строк

const SKIP_DIRS = new Set([".git", "node_modules", "__pycache__", ".venv", "venv"]);

// --- Джейл путей -------------------------------------------------------------

function isWindows() {
  return process.platform === "win32";
}

function samePath(a, b) {
  return isWindows() ? a.toLowerCase() === b.toLowerCase() : a === b;
}

function insideRoot(abs) {
  const rootWithSep = ROOT.endsWith(path.sep) ? ROOT : ROOT + path.sep;
  return samePath(abs, ROOT) || (isWindows() ? abs.toLowerCase().startsWith(rootWithSep.toLowerCase()) : abs.startsWith(rootWithSep));
}

// Лексический джейл + запрет джанк-путей. Возвращает { abs, rel } (rel — с «/»).
function resolveInJail(rawPath) {
  if (typeof rawPath !== "string" || !rawPath.trim()) {
    throw new Error("path required: укажи путь относительно корня рабочей области");
  }
  const abs = path.resolve(ROOT, rawPath);
  if (!insideRoot(abs)) {
    throw new Error(`доступ запрещён: путь «${rawPath}» вне рабочей области (${ROOT})`);
  }
  const relNative = path.relative(ROOT, abs);
  const parts = relNative.split(path.sep).filter(Boolean);
  if (parts.some((p) => SKIP_DIRS.has(p) || p === ".git" || p === "node_modules")) {
    throw new Error(`доступ запрещён: «${parts.join("/")}» — служебная директория`);
  }
  const base = parts[parts.length - 1] || "";
  if (base === ".env" || base.startsWith(".env.")) {
    throw new Error("доступ запрещён: .env файлы нельзя читать или менять");
  }
  if (base.endsWith(".log")) {
    throw new Error("доступ запрещён: *.log файлы нельзя читать или менять");
  }
  const rel = parts.join("/");
  return { abs, rel };
}

// realpath-проверка для существующих путей (симлинки наружу — под запретом).
async function assertRealInJail(abs) {
  let real;
  try {
    real = await fs.realpath(abs);
  } catch {
    return; // не существует — проверять нечего
  }
  let rootReal = ROOT;
  try {
    rootReal = await fs.realpath(ROOT);
  } catch {}
  if (!insideRoot(real) || !insideRoot(rootReal)) {
    throw new Error("доступ запрещён: символическая ссылка ведёт вне рабочей области");
  }
}

// --- Хелперы -----------------------------------------------------------------

function splitLines(text) {
  const lines = text.split("\n");
  if (lines.length && lines[lines.length - 1] === "") lines.pop();
  return lines.map((l) => (l.endsWith("\r") ? l.slice(0, -1) : l));
}

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// Glob-lite: «*» → любая подстрока, иначе — case-insensitive подстрока.
function matchPattern(rel, pattern) {
  if (!pattern) return true;
  const p = String(pattern).replaceAll("\\", "/");
  if (p.includes("*")) {
    const rx = new RegExp("^" + p.split("*").map(escapeRegExp).join(".*") + "$", "i");
    return rx.test(rel);
  }
  return rel.toLowerCase().includes(p.toLowerCase());
}

function looksBinary(buf) {
  const probe = buf.subarray(0, 8000);
  return probe.includes(0);
}

async function walk(dir, out) {
  if (out.length >= LIST_CAP) return;
  let entries;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of entries) {
    if (out.length >= LIST_CAP) return;
    if (e.isSymbolicLink()) continue; // джейл важнее полноты списка
    const full = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name)) continue;
      await walk(full, out);
    } else if (e.isFile()) {
      const rel = path.relative(ROOT, full).split(path.sep).join("/");
      if (isDeniedRel(rel)) continue;
      out.push(rel);
    }
  }
}

function isDeniedRel(rel) {
  const parts = rel.split("/");
  const base = parts[parts.length - 1];
  if (base === ".env" || base.startsWith(".env.") || base.endsWith(".log")) return true;
  return parts.some((p) => SKIP_DIRS.has(p));
}

// --- Простые unified-диффы (без зависимостей, детерминированные) -------------

function diffLines(oldText, newText, rel, created) {
  const out = [];
  const newLines = splitLines(newText);
  if (created) {
    out.push("--- /dev/null", `+++ b/${rel}`, `@@ -0,0 +1,${newLines.length} @@`);
    for (const l of newLines) out.push("+" + l);
    return out;
  }
  const oldLines = splitLines(oldText);
  // Общий префикс/суффикс → один hunk с изменённой серединой (детерминированно).
  let start = 0;
  while (start < oldLines.length && start < newLines.length && oldLines[start] === newLines[start]) start++;
  let endOld = oldLines.length;
  let endNew = newLines.length;
  while (endOld > start && endNew > start && oldLines[endOld - 1] === newLines[endNew - 1]) {
    endOld--;
    endNew--;
  }
  const midOld = oldLines.slice(start, endOld);
  const midNew = newLines.slice(start, endNew);
  if (!midOld.length && !midNew.length) return [];
  out.push(`--- a/${rel}`, `+++ b/${rel}`);
  const oldHead = midOld.length ? start + 1 : start;
  const newHead = midNew.length ? start + 1 : start;
  out.push(`@@ -${oldHead},${midOld.length} +${newHead},${midNew.length} @@`);
  for (const l of oldLines.slice(Math.max(0, start - 3), start)) out.push(" " + l);
  for (const l of midOld) out.push("-" + l);
  for (const l of midNew) out.push("+" + l);
  for (const l of oldLines.slice(endOld, endOld + 3)) out.push(" " + l);
  return out;
}

function capDiff(diffLinesArr) {
  const LIMIT = 120;
  if (diffLinesArr.length <= LIMIT) return diffLinesArr.join("\n");
  return (
    diffLinesArr.slice(0, LIMIT).join("\n") +
    `\n… (дифф усечён: всего ${diffLinesArr.length} строк)`
  );
}

// --- Инструменты -------------------------------------------------------------

async function listFiles(args) {
  const pattern = typeof args.pattern === "string" ? args.pattern.trim() : "";
  const out = [];
  await walk(ROOT, out);
  const files = out.sort();
  const filtered = pattern ? files.filter((rel) => matchPattern(rel, pattern)) : files;
  const truncated = filtered.length > LIST_CAP;
  const list = truncated ? filtered.slice(0, LIST_CAP) : filtered;
  const text =
    (list.length ? list.join("\n") : "Файлов не найдено.") +
    (truncated ? `\n\n[список усечён до ${LIST_CAP} записей, всего ${filtered.length}]` : "");
  return { text, data: { count: list.length, truncated, files: list } };
}

async function readFileTool(args) {
  const { abs, rel } = resolveInJail(args.path);
  await assertRealInJail(abs);
  const stat = await fs.stat(abs).catch(() => null);
  if (!stat) throw new Error(`файл не найден: ${rel}`);
  if (stat.isDirectory()) throw new Error(`это директория, а не файл: ${rel}`);
  if (stat.size > MAX_FILE_BYTES) throw new Error(`файл слишком большой (${stat.size} байт > ${MAX_FILE_BYTES}): ${rel}`);
  const buf = await fs.readFile(abs);
  if (looksBinary(buf)) throw new Error(`похоже, файл бинарный: ${rel}`);
  const allLines = buf.toString("utf8").split("\n");
  const total = allLines.length;
  const offset = Math.max(1, Number(args.offset) || 1);
  const limit = Math.min(READ_MAX_LINES, Math.max(1, Number(args.limit) || READ_DEFAULT_LINES));
  const slice = allLines.slice(offset - 1, offset - 1 + limit);
  let text = slice.join("\n");
  if (Buffer.byteLength(text, "utf8") > READ_MAX_BYTES) {
    // Режем по байтам на границе строки.
    let end = 0;
    let acc = 0;
    for (let i = 0; i < slice.length; i++) {
      acc += Buffer.byteLength(slice[i], "utf8") + 1;
      if (acc > READ_MAX_BYTES) break;
      end = i + 1;
    }
    text = slice.slice(0, Math.max(end, 1)).join("\n") + "\n\n[обрезано по 48KB]";
  }
  const shownTo = offset - 1 + slice.length;
  const meta = `[${rel} · строки ${offset}–${offset - 1 + slice.length} из ${total}]`;
  const truncated = offset - 1 + slice.length < total || text.includes("[обрезано по 48KB]");
  return { text: meta + "\n" + text + (truncated && !text.includes("[обрезано по 48KB]") ? `\n\n[показано ${slice.length} строк из ${total} — есть продолжение]` : "") };
}

async function searchFiles(args) {
  const query = typeof args.query === "string" ? args.query.trim() : "";
  if (!query) throw new Error("query required: что искать (подстрока, без учёта регистра)");
  const glob = typeof args.glob === "string" ? args.glob.trim() : "";
  const needle = query.toLowerCase();
  const files = [];
  await walk(ROOT, files);
  const matches = [];
  for (const rel of files.sort()) {
    if (glob && !matchPattern(rel, glob)) continue;
    let buf;
    try {
      const abs = path.join(ROOT, rel);
      const stat = await fs.stat(abs);
      if (stat.size > MAX_FILE_BYTES) continue;
      buf = await fs.readFile(abs);
    } catch {
      continue;
    }
    if (looksBinary(buf)) continue;
    const lines = buf.toString("utf8").split("\n");
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (line.toLowerCase().includes(needle)) {
        const snippet = line.trim().slice(0, 200);
        matches.push(`${rel}:${i + 1}: ${snippet}`);
        if (matches.length >= SEARCH_CAP) break;
      }
    }
    if (matches.length >= SEARCH_CAP) break;
  }
  if (!matches.length) return { text: `Совпадений не найдено: «${query}»`, data: { matches: [], truncated: false } };
  const truncated = matches.length >= SEARCH_CAP;
  const text =
    matches.join("\n") +
    (truncated ? `\n\n[совпадения усечены до ${SEARCH_CAP} — уточни glob или запрос]` : "");
  return { text, data: { matches, truncated } };
}

async function writeFileTool(args) {
  const content = typeof args.content === "string" ? args.content : "";
  const { abs, rel } = resolveInJail(args.path);
  if (Buffer.byteLength(content, "utf8") > MAX_FILE_BYTES) {
    throw new Error(`контент слишком большой (> ${MAX_FILE_BYTES} байт): ${rel}`);
  }
  await assertRealInJail(abs);
  const exists = await fs
    .access(abs)
    .then(() => true)
    .catch(() => false);
  let created = !exists;
  let changed = true;
  let diff = "";
  // Детерминизм: новый файл без завершающего \n получает его.
  let finalContent = content;
  if (created && finalContent.length && !finalContent.endsWith("\n")) finalContent += "\n";
  if (!created) {
    const oldBuf = await fs.readFile(abs);
    const oldText = oldBuf.toString("utf8");
    if (oldText === finalContent) {
      changed = false;
      return {
        text: `Файл ${rel} без изменений (контент идентичен).`,
        data: { path: rel, created: false, changed: false, diff: "" },
      };
    }
    diff = capDiff(diffLines(oldText, finalContent, rel, false));
  } else {
    diff = capDiff(diffLines("", finalContent, rel, true));
  }
  // Родительская директория обязана уже существовать внутри джейла (создаём при необходимости).
  const dir = path.dirname(abs);
  await fs.mkdir(dir, { recursive: true });
  await fs.writeFile(abs, finalContent, "utf8");
  const action = created ? "создан" : "обновлён";
  const text = `Файл ${action}: ${rel}\n\n\`\`\`diff\n${diff}\n\`\`\``;
  return { text, data: { path: rel, created, changed, diff } };
}

const TOOLS = [
  {
    name: "list_files",
    description:
      "Рекурсивный список файлов рабочей области (относительные пути). Опциональный pattern: подстрока в пути или glob-lite («*.md», «src/*»). Служебные директории (.git, node_modules) пропускаются, максимум 500 файлов.",
    inputSchema: {
      type: "object",
      properties: {
        pattern: { type: "string", description: "Подстрока пути или glob-lite вида *.md, src/*" },
      },
      additionalProperties: false,
    },
  },
  {
    name: "read_file",
    description:
      "Прочитать текстовый файл: содержимое (по умолчанию до 400 строк / 48KB). offset — начальная строка (с 1), limit — число строк (макс 1000). Бинарные, .env, *.log, .git/, node_modules/ и файлы >2MB запрещены.",
    inputSchema: {
      type: "object",
      properties: {
        path: { type: "string", description: "Путь относительно корня рабочей области" },
        offset: { type: "number", description: "Номер начальной строки (с 1), по умолчанию 1" },
        limit: { type: "number", description: "Сколько строк читать, по умолчанию 400" },
      },
      required: ["path"],
      additionalProperties: false,
    },
  },
  {
    name: "search_files",
    description:
      "Поиск подстроки по всем текстовым файлам рабочей области (без учёта регистра). Возвращает совпадения в формате «путь:строка: фрагмент», максимум 80. glob опционально ограничивает файлы («*.js»).",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Искомая подстрока" },
        glob: { type: "string", description: "Фильтр файлов, например *.js" },
      },
      required: ["query"],
      additionalProperties: false,
    },
  },
  {
    name: "write_file",
    description:
      "Создать или перезаписать файл внутри рабочей области (UTF-8). Возвращает path, created и unified diff старое→новое (до 120 строк). Идентичный контент — changed:false. Вне рабочей области писать нельзя.",
    inputSchema: {
      type: "object",
      properties: {
        path: { type: "string", description: "Путь относительно корня рабочей области" },
        content: { type: "string", description: "Полное новое содержимое файла" },
      },
      required: ["path", "content"],
      additionalProperties: false,
    },
  },
];

async function callTool(name, args) {
  args = args || {};
  try {
    if (name === "list_files") return await listFiles(args);
    if (name === "read_file") return await readFileTool(args);
    if (name === "search_files") return await searchFiles(args);
    if (name === "write_file") return await writeFileTool(args);
    return { text: `неизвестный инструмент: ${name}`, isError: true };
  } catch (e) {
    return { text: `Ошибка инструмента «${name}»: ${e.message}`, isError: true };
  }
}

// --- JSON-RPC over stdio (как в crm-server.js) --------------------------------

const rl = createInterface({ input: process.stdin });

function respond(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

rl.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let req;
  try {
    req = JSON.parse(trimmed);
  } catch {
    return;
  }
  const { id, method, params } = req;

  if (method === "initialize") {
    respond({
      jsonrpc: "2.0",
      id,
      result: {
        protocolVersion: "2024-11-05",
        capabilities: { tools: {} },
        serverInfo: { name: "file-assistant-fs-mcp", version: "1.0.0" },
      },
    });
    return;
  }
  if (method === "notifications/initialized" || method === "initialized") return;
  if (method === "ping") {
    respond({ jsonrpc: "2.0", id, result: {} });
    return;
  }
  if (method === "tools/list") {
    respond({ jsonrpc: "2.0", id, result: { tools: TOOLS } });
    return;
  }
  if (method === "tools/call") {
    const { name, arguments: args } = params || {};
    callTool(name, args).then((r) => {
      const result = { content: [{ type: "text", text: r.text }], isError: Boolean(r.isError) };
      if (r.data !== undefined && r.data !== null) result.structuredContent = r.data;
      respond({ jsonrpc: "2.0", id, result });
    });
    return;
  }
  respond({ jsonrpc: "2.0", id, error: { code: -32601, message: `Метод не найден: ${method}` } });
});
