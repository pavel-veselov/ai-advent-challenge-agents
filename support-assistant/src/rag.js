// RAG по базе знаний поддержки (support-assistant/faq/*.md) с кэшем эмбеддингов.
import { readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { config } from "./config.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const FAQ_DIR = path.join(here, "..", "faq");
const CACHE_FILE = path.join(here, "..", ".rag-cache.json");

const CHUNK_SIZE = 900;
const CHUNK_OVERLAP = 120;
const EMBED_BATCH = 16;

let chunks = [];

// ---------- корпус ----------

async function collectChunks() {
  const files = (await readdir(FAQ_DIR)).filter((f) => f.endsWith(".md"));
  const out = [];
  for (const file of files) {
    const rel = `faq/${file}`;
    const text = await readFile(path.join(FAQ_DIR, file), "utf8");
    for (let i = 0, p = 0; p < text.length; i++, p += CHUNK_SIZE - CHUNK_OVERLAP) {
      let end = Math.min(text.length, p + CHUNK_SIZE);
      if (end < text.length) {
        const nl = text.lastIndexOf("\n", end);
        if (nl > p + 200) end = nl;
      }
      const chunk = text.slice(p, end).trim();
      if (chunk.length > 40) out.push({ rel, chunk: i, text: chunk });
      if (end >= text.length) break;
    }
  }
  return out;
}

// ---------- эмбеддинги ----------

async function embed(texts) {
  const out = [];
  for (let i = 0; i < texts.length; i += EMBED_BATCH) {
    const batch = texts.slice(i, i + EMBED_BATCH);
    const res = await fetch(`${config.baseUrl}/v1/embeddings`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${config.apiKey}`,
      },
      body: JSON.stringify({ model: config.embedModel, input: batch }),
    });
    if (!res.ok) {
      const t = await res.text().catch(() => "");
      throw new Error(`embeddings ${res.status}: ${t.slice(0, 200)}`);
    }
    const data = await res.json();
    for (const d of data.data) out.push(d.embedding);
  }
  return out;
}

function cosine(a, b) {
  let dot = 0, na = 0, nb = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    dot += a[i] * b[i];
    na += a[i] * a[i];
    nb += b[i] * b[i];
  }
  const den = Math.sqrt(na * nb);
  return den ? dot / den : 0;
}

// ---------- индекс с кэшем ----------

export async function buildIndex() {
  chunks = await collectChunks();
  const cache = await readCache();
  const need = [];
  const vectors = new Array(chunks.length).fill(null);
  let hits = 0;

  chunks.forEach((c, i) => {
    const key = `${c.rel}#${c.chunk}`;
    const hit = cache[key];
    if (hit && hit.text === c.text) {
      vectors[i] = hit.vec;
      hits += 1;
    } else {
      need.push(i);
    }
  });

  if (need.length) {
    const vecs = await embed(need.map((i) => chunks[i].text));
    need.forEach((idx, k) => (vectors[idx] = vecs[k]));
    const nextCache = {};
    chunks.forEach((c, i) => (nextCache[`${c.rel}#${c.chunk}`] = { text: c.text, vec: vectors[i] }));
    await writeFile(CACHE_FILE, JSON.stringify(nextCache)).catch(() => {});
  }

  chunks.forEach((c, i) => (c.vec = vectors[i]));
  console.log(`[support-assistant] RAG: ${chunks.length} чанков (${hits} из кэша, ${need.length} новых)`);
}

async function readCache() {
  try {
    return JSON.parse(await readFile(CACHE_FILE, "utf8"));
  } catch {
    return {};
  }
}

// ---------- поиск ----------

export async function search(question, topK = config.maxContext) {
  if (!chunks.length) return [];
  const [qv] = await embed([question]);
  const scored = chunks.map((c) => ({ rel: c.rel, text: c.text, score: cosine(qv, c.vec) }));
  scored.sort((a, b) => b.score - a.score);
  return scored.filter((s) => s.score >= config.similarityThreshold).slice(0, topK);
}
