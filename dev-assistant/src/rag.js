// RAG over project docs: discover doc files, chunk, embed via GPUStack, cosine, top-k.
import path from "node:path";
import { readFile, readdir, stat, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { config } from "./config.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const CACHE_FILE = path.join(here, "..", ".rag-cache.json");

const SKIP_DIRS = new Set(["node_modules", ".git", ".omo", ".playwright-mcp", "public", "dist", "build", "frames"]);
const SKIP_EXT = new Set([".mp4", ".srt", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".env", ".log", ".lock"]);
const MAX_DOCS = 200;

function isDocFile(name) {
  if (SKIP_EXT.has(path.extname(name).toLowerCase())) return false;
  if (name.endsWith("README.md") || name.endsWith("CONTRACT.md")) return true;
  const ext = path.extname(name).toLowerCase();
  if (ext === ".md") return true; // все md (docs/, README, инструкции)
  if ((ext === ".json" || ext === ".yaml" || ext === ".yml") &&
      /(schema|openapi|swagger|contract)/.test(name.toLowerCase())) return true;
  return false;
}

async function walk(dir, rel, acc) {
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return acc;
  }
  for (const e of entries) {
    if (acc.length >= MAX_DOCS) return acc;
    if (SKIP_DIRS.has(e.name)) continue;
    const abs = path.join(dir, e.name);
    const relPath = rel ? rel + "/" + e.name : e.name;
    if (e.isDirectory()) {
      await walk(abs, relPath, acc);
    } else if (e.isFile() && isDocFile(e.name)) {
      acc.push({ abs, rel: relPath.replaceAll("\\", "/") });
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
  return chunks.filter((c) => c.trim().length > 0);
}

async function embed(texts) {
  const out = [];
  for (let i = 0; i < texts.length; i += 16) {
    const batch = texts.slice(i, i + 16);
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
    if (!Array.isArray(data.data)) throw new Error("неожиданный ответ embeddings");
    for (const d of data.data) out.push(d.embedding);
  }
  return out;
}

function cosine(a, b) {
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    na += a[i] * a[i];
    nb += b[i] * b[i];
  }
  const den = Math.sqrt(na * nb);
  return den ? dot / den : 0;
}

let index = null; // { docs:[{id,rel,text}], vectors:[[...]], sig:{} }

async function getIndex() {
  const docs = await walk(config.projectRoot, "", []);
  const sig = {};
  for (const d of docs) {
    const st = await stat(d.abs).catch(() => null);
    sig[d.rel] = st ? st.mtimeMs : 0;
  }
  return { docs, sig };
}

function cacheIsValid(cached, sig) {
  if (!cached || !cached.sig) return false;
  const ck = Object.keys(cached.sig);
  const sk = Object.keys(sig);
  if (ck.length !== sk.length) return false;
  return sk.every((k) => cached.sig[k] === sig[k]);
}

export async function buildIndex() {
  if (index) return index;
  if (!building) building = doBuild().finally(() => { building = null; });
  return building;
}

let building = null;

async function doBuild() {
  const { docs, sig } = await getIndex();
  if (existsSync(CACHE_FILE)) {
    try {
      const cached = JSON.parse(await readFile(CACHE_FILE, "utf8"));
      if (cacheIsValid(cached, sig)) {
        index = cached;
        return index;
      }
    } catch {}
  }

  const entries = [];
  for (const d of docs) {
    const raw = await readFile(d.abs, "utf8").catch(() => "");
    for (const c of chunkText(raw)) {
      entries.push({ id: entries.length, rel: d.rel, text: c });
    }
  }
  const vectors = entries.length ? await embed(entries.map((e) => e.text)) : [];
  index = { docs: entries, vectors, sig };
  await writeFile(CACHE_FILE, JSON.stringify(index), { encoding: "utf8" }).catch(() => {});
  return index;
}

export async function search(query, k = config.maxContext) {
  if (!index) await buildIndex();
  if (!index.docs.length || !index.vectors.length) return [];
  let qvec;
  try {
    qvec = (await embed([query]))[0];
  } catch {
    return [];
  }
  return index.docs
    .map((d, i) => ({ ...d, score: cosine(qvec, index.vectors[i]) }))
    .sort((a, b) => b.score - a.score)
    .slice(0, k);
}
