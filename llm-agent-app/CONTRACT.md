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
| `GET` | `/api/sessions/{sessionId}/task-memory` | — | `200` — `{ "goal": string, "clarifications": string[], "constraints": string[], "updatedAt": string\|null }` — память задачи (день 25, task_memory): структурированное состояние диалога, извлечённое LLM после завершённых обменов (см. «Память задачи (task_memory, день 25)»); строки в `task_memory` нет / состояние пустое → пустая структура с `updatedAt: null`, НИКОГДА не `404`; запись ТОЛЬКО автоматическая (REST-мутаций у памяти задачи нет) |
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
| `GET` | `/api/kb` | — | `200` — `{ "knowledgeBases": [{ "id": number, "name": string, "status": "indexing"\|"indexed"\|"failed", "active": boolean, "strategy": "fixed"\|"structural", "chunkSize": number\|null, "overlap": number\|null, "embeddingModel": string, "documentsCount": number, "chunksCount": number\|null, "progress": { "processedDocs": number, "totalDocs": number, "percent": number, "etaSeconds": number\|null }\|null, "error": string\|null, "createdAt": string }] }` — все базы знаний (`progress` непусто только при `status=indexing`, `chunksCount` только для `indexed`; семантика полей и механика RAG — см. раздел «База знаний / RAG (день 22/23)» ниже) |
| `POST` | `/api/kb` | multipart-форма: `name` (непустая строка), `strategy` (`"fixed"`\|`"structural"`), `chunkSize`? (строка, положительное целое, размер чанка в символах), `overlap`? (положительное целое, перекрытие чанков в символах), `embeddingModel`? (id из `GET /api/kb/models`; если не задана — дефолтная модель каталога), `files[]` (минимум один файл, расширения `.md`, `.txt`, `.pdf`, `.kt`, `.ts`, `.js`, `.json`, `.csv`) | `201` — созданная база (item shape, как у `GET /api/kb`); индексация стартует фоновым джобом (`status=indexing`); `400` — валидация, тело `{ "error": string }` с дословным сообщением (список — в разделе «База знаний / RAG (день 22/23)»); `500` — не удалось сохранить базу/файлы |
| `PUT` | `/api/kb/{id}/active` | `{ "active": boolean }` | `200` — `{ "id": number, "active": boolean }`; `400` — `active` не boolean: `{ "error": "active: ожидалось true/false" }`; `404` — `{ "error": "База знаний не найдена: <id>" }` |
| `DELETE` | `/api/kb/{id}` | — | `200` — `{ "deleted": true }`: каскад (чанки/документы в БД, каталог файлов `data/kb/<id>/`, остановка идущей индексации); `404` — `{ "error": "База знаний не найдена: <id>" }`; `500` — `{ "error": "Не удалось удалить базу знаний: <id>" }` |
| `GET` | `/api/kb/models` | — | `200` — `{ "models": [{ "id": string, "dimension": number, "description": string }] }` — каталог эмбеддинг-моделей (сейчас одна: `qwen3-vl-embedding-8b`, dimension 4096); дефолтная (не заданная при `POST /api/kb`) — первая в каталоге |
| `GET` | `/api/kb/settings` | — | `200` — `{ "filterEnabled": boolean, "minScore": number, "candidateK": number, "topK": number, "rewriteEnabled": boolean, "refusalEnabled": boolean }` — текущие настройки RAG (дефолты, перекрытые сохранёнными в `app_settings`; см. раздел «База знаний / RAG (день 22/23)») |
| `PUT` | `/api/kb/settings` | полное обновление (все 6 полей тела): `{ "filterEnabled": boolean, "minScore": number, "candidateK": number, "topK": number, "rewriteEnabled": boolean, "refusalEnabled": boolean }` | `200` — сохранённые настройки (эхо тела после персиста); `400` — невалидное значение, тело `{ "message": string }` с дословным русским сообщением (список — в разделе «База знаний / RAG (день 22/23)»); при 400 НИЧЕГО не персистится |

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

### Память задачи (task_memory, день 25)

