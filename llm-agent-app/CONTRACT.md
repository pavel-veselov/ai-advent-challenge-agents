# Контракт событий и API (shared between backend & frontend)

> Этот файл — единственный источник истины для контракта. Backend и frontend следуют ему строго.

## API

| Метод | Путь | Тело / Параметры | Ответ |
|-------|------|------------------|-------|
| `POST` | `/api/chat` | `{ "sessionId": string, "message": string }` | `text/event-stream` — поток событий агента (см. ниже) |
| `GET` | `/api/sessions/{sessionId}/history` | — | `{ "sessionId": string, "messages": [{ "id": number, "role": "user"\|"assistant"\|"system", "content": string, "promptTokens": number\|null, "completionTokens": number\|null }], "totals": { "promptTokens": number, "completionTokens": number, "costUsd": number } \| null }` — см. раздел «История и ветки» ниже |
| `DELETE` | `/api/sessions/{sessionId}` | — | `{ "deleted": boolean }` — удаляет всю историю сессии и ВСЕ per-session данные (сжатие, настройки LLM, стратегию контекста, факты, ветки); рабочую память ПРОЕКТА (общую для сессий проекта) и глобальную LTM НЕ трогает; всегда 200 |
| `GET` | `/api/sessions` | — | `{ "sessions": [{ "sessionId": string, "messageCount": number, "promptTokens": number, "completionTokens": number, "costUsd": number, "lastActivity": string, "firstUserMessage": string\|null, "projectId": number }] }` — все сессии с агрегатами по токенам, стоимостью и первым user-сообщением (`projectId` — id проекта сессии; `-1` — сессия без строки в `chat_sessions`) |
| `GET` | `/api/stats` | — | `{ "sessionCount": number, "messageCount": number, "promptTokens": number, "completionTokens": number, "costUsd": number, "lifetime": { "sessions": number, "promptTokens": number, "completionTokens": number, "totalTokens": number, "costUsd": number } }` — глобальная статистика по всем сессиям + кумулятивные счётчики «за всё время» (переживают удаление сессий) |
| `GET` | `/api/llm-settings` | — | применённые настройки LLM — та же структура, что `settings` у `agent_started` (см. ниже) |
| `PUT` | `/api/llm-settings` | частичное обновление: `{ "model"?: string, "temperature"?: number, "topP"?: number, "topK"?: number\|null, "maxTokens"?: number\|null, "reasoningEnabled"?: boolean\|null, "timeoutSeconds"?: number, "priceInputPer1M"?: number, "priceOutputPer1M"?: number }` | `200` — применённые настройки после обновления (та же структура, что `GET`); `400` — невалидное значение (см. раздел «Динамические настройки LLM») |
| `GET` | `/api/models` | — | `{ "models": [{ "id": string, "description": string, "enabled": boolean }] }` — каталог моделей: ровно 3 чат-модели, порядок фиксирован (см. «Каталог моделей») |
| `PUT` | `/api/models/{id}/enabled` | `{ "enabled": boolean }` | `200` — `{ "id": string, "enabled": boolean }`; `404` — неизвестный `id`; `400` — попытка отключить ТЕКУЩУЮ активную модель (`llm-settings.model`) или невалидное тело |
| `GET` | `/api/sessions/{sessionId}/compression` | — | `200` — `{ "sessionId": string, "enabled": boolean, "keepLast": number, "summaryEvery": number }` — настройки сжатия истории сессии; `404` — неизвестная сессия (нет ни одного сообщения) |
| `PUT` | `/api/sessions/{sessionId}/compression` | частичное обновление: `{ "enabled"?: boolean, "keepLast"?: number, "summaryEvery"?: number }` (отсутствующее поле не меняется) | `200` — полное состояние после обновления (та же структура, что `GET`); `400` — невалидное значение (`enabled` не boolean, `keepLast` вне 1..50, `summaryEvery` вне 2..100); `404` — неизвестная сессия |
| `GET` | `/api/sessions/{sessionId}/llm-settings` | — | `200` — `{ "model": string, "contextLimit": number, "temperature": number, "topP": number, "topK": number\|null, "maxTokens": number\|null, "timeoutSeconds": number, "priceInputPer1M": number, "priceOutputPer1M": number, "reasoningEnabled": boolean }` — эффективные настройки LLM сессии (те же поля, что у глобального `/api/llm-settings`, БЕЗ `provider`): per-field сохранённое значение сессии, иначе ТЕКУЩЕЕ глобальное; `404` — неизвестная сессия |
| `PUT` | `/api/sessions/{sessionId}/llm-settings` | частичное обновление: `{ "model"?: string, "contextLimit"?: number, "temperature"?: number, "topP"?: number, "topK"?: number\|null, "maxTokens"?: number\|null, "timeoutSeconds"?: number, "priceInputPer1M"?: number, "priceOutputPer1M"?: number, "reasoningEnabled"?: boolean\|null }` — отсутствующее поле не меняется; `null` у любого поля — снять переопределение сессии (эффективно применяется ТЕКУЩЕЕ глобальное значение) | `200` — полное эффективное состояние после обновления (та же структура, что `GET`); `400` — невалидное значение (модель не из каталога/отключена; числовые поля — проверки как в глобальном PUT; `maxTokens` дополнительно ≤ контекстное окно эффективной модели); `404` — неизвестная сессия |
| `GET` | `/api/sessions/{sessionId}/context-strategy` | — | `200` — `{ "sessionId": string, "strategy": "none"\|"sliding_window"\|"sticky_facts"\|"summary"\|"branching", "windowSize": number, "activeBranchId": number\|null }` — стратегия контекста сессии; строки нет → `strategy="none"`, `windowSize=12`, `activeBranchId=null`; `404` НЕ выбрасывается |
| `PUT` | `/api/sessions/{sessionId}/context-strategy` | частичное обновление: `{ "strategy"?: string, "windowSize"?: number }` (отсутствующее поле не меняется) | `200` — полное состояние после обновления (та же структура, что `GET`); `400` — неизвестная стратегия или `windowSize` вне 1..50; `404` НЕ выбрасывается. Побочные эффекты: `summary` → `compression.enabled=true`; любая другая стратегия → `compression.enabled=false`; `branching` → плюс ленивое подключение веток (см. «Стратегии контекста») |
| `GET` | `/api/sessions/{sessionId}/facts` | — | `200` — `{ "sessionId": string, "facts": { "ключ": "значение" } }` — «липкие факты» сессии (порядок — порядок вставки; пусто — фактов ещё нет) |
| `GET` | `/api/sessions/{sessionId}/branches` | — | `200` — `{ "sessionId": string, "activeBranchId": number\|null, "branches": [ { "id": number, "name": string, "headMessageId": number\|null, "createdAt": string } ] }` — ветки диалога; пусто — веток нет |
| `POST` | `/api/sessions/{sessionId}/branches` | `{ "messageId": number }` | `200` — объект ветки (`{ "id", "name", "headMessageId", "createdAt" }`): ветка создаётся с головой в `messageId` (fork), авто-имя «Ветка N» (N = число веток + 1), становится АКТИВНОЙ; `400` — `messageId` не принадлежит сессии |
| `PUT` | `/api/sessions/{sessionId}/branches` | `{ "activeBranchId": number }` | `200` — полный GET-shape после переключения активной ветки; `400` — неизвестная ветка |
| `GET` | `/api/projects` | — | `200` — `[{ "id": number, "name": string, "createdAt": string, "updatedAt": string }]` — все проекты в порядке создания |
| `POST` | `/api/projects` | `{ "name": string }` | `200` — созданный проект (тот же shape, что у `GET /api/projects`); `400` — пустое/отсутствующее `name` |
| `PATCH` | `/api/projects/{id}` | `{ "name": string }` | `200` — переименованный проект; `404` — нет проекта; `400` — пустое `name` |
| `DELETE` | `/api/projects/{id}` | — | `200` — `{ "deleted": true }` — КАСКАДНОЕ удаление: все сессии проекта (история + реестр `chat_sessions`), все per-session данные (сжатие, настройки LLM, стратегия контекста, факты, ветки) и рабочая память ПРОЕКТА; LTM (глобальная) НЕ трогается; `404` — нет проекта |
| `POST` | `/api/projects/{id}/sessions` | `{ "title"?: string }` | `200` — `{ "sessionId": string, "projectId": number, "title": string\|null }` — создание сессии В проекте (server-side id, UUID); `404` — нет проекта |
| `GET` | `/api/projects/{id}/sessions` | — | `200` — `[{ "sessionId", "title", "messageCount", "promptTokens", "completionTokens", "costUsd", "lastActivity", "firstUserMessage", "projectId" }]` — сессии проекта (включая пустые), по последней активности DESC; `404` — нет проекта |
| `GET` | `/api/projects/{projectId}/memory` | — | `200` — `{ "working": { "task": string\|null, "notes": string[] }, "longTerm": [...] }` — память ПРОЕКТА: `working` — ОБЩАЯ для всех сессий проекта (см. «Память агента»), `longTerm` — ГЛОБАЛЬНЫЙ список (все сессии; `sourceSessionId` — сессия-источник); для нового/несуществующего проекта — пустые структуры (НИКОГДА не `404`) |
| `POST` | `/api/projects/{projectId}/memory/notes` | `{ "note": string }` | `200` — `{ "note": string, "notes": string[] }` — заметка ПОЛЬЗОВАТЕЛЯ добавлена в рабочую память ПРОЕКТА (`note` — добавленный текст, `notes` — полный актуальный список; cap 1000 на заметку); `400` — пустая `note`; `404` — проекта нет. Единственный путь записи WM (только пользователь) |
| `POST` | `/api/projects/{projectId}/memory/new-task` | — | `200` — рабочая память ПРОЕКТА очищена (`task=null`, `notes=[]` — «новая задача»); `longTerm` не трогает |
| `POST` | `/api/sessions/{sessionId}/memory/long-term` | `{ "type": "profile"\|"decision"\|"knowledge", "key": string, "value": string }` | `200` — созданная/обновлённая запись (longTerm-shape из `GET .../memory`): upsert по (type, key), `sourceSessionId` = сессия запроса; `400` — невалидный `type` (должен быть `profile`/`decision`/`knowledge`) или пустые `key`/`value` |
| `DELETE` | `/api/sessions/{sessionId}/memory/long-term/{entryId}` | — | `200` — запись долговременной памяти с `id = entryId` удалена; `404` — такой записи нет |

