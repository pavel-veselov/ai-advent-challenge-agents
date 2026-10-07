/**
 * RAG по инструкции робота-пылесоса (LEGEE D8 LuLu): снапшот проиндексированной
 * базы знаний (kb_id=16) грузится из data/rag-vacuum.json (генерируется
 * scripts/dump-kb.mjs), вопрос эмбеддится через GPUStack /v1/embeddings,
 * релевантность — cosine. Воронка как в llm-agent-app: candidateK → порог
 * minScore → topK. Фичa опциональная и fail-open: любая ошибка (нет снапшота,
 * недоступен embeddings-эндпоинт) — обычный чат без базы, бот не падает.
 */
import { existsSync, readFileSync } from "node:fs";
import { isAbsolute, join } from "node:path";
import { fileURLToPath } from "node:url";

import config from "./config.js";

/** Заголовок KB-блока — как в llm-agent-app (KbRagService.HEADER). */
const KB_HEADER = "### База знаний";

/**
 * Правило цитирования — адаптировано из KbRagService.CITATION_RULE (llm-agent-app):
 * модель цитирует фрагменты метками [n] в тексте ответа, а список источников
 * в конце НЕ печатает — его детерминированно добавляет бот (иначе выходит дубль).
 */
const CITATION_RULE =
  "Отвечай ТОЛЬКО на основе фрагментов ниже. Ссылайся на фрагменты в тексте ответа метками [n] " +
  "(например: «цитата из инструкции» [2]). Ключевые утверждения подкрепляй цитатой из фрагмента [n]. " +
  "Список источников в конце ответа НЕ добавляй — он будет добавлен автоматически. " +
  "Если ответа во фрагментах нет — скажи «Не знаю» и попроси уточнить вопрос.";

/** Таймаут запроса эмбеддинга, мс. */
const EMBED_TIMEOUT_MS = 60_000;

/**
 * Снапшот: undefined — ещё не загружался, null — снапшота нет/битый (RAG отключён),
 * иначе { kbId, name, dim, chunks: [{id, source, section, content, vector}] }.
 */
let snapshot;
let warnedNoSnapshot = false;

/** Путь к снапшоту: абсолютный из env — как есть; относительный — от корня проекта. */
function resolveSnapshotPath(raw) {
  if (isAbsolute(raw)) return raw;
  return fileURLToPath(new URL(join("..", raw), import.meta.url));
}

/** Ленивая загрузка снапшота (один раз); отсутствует/битый → null (RAG выключен). */
function loadSnapshot() {
  if (snapshot !== undefined) return snapshot;

  const path = resolveSnapshotPath(config.kbSnapshotPath);
  if (!existsSync(path)) {
    if (!warnedNoSnapshot) {
      console.warn(`RAG: снапшот не найден (${path}) — поиск по базе знаний отключён.`);
      warnedNoSnapshot = true;
    }
    snapshot = null;
    return snapshot;
  }

  try {
    const raw = JSON.parse(readFileSync(path, "utf8"));
    const chunks = (Array.isArray(raw?.chunks) ? raw.chunks : []).map((chunk) => {
      const buf = Buffer.from(chunk.embedding, "base64");
      return {
        id: chunk.id,
        source: chunk.source,
        section: chunk.section,
        content: chunk.content,
        vector: new Float32Array(buf.buffer, buf.byteOffset, buf.byteLength / 4),
      };
    });
    snapshot = { kbId: raw?.kbId ?? null, name: raw?.name ?? null, dim: raw?.dim ?? null, chunks };
  } catch (error) {
    console.error(`RAG: не удалось загрузить снапшот (${path}):`, error?.message ?? error);
    snapshot = null;
  }
  return snapshot;
}