Per-session структурированное состояние диалога — `task_memory` (SQLite, одна строка на сессию):
`goal` (цель), `clarifications[]` (уточнения пользователя) и `constraints[]` (ограничения,
термины, требования). Пишется ТОЛЬКО автоматически; пользователь через REST состояние не правит
(в отличие от task_state дня 13 — FSM воркфлоу: таблицы и код независимы, UI-панели разные).

- **Извлечение (LLM)**: на НОРМАЛЬНОМ финальном пути run'а (после сохранения ответа в историю,
  ДО `agent_finished`) агент одним post-run LLM-вызовом (без инструментов, через обёрнутый
  клиент — записи «Запрос в llm»/«Ответ от llm» уходят в панель «Логи») извлекает ПОЛНУЮ замену
  состояния: строгий JSON `{"goal": string, "clarifications": string[], "constraints": string[]}`.
  В промпт идут прежнее состояние (JSON), текущее user-сообщение и финальный ответ (обрезка до
  1500 символов каждое); для продолжения воркфлоу (/continue) вместо служебного сообщения берётся
  последнее РЕАЛЬНОЕ user-сообщение из истории. Один ретрай на пустой/сбойный/не-JSON ответ
  (как extractFacts).
- **Caps в коде**: ≤10 пунктов на список, ≤300 символов на пункт, trim, пустые выбрасываются
  (TaskMemoryService.sanitize).
- **SSE `task_memory_updated`**: при успехе сохранения эмитится ДО `agent_finished` (payload —
  `{goal, clarifications, constraints}`, `stepId="task-memory"`). На паузе, error/length и при
  сбое извлечения/записи событие НЕ эмитится — состояние остаётся
  прежним; любой сбой — warn в лог, run не ломается (fail-open).
- **Инъекция в промпт**: при следующем run непустое состояние подаётся системным блоком
  `=== ПАМЯТЬ ЗАДАЧИ ===` (секции «Цель:» / «Уточнено пользователем:» / «Ограничения и
  термины:») между блоком LTM и инвариантами — для ВСЕХ стратегий контекста; пустое состояние
  блока не создаёт.
- **Настройки**: у функции НЕТ переключателя — всегда включена; отдельных LLM-настроек не
  добавляет (извлечение использует те же per-session настройки LLM, что и основной цикл).
- **REST**: `GET /api/sessions/{sessionId}/task-memory` — только чтение (см. таблицу API выше);
  строки нет / состояние пустое → пустая структура с `updatedAt: null` и 200 (никогда не 404).