### Проекты (задача над сессиями)

Иерархия **Проект → Сессии** (день 12): сессии больше не создаются неявно первым сообщением —
они СОЗДАЮТСЯ ЯВНО внутри проекта (`POST /api/projects/{id}/sessions`, server-side `sessionId` = UUID)
и хранятся в реестре `chat_sessions` (FK на `projects`). «Без проекта» намеренно нет: старые
(неявные) сессии и их данные удаляются миграцией при старте — приложение работает «чисто», с проектами.

- **Таблицы SQLite**: `projects (id, name, created_at, updated_at)` + `chat_sessions (session_id
  TEXT PK, project_id FK → projects, title, created_at)` + индекс по `project_id`. `chat_messages`
  остаётся таблицей истории; `agent_working_memory` с day-12 ключуется по **`project_id`** (TEXT),
  а не по сессии.
- **Рабочая память (WM) = память ПРОЕКТА**: `{ task, notes[] }` — ОБЩАЯ для всех сессий проекта
  (переключатель сессии внутри проекта переносит ту же WM). Читается агентом по `projectId` (для
  сессии без строки в `chat_sessions` — fallback на `sessionId`) и ПОДАЁТСЯ модели контекстным
  блоком; пишется ТОЛЬКО пользователем через `POST /api/projects/{projectId}/memory/notes`.
  Сброс — `POST /api/projects/{projectId}/memory/new-task`; каскадное удаление — `DELETE /api/projects/{id}`.
- **LTM (долговременная память) остаётся ГЛОБАЛЬНОЙ** (вне проектов): удаление проекта/сессии её
  не трогает; `sourceSessionId` записи хранит сессию-источник. Пишется ТОЛЬКО пользователем через
  `POST /api/sessions/{sessionId}/memory/long-term`.
- **День 13 — память пишет ТОЛЬКО пользователь**: авто-записи агента (захват `task` на старте run,
  заметки WM после tool-результатов) и инструмент `memory_save` УДАЛЕНЫ. Агент память больше НЕ
  пишет — WM/LTM лишь подаются модели как контекстные блоки.
