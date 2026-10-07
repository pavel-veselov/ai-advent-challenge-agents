/**
 * Хранилище состояния бота в data/state.json:
 * - провайдер и модель — глобальные (одно выделение на бота, как выбор в llm-agent-app);
 * - история — на каждый чат, хранятся только ПОСЛЕДНИЕ HISTORY_LIMIT сообщений.
 * Файл перезаписывается после каждого изменения; битый файл не роняет бота —
 * начинаем с чистого состояния (провайдер/модель из env).
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";

import config from "./config.js";

/** Сколько последних сообщений чата держим в контексте. */
export const HISTORY_LIMIT = 10;

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
    if (raw?.chats && typeof raw.chats === "object") {
      for (const [chatId, chat] of Object.entries(raw.chats)) {
        if (Array.isArray(chat?.history)) {
          const history = chat.history.filter(isValidMessage).slice(-HISTORY_LIMIT);
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

  /** Копия истории чата (последние HISTORY_LIMIT сообщений). */
  getHistory(chatId) {
    const chat = this.#chats.get(String(chatId));
    return chat ? [...chat.history] : [];
  }

  /** Добавляет сообщение в конец истории чата и обрезает до последних HISTORY_LIMIT. */
  appendMessage(chatId, message) {
    const key = String(chatId);
    const chat = this.#chats.get(key) ?? { history: [] };
    chat.history.push({ role: message.role, content: message.content });
    if (chat.history.length > HISTORY_LIMIT) {
      chat.history.splice(0, chat.history.length - HISTORY_LIMIT);
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
      const state = { provider: this.#provider, model: this.#model, chats };
      writeFileSync(this.#filePath, `${JSON.stringify(state, null, 2)}\n`, "utf8");
    } catch (error) {
      // Персистентность не критична для работы чата — бот продолжает работать в памяти.
      console.error("Не удалось сохранить data/state.json:", error?.message ?? error);
    }
  }
}

/** Единственный экземпляр хранилища с дефолтами из конфига. */
export const store = new Store(STATE_FILE, { provider: config.provider, model: config.model });