- **DELETE сессии** строку `task_memory` не удаляет (извлечение при следующем обмене перезапишет
  её целиком); ORM-каскадов нет — таблица keyed по `session_id`, осиротевшие строки недостижимы.

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
| `llm_request_started` | `{ "iteration": number, "prompt": [{ "role": string, "content": string }], "estimatedRequestTokens": number, "requestBody"?: string (фактическое тело HTTP-запроса к LLM API, pretty JSON) }` | `llm-<iteration>` | «LLM (итерация N)» |
| `llm_token` | `{ "delta": string }` | `llm-<iteration>` | — (токен, статус running) |
| `llm_response_finished` | `{ "finishReason": "stop"\|"tool_calls"\|"length"\|"error", "estimatedRequestTokens": number\|null, "costUsd"?: number, "usage"?: { "inputTokens": number, "outputTokens": number }, "responseBody"?: string (тело ответа LLM API, собранное из стрима, pretty JSON) }` | `llm-<iteration>` | — (статус success/error) |
| `tool_call_started` | `{ "toolName": string, "args": object }` | `tool-<toolName>-<idx>` | «Инструмент: <toolName>» |
| `tool_call_finished` | `{ "result": string, "status": "success"\|"error" }` | `tool-<toolName>-<idx>` | — (статус, результат) |
| `agent_finished` | `{ "finalText": string, "sources"?: [{ "chunkId": number, "label": number, "kbName": string, "source": string, "section": string, "score": number }] }` | `answer` | «Ответ» |
| `context_summary_started` | `{ "foldCount": number, "prompt": [{ "role": string, "content": string }] }` | `context-summary` | «Сжатие истории» (идёт при сжатии ДО основного цикла); `prompt` — точный промпт вызова резюмирования |
| `context_summary_finished` | `{ "foldCount": number, "promptTokens": number, "completionTokens": number, "summary": string }` | `context-summary` | — (финализация сжатия); `summary` — дословный текст резюме от LLM |
| `facts_updated` | `{ "facts": { "ключ": "значение" } }` | `facts` | — (sticky_facts): только что извлечённые «липкие факты» сохранены и применены к контексту текущего run; идёт ДО основного цикла (фиксированный `stepId`, вне нумерации итераций, как `context-summary`); payload минимальный — полей токенов нет |
| `memory_updated` | `{ "projectId": string, "working": { "task": string\|null, "notes": string[] }, "longTerm": [{ "id": number, "sourceSessionId": string, "type": "profile"\|"decision"\|"knowledge", "key": string, "value": string, "createdAt": string, "updatedAt": string }] }` | `memory` | — (memory): полный снапшот памяти — рабочая память ПРОЕКТА (`working`, общая для сессий проекта) и ГЛОБАЛЬНАЯ долговременная (`longTerm` — все сессии, `sourceSessionId` помнит происхождение); приходит, когда агент записывает рабочую память (task/notes) или когда выполняется tool `memory_save`; фиксированный `stepId`, вне нумерации итераций (как `context-summary`); REST-эндпоинты памяти его НЕ отправляют — UI после них делает refetch (см. «Память агента (memory layers)») |
| `task_state_changed` | `{ "stage": string, "currentStep"?: string, "expectedAction"?: string, "paused": boolean, "plan"?: string, "implementation"?: string, "validation"?: string, "awaitConfirmation": boolean }` | `task-state` | — (task_state, день 13/14): агент только что успешно выполнил инструмент task_state — этап/шаг/ожидаемое действие сохранены в per-session `task_state`; идёт сразу после `tool_call_finished`, вне нумерации итераций (фиксированный `stepId`, как `context-summary`); условные поля (`currentStep`/`expectedAction`/`plan`/`implementation`/`validation`) опущены при null. REST-мутации состояния (пауза через UI, /continue, /cancel) SSE НЕ отправляют — фронтенд обновляет панель из ответа PUT/POST |
| `workflow_paused` | `{ "stage": string, "output": string, "await": boolean }` | `workflow` | — (воркфлоу день 14, ручной режим): агент завершил этап и ждёт подтверждения — под последним сообщением ассистента кнопки «Продолжить»/«Отмена»; эмитится в конце run ПОСЛЕ `agent_finished`; в авто-режиме не эмитится |
| `workflow_stage_finished` | `{ "stage": string, "output": string }` | `workflow-stage` | — (воркфлоу день 14, авто-режим): этап завершён, повествование сохранено и в историю, и в результат этапа (`plan`/`implementation`/`validation`) — надёжная граница этапа; фронтенд закрывает пузырь этапа и открывает новый для следующего; в ручном режиме не эмитится |
| `task_memory_updated` | `{ "goal": string, "clarifications": string[], "constraints": string[] }` | `task-memory` | — (task_memory, день 25): LLM-извлечение после завершённого обмена успешно сохранило структурированное состояние диалога в per-session `task_memory` (см. «Память задачи (task_memory, день 25)»); эмитится ТОЛЬКО на нормальном финальном пути, ДО `agent_finished`; на паузе, error/length и при сбое извлечения НЕ эмитится — состояние остаётся прежним |
| `scheduler_result` | `{ "text": string }` | `scheduler` | — (планировщик, день 17/18): результат периодической задачи доставляется активной сессии в фоновый SSE-канал ПОСЛЕ `AgentFinished`; фронтенд помечает сообщение как автоматическое (не user-вопрос) |
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

### База знаний / RAG (день 22/23)

Базы знаний (`/api/kb`, день 22) — наборы текстовых файлов, проиндексированных эмбеддингами
(`qwen3-vl-embedding-8b`, dim 4096, удалённый GPUStack `/v1/embeddings`). При каждом запросе
агент по тексту последнего user-сообщения ищет релевантные чанки по всем АКТИВНЫМ
ПРОИНДЕКСИРОВАННЫМ базам и подаёт их модели system-блоком `### База знаний`. Настройки RAG
(день 23): фильтр релевантности по порогу cosine, top-K до/после фильтра, query rewrite.
Детальная механика воронки, режимы сравнения A/B/C и ограничения — в
`docs/rag-reranking.md`.