- **`memory_updated`** (SSE): класс события сохранён для обратной совместимости схемы, НО агент
  его БОЛЬШЕ НЕ ЭМИТИРУЕТ (авто-записи, на которые эмиссия была завязана, удалены); REST-эндпоинты
  памяти SSE не отправляют — фронтенд после мутаций делает refetch `GET /api/projects/{projectId}/memory`.
- **Каскад DELETE /api/projects/{id}**: для каждой сессии проекта удаляются `chat_messages` +
  строка `chat_sessions` + per-session данные (сжатие, настройки LLM, стратегия контекста, факты,
  ветки); дополнительно удаляется рабочая память проекта (`agent_working_memory` по project_id).
  LTM не трогается.

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
**переживает перезапуск backend**. Схема таблиц `chat_messages`, `projects` и `chat_sessions`
(реестр сессий внутри проектов, day-12), `lifetime_stats`, `app_settings` (динамические настройки LLM),
`app_models` (состояние «включена/отключена» моделей каталога),
`session_compression` (per-session настройки сжатия истории), `session_summaries` (свёрнутые
резюме), `session_llm_settings` (per-session настройки LLM), `session_context_strategy`
(стратегия контекста + активная ветка), `session_facts` («липкие факты») и `session_branches`
(ветки диалога) создаётся автоматически при старте из
`backend/src/main/resources/schema.sql` (`spring.sql.init.mode=always`); для старых файлов БД
таблица `lifetime_stats` и колонки токенов добавляются миграцией при инициализации `SessionStore`,
а `app_settings`/`app_models`/`session_compression`/`session_summaries`/`session_llm_settings`/
`session_context_strategy`/`session_facts`/`session_branches` досоздаются имплементациями
соответствующих хранилищ (страховочные `CREATE TABLE IF NOT EXISTS` в init-блоках + `ALTER TABLE`
для новых колонок `prompt_tokens`/`completion_tokens`/`parent_id` у `chat_messages`).
`DELETE /api/sessions/{sessionId}` удаляет все строки сессии из `chat_messages`, **не**
уменьшает кумулятивные счётчики `lifetime_stats` («за всё время») и — вместе с историей —
удаляет все per-session данные: сжатие (`session_compression`, `session_summaries`), настройки
LLM (`session_llm_settings`), стратегию контекста (`session_context_strategy`), «липкие факты»
(`session_facts`) и ветки (`session_branches`).

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
| `agent_started` | `{ "userMessage": string, "settings": { "provider": string, "model": string, "temperature": number, "topP": number, "topK": number\|null, "maxTokens": number\|null, "reasoningEnabled": boolean, "timeoutSeconds": number, "contextLimit": number, "priceInputPer1M": number, "priceOutputPer1M": number, "maxToolCallIterations": number, "tools": string[], "contextStrategy": string } }` | `user` | «Запрос пользователя» |
| `llm_request_started` | `{ "iteration": number, "prompt": [{ "role": string, "content": string }], "estimatedRequestTokens": number }` | `llm-<iteration>` | «LLM (итерация N)» |
| `llm_token` | `{ "delta": string }` | `llm-<iteration>` | — (токен, статус running) |
| `llm_response_finished` | `{ "finishReason": "stop"\|"tool_calls"\|"length"\|"error", "estimatedRequestTokens": number\|null, "costUsd"?: number, "usage"?: { "inputTokens": number, "outputTokens": number } }` | `llm-<iteration>` | — (статус success/error) |
| `tool_call_started` | `{ "toolName": string, "args": object }` | `tool-<toolName>-<idx>` | «Инструмент: <toolName>» |
| `tool_call_finished` | `{ "result": string, "status": "success"\|"error" }` | `tool-<toolName>-<idx>` | — (статус, результат) |
| `agent_finished` | `{ "finalText": string }` | `answer` | «Ответ» |
| `context_summary_started` | `{ "foldCount": number, "prompt": [{ "role": string, "content": string }] }` | `context-summary` | «Сжатие истории» (идёт при сжатии ДО основного цикла); `prompt` — точный промпт вызова резюмирования |
| `context_summary_finished` | `{ "foldCount": number, "promptTokens": number, "completionTokens": number, "summary": string }` | `context-summary` | — (финализация сжатия); `summary` — дословный текст резюме от LLM |
| `facts_updated` | `{ "facts": { "ключ": "значение" } }` | `facts` | — (sticky_facts): только что извлечённые «липкие факты» сохранены и применены к контексту текущего run; идёт ДО основного цикла (фиксированный `stepId`, вне нумерации итераций, как `context-summary`); payload минимальный — полей токенов нет |
| `memory_updated` | `{ "projectId": string, "working": { "task": string\|null, "notes": string[] }, "longTerm": [{ "id": number, "sourceSessionId": string, "type": "profile"\|"decision"\|"knowledge", "key": string, "value": string, "createdAt": string, "updatedAt": string }] }` | `memory` | — (memory): полный снапшот памяти — рабочая память ПРОЕКТА (`working`, общая для сессий проекта) и ГЛОБАЛЬНАЯ долговременная (`longTerm` — все сессии, `sourceSessionId` помнит происхождение); приходит, когда агент записывает рабочую память (task/notes) или когда выполняется tool `memory_save`; фиксированный `stepId`, вне нумерации итераций (как `context-summary`); REST-эндпоинты памяти его НЕ отправляют — UI после них делает refetch (см. «Память агента (memory layers)») |
| `log` | `{ "text": string }` | `log-<idx>` | — «обычная» строка лога каждого действия агента (тот же текст, что в серверном логе с префиксом `[AGENT]`); служебное событие для панели «Логи» UI, на работу агента не влияет |
| `error` | `{ "message": string }` | `error-<idx>` | — (только баннер в UI) |

Правила генерации `stepId`:
- `llm-<iteration>` — iteration начинается с 1.
- `tool-<toolName>-<idx>` — idx (начиная с 0) отличает несколько вызовов одного инструмента в рамках одного run.

