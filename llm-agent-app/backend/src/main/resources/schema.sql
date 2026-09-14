-- Схема SQLite: история диалогов. Файл выполняется автоматически при старте
-- (spring.sql.init.mode=always), идемпотентен — можно перезапускать без потери данных.

CREATE TABLE IF NOT EXISTS chat_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    -- id предыдущего сообщения той же ветки (branching); null — корень/системные заметки.
    -- Поддерживается SessionStore.append для ВСЕХ стратегий; подробности в SessionBranchStore.
    parent_id INTEGER,
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

-- Per-session настройки сжатия истории (см. SessionCompressionStore, GET/PUT
-- /api/sessions/{sessionId}/compression). Строка отсутствует → значения по умолчанию:
-- enabled=0 (выключено), keep_last=5, summary_every=10. Переживают перезапуск backend.
CREATE TABLE IF NOT EXISTS session_compression (
    session_id    TEXT PRIMARY KEY,
    enabled       INTEGER NOT NULL DEFAULT 0,
    keep_last     INTEGER NOT NULL DEFAULT 5,
    summary_every INTEGER NOT NULL DEFAULT 10
);

-- Свёрнутое резюме истории сессии (см. SessionCompressionStore). upto_order — id
-- последнего свёрнутого сообщения из chat_messages (граница «уже покрыто резюме»).
-- Содержимое НИКОГДА не отдаётся наружу через history/sessions-эндпоинты — его
-- читает только агент при построении контекста LLM-запроса.
CREATE TABLE IF NOT EXISTS session_summaries (
    session_id TEXT PRIMARY KEY,
    summary    TEXT NOT NULL,
    upto_order INTEGER NOT NULL
);

-- Per-session настройки LLM (см. SessionLlmSettingsStore, GET/PUT
-- /api/sessions/{sessionId}/llm-settings). Строка отсутствует → применяются ТЕКУЩИЕ
-- ГЛОБАЛЬНЫЕ настройки (app_settings / defaults) без их персистентности.
-- ВСЕ редактируемые поля настройки LLM переопределяются per-session (те же поля, что у
-- глобального /api/llm-settings, кроме provider — тот привязан к env): model/text и др.
-- Колонки TEXT: NULL = не переопределено (действует ТЕКУЩЕЕ глобальное значение),
-- иначе строковое представление значения ("qwen3.8-27b", "0.2", "500", "true"/"false").
-- contextLimit НЕ хранится — выводится из эффективной модели по каталогу (как в глобальном PUT).
-- Переживают перезапуск backend. Для старых БД недостающие колонки добавляет store (ALTER TABLE).
CREATE TABLE IF NOT EXISTS session_llm_settings (
    session_id          TEXT PRIMARY KEY,
    model               TEXT,
    temperature         TEXT,
    top_p               TEXT,
    top_k               TEXT,
    max_tokens          TEXT,
    timeout_seconds     TEXT,
    price_input_per_1m  TEXT,
    price_output_per_1m TEXT,
    reasoning_enabled   TEXT
);

-- Стратегия контекста сессии (см. SessionContextStore, GET/PUT
-- /api/sessions/{sessionId}/context-strategy). Строка отсутствует → strategy='none',
-- window_size=12. active_branch_id — ид активной ветки (branching; см. SessionBranchStore);
-- null — активная не выбрана (действует ветка «Основная»). Переживает перезапуск backend.
CREATE TABLE IF NOT EXISTS session_context_strategy (
    session_id       TEXT PRIMARY KEY,
    strategy         TEXT NOT NULL DEFAULT 'none',
    window_size      INTEGER NOT NULL DEFAULT 12,
    active_branch_id INTEGER NULL
);

-- «Липкие факты» сессии (см. SessionFactsStore, GET /api/sessions/{sessionId}/facts):
-- ключ-значение, извлекаемые LLM при стратегии sticky_facts. Порядок строк — порядок
-- вставки replaceAll (без сортировки по ключу). Переживают перезапуск backend.
CREATE TABLE IF NOT EXISTS session_facts (
    session_id TEXT NOT NULL,
    fact_key   TEXT NOT NULL,
    fact_value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY(session_id, fact_key)
);

-- Ветки диалога сессии (см. SessionBranchStore, GET/POST/PUT
-- /api/sessions/{sessionId}/branches). head_message_id — id последнего сообщения ветки
-- (chat_messages.id); цепочка ветки строится по parent_id (см. SessionStore.append).
-- Переживают перезапуск backend.
CREATE TABLE IF NOT EXISTS session_branches (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT NOT NULL,
    name            TEXT NOT NULL,
    head_message_id INTEGER,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);
