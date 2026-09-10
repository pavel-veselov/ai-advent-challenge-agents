-- Схема SQLite: история диалогов. Файл выполняется автоматически при старте
-- (spring.sql.init.mode=always), идемпотентен — можно перезапускать без потери данных.

CREATE TABLE IF NOT EXISTS chat_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON chat_messages (session_id);

-- Кумулятивная статистика «за всё время»: одиночная строка (id = 1), счётчики
-- не уменьшаются при удалении сессий — переживают DELETE /api/sessions/{sessionId}.
CREATE TABLE IF NOT EXISTS lifetime_stats (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    sessions_total INTEGER NOT NULL DEFAULT 0,
    prompt_tokens_total INTEGER NOT NULL DEFAULT 0,
    completion_tokens_total INTEGER NOT NULL DEFAULT 0,
    cost_usd_total REAL NOT NULL DEFAULT 0
);

-- Динамические настройки LLM (ключ-значение): переопределяют defaults из application.yml /
-- переменных окружения и переживают перезапуск backend (см. DynamicLlmSettings).
-- Значение — строковое представление (типизированную интерпретацию делает DynamicLlmSettings);
-- provider НЕ хранится здесь (он неизменяем и берётся из конфигурации старта).
CREATE TABLE IF NOT EXISTS app_settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Состояние «включена/отключена» для моделей каталога LlmCatalog (см. ModelEnabledStore).
-- id = идентификатор модели из каталога; enabled = 1 включена, 0 отключена.
-- Строка отсутствует → модель включена (enabled=true) по умолчанию.
-- Переживает перезапуск backend: отключённая модель недоступна в PUT /api/llm-settings.
CREATE TABLE IF NOT EXISTS app_models (
    id      TEXT PRIMARY KEY,
    enabled INTEGER NOT NULL
);