Семантика `error`-события при сбое апстрима LLM (GPUStack):
- `payload.message` — человекочитаемый текст, **дословно** показываемый в UI. Содержит причину, НЕ закрытую за «Failed to fetch»:
  - `Ошибка LLM API (HTTP <status>): <message>` — апстрим вернул не-2xx (4xx и 5xx); `<message>` —
    текст из тела ответа (`{"error":{"message":"…"}}` / `{"message":"…"}`), либо сырое тело (обрезанное до 500 символов);
  - `Превышен таймаут ожидания ответа от LLM (<N> с). …` — апстрим молчал дольше `timeoutSeconds`;
  - `Нет связи с сервером LLM: …` — соединение отклонено/не резолвится/оборвано;
  - `Ошибка агента: …` / `Ошибка сервера: …` — прочие внутренние ошибки.
- Любой сбой апстрима завершает поток **error-событием перед закрытием** — поток не обрывается молча
  (иначе фронтенд видел бы голую сетевую ошибку браузера). Сетевой обрыв самого SSE (нет связи с backend —
  браузерный `TypeError "Failed to fetch"`) фронтенд показывает как «Нет связи с сервером».

Поле `settings` у `agent_started` — применённые настройки LLM этого запуска (см. также
«Per-session настройки LLM»: для сессии с собственными настройками ВСЕ редактируемые поля
(`model`/`temperature`/`topP`/`topK`/`maxTokens`/`timeoutSeconds`/`priceInputPer1M`/
`priceOutputPer1M`/`reasoningEnabled`/`contextLimit`) отражают эффективные значения сессии,
иначе — глобальные): `provider` (gpustack),
`model`, `temperature`, `topP` (nucleus sampling, уходит в API всегда), `topK` (top-k sampling; `null` — не задано,
параметр в API не уходит), `maxTokens` (лимит выходных токенов; по умолчанию `10000`; при `null`
в PUT — вернуть к значению по умолчанию, поэтому в выдаче всегда число, если дефолт задан),
`timeoutSeconds`, `reasoningEnabled` (включено ли «рассуждение» модели; при `false` клиент шлёт
`chat_template_kwargs.enable_thinking=false`, см. ниже), `contextLimit` (лимит контекста модели в токенах — сам
лимит локально не проверяется: при переполнении ошибка апстрима пробрасывается дословно, см. ниже),
`priceInputPer1M` / `priceOutputPer1M` (условная цена в USD за 1M входных/выходных токенов),
`maxToolCallIterations` (лимит цикла tool-calling), `tools`
(отсортированный список имён зарегистрированных инструментов, уходящих в параметр `tools` API),
`contextStrategy` (разрешённая для этого run стратегия контекста — одна из
`none|sliding_window|sticky_facts|summary|branching`; правило разрешения см. «Стратегии контекста»).
Секреты (`apiKey`) и внутренний `baseUrl` наружу не отдаются. Значения по умолчанию настраиваются env
(`LLM_TOP_P` / `LLM_TOP_K` / `LLM_MAX_TOKENS` / `LLM_CONTEXT_LIMIT` / `LLM_PRICE_INPUT_PER_1M` /
`LLM_PRICE_OUTPUT_PER_1M` и др.; по умолчанию `LLM_MAX_TOKENS=10000`).
Та же структура доступна через `GET /api/llm-settings` — фронтенд забирает настройки при открытии
страницы и показывает блок в самом верху лога шагов до первого запроса; на каждом запуске блок
обновляется из `agent_started`.

### Динамические настройки LLM (GET/PUT /api/llm-settings)

Настройки LLM изменяются на лету через `PUT /api/llm-settings` БЕЗ перезапуска backend
и персистятся в SQLite-таблице `app_settings` (ключ-значение, см. schema.sql), поэтому переживают
перезапуск backend. При старте применяются defaults из конфигурации (env), поверх которых
загружаются сохранённые строки `app_settings`.

- **Каталог моделей** — единственный источник выбора `model`. Доступные модели, их контекстные окна
  (1K = 1024 токена) и описания для UI (GET /api/models):

  | model | contextLimit (токены) | description |
  |-------|----------------------|-------------|
  | `qwen3.8-27b` | 202752 (198K) | Универсальная чат-модель с окном 202 752 токена. Режим рассуждений включается и отключается в настройках. |
  | `deepseek-v4-flash` | 1048576 (1M) | Быстрая и экономичная чат-модель. Режим рассуждений включается и отключается в настройках. |
  | `glm-5.3-flash` | 262144 (256K) | Чат-модель с принудительным режимом рассуждений — шлюз не позволяет его отключить. |

  Порядок `models` в ответе `GET /api/models` фиксирован (qwen3.8-27b → deepseek-v4-flash → glm-5.3-flash)
  и совпадает с порядком каталога в backend.
- **Включение/отключение моделей (GET /api/models, PUT /api/models/{id}/enabled)**: каждая модель каталога
  имеет флаг `enabled` (по умолчанию `true`), персистящийся в SQLite-таблице `app_models` и переживающий
  перезапуск backend. Отключённая модель остаётся в каталоге (видна в `GET /api/models` со значением
  `enabled=false`), но её **нельзя выбрать** в `PUT /api/llm-settings` → `400` с сообщением
  `Модель отключена в каталоге: <id>` (отличается от `Неизвестная модель` — та остаётся без изменений).
  `PUT /api/models/{id}/enabled` c `{"enabled": false}` возвращает `400`, если `id` — ТЕКУЩАЯ активная
  модель (`llm-settings.model`): «Нельзя отключить активную модель '<id>'.». Неизвестный `id` → `404`.
  Отключение модели не трогает логику запроса к LLM — лишь запрещает её выбор в настройках.
- **`contextLimit` выводится из выбранной модели по каталогу**, отдельно не хранится и не
  принимается в PUT: смена `model` автоматически пересчитывает `contextLimit`. Если модель ещё
  не выбиралась через PUT и не входит в каталог (например дефолт `default-coding` из env), то
  `contextLimit` берётся из конфигурации (`LLM_CONTEXT_LIMIT`).
- **`provider` неизменяем** — определяется на старте из окружения (`LLM_PROVIDER`) и привязан
  к клиенту транспорта; изменения `provider` в теле PUT игнорируются, текущий провайдер сохраняется.