/** Эмбеддинг вопроса через GPUStack (заголовки — как в src/llm.js). */
export async function embedQuestion(text) {
  const url = `${config.gpustackBaseUrl}/v1/embeddings`;
  let response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${config.gpustackApiKey}`,
      },
      body: JSON.stringify({ model: config.kbEmbeddingModel, input: [text] }),
      signal: AbortSignal.timeout(EMBED_TIMEOUT_MS),
    });
  } catch (error) {
    const reason = error?.cause?.code ?? error?.message ?? "неизвестная ошибка";
    throw new Error(`не удалось получить эмбеддинг (${url}): ${reason}`);
  }

  if (!response.ok) {
    const bodyText = (await response.text()).slice(0, 300);
    throw new Error(`embeddings вернул ${response.status} ${response.statusText}: ${bodyText}`);
  }

  let data;
  try {
    data = await response.json();
  } catch {
    throw new Error(`embeddings вернул не-JSON ответ: ${url}`);
  }

  const embedding = data?.data?.[0]?.embedding;
  if (!Array.isArray(embedding) || embedding.length === 0) {
    throw new Error("embeddings вернул ответ без data[0].embedding");
  }

  const kb = loadSnapshot();
  if (kb && kb.dim && embedding.length !== kb.dim) {
    throw new Error(`размерность эмбеддинга ${embedding.length} не совпадает с dim=${kb.dim}`);
  }
  return embedding;
}

/** Косинусная близость (0 при нулевых нормах). */
function cosine(a, b) {
  let dot = 0;
  let normA = 0;
  let normB = 0;
  const len = Math.min(a.length, b.length);
  for (let i = 0; i < len; i++) {
    dot += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  if (normA === 0 || normB === 0) return 0;
  return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}

/** Собирает KB-блок для system-сообщения; лимит общего размера — kbBlockMaxChars. */
function buildBlock(hits) {
  let block = `${KB_HEADER}\n\n${CITATION_RULE}\n\n`;
  const sources = [];
  for (let index = 0; index < hits.length; index++) {
    const { chunk, score } = hits[index];
    const entry = `[${index + 1}] Файл: ${chunk.source} — Раздел: ${chunk.section}\n${chunk.content}\n\n`;
    if (block.length + entry.length > config.kbBlockMaxChars) break;
    block += entry;
    sources.push({
      label: index + 1,
      source: chunk.source,
      section: chunk.section,
      score: Math.round(score * 1000) / 1000,
    });
  }
  if (sources.length === 0) return { block: null, sources: [] };
  return { block: block.trimEnd(), sources };
}

/**
 * Поиск по базе знаний. Возвращает { block, sources, empty }:
 * - block === null → база не помогла (нет снапшота / пустой поиск / ошибка) — обычный чат;
 * - empty=true — осмысленно «пустой поиск» (снапшот есть, релевантных фрагментов нет).
 */
export async function retrieve(question) {
  try {
    const kb = loadSnapshot();
    if (!kb || kb.chunks.length === 0) return { block: null, sources: [], empty: true };
    if (!config.gpustackBaseUrl || !config.gpustackApiKey) {
      // Эмбеддинг нечем посчитать — RAG просто пропускаем.
      return { block: null, sources: [], empty: true };
    }

    const query = await embedQuestion(question);
    const scored = kb.chunks
      .map((chunk) => ({ chunk, score: cosine(query, chunk.vector) }))
      .sort((a, b) => b.score - a.score)
      .slice(0, config.kbCandidateK)
      .filter((hit) => hit.score >= config.kbMinScore)
      .slice(0, config.kbTopK);

    if (scored.length === 0) return { block: null, sources: [], empty: true };

    const { block, sources } = buildBlock(scored);
    if (!block) return { block: null, sources: [], empty: true };
    return { block, sources, empty: false };
  } catch (error) {
    // Fail-open: RAG — необязательная фича, обычный чат важнее.
    console.error("RAG: поиск не выполнен:", error?.message ?? error);
    return { block: null, sources: [], empty: true };
  }
}

/** Метаданные загруженной базы (для /kb): {kbId, name, chunkCount} | null. */
export function kbInfo() {
  const kb = loadSnapshot();
  if (!kb) return null;
  return { kbId: kb.kbId, name: kb.name, chunkCount: kb.chunks.length };
}
