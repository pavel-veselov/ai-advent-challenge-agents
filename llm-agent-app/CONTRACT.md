# Контракт событий и API (shared between backend & frontend)

> Этот файл — единственный источник истины для контракта. Backend и frontend следуют ему строго.

## API

| Метод | Путь | Тело / Параметры | Ответ |
|-------|------|------------------|-------|
| `POST` | `/api/chat` | `{ "sessionId": string, "message": string }` | `text/event-stream` — поток событий агента (см. ниже) |
| `GET` | `/api/sessions/{sessionId}/history` | — | `{ "sessionId": string, "messages": [{ "role": "user"\|"assistant", "content": string }] }` |
| `DELETE` | `/api/sessions/{sessionId}` | — | `{ "deleted": boolean }` — удаляет всю историю сессии (всегда 200) |
| `GET` | `/api/llm-settings` | — | применённые настройки LLM — та же структура, что `settings` у `agent_started` (см. ниже) |

### Управление сервисом (супервизор, не HTTP-эндпоинт backend)

Остановка/запуск backend выполняется НЕ через HTTP backend'а, а через **супервизор** —
отдельный контрольный сервер на `127.0.0.1:8081` (владеет скрипт `scripts/run-backend.ps1`,
управление стоп-флагом `%TEMP%\opencode\llm-agent.stop`):

| Метод | Путь | Ответ | Действие |
|-------|------|-------|----------|
| `POST` | `127.0.0.1:8081/stop` | `{ "stopped": true }` | создаёт стоп-флаг и убивает процесс java backend'а; перезапуск блокируется, пока флаг стоит |
| `POST` | `127.0.0.1:8081/start` | `{ "starting": true }` | снимает стоп-флаг; супервизор поднимает backend заново |
| `GET` | `127.0.0.1:8081/status` | `{ "stopped": <bool>, "java": <bool> }` | состояние: стоит ли стоп-флаг и жив ли процесс java |

- Контрольный сервер слушает только `127.0.0.1` — наружу не доступен. Любой другой путь/метод → `404`.
- Флаг-файл: `%TEMP%\opencode\llm-agent.stop`. Свежий запуск супервизора снимает флаг (сервис должен работать).
- В dev-режиме фронтенд ходит на этот сервер через Vite-прокси **`/system-ctrl`**
  (`/system-ctrl/stop` → `127.0.0.1:8081/stop`, префикс срезается), минуя `http://localhost:8080`.

### Персистентность истории

История диалогов хранится в SQLite-файле (по умолчанию `./data/llm-agent.db` относительно
каталога запуска backend; путь переопределяется переменной окружения `SQLITE_DB_PATH`) и
**переживает перезапуск backend**. Схема таблицы `chat_messages` создаётся автоматически при
старте из `backend/src/main/resources/schema.sql` (`spring.sql.init.mode=always`).
`DELETE /api/sessions/{sessionId}` удаляет все строки сессии из этой таблицы.

Жизненным циклом backend управляет супервизор (см. раздел «Управление сервисом» выше):
остановка/запуск через флаг-файл и контрольный сервер `127.0.0.1:8081`. При остановке и
последующем старте история в SQLite сохраняется.

## Единый контракт событий

Каждое SSE-событие в потоке `POST /api/chat` — это JSON-объект:

```json
{
  "type": "agent_started",
  "runId": "3f2a6b40-9c11-4b01-8e01-3f0a1b2c3d4e",
  "stepId": "user",
  "timestamp": "2026-09-07T12:00:00.000Z",
  "payload": { "userMessage": "..." },
  "sequence": 0
}
```

Поля:
- `type` — string, одно из значений ниже.
- `runId` — uuid серии агента (одна на один `POST /api/chat`).
- `stepId` — идентификатор узла графа (см. правила генерации).
- `timestamp` — ISO-8601 UTC (например `2026-09-07T12:00:00.000Z`).
- `payload` — объект, специфичный для типа.
- `sequence` — int, монотонно растущий счётчик событий внутри runId (начиная с 0).

### Типы событий и payload

