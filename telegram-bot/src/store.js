/**
 * Хранилище состояния бота в data/state.json:
 * - провайдер и модель — глобальные (одно выделение на бота, как выбор в llm-agent-app);
 * - системный промпт и параметры генерации (temperature, max tokens, размер
 *   контекста) — тоже глобальные, null означает «дефолт» (настраиваются /prompt и /params);
 * - история — на каждый чат, хранятся только ПОСЛЕДНИЕ HISTORY_LIMIT сообщений.
 * Файл перезаписывается после каждого изменения; битый файл не роняет бота —
 * начинаем с чистого состояния (провайдер/модель из env).
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

import config, { DEFAULT_CONTEXT_LIMIT, DEFAULT_SYSTEM_PROMPT } from "./config.js";

/** Сколько последних сообщений чата держим в контексте (дефолт /params context). */
export const HISTORY_LIMIT = DEFAULT_CONTEXT_LIMIT;

/** data/state.json рядом с проектом (независимо от cwd). */
const STATE_FILE = fileURLToPath(new URL("../data/state.json", import.meta.url));

const ROLES = new Set(["user", "assistant"]);

function isValidMessage(message) {
  return (
    message !== null &&
    typeof message === "object" &&
    ROLES.has(message.role) &&
    typeof message.content === "string"
  );
}

export class Store {
  #filePath;
  #provider;
  #model;
  /** null = пользователь не менял, действует дефолт (DEFAULT_SYSTEM_PROMPT). */
  #systemPrompt = null;
  /** null = дефолтная температура из config. */
  #temperature = null;
  /** null = max_tokens не отправляется в запросе. */
  #maxTokens = null;
  /** null = HISTORY_LIMIT сообщений контекста. */
  #contextLimit = null;
  /** Map<string, {history: Array<{role, content}>, ragEnabled?: boolean}> — по строковому chat_id. */
  #chats = new Map();

  /**
   * @param {string} filePath путь к файлу состояния
   * @param {{provider: string, model: string}} defaults стартовые значения из env (первый запуск)
   */
  constructor(filePath, defaults) {
    this.#filePath = filePath;
    this.#provider = defaults.provider;
    this.#model = defaults.model;
    this.#load();
  }

