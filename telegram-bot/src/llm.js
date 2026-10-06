/**
 * Клиент OpenAI-совместимого chat/completions (зеркало схемы llm-agent-app):
 * - GPUStack: POST {LLM_BASE_URL}/v1/chat/completions с Bearer-ключом;
 * - Ollama:   POST {OLLAMA_BASE_URL}/v1/chat/completions без ключа.
 * Только обычные (не стриминговые) запросы, без tools — бот чисто чатовый.
 */
import config from "./config.js";

/** Базовый адрес провайдера (без /v1). */
function baseUrlFor(provider) {
  if (provider === "ollama") return config.ollamaBaseUrl;
  if (provider === "gpustack") return config.gpustackBaseUrl;
  throw new Error(`Неизвестный провайдер: "${provider}"`);
}

/** Заголовки запроса: Authorization — только для GPUStack (Ollama ходит без ключа). */
function headersFor(provider) {
  const headers = { "Content-Type": "application/json" };
  if (provider === "gpustack") {
    headers.Authorization = `Bearer ${config.gpustackApiKey}`;
  }
  return headers;
}

/**
 * Нестриминговый вызов чата. Возвращает текст ответа (choices[0].message.content).
 * Ошибки — всегда с читаемой причиной: сеть, статус + фрагмент тела, отсутствие content.
 */
export async function chatCompletion({ provider, model, messages }) {
  const base = baseUrlFor(provider);
  const url = `${base}/v1/chat/completions`;

  const body = JSON.stringify({ model, messages, temperature: config.temperature });

  let response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: headersFor(provider),
      body,
      signal: AbortSignal.timeout(config.requestTimeoutMs),
    });
  } catch (error) {
    const reason = error?.cause?.code ?? error?.message ?? "неизвестная ошибка";
    throw new Error(`не удалось обратиться к ${provider} (${url}): ${reason}`);
  }

  if (!response.ok) {
    const bodyText = (await response.text()).slice(0, 500);
    throw new Error(`${provider} вернул ${response.status} ${response.statusText}: ${bodyText}`);
  }

  let data;
  try {
    data = await response.json();
  } catch {
    throw new Error(`${provider} вернул не-JSON ответ: ${url}`);
  }

  const content = data?.choices?.[0]?.message?.content;
  if (typeof content !== "string") {
    throw new Error(`${provider} вернул ответ без message.content (model=${model})`);
  }
  return content;
}
