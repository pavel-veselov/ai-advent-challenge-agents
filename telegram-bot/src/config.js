/**
 * Конфигурация: разбор и валидация переменных окружения (fail-fast, как в llm-agent-app).
 * Токен бота и ключ GPUStack в код не зашиваются — приходят из .env
 * (бот стартует командой `node --env-file=.env src/bot.js`).
 */

/** Стартовый системный промпт бота. */
export const SYSTEM_PROMPT = "Ты душевный собеседник.";

/** Допустимые провайдеры LLM. */
export const PROVIDERS = Object.freeze(["gpustack", "ollama"]);

/** Таймаут одного запроса к LLM, мс. */
const REQUEST_TIMEOUT_MS = 120_000;

/** Температура генерации. */
const TEMPERATURE = 0.7;

/** Обрезает строку и хвостовые слэши (адреса приходят без /v1). */
function normalizeBaseUrl(raw) {
  return raw.trim().replace(/\/+$/, "");
}

function readEnv(name) {
  const value = process.env[name];
  return typeof value === "string" ? value.trim() : "";
}

const telegramBotToken = readEnv("TELEGRAM_BOT_TOKEN");
if (!telegramBotToken) {
  throw new Error(
    "TELEGRAM_BOT_TOKEN не задан. Скопируйте .env.example в .env и укажите токен от @BotFather.",
  );
}

const provider = readEnv("LLM_PROVIDER").toLowerCase() || "gpustack";
if (!PROVIDERS.includes(provider)) {
  throw new Error(
    `LLM_PROVIDER="${provider}" не поддерживается. Допустимо: ${PROVIDERS.join(", ")}.`,
  );
}

const model = readEnv("LLM_MODEL") || "qwen3.8-27b";

const gpustackBaseUrl = normalizeBaseUrl(readEnv("LLM_BASE_URL"));
const gpustackApiKey = readEnv("LLM_API_KEY");

// GPUStack без адреса/ключа не работает — падаем сразу (fail-fast), а не на первом запросе.
if (provider === "gpustack") {
  if (!gpustackBaseUrl) {
    throw new Error("LLM_BASE_URL обязателен при LLM_PROVIDER=gpustack (адрес без /v1).");
  }
  if (!gpustackApiKey) {
    throw new Error("LLM_API_KEY обязателен при LLM_PROVIDER=gpustack (Bearer-ключ GPUStack).");
  }
}

const ollamaBaseUrl =
  normalizeBaseUrl(readEnv("LLM_OLLAMA_BASE_URL")) || "http://localhost:11434";

const config = Object.freeze({
  telegramBotToken,
  provider,
  model,
  gpustackBaseUrl,
  gpustackApiKey,
  ollamaBaseUrl,
  temperature: TEMPERATURE,
  requestTimeoutMs: REQUEST_TIMEOUT_MS,
});

export default config;