- **Изменяемые поля** PUT: `model` (обязана быть в каталоге), `temperature` (>= 0),
  `topP`, `topK` (число или null; <= 0 трактуется как «не задано»), `maxTokens` (число > 0
  или null — сброс к дефолту 10000), `reasoningEnabled` (boolean; null — сброс к дефолту `true`),
  `timeoutSeconds` (> 0), `priceInputPer1M` / `priceOutputPer1M` (>= 0).
  Некорректные значения → `400`. `maxToolCallIterations` и `tools` — статичны, в PUT игнорируются.
- **`reasoningEnabled` и «рассуждение» модели**: поле включено по умолчанию (`true`), дефолт
  настраивается env `LLM_REASONING_ENABLED`. При `false` GPUStack-клиент добавляет в тело запроса
  `chat_template_kwargs: { "enable_thinking": false }` — шлюз передаёт его vLLM, который выключает
  thinking (проверено для `qwen*` и `deepseek*`: чисто короткие ответы, поле reasoning пустое).
  **Исключение — модели `glm*`**: у них визуальное рассуждение форсировано, kwarg лишь очищает
  поле reasoning, а текст рассуждений протекает в `content`, поэтому для моделей с префиксом `glm`
  (без учёта регистра) `chat_template_kwargs` НЕ отправляется ни при каком значении настройки.
  `reasoning_effort` для выключения не используется — у него нет состояния «выкл».
  При `true` (или отсутствии поля) `chat_template_kwargs` не шлется — vLLM оставляет thinking включённым.
- **Чтение на каждый запрос**: `AgentImpl` (лимит контекста, maxTokens, тарифы стоимости) и клиент
  к LLM (model, temperature, top_p/top_k, max_tokens, timeout) берут ТЕКУЩИЕ динамические значения
  на каждый запрос, т.е. изменения применяются к следующим запросам без рестарта.

Поле `prompt` у `llm_request_started` — точный массив сообщений, отправленный в LLM на этой итерации
(system + история сессии + наблюдения инструментов). Для assistant-сообщения с tool_calls в `content`
уходит `[вызов инструмента: <имена>]`. Поле служит для отображения промпта в логе шагов (режим отладки).

Поле `estimatedRequestTokens`:
- у `llm_request_started` — всегда `0`;
- у `llm_response_finished` — всегда `null`.
Поля сохранены в схеме для совместимости, но локальная оценка токенов до отправки в LLM **удалена**
(jtokkit и эндпоинты `/api/tokens/estimate`, `/api/tokens/estimate-context` больше не существуют).

Переполнение контекста: никакой pre-send проверки в агенте нет — запрос всегда уходит в LLM. При
переполнении апстрим (GPUStack) возвращает ошибку (обычно HTTP 400), которую backend пробрасывает
пользователю **дословно** через `error`-событие (см. семантику `error` выше).

Поле `costUsd` у `llm_response_finished` — условная стоимость запроса+ответа в USD:
`(inputTokens*priceInputPer1M + outputTokens*priceOutputPer1M) / 1_000_000`, считается только по точному
`usage` из API. `null`, если провайдер usage не вернул.

Поле `usage` у `llm_response_finished` — опционально. Заполняется, если LLM-провайдер вернул статистику
токенов (бэкенд запрашивает её через `stream_options.include_usage` в OpenAI-совместимом API):
`inputTokens` = prompt_tokens, `outputTokens` = completion_tokens. Если провайдер usage не прислал,
поле отсутствует.

История (`GET /api/sessions/{sessionId}/history`): у каждого сообщения — `promptTokens`
(user-сообщения больше НЕ получают локальную оценку — поле `null`; у assistant — точный
`inputTokens` из usage) и `completionTokens`
(только для assistant из usage; `null`, если неизвестно). `totals` — сумма токенов по всем сообщениям
и условная стоимость по **текущим** тарифам настроек
(`(ΣpromptTokens*priceInputPer1M + ΣcompletionTokens*priceOutputPer1M) / 1_000_000`); `null`, если
токенов нет ни у одного сообщения. Токены персистятся в SQLite (колонки `prompt_tokens`/`completion_tokens`,
добавляются миграцией к существующим БД).

### Агрегация по сессиям

`GET /api/sessions` — список всех сессий с агрегатами по токенам, отсортированный по последней
активности (самая свежая сессия — первой):

```json
{
  "sessions": [
    {
      "sessionId": "demo",
      "messageCount": 12,
      "promptTokens": 8400,
      "completionTokens": 1200,
      "costUsd": 0.00108,
      "lastActivity": "2026-09-09 10:24:01",
      "firstUserMessage": "сколько будет 2+2?"
    }
  ]
}
```

`GET /api/stats` — глобальная статистика по всем сессиям (сумма агрегатов в памяти) + кумулятивная
статистика «за всё время»:

```json
{
  "sessionCount": 3,
  "messageCount": 27,
  "promptTokens": 18850,
  "completionTokens": 3910,
  "costUsd": 0.002276,
  "lifetime": {
    "sessions": 5,
    "promptTokens": 31240,
    "completionTokens": 7560,
    "totalTokens": 38800,
    "costUsd": 0.004088
  }
}
```

Поля агрегатов:
- `messageCount` — число сообщений сессии; `promptTokens`/`completionTokens` — суммы колонок
  `prompt_tokens`/`completion_tokens` по всем сообщениям сессии (0, если токенов нет — никогда не `null`).
- `sessionCount` у `/api/stats` — число сессий; `messageCount`/`promptTokens`/`completionTokens` —
  суммы соответствующих агрегатов по всем сессиям.
- `costUsd` у обоих эндпоинтов — условная стоимость по **текущим** тарифам настроек:
  `(promptTokens*priceInputPer1M + completionTokens*priceOutputPer1M) / 1_000_000`; для сессии/строки
  без токенов — `0.0` (не `null`). Пустая база: `/api/sessions` → `{ "sessions": [] }`,
  `/api/stats` → все значения 0 (`sessionCount` = 0), `lifetime` → нулевые счётчики.
