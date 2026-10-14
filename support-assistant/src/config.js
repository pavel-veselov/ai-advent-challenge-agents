// Конфигурация support-assistant из окружения.
export const config = {
  baseUrl: process.env.LLM_BASE_URL || "https://gpustack.data.lmru.tech",
  apiKey: process.env.LLM_API_KEY || "",
  model: process.env.LLM_MODEL || "glm-5.3-flash",
  embedModel: process.env.EMBED_MODEL || "qwen3-vl-embedding-8b",
  port: Number(process.env.PORT) || 8082,
  maxContext: Number(process.env.MAX_CONTEXT) || 6,
  similarityThreshold: Number(process.env.SIMILARITY_THRESHOLD) || 0.45,
};
