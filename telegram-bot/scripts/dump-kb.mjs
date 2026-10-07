/**
 * Генератор снапшота RAG: выгружает проиндексированную базу знаний (kb_id=16 —
 * инструкция робота-пылесоса LEGEE D8 LuLu) из SQLite соседнего проекта
 * llm-agent-app в data/rag-vacuum.json для Telegram-бота.
 *
 * Запуск: npm run dump:kb  (или node scripts/dump-kb.mjs).
 * better-sqlite3 — devDependency: в production-образ не попадает
 * (Dockerfile ставит зависимости через `npm ci --omit=dev`).
 * БД открывается строго read-only — оригинал не изменяется.
 */
import Database from "better-sqlite3";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

/** Авторитетный путь к БД соседнего проекта (переопределяется env KB_DB_PATH). */
const DEFAULT_DB_PATH = fileURLToPath(
  new URL("../../llm-agent-app/data/llm-agent.db", import.meta.url),
);
const DB_PATH = process.env.KB_DB_PATH || DEFAULT_DB_PATH;

const OUT_PATH = fileURLToPath(new URL("../data/rag-vacuum.json", import.meta.url));

const KB_ID = Number(process.env.KB_BASE_ID) || 16;
const EMBEDDING_MODEL = process.env.KB_EMBEDDING_MODEL || "qwen3-vl-embedding-8b";

/** qwen3-vl-embedding-8b: 4096 float32 → 16384 байта на эмбеддинг. */
const EXPECTED_EMBEDDING_BYTES = 16384;

if (!existsSync(DB_PATH)) {
  console.error(`БД не найдена: ${DB_PATH} (переопределить: KB_DB_PATH=...)`);
  process.exit(1);
}

const db = new Database(DB_PATH, { readonly: true });

const base = db.prepare("SELECT id, name FROM knowledge_bases WHERE id = ?").get(KB_ID);
if (!base) {
  console.error(`База знаний id=${KB_ID} не найдена в ${DB_PATH}`);
  process.exit(1);
}

const rows = db
  .prepare(
    "SELECT id, source, section, content, embedding FROM kb_chunks WHERE kb_id = ? ORDER BY id",
  )
  .all(KB_ID);

if (rows.length === 0) {
  console.error(`В базе id=${KB_ID} нет чанков — снапшот не пишем.`);
  process.exit(1);
}

const chunks = [];
for (const row of rows) {
  const embedding = Buffer.from(row.embedding);
  if (embedding.length !== EXPECTED_EMBEDDING_BYTES) {
    console.error(
      `Чанк id=${row.id}: embedding ${embedding.length} байт, ожидалось ${EXPECTED_EMBEDDING_BYTES}. Останавливаюсь.`,
    );
    process.exit(1);
  }
  chunks.push({
    id: row.id,
    source: row.source,
    section: row.section,
    content: row.content,
    embedding: embedding.toString("base64"),
  });
}

const snapshot = {
  kbId: base.id,
  name: base.name,
  embeddingModel: EMBEDDING_MODEL,
  dim: EXPECTED_EMBEDDING_BYTES / 4,
  chunks,
};

mkdirSync(dirname(OUT_PATH), { recursive: true });
writeFileSync(OUT_PATH, `${JSON.stringify(snapshot, null, 2)}\n`, "utf8");

console.log(`OK: ${OUT_PATH}`);
console.log(
  `kb_id=${snapshot.kbId}; name="${snapshot.name}"; chunks=${chunks.length}; dim=${snapshot.dim}`,
);

db.close();