- `lifetime` у `/api/stats` — кумулятивные счётчики «за всё время» из таблицы `lifetime_stats`
  (одиночная строка-счётчик): `sessions` — число **созданных** сессий (первая запись сессии),
  `promptTokens`/`completionTokens` — суммы токенов финализированных assistant-сообщений,
  `totalTokens` = `promptTokens + completionTokens`, `costUsd` — накопленная стоимость по тарифам,
  действовавшим на момент каждого сообщения. Эти счётчики **только растут**: удаление сессий
  (`DELETE /api/sessions/{sessionId}`) их не уменьшает — так работает «статистика за всё время».
- `lastActivity` — `created_at` последнего сообщения сессии (UTC `YYYY-MM-DD HH:MM:SS`).
- `firstUserMessage` — содержимое **первого** user-сообщения сессии (сабквери по `id`), для
  человекочитаемого заголовка вкладки; `null`, если в сессии нет user-сообщений. Оборачивается
  в JSON-строку как есть (без обрезки — фронтенд сам ограничит длину для отображения).

### Сжатие истории (per-session)

Включается/выключается для каждой сессии отдельно через `GET/PUT /api/sessions/{sessionId}/compression`
(см. таблицу API выше). Хранение — SQLite-таблицы `session_compression` (settings) и `session_summaries`
(уже свёрнутые резюме), переживают перезапуск backend. Значения по умолчанию: `enabled=false`,
`keepLast=5`, `summaryEvery=10`. Валидация PUT:
`enabled` — boolean, `keepLast` 1..50, `summaryEvery` 2..100; отсутствующие поля не меняются;
неизвестная сессия (нет ни одного сообщения) → 404.

**Триггер сжатия** (в начале каждого run, ПОСЛЕ добавления нового user-сообщения в историю):
- `total` = число сообщений (user+assistant), уже сохранённых для сессии (включая новый вопрос);
- `foldable` = сообщения, ещё НЕ покрытые текущим резюме И лежащие ВНЕ хвоста, который остаётся
  в контексте дословно: первые `total - keepLast - 1` сообщений, исключая уже свёрнутые. Хвост
  (`keepLast` сообщений перед вопросом) и сам новый вопрос в резюмирование НЕ уходят — иначе
  модель резюмирования отвечает на последний вопрос вместо сжатия, а хвостовое сообщение
  дублировалось бы (в резюме и в контексте «как есть»);
- если `foldable.length >= summaryEvery` — запускается сжатие:
  - вызов LLM только для резюмирования, БЕЗ системного промпта: прежнее резюме (если есть,
    помечено «Предыдущее резюме») + `foldable`-сообщения с их реальными ролями
    (`user`/`assistant`) + простой запрос «Сожми историю нашего диалога. Это служебное сообщение
    о сжатии — его запоминать и включать в резюме не нужно.» ПОСЛЕДНИМ
    user-сообщением (в конце — чтобы модель отвечала на запрос, а не на последнее сообщение
    истории); инструменты не передаются;
  - новое резюме = текст ответа LLM; персистится в `session_summaries` вместе с `upto_order`
    (id последнего свёрнутого сообщения).
- Сообщения из БД НЕ удаляются — резюме лишь отмечает границу «уже покрыто».

**Сборка контекста при `enabled=true`** (для всех запросов основного цикла):
`[system промпт агента]` + `[резюме как system-сообщение «Резюме ранее: …»]` + последние `keepLast`
сообщений истории «как есть» + новый вопрос пользователя. Пока резюме ещё не создано (порог
`summaryEvery` не достигнут и прежнего резюме нет) — в контекст уходит **вся история целиком**:
хвостовой контекст без резюме молча терял бы старые сообщения. **При `enabled=false`** поведение не
меняется — в контекст уходит вся история целиком (без резюме, без обрезки).

**Fail-open**: если вызов LLM для резюмирования завершился ошибкой (LLM API 4xx/5xx, таймаут, сбой
сети) или вернул нетекстовый/пустой ответ — агент шлёт `error`-событие с причинами сбоя в сообщении
(«Ошибка сжатия истории: …») и **продолжает run без сжатия**: контекст этого run — вся история
(как при `enabled=false`), сжатие НЕ применяется, ничего не персистится. Сбою предшествует
`context_summary_started`, а вот `context_summary_finished` в этом случае НЕ будет.

**Резюме никогда не отдаётся наружу** через отдельные эндпоинты: содержимое
`session_summaries.summary` НЕ входит в `GET /api/sessions` и прочие агрегаты; его читает только
агент при построении контекста. При УСПЕШНОМ сжатии агент, кроме того, добавляет в историю диалога
системную заметку `role = "system"` («Сжатие контекста: N старых сообщений свернуто в резюме.
Контекст: X → Y токенов.») — она видна в `GET /api/sessions/{sessionId}/history` и в чате (приглушённая
строка), а при сборке LLM-контекста отфильтровывается (в резюмирование и в промпт агента не попадает).

События `context_summary_started`/`context_summary_finished` попадают в тот же SSE-поток, имеют
фиксированный `stepId = "context-summary"` и **не входят в нумерацию итераций** основного цикла
(original-нумерация `llm-<iteration>` начинается после сжатия). `foldCount` = число свёрнутых
сообщений; `prompt` (у `started`) — снимок промпта резюмирования в формате `llm_request_started`
(прежнее резюме при наличии, сворачиваемые сообщения, в конце запрос «Сожми историю нашего
диалога. Это служебное сообщение о сжатии — его запоминать и включать в резюме не нужно.» — БЕЗ
хвоста и нового вопроса); `promptTokens`/
`completionTokens` — токены из `usage` ответа резюмирования (0, если провайдер их не прислал);
`summary` (у `finished`) — дословный текст резюме, который вернула LLM (тот же, что сохранён в
сессии; фронтенд показывает его в логе шагов как «Результат»). `contextTokensBefore`/
`contextTokensAfter` — размер контекста ДО сжатия (system + вся история с вопросом) и ПОСЛЕ
(system + резюме + хвост + вопрос), подсчитанный BPE-токенизатором o200k_base (jtokkit,
+4 токена на сообщение на служебную разметку чат-формата); это фактический токенный подсчёт, а
не эвристика — значения «после» совпадают по порядку величины с `usage.prompt_tokens`
основного запроса. Текст системной заметки в истории: «Сжатие контекста: N старых сообщений
свернуто в резюме. Контекст: X → Y токенов.». Порядок в потоке:
`agent_started` → (при сжатии) `context_summary_started` → `context_summary_finished` →
`llm_request_started` (итерация 1) → …