**Жизненный цикл и поля базы** (item `GET /api/kb`, shape зафиксирован контрактом дня 22):
- `status`: `indexing` → `indexed` (успех) или `failed` (ошибка; `error` — текст ошибки).
  Индексация — фоновый джоб после `POST /api/kb`.
- `active` — участвует ли база в поиске (агент ищет только по `active=true` И `status=indexed`).
- `progress` непусто ТОЛЬКО при `status=indexing`: `processedDocs`, `totalDocs`,
  `percent` (= round(processed/total*100)), `etaSeconds` (null, пока обработано <1 документа).
- `chunksCount` заполняется ТОЛЬКО для `indexed`, иначе `null`; `documentsCount` — число
  загруженных файлов.
- Файлы базы на диске: `data/kb/<id>/` (имена санитизируются до `fileName`). `DELETE`
  удаляет каскадно: чанки/документы БД + каталог файлов + остановка идущей индексации
  (джоб молча завершится).

**Валидация `POST /api/kb`** (400, тело `{ "error": "<сообщение>" }`; сообщения дословно):
- `name не должен быть пустым` (пустое/отсутствующее `name` после trim);
- `strategy: ожидалось «fixed» или «structural», получено «<значение>»`;
- `chunkSize должен быть положительным числом` (непустое `chunkSize`, не число или <= 0);
- `overlap должен быть положительным числом` (аналогично);
- `Неизвестная модель эмбеддингов «<id>»: доступные — <список id из каталога>`;
- `Загрузите хотя бы один файл` (нет части `files` или пустой список);
- `Неподдерживаемое расширение файла «<имя>»: ожидаются .md, .txt, .pdf, .kt, .ts, .js, .json или .csv`;
- `Файл «<имя>» пуст` — полный откат: все записанные файлы (включая пустой) + запись БД
  удаляются, ответ 400.
- `500`: `Не удалось сохранить базу знаний` (сбой записи в БД) /
  `Не удалось сохранить файлы базы знаний: <причина>` (сбой записи файлов; база переводится
  в `status=failed`).

**Механика RAG-поиска (воронка, день 23)** — `KbRagService.buildContextResult`:
1. ровно 1 вызов эмбеддинга для запроса (модель — дефолтная каталога);
2. загрузка ВСЕХ чанков активных проиндексированных баз (`chunksOfBases`);
3. cosine-оценка каждого чанка, сортировка по score DESC;
4. `take(candidateK)` — верх воронки;
5. если `filterEnabled`: оставляем `score >= minScore` (порог);
6. `take(topK)` — финальные чанки, уходящие в промпт;
7. сборка блока `### База знаний` (день 24: формат с метками `[n]`): записи вида
   `[n] Файл: <файл> — Раздел: <секция>` + текст чанка, где `n` — позиция чанка в итоговой
   воронке (1..topK), суммарно не более `MAX_BLOCK_CHARS = 6000` символов; запись, которая
   не влезла, просто не добавляется (обрезки середины нет). В заголовок блока `### База знаний`
   вшивается правило цитирования (день 24): модель обязана отвечать ТОЛЬКО по фрагментам,
   в конце ответа приводить список источников в формате `[n] Файл — Раздел`, подкреплять
   ключевые утверждения цитатой из фрагмента `[n]` и говорить «Не знаю», если ответа
   во фрагментах нет. Правило живёт в заголовке блока и действует, только когда блок
   собран (фрагменты есть); при пустом поиске блок не собирается и работает отдельная
   system-заметка `EMPTY_SEARCH_NOTE` — модель сама отвечает пользователю (без `[n]`
   и источников), см. «Источники ответа и пустой поиск» ниже.

Если после порога не осталось ни одного чанка (`passedFilter = 0`) либо ни один чанк не
влез в лимит блока — блок НЕ собирается и агент отвечает БЕЗ базы знаний (fail-open: run
не роняется, в `KbRagResult.error` — причина сбоя, если был). Нет активных проиндексированных
баз — поиск не запускается вовсе (нулевых сетевых вызовов, ноль оверхеда). Настройки
вычитываются из `KbRagSettingsService.load()` при КАЖДОМ вызове.