  #load() {
    if (!existsSync(this.#filePath)) return; // первый запуск — дефолты из env
    let raw;
    try {
      raw = JSON.parse(readFileSync(this.#filePath, "utf8"));
    } catch {
      // Битый файл — терпимо: начинаем с чистого состояния, не роняя бота.
      this.#chats.clear();
      return;
    }
    if (typeof raw?.provider === "string" && raw.provider) this.#provider = raw.provider;
    if (typeof raw?.model === "string" && raw.model) this.#model = raw.model;
    // Глобальные настройки: null = «не трогали». У старых state.json этих полей нет —
    // миграция ленивая (дефолты применяются на лету), история чатов не переписывается.
    if (typeof raw?.systemPrompt === "string" && raw.systemPrompt.trim()) {
      this.#systemPrompt = raw.systemPrompt;
    }
    if (typeof raw?.temperature === "number" && Number.isFinite(raw.temperature)) {
      this.#temperature = raw.temperature;
    }
    if (typeof raw?.maxTokens === "number" && Number.isFinite(raw.maxTokens)) {
      this.#maxTokens = raw.maxTokens;
    }
    if (typeof raw?.contextLimit === "number" && Number.isFinite(raw.contextLimit)) {
      this.#contextLimit = raw.contextLimit;
    }
    const contextLimit = this.#contextLimit ?? HISTORY_LIMIT;
    if (raw?.chats && typeof raw.chats === "object") {
      for (const [chatId, chat] of Object.entries(raw.chats)) {
        if (Array.isArray(chat?.history)) {
          const history = chat.history.filter(isValidMessage).slice(-contextLimit);
          const entry = { history };
          // Флаг RAG — необязательное поле; у старых state.json его просто нет (дефолт — вкл).
          if (typeof chat.ragEnabled === "boolean") entry.ragEnabled = chat.ragEnabled;
          this.#chats.set(String(chatId), entry);
        }
      }
    }
  }

  getProvider() {
    return this.#provider;
  }

  setProvider(provider) {
    this.#provider = provider;
    this.#save();
  }

  getModel() {
    return this.#model;
  }

  setModel(model) {
    this.#model = model;
    this.#save();
  }

  /** Текущий системный промпт (дефолт, если пользователь не задавал свой). */
  getSystemPrompt() {
    return this.#systemPrompt ?? DEFAULT_SYSTEM_PROMPT;
  }

  /** Задаёт системный промпт; null/пустой — вернуть дефолтный. */
  setSystemPrompt(prompt) {
    this.#systemPrompt = typeof prompt === "string" && prompt.trim() ? prompt : null;
    this.#save();
  }

  /** Температура генерации (null — дефолт из конфига). */
  getTemperature() {
    return this.#temperature;
  }

  setTemperature(value) {
    this.#temperature = typeof value === "number" && Number.isFinite(value) ? value : null;
    this.#save();
  }

  /** Лимит токенов ответа (null — не отправлять max_tokens). */
  getMaxTokens() {
    return this.#maxTokens;
  }

  setMaxTokens(value) {
    this.#maxTokens =
      typeof value === "number" && Number.isInteger(value) && value > 0 ? value : null;
    this.#save();
  }

  /** Сколько последних сообщений истории уходит в LLM (null — HISTORY_LIMIT). */
  getContextLimit() {
    return this.#contextLimit;
  }

  setContextLimit(value) {
    this.#contextLimit =
      typeof value === "number" && Number.isInteger(value) && value > 0 ? value : null;
    this.#save();
  }

  /** Копия истории чата (последние contextLimit ?? HISTORY_LIMIT сообщений). */
  getHistory(chatId) {
    const chat = this.#chats.get(String(chatId));
    if (!chat) return [];
    return chat.history.slice(-(this.#contextLimit ?? HISTORY_LIMIT));
  }

  /** Добавляет сообщение в конец истории чата и обрезает до contextLimit ?? HISTORY_LIMIT. */
  appendMessage(chatId, message) {
    const key = String(chatId);
    const chat = this.#chats.get(key) ?? { history: [] };
    chat.history.push({ role: message.role, content: message.content });
    const limit = this.#contextLimit ?? HISTORY_LIMIT;
    if (chat.history.length > limit) {
      chat.history.splice(0, chat.history.length - limit);
    }
    this.#chats.set(key, chat);
    this.#save();
  }

  /** Убирает последнее сообщение (откат user-сообщения при ошибке LLM). */
  popLastMessage(chatId) {
    const key = String(chatId);
    const chat = this.#chats.get(key);
    if (!chat || chat.history.length === 0) return;
    chat.history.pop();
    this.#save();
  }

  /** Полностью очищает историю чата (/reset); настройка RAG сохраняется. */
  clearHistory(chatId) {
    const key = String(chatId);
    const chat = this.#chats.get(key);
    if (!chat) return;
    chat.history = [];
    this.#save();
  }

  /** Включён ли RAG для чата (по умолчанию — вкл). */
  getRag(chatId) {
    return this.#chats.get(String(chatId))?.ragEnabled ?? true;
  }

  /** Включает/выключает RAG для чата (персистится в state.json). */
  setRag(chatId, enabled) {
    const key = String(chatId);
    const chat = this.#chats.get(key) ?? { history: [] };
    chat.ragEnabled = Boolean(enabled);
    this.#chats.set(key, chat);
    this.#save();
  }

  #save() {
    try {
      mkdirSync(dirname(this.#filePath), { recursive: true });
      const chats = {};
      for (const [chatId, chat] of this.#chats) {
        chats[chatId] = chat;
      }
      const state = {
        provider: this.#provider,
        model: this.#model,
        systemPrompt: this.#systemPrompt,
        temperature: this.#temperature,
        maxTokens: this.#maxTokens,
        contextLimit: this.#contextLimit,
        chats,
      };
      writeFileSync(this.#filePath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
    } catch (error) {
      // Персистентность не критична для работы чата — бот продолжает работать в памяти.
      console.error("Не удалось сохранить data/state.json:", error?.message ?? error);
    }
  }
}

/** Единственный экземпляр хранилища с дефолтами из конфига. */
export const store = new Store(STATE_FILE, { provider: config.provider, model: config.model });
