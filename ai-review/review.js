// AI Code Review (день 32): пайплайн ревью PR.
// Zero deps: Node 20+ (встроенный fetch). Два режима:
//   CI:    GITHUB_TOKEN + PR_NUMBER + GITHUB_REPOSITORY -> diff из GitHub API -> ревью -> PR-комментарий
//          (существующий комментарий бота обновляется, спама нет)
//   Local: node --env-file=<.env> review.js --diff <file> -> ревью в stdout (для тестов)
// LLM и эмбеддинги — GPUStack (OpenAI-совместимый API), как в dev-assistant.

import { readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";

// ---------- конфиг ----------
const env = process.env;
const CFG = {
  baseUrl: (env.LLM_BASE_URL || "https://gpustack.data.lmru.tech").replace(/\/+$/, ""),
  apiKey: env.LLM_API_KEY || "",
  model: env.LLM_MODEL || "glm-5.3-flash",
  embedModel: env.EMBED_MODEL || "qwen3-vl-embedding-8b",
  repo: env.GITHUB_REPOSITORY || "", // "owner/name"
  prNumber: Number(env.PR_NUMBER || 0),
  token: env.GITHUB_TOKEN || "",
  api: (env.GITHUB_API_URL || "https://api.github.com").replace(/\/+$/, ""),
  maxContext: Number(env.MAX_CONTEXT || 8),
  root: process.cwd(),
  maxFiles: 25,        // файлов в diff для промпта
  maxPatchChars: 4000, // обрезка патча одного файла
  maxDiffChars: 40000, // обрезка всего diff
  maxCorpusFiles: 250, // максимум файлов в RAG-корпусе
  maxChunks: 2000,     // максимум чанков для эмбеддингов
};
const MARKER = "<!-- ai-review-bot -->";
const SKIP_DIRS = new Set([
  "node_modules", ".git", ".omo", ".playwright-mcp", "dist", "build", "out",
  "frames", "coverage", "target", ".gradle", ".idea", "__pycache__",
]);
const DOC_EXT = new Set([".md"]);
const CODE_EXT = new Set([
  ".js", ".mjs", ".ts", ".tsx", ".jsx", ".java", ".kt", ".py", ".go", ".rb",
  ".php", ".cs", ".c", ".cpp", ".h", ".sh", ".sql", ".html", ".css", ".yml", ".yaml",
]);
const BINARY_EXT = new Set([
  ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".pdf", ".zip",
  ".jar", ".class", ".woff", ".woff2", ".ttf", ".mp4", ".mp3", ".lock", ".env", ".log",
]);

const log = (step, msg = "") => console.log(`[ai-review] ${step}${msg ? ": " + msg : ""}`);
class FatalError extends Error {}
const die = (msg) => { throw new FatalError(msg); };

// ---------- GitHub ----------
function ghHeaders() {
  return {
    authorization: `Bearer ${CFG.token}`,
    accept: "application/vnd.github+json",
    "user-agent": "ai-review-bot",
    "x-github-api-version": "2022-11-28",
  };
}

async function fetchPrFiles() {
  const url = `${CFG.api}/repos/${CFG.repo}/pulls/${CFG.prNumber}/files?per_page=100`;
  const res = await fetch(url, { headers: ghHeaders(), signal: AbortSignal.timeout(30000) });
  if (!res.ok) throw new Error(`GitHub API ${res.status}: ${(await res.text()).slice(0, 300)}`);
  return res.json();
}

async function upsertPrComment(body) {
  const listUrl = `${CFG.api}/repos/${CFG.repo}/issues/${CFG.prNumber}/comments?per_page=100`;
  const listRes = await fetch(listUrl, { headers: ghHeaders(), signal: AbortSignal.timeout(30000) });
  if (!listRes.ok) throw new Error(`GitHub API ${listRes.status} (list comments)`);
  const list = await listRes.json();
  const prev = Array.isArray(list) ? list.find((c) => c.body && c.body.includes(MARKER)) : null;

  const url = prev
    ? `${CFG.api}/repos/${CFG.repo}/issues/comments/${prev.id}`
    : `${CFG.api}/repos/${CFG.repo}/issues/${CFG.prNumber}/comments`;
  const res = await fetch(url, {
    method: prev ? "PATCH" : "POST",
    headers: { ...ghHeaders(), "content-type": "application/json" },
    body: JSON.stringify({ body }),
    signal: AbortSignal.timeout(30000),
  });
  if (!res.ok) throw new Error(`GitHub API ${res.status} (${prev ? "PATCH" : "POST"} comment)`);
  return prev ? "обновлён" : "создан";
}

// ---------- diff ----------
function buildDiff(files) {
  const parts = [];
  let total = 0;
  for (const f of files) {
    if (parts.length >= CFG.maxFiles) { parts.push(`### Ещё ${files.length - parts.length} файлов — не показано`); break; }
    if (BINARY_EXT.has(path.extname(f.filename).toLowerCase())) continue;
    if (f.status === "removed") { parts.push(`### ФАЙЛ УДАЛЁН: ${f.filename}`); continue; }
    let patch = f.patch || "(патч недоступен — файл слишком большой или бинарный)";
    if (patch.length > CFG.maxPatchChars) patch = patch.slice(0, CFG.maxPatchChars) + "\n… (усечено)";
    const head = `### ФАЙЛ: ${f.filename} (+${f.additions}/-${f.deletions}, ${f.status})`;
    const block = `${head}\n\`\`\`diff\n${patch}\n\`\`\``;
    if (total + block.length > CFG.maxDiffChars) { parts.push("### (diff усечён по лимиту размера)"); break; }
    parts.push(block);
    total += block.length;
  }
  return parts.join("\n\n");
}

async function loadLocalDiff(file) {
  if (!file) die("локальный режим: укажите --diff <файл> (unified diff)");
  // срезаем BOM, который пишут PowerShell Out-File / Set-Content (UTF-8 BOM)
  return (await readFile(file, "utf8")).replace(/^\uFEFF/, "");
}

// ---------- RAG: корпус (доки + код) -> чанки -> эмбеддинги -> косинус ----------
async function walk(dir, rel, acc) {
  if (acc.length >= CFG.maxCorpusFiles) return acc;
  let entries;
  try { entries = await readdir(dir, { withFileTypes: true }); } catch { return acc; }
  for (const e of entries) {
    if (acc.length >= CFG.maxCorpusFiles) return acc;
    if (e.name.startsWith(".") && e.isDirectory()) continue; // скрытые каталоги целиком
    if (SKIP_DIRS.has(e.name)) continue;
    const abs = path.join(dir, e.name);
    const relPath = rel ? `${rel}/${e.name}` : e.name;
    if (e.isDirectory()) {
      await walk(abs, relPath, acc);
    } else if (e.isFile()) {
      const ext = path.extname(e.name).toLowerCase();
      const isDoc = DOC_EXT.has(ext) || /contract|openapi|swagger/i.test(e.name);
      const isCode = CODE_EXT.has(ext) && !/package-lock/i.test(e.name);
      if (isDoc || isCode) acc.push({ abs, rel: relPath.replaceAll("\\", "/"), isDoc });
    }
  }
  return acc;
}

function chunkText(text, size = 900, overlap = 120) {
  const clean = text.replace(/\r\n/g, "\n").trim();
  if (!clean) return [];
  if (clean.length <= size) return [clean];
  const chunks = [];
  let i = 0;
  while (i < clean.length) {
    let end = i + size;
    if (end < clean.length) {
      const nl = clean.lastIndexOf("\n", end);
      if (nl > i + size / 2) end = nl;
    }
    chunks.push(clean.slice(i, end));
    i = Math.max(end - overlap, i + 1);
  }
  return chunks.filter((c) => c.trim());
}

async function embed(texts) {
  const out = [];
  for (let i = 0; i < texts.length; i += 16) {
    const res = await fetch(`${CFG.baseUrl}/v1/embeddings`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${CFG.apiKey}` },
      body: JSON.stringify({ model: CFG.embedModel, input: texts.slice(i, i + 16) }),
      signal: AbortSignal.timeout(120000),
    });
    if (!res.ok) throw new Error(`embeddings ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const data = await res.json();
    for (const d of data.data) out.push(d.embedding);
  }
  return out;
}

function cosine(a, b) {
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
  const den = Math.sqrt(na * nb);
  return den ? dot / den : 0;
}

// запросы к RAG: имя файла + начало патча — так поиск находит и доки по теме, и соседний код
async function ragContext(fileInfos) {
  log("RAG", "сбор корпуса (доки + код)…");
  const files = await walk(CFG.root, "", []);
  const entries = [];
  for (const f of files) {
    const st = await stat(f.abs).catch(() => null);
    if (!st || st.size > 100 * 1024) continue;
    const raw = await readFile(f.abs, "utf8").catch(() => "");
    for (const c of chunkText(raw)) {
      entries.push({ id: entries.length, rel: f.rel, text: c });
      if (entries.length >= CFG.maxChunks) break;
    }
    if (entries.length >= CFG.maxChunks) break;
  }
  if (!entries.length) return { context: "(корпус пуст — RAG недоступен)", chunks: 0 };
  log("RAG", `корпус: ${files.length} файлов -> ${entries.length} чанков, эмбеддинги…`);
  const vectors = await embed(entries.map((e) => e.text));

  const queries = fileInfos
    .slice(0, CFG.maxFiles)
    .map((f) => `${f.name}\n${(f.patch || "").slice(0, 300)}`)
    .filter((q) => q.trim().length > 3);
  if (!queries.length) return { context: "(не найден)", chunks: entries.length };
  const qvecs = await embed(queries);

  const best = new Map(); // id -> best score
  for (const qv of qvecs) {
    entries.forEach((e, i) => {
      const s = cosine(qv, vectors[i]);
      if (!best.has(e.id) || best.get(e.id).score < s) best.set(e.id, { score: s });
    });
  }
  const top = [...best.entries()]
    .map(([id, { score }]) => ({ ...entries[id], score }))
    .sort((a, b) => b.score - a.score)
    .slice(0, CFG.maxContext);

  log("RAG", `топ-${top.length}: ${top.map((t) => `${t.rel}(${t.score.toFixed(2)})`).join(", ")}`);
  const context = top
    .map((t) => `### ${t.rel} (релевантность ${t.score.toFixed(2)})\n\`\`\`\n${t.text.slice(0, 600)}\n\`\`\``)
    .join("\n\n");
  return { context, chunks: entries.length };
}

// ---------- LLM ----------
const SYSTEM = `Ты — внимательный code reviewer. Тебе дают diff пулл-реквеста (ханки пронумерованы [H1], [H2], … — проанализируй КАЖДЫЙ, ни один не пропускай) и RAG-контекст (документация и код проекта).
Методика: пройди по каждому ханку построчно по чек-листу:
- границы циклов и off-by-one, смену условия/счётчика;
- сломанная или ослабленная обработка ошибок (какие коды/исключения больше не обрабатываются);
- null/undefined, порядок аргументов, перепутанные переменные;
- поведение при пустых входных данных и на границах;
- расхождение с соглашениями проекта из RAG-контекста.
Правила:
- Ищи конкретные проблемы в diff, а не общие слова. Не выдумывай то, чего нет в diff или контексте.
- Ссылайся на файл (пути из diff). Если проблема теоретическая — помечай «возможно».
- Отвечай на русском, только markdown, строго в формате ниже и ничего лишнего.

## 🐛 Потенциальные баги
- (пункт или «Не найдено.»)

## 🏗 Архитектурные проблемы
- (пункт или «Не найдено.»)

## 💡 Рекомендации
- (пункт или «Не найдено.»)`;

// делим diff на пронумерованные ханки — так LLM разбирает каждый, а не поток строк
function buildHunksText(fileInfos, maxChars) {
  const parts = [];
  let n = 0, total = 0;
  for (const f of fileInfos) {
    const hunks = f.patch.match(/^@@[^\n]*(?:\n(?!@@)[^\n]*)*/gm) || [];
    if (!hunks.length) { parts.push(`(файл без диффа: ${f.name})`); continue; }
    for (const h of hunks) {
      if (total > maxChars) { parts.push(`(дальнейшие ханки усечены по лимиту)`); n = -1; break; }
      n += 1;
      const block = `[H${n}] ${f.name}\n\`\`\`diff\n${h.trimEnd()}\n\`\`\``;
      total += block.length;
      parts.push(block);
    }
    if (n === -1) break;
  }
  return { text: parts.join("\n\n"), count: n < 0 ? "?" : n };
}

async function callLlm(hunksText, context) {
  const user = `## Diff пулл-реквеста (ханки пронумерованы)\n${hunksText}\n\n## RAG-контекст проекта (документация и код)\n${context}\n\nПроведи ревью строго по формату. Разбери каждый ханк.`;
  const res = await fetch(`${CFG.baseUrl}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${CFG.apiKey}` },
    body: JSON.stringify({
      model: CFG.model,
      messages: [
        { role: "system", content: SYSTEM },
        { role: "user", content: user },
      ],
      stream: false,
      temperature: 0.2,
    }),
    signal: AbortSignal.timeout(300000),
  });
  if (!res.ok) throw new Error(`GPUStack ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  const text = data.choices?.[0]?.message?.content;
  if (!text) throw new Error("LLM вернула пустой ответ");
  return text.trim();
}

// ---------- форматирование ----------
function formatComment(review, { files, chunks, secs }) {
  const footer = `\n\n---\n*🤖 Модель: \`${CFG.model}\` · эмбеддинги: \`${CFG.embedModel}\` · RAG: ${chunks} чанков · файлов в diff: ${files} · ${secs} с*`;
  return `${MARKER}\n## 🤖 AI Code Review\n\n${review}${footer}`;
}

// ---------- main ----------
async function main() {
const t0 = Date.now();
const isCI = Boolean(CFG.token && CFG.prNumber && CFG.repo);

if (!CFG.apiKey) die("LLM_API_KEY не задан (нужен доступ к GPUStack)");

let diffText;
let fileInfos = [];
if (isCI) {
  log("CI", `${CFG.repo} PR#${CFG.prNumber} — получаю изменённые файлы…`);
  let prFiles;
  try { prFiles = await fetchPrFiles(); } catch (err) { die(`GitHub API: ${err.message}`); }
  diffText = buildDiff(prFiles);
  fileInfos = prFiles.map((f) => ({ name: f.filename, patch: f.patch || "" }));
} else {
  const diffIdx = process.argv.indexOf("--diff");
  const diffArg = diffIdx >= 0 ? process.argv[diffIdx + 1] : undefined;
  diffText = await loadLocalDiff(diffArg);
  // имена файлов и их патчи из unified diff (для RAG-запросов)
  const blocks = diffText.split(/^diff --git /m).slice(1);
  fileInfos = blocks.map((b) => {
    const name = (b.match(/ b\/(.+)/) || [])[1] || "unknown";
    return { name, patch: b };
  });
}

if (!diffText.trim()) { log("готово", "нет изменений для ревью"); process.exit(0); }
log("diff", `${fileInfos.length} файлов, ${diffText.length} симв.`);

const { context, chunks } = await ragContext(fileInfos);
const hunks = buildHunksText(fileInfos, CFG.maxDiffChars);
log("LLM", `ревью через ${CFG.model} (${hunks.count} ханков)…`);
const review = await callLlm(hunks.text, context);

const stats = { files: fileInfos.length, chunks, secs: Math.round((Date.now() - t0) / 1000) };
const body = formatComment(review, stats);

if (isCI) {
  const how = await upsertPrComment(body);
  log("готово", `комментарий ${how} в PR#${CFG.prNumber} за ${stats.secs} с`);
} else {
  console.log("\n" + body + "\n");
  log("готово", `локальный прогон за ${stats.secs} с`);
}
}

main().catch((err) => {
  if (err instanceof FatalError) {
    console.error(`[ai-review] FATAL: ${err.message}`);
  } else {
    console.error("[ai-review] FATAL:", err && err.stack ? err.stack : err);
    // undici прячет настоящую причину сетевого сбоя в err.cause (DNS, TLS, refuse) — показываем
    const cause = err && err.cause;
    if (cause) console.error(`[ai-review] причина: ${cause.code || cause.name}: ${cause.message || cause}`);
  }
  process.exitCode = 1;
});