> **Устаревший переключатель `/compression` синхронизирован со стратегией:** эндпоинт
> `/api/sessions/{sessionId}/compression` остаётся и работает как раньше, но при переключении
> стратегии через `/context-strategy` он ведётся в соответствие: стратегия `summary` →
> `compression.enabled=true`; любая другая стратегия → `compression.enabled=false` (legacy-сессия,
> включившая сжатие напрямую и не выбиравшая стратегию, продолжает работать «как раньше» — см.
> правило разрешения ниже). Обратное изменение `compression.enabled` стратегию не меняет
> (стратегия `none` + включённое сжатие разрешается в `summary`).

### Стратегии контекста (переключатель context-strategy)

Каждая сессия может выбрать стратегию построения контекста LLM-запроса через
`GET/PUT /api/sessions/{sessionId}/context-strategy` (см. таблицу API). Доступные стратегии:

| Стратегия | Контекст основного цикла агента |
|-----------|-------------------------------|
| `none` | Вся история целиком (не-системные сообщения по id) — поведение «как раньше» при выключенном сжатии |
| `sliding_window` | `system` + последние `windowSize` не-системных сообщений (новый вопрос — уже последнее сохранённое, входит в окно) |
| `sticky_facts` | `system` + (если факты есть) системное «Известные факты:\n- ключ: значение…» + последние `windowSize` не-системных сообщений; факты извлекаются LLM ДО основного цикла (см. событие `facts_updated`) |
| `summary` | Существующий механизм сжатия истории (резюме + хвост `keepLast` + вопрос), см. «Сжатие истории» выше — равно legacy `compression.enabled=true` |
| `branching` | `system` + цепочка АКТИВНОЙ ветки (корень → … → новый вопрос; только не-системные сообщения в хронологическом порядке) |

**Разрешение эффективной стратегии** (для каждого `POST /api/chat` и для `GET /history`):
сохранённая в `session_context_strategy` стратегия, а при `none` — **fallback на `summary`**,
если включено legacy-сжатие (`compression.enabled=true`). Это правило сохраняет старое поведение
сжатия для сессий, никогда не выбиравших стратегию: `/context-strategy` GET отдаёт `"none"`, а
чат этих сессий сжимает историю как раньше. Сессии, явно выбравшие `sliding_window` /
`sticky_facts` / `branching`, не сжимаются даже при включённом legacy-сжатии (в PUT стратегия
выключает `compression.enabled`).

**Настройки окна:** `windowSize` 1..50, по умолчанию 12 (применяется в `sliding_window` и
`sticky_facts`). Полное состояние GET: `{ sessionId, strategy, windowSize, activeBranchId }`
(`activeBranchId` — ид активной ветки для `branching`, служебное поле полного состояния).

**Семантика перехода на `branching`** (побочный эффект PUT `strategy=branching`):
- `compression.enabled` выключается (как для любой не-`summary` стратегии);
- история сессии линейно бэккафиллится: каждое не-системное сообщение получает
  `parent_id` предыдущего не-системного (корень — NULL; системные заметки не связываются);
- создаётся ветка по умолчанию **«Основная»** с головой в последнем не-системном сообщении.

**Дерево веток поддерживается автоматически для ВСЕХ стратегий** (SessionStore.append): каждое
не-системное сообщение прикрепляется к голове активной ветки и продвигает её; системные заметки
(о сжатии) голову не двигают и `parent_id` не получают. Поэтому ветки существуют даже у сессий,
которые не выбирали `branching` (после первого не-системного сообщения появляется «Основная»), а
переключение стратегии не ломает целостность дерева.

> **Совместимость:** изменение существующих поведений минимально — `SummaryRequestPrompt` и вся
> семантика `summary` не тронуты; `agent_started` лишь дополнен полем `settings.contextStrategy`.

### Память агента (memory layers)

Модель памяти агента состоит из двух персистентных слоёв (плюс краткосрочная память — окно
истории, см. «Стратегии контекста»):

- **Рабочая память (working memory, WM)** — **память ПРОЕКТА** (day-12): `{ task, notes[] }`
  (текущая задача и заметки), ОБЩАЯ для всех сессий проекта.
  **День 13 — пишется ТОЛЬКО пользователем**: авто-захват `task` на старте run и авто-заметки
  после tool-результатов УДАЛЕНЫ. Единственный путь записи — `POST /api/projects/{projectId}/memory/notes`
  (заметка, cap 1000 на заметку; task поле остаётся в схеме, но агентом/контроллером не
  устанавливается). Очищается `POST /api/projects/{projectId}/memory/new-task` («новая задача»)
  и каскадно при `DELETE /api/projects/{id}`; удаление ОТДЕЛЬНОЙ сессии WM ПРОЕКТА не трогает.
- **Долговременная память (long-term memory, LTM)** — ГЛОБАЛЬНАЯ (общая для всех сессий;
  `sourceSessionId` у записи помнит сессию-источник): записи `{ id, sourceSessionId, type,
  key, value, createdAt, updatedAt }`, `type ∈ profile|decision|knowledge`, `value` обрезается
  до 2000 символов. `DELETE /api/sessions/{sessionId}` LTM **НЕ трогает** — записи переживают
  удаление сессий. **День 13 — запись только пользователем** (инструмент `memory_save`
  УДАЛЁН): единственный путь — REST `POST /api/sessions/{sessionId}/memory/long-term`;
  upsert по (type, key). Агент LTM не пишет.

Агент память НЕ пишет и НЕ модифицирует: WM и LTM лишь ЧИТАЮТСЯ и подаются модели
контекстными блоками при каждом запросе (см. ниже) — блоки всегда отражают ТОЛЬКО
пользовательские данные (user-saved), а не продукты работы агента.

Хранение — SQLite-таблицы `agent_working_memory` (по ПРОЕКТУ: `project_id` PK (TEXT), `task`,
`notes` — JSON-массив строк, `updated_at`; одна строка на проект, память общая для сессий проекта)
и `agent_long_term_memory` (глобальная: `id` PK
AUTOINCREMENT, `source_session_id`, `type`, `key`, `value`, `created_at`, `updated_at`,
`UNIQUE(type, key)`), создаются автоматически, переживают перезапуск backend.