**Настройки RAG (GET/PUT `/api/kb/settings`, день 23)** — глобальные (не per-base: все базы
одной эмбеддинг-модели, распределения score сопоставимы). Хранение — `app_settings`
(ключи `kb.filterEnabled`, `kb.minScore`, `kb.candidateK`, `kb.topK`, `kb.rewriteEnabled`,
`kb.refusalEnabled`), переживают перезапуск backend.

| Поле | Дефолт | Диапазон | Смысл |
|------|--------|----------|-------|
| `filterEnabled` | `false` | boolean | фильтр релевантности по порогу |
| `minScore` | `0.35` | [0..1] | порог cosine для отсечения нерелевантных чанков |
| `candidateK` | `8` | [1..100] | сколько лучших чанков берётся до порога (верх воронки) |
| `topK` | `4` | [1..candidateK] | сколько чанков попадает в промпт после фильтра |
| `rewriteEnabled` | `false` | boolean | перезапись запроса LLM перед ретривалом (query rewrite) |
| `refusalEnabled` | `true` | boolean | заметка LLM о пустом поиске (день 24) при релевантности ниже порога |

Дефолты фильтра/rewrite/topK = поведение дня 22 — обратная совместимость; `refusalEnabled`
по умолчанию `true` (требование задания дня 24 — анти-галлюцинация включена из коробки).
- `GET /api/kb/settings` — дефолты, перекрытые сохранёнными значениями; порченое/нераспознаваемое
  сохранённое значение = дефолт этого поля (load исключений на содержимом хранилища не бросает).
- `PUT /api/kb/settings` — полное обновление: все 6 полей в теле. Валидация (400, тело
  `{ "message": "<сообщение>" }` — стандартный error body WebFlux, `server.error.include-message: always`);
  `refusalEnabled` — boolean без ограничений (тип гарантирует допустимость); сообщения дословно:
  - `minScore должен быть в диапазоне от 0 до 1: <значение>`;
  - `candidateK должен быть в диапазоне от 1 до 100: <значение>`;
  - `topK должен быть в диапазоне от 1 до candidateK=<candidateK>: <значение>`.
  При нарушении валидации НИЧЕГО не персистится. Кросс-проверка `topK <= candidateK`
  выполняется только в PUT; при чтении воронка сама ограничивает итог числом кандидатов
  (`take(topK)` не выдаст больше, чем есть после порога).
- Изменения применяются к СЛЕДУЮЩИМ ответам без перезапуска backend (свежее `load()` на
  каждом ретривале).

**Лог поискового движка** (панель «Логи», SSE `type="log"`, kind «Ответ поискового движка»,
detail — pretty JSON): итог воронки + отобранные чанки. Поля:
- `topK`, `candidateK`, `minScore`, `filterEnabled` — настройки на момент вызова;
- `embeddingModel` — модель эмбеддингов запроса;
- `candidateChunks` — всего чанков в активных базах (до воронки); `scored` — всех оценено
  косинусом; `candidates` — после `candidateK`; `passedFilter` — после порога (при
  `filterEnabled=false` равно `candidates`); `usedChunks` — реально вошло в блок
  (лимит `MAX_BLOCK_CHARS` может сократить);
- `rewrittenQuery` — переписанный запрос (null, если rewrite выключен или фолбэк),
  `rewriteUsed` — использован ли переписанный запрос для эмбеддинга;
- `chunks[]` — финальные чанки воронки (даже если не поместились в блок, полный трейс):
  `kbName`, `source` (имя файла), `section`, `score` (cosine, округлён до 4 знаков),
  `contentChars` (длина текста), `content` (полный текст чанка). Векторы чанков в лог
  НЕ попадают (существующее правило).
- Связанные записи той же цепочки: «Запрос в БД»/«Ответ БД» (обращения к `KbRepository`:
  `listActiveIndexed`, `chunksOfBases`; ответ `chunksOfBases` в лог не пишется — сотни
  чанков с векторами), «Запрос в эмбеддинги»/«Ответ от эмбеддингов».
- `passedFilter: 0` + `chunks: []` — воронка отсекла всё, блок не собран, агент ответил
  без базы.