| type | payload | stepId (id узла графа) | Узел графа |
|------|---------|------------------------|------------|
| `agent_started` | `{ "userMessage": string, "settings": { "provider": string, "model": string, "temperature": number, "topP": number, "topK": number\|null, "maxTokens": number\|null, "timeoutSeconds": number, "maxToolCallIterations": number, "tools": string[] } }` | `user` | «Запрос пользователя» |
| `llm_request_started` | `{ "iteration": number, "prompt": [{ "role": string, "content": string }] }` | `llm-<iteration>` | «LLM (итерация N)» |
| `llm_token` | `{ "delta": string }` | `llm-<iteration>` | — (токен, статус running) |
| `llm_response_finished` | `{ "finishReason": "stop"\|"tool_calls"\|"length"\|"error", "usage"?: { "inputTokens": number, "outputTokens": number } }` | `llm-<iteration>` | — (статус success/error) |
| `tool_call_started` | `{ "toolName": string, "args": object }` | `tool-<toolName>-<idx>` | «Инструмент: <toolName>» |
| `tool_call_finished` | `{ "result": string, "status": "success"\|"error" }` | `tool-<toolName>-<idx>` | — (статус, результат) |
| `agent_finished` | `{ "finalText": string }` | `answer` | «Ответ» |
| `error` | `{ "message": string }` | `error-<idx>` | — (только баннер в UI) |

Правила генерации `stepId`:
- `llm-<iteration>` — iteration начинается с 1.
- `tool-<toolName>-<idx>` — idx (начиная с 0) отличает несколько вызовов одного инструмента в рамках одного run.

Поле `settings` у `agent_started` — применённые настройки LLM этого запуска: `provider` (mock|gpustack),
`model`, `temperature`, `topP` (nucleus sampling, уходит в API всегда), `topK` (top-k sampling; `null` — не задано,
параметр в API не уходит), `maxTokens` (лимит выходных токенов; `null` — без лимита, параметр в API не уходит),
`timeoutSeconds`, `maxToolCallIterations` (лимит цикла tool-calling), `tools`
(отсортированный список имён зарегистрированных инструментов, уходящих в параметр `tools` API).
Секреты (`apiKey`) и внутренний `baseUrl` наружу не отдаются. Значения настраиваются env
(`LLM_TOP_P` / `LLM_TOP_K` / `LLM_MAX_TOKENS` и др.).
Та же структура доступна через `GET /api/llm-settings` — фронтенд забирает настройки при открытии
страницы и показывает блок в самом верху лога шагов до первого запроса; на каждом запуске блок
обновляется из `agent_started`.

Поле `prompt` у `llm_request_started` — точный массив сообщений, отправленный в LLM на этой итерации
(system + история сессии + наблюдения инструментов). Для assistant-сообщения с tool_calls в `content`
уходит `[вызов инструмента: <имена>]`. Поле служит для отображения промпта в логе шагов (режим отладки).

Поле `usage` у `llm_response_finished` — опционально. Заполняется, если LLM-провайдер вернул статистику
токенов (бэкенд запрашивает её через `stream_options.include_usage` в OpenAI-совместимом API):
`inputTokens` = prompt_tokens, `outputTokens` = completion_tokens. Если провайдер usage не прислал,
поле отсутствует.

### Порядок событий в нормальном run с одним tool call

```
agent_started
llm_request_started (iteration=1) → llm_token* → llm_response_finished (finishReason=tool_calls)
tool_call_started → tool_call_finished
llm_request_started (iteration=2) → llm_token* → llm_response_finished (finishReason=stop)
agent_finished
```

### SSE-транспорт
- Content-Type: `text/event-stream`.
- Каждое событие агента — одна SSE-запись: `event: <type>\ndata: <json>\n\n`.
- Непустые `data:`-строки фронтенд парсит как JSON и ориентируется на поле `type`.
- При завершении run (после `agent_finished` или `error`) поток закрывается.

### Семантика для лога шагов (фронтенд)
- Записи лога создаются по первым событиям с новым `stepId`: `user`, `llm-<i>`, `tool-<name>-<idx>`, `answer`, `error-<idx>`.
- Статусы: запись появляется как `running` → при завершающем событии становится `success` / `error`.
  - `agent_started` → `user` success (текст запроса раскрывается).
  - `llm_request_started` → `llm-<i>` running (промпт раскрывается).
  - `llm_response_finished`: `finishReason=stop`/`tool_calls` → success; `length`/`error` → error.
  - `tool_call_started` → `tool-<name>-<idx>` running (аргументы раскрываются).
  - `tool_call_finished`: `success` → success (результат раскрывается); `error` → error.
  - `agent_finished` → `answer` success (ответ раскрывается).
  - `error` → `error-<idx>` error (сообщение раскрывается).
- Пояснения шагов («что происходит и зачем») фронтенд генерирует сам из семантики событий выше — это презентационный слой, частью контракта не является.