**Сборка контекста (порядок блоков)** — для ВСЕХ стратегий контекста, независимо от их выбора:

```
SYSTEM_PROMPT
→ блок рабочей памяти (omit, если пусто)
→ блок долговременной памяти (omit, если пусто)
→ факты/стратегия-блок (напр. «Известные факты» при sticky_facts)
→ окно истории (по разрешённой стратегии)
```

- **Блок рабочей памяти** — system-сообщение «Текущая задача: <task>» + нумерованный список
  `notes`; OMIT-ится, если `task == null` И `notes` пуст.
- **Блок долговременной памяти** — system-сообщение «Долговременная память»: top-20 записей
  (по `updated_at` DESC, формат `<type> | <key>: <value>` построчно); если записей больше 20 —
  доп. строка «…и ещё N записей в долговременной памяти»; OMIT-ится, если записей нет.
- Блоки пустые — молча пропускаются (fail-open: сбой чтения памяти не ломает run — контекст
  собирается без соответствующего блока).

**`memory_updated`** (SSE): класс события сохранён в схеме для обратной совместимости, НО агент
его БОЛЬШЕ НЕ ЭМИТИРУЕТ (эмиссии были привязаны к удалённым авто-записям). REST-эндпоинты памяти
SSE не отправляют вообще — фронтенд после мутаций делает refetch `GET /api/projects/{projectId}/memory`.

### История и ветки (branching в GET /history)

У каждого сообщения `GET /api/sessions/{sessionId}/history` теперь есть поле `id` (аддитивно).
При разрешённой стратегии `branching` И наличии веток история возвращает цепочку АКТИВНОЙ ветки:
корень → голова (только не-системные сообщения в хронологическом порядке); общие предки до точки
fork входят, сообщения других веток — нет. Иначе (legacy) — все сообщения сессии по id (включая
системные заметки, как и раньше). `POST /branches` с `{messageId}` создаёт ветку с головой в этом
сообщении (fork), имя «Ветка N» (N = число веток + 1) и делает её активной; `PUT /branches` с
`{activeBranchId}` переключает активную ветку. Сообщения, добавленные после fork/переключения,
уходят в активную ветку — именно они видны в её истории и контексте агента.

### Per-session настройки LLM (GET/PUT /api/sessions/{sessionId}/llm-settings)

Каждая сессия может иметь СОБСТВЕННЫЕ настройки LLM — ВСЕ редактируемые поля: `model`,
`temperature`, `topP`, `topK`, `maxTokens`, `timeoutSeconds`, `priceInputPer1M`,
`priceOutputPer1M`, `reasoningEnabled` (`contextLimit` выводится из эффективно выбранной
модели по каталогу и отдельно не хранится — как в глобальном PUT). Управляются через
`GET/PUT /api/sessions/{sessionId}/llm-settings` (см. таблицу API выше).
Хранение — SQLite-таблица `session_llm_settings`, переживает перезапуск backend.
Изменение настроек сессии A не влияет на сессию B (например температура).

- **Эффективные значения** (ответ `GET`/`PUT`, поле `settings` у `agent_started`): per-field
  правило — сохранённое значение сессии, если есть, иначе ТЕКУЩЕЕ глобальное значение
  (`GET /api/llm-settings`). Форма ответа — те же имена полей, что у глобального
  `/api/llm-settings`, БЕЗ `provider` (тот определён на уровне env и неизменяем; в `settings`
  события `agent_started` он, как и раньше, присутствует). `topK`/`maxTokens` нормализуются:
  0/не задано → `null`.
- **Fallback без персистентности**: строки настроек у сессии НЕТ → GET возвращает ТЕКУЩИЕ
  ГЛОБАЛЬНЫЕ значения (из `app_settings` / defaults, те же, что отдаёт `GET /api/llm-settings`)
  и НЕ создаёт строку; чат-поток такой сессии ведёт себя ровно как сегодня (глобальные настройки).
- **PUT — частичное обновление**: отсутствующие в теле ключи не меняются.
  **`null` у любого поля = СНЯТЬ переопределение сессии** — эффективно применяется ТЕКУЩЕЕ
  глобальное значение (как если бы сессия никогда не переопределяла это поле: `maxTokens: null` →
  глобальный дефолт 10000, если глобально не переопределён; `reasoningEnabled: null` → глобальное
  значение). Когда после PUT у сессии не осталось ни одного переопределения — строка удаляется.
- **`model`**: при значении в теле обязана быть в каталоге И включённой (сообщения те же, что в
  глобальном PUT: `Неизвестная модель: '<id>'. Доступные модели: …` и `Модель отключена в каталоге: <id>` —
  тот же каталог/флаг `app_models`); `null` снимает переопределение модели (применяется глобальная).
- **Валидация остальных полей** — идентична глобальному PUT (→ 400):
  - `contextLimit` — целое > 0 (значение не хранится — лимит всегда выводится из модели по каталогу);
  - `temperature` >= 0 (`не может быть отрицательной`), `topP` — число,
    `topK` — число (<= 0 трактуется как «не задано»);
  - `maxTokens` — целое в 1..контекстное окно эффективно выбранной модели
    (`maxTokens не может превышать контекстное окно модели (<N> токенов): …`);
  - `timeoutSeconds` > 0, `priceInputPer1M`/`priceOutputPer1M` >= 0, `reasoningEnabled` — boolean.
- **Чат-поток (resolved per-run)**: в начале каждого `POST /api/chat` агент разрешает эффективные
  настройки сессии по ВСЕМ полям (строка есть → свои значения; строки нет → глобальные) и применяет
  их ко ВСЕМ LLM-вызовам run (основной цикл и резюмирование): билдер запроса берёт отсюда
  `model`, `temperature`, `top_p`, `top_k`, `max_tokens`, `timeout` и гейт
  `chat_template_kwargs.enable_thinking` (`reasoningEnabled`, см. «Динамические настройки LLM»);
  поле `settings` события `agent_started` несёт тот же эффективный набор сессии.
  Две сессии могут одновременно работать с разными моделями, температурами и лимитами —
  выбор не глобальный.
- `DELETE /api/sessions/{sessionId}` удаляет строку `session_llm_settings` вместе с историей.

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
