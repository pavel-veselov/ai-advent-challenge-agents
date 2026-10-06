/**
 * Каталоги моделей по провайдерам (зеркало механики llm-agent-app):
 * - gpustack — фиксированный каталог (порядок из LlmCatalog соседнего проекта);
 * - ollama — живое обнаружение через OpenAI-совместимый GET /v1/models.
 */
import config from "./config.js";

/** Фиксированный каталог моделей GPUStack (первая — модель по умолчанию при переключении). */
export const GPUSTACK_MODELS = Object.freeze([
  "qwen3.8-27b",
  "deepseek-v4-flash",
  "glm-5.3-flash",
]);

/**
 * Живой список моделей Ollama: GET {OLLAMA_BASE_URL}/v1/models → массив id.
 * Бросает понятную ошибку, если Ollama недоступна или ответ невалиден.
 */
export async function listOllamaModels() {
  const url = `${config.ollamaBaseUrl}/v1/models`;

  let response;
  try {
    response = await fetch(url, {
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(10_000),
    });
  } catch (error) {
    const reason = error?.cause?.code ?? error?.message ?? "неизвестная ошибка";
    throw new Error(`Ollama недоступна (${config.ollamaBaseUrl}): ${reason}`);
  }

  if (!response.ok) {
    const body = (await response.text()).slice(0, 300);
    throw new Error(`Ollama ответила ${response.status} ${response.statusText}: ${body}`);
  }

  let data;
  try {
    data = await response.json();
  } catch {
    throw new Error(`Ollama вернула не-JSON ответ: ${url}`);
  }

  const entries = Array.isArray(data?.data) ? data.data : [];
  return entries
    .map((entry) => (typeof entry?.id === "string" ? entry.id.trim() : ""))
    .filter((id) => id.length > 0);
}
