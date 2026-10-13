import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
// dev-assistant/src/config.js -> repo root is two levels up
const defaultProjectRoot = path.resolve(here, "..", "..");

export const config = {
  baseUrl: process.env.LLM_BASE_URL || "https://gpustack.data.lmru.tech",
  apiKey: process.env.LLM_API_KEY || "",
  model: process.env.LLM_MODEL || "glm-5.3-flash",
  embedModel: process.env.EMBED_MODEL || "qwen3-vl-embedding-8b",
  port: Number(process.env.PORT) || 8081,
  projectRoot: path.resolve(process.env.PROJECT_ROOT || defaultProjectRoot),
  maxContext: Number(process.env.MAX_CONTEXT) || 8,
  similarityThreshold: Number(process.env.SIMILARITY_THRESHOLD) || 0.5,
};