- `chunks[]` (день 24): у каждого чанка + `label` (позиция в итоговой воронке, 1..topK —
  совпадает с меткой `[n]` в блоке промпта) и `chunkId` (id строки `kb_chunks` — ссылка
  источника в payload `agent_finished`).

**Источники ответа и пустой поиск (день 24, цитаты и анти-галлюцинация)**:
- `agent_finished` возвращает опциональное `sources[]` — реально использованные чанки KB
  (те, что вошли в блок, `usedChunks` в порядке блока): `{ chunkId, label, kbName, source,
  section, score }`. `label` — метка `[n]` фрагмента (1..usedChunks), совпадает с `label`
  в логе поискового движка. Поле ОТСУТСТВУЕТ, когда активных баз нет или блок не собран
  (всё отсечено / ни один чанк не влез в лимит / пустой поиск) — обратная
  совместимость со старым бэкендом. Фронтенд вешает `sources` на финальное сообщение и
  рендерит блок «Источники:» под ответом.
- **Пустой поиск — честная заметка LLM** (`refusalEnabled`, дефолт `true`): если активные
  проиндексированные базы есть, поиск завершился БЕЗ сбоя (`error == null`) и
  релевантность ниже порога — `usedChunks == 0` ИЛИ лучший `score` отобранных чанков
  `< minScore` — в контекст ДО цикла tool-calling добавляется system-заметка
  `KbRagService.EMPTY_SEARCH_NOTE` («Поиск по базе знаний не дал результатов… Честно
  сообщи пользователю…»), после чего LLM-цикл запускается КАК ОБЫЧНО: модель сама
  сообщает пользователю, что в базе знаний материалов по вопросу нет, и даёт полезный
  ответ (может отвечать из общих знаний, не выдавая их за материалы базы, без выдуманных
  источников и меток `[n]`). Финальный текст приходит от LLM; `sources` в payload нет
  (usedChunks == 0). Лог поискового движка уходит в панель штатно (ровно один раз).
  Fail-open: при сбоях памяти/ретривала (`error != null`) или без активных баз заметки
  нет — агент отвечает как раньше. При `refusalEnabled=false` заметки тоже нет — модель
  отвечает вслепую (поведение дня 22).

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

---

## День 17/18 — Планировщик (papkin-helper)

Панель «Планировщик» на фронтенде управляет задачами периодического сбора через REST-эндпоинты
backend. Сводка доступна только вручную (кнопка в панели) — автопуша в чат нет.

### API планировщика (backend)

Backend — посредник между фронтендом (snake_case) и MCP papkin-helper (camelCase): на входе
переводит snake_case (`interval_seconds`/`task_id`/`since_hours`) в camelCase аргументы инструментов
(`intervalSeconds`/`taskId`/`sinceHours`), на выходе — рекурсивно переводит поля ответа MCP
(`intervalSeconds`→`interval_seconds`, `latestAt`→`latest_at`, `runsCount`→`runs_count` и т.д.).

| Метод | Путь | Тело / Параметры | Ответ |
|-------|------|------------------|-------|
| `GET` | `/api/scheduler/tasks` | — | `200` — `[{ "id": number, "name": string, "source": "weather"\|"currency"\|"news", "interval_seconds": number, "active": boolean, "last_run_at": string\|null, "runs_count": number }]`; `502` — MCP недоступен (`{ "error": string }`) |
| `POST` | `/api/scheduler/tasks` | `{ "name": string, "source": "weather"\|"currency"\|"news", "interval_seconds": number, "params"?: object }` | `200` — `{ "id", "name", "source", "interval_seconds", "active" }`; `502`/`400` при ошибке |
| `PATCH` | `/api/scheduler/tasks/{id}/interval` | `{ "interval_seconds": number }` | `200` — `{ "id", "interval_seconds", "last_run_at" }`; `400` — некорректный `id`; `502` |
| `DELETE` | `/api/scheduler/tasks/{id}` | — | `200` — `{ "ok": boolean }`; `400` — некорректный `id`; `502` |
| `POST` | `/api/scheduler/summary` | `{ "task_id"?: string, "since_hours"?: number }` | `200` — сводка: weather `{ task_id?, count, since, min, max, avg, latest }` / currency `{ count, since, latest, rates_list }` / news `{ count, since, latest_titles }` / generic `{ count, since, latest_at }`; `502` |
