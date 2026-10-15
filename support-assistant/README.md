# Support Assistant

AI-ассистент поддержки пользователей (день 33). Отвечает на вопросы о продукте, опираясь на **данные пользователя и тикеты из CRM** (через MCP) и **базу знаний** (RAG по `faq/*.md`). Отвечает локальная LLM из **GPUStack**.

Zero-deps: только Node 20+ (`fetch`, `node:child_process`), без npm-пакетов и CDN.

## Запуск

```bash
cd support-assistant
node --env-file=.env server.js
```

Откройте **http://127.0.0.1:8082**.

> Сервер начинает отвечать сразу, индекс RAG (эмбеддинги чанков FAQ) строится фоном при первом запуске; результат кэшируется в `.rag-cache.json`.

## Схема

```
Браузер ──/api/ask (NDJSON-стрим)──> server.js
                                      │
                              ┌───────┴────────┐
                        RAG                    MCP
                      src/rag.js            src/mcp-client.js
                   (faq/*.md) →            (spawn stdio JSON-RPC)
                    эмбеддинги →             mcp/crm-server.js
                      GPUStack               get_user / search_tickets
                                      │
                              ┌───────┴────────┐
                              src/llm.js → GPUStack /v1/chat/completions
```

Стрим `/api/ask` (тело `{question, email}`, NDJSON) — наружу только ответ:
`{type:"delta",text}*` → `{type:"done",text}`; при обрыве клиента — `{type:"aborted"}`, при ошибке — `{type:"error",message}`.
RAG-источники и вызовы CRM-инструментов в стрим не выводятся — пользователь видит только ответ ассистента.

`GET /api/history?email=…` → `{messages:[{role,text}]}` (последние 10 сообщений профиля, старые→новые) · `GET /api/status` → `{model, embedModel, crmTools:[...]}` · `GET /api/health` → `{ok:true}`.

CRM-админка: страница **/crm** (пользователи, тикеты с фильтрами по статусу/пользователю, создание тикета) · `GET /api/crm/users` · `GET /api/crm/tickets` · `POST /api/crm/tickets` (тело `{userId, subject, priority?}` → `{ok, ticket}`; ошибки — `{error}`).

## MCP-инструменты CRM (`mcp/crm-server.js`, данные в `data/`)

| Инструмент | Аргументы | Что делает |
|---|---|---|
| `get_user` | `email` или `id` | Пользователь CRM: статус, тариф, причина блокировки |
| `list_users` | — | Все пользователи CRM (JSON: статус, тариф, блокировки) |
| `get_ticket` | `id` | Тикет по ID: тема, статус, приоритет, заметки |
| `search_tickets` | `userId`/`email`, `status?` | Поиск тикетов (`status`: open/resolved/all; без `status` — все тикеты) |
| `create_ticket` | `userId`, `subject`, `priority?` | Создаёт тикет в `data/tickets.json` |

Email всегда приходит из селектора профиля в UI. Сервер резолвит пользователя по email и обращается к CRM только от его имени — изоляция на сервере: `search_tickets` всегда с `userId` этого пользователя, `get_ticket(id)` исполняется только если тикет принадлежит resolved-пользователю (чужой выглядит как «Тикет не найден среди ваших»), `create_ticket` всегда с `userId` resolved-пользователя (LLM не передаёт его). Если MCP недоступен, запрос не падает — в контекст идёт `CRM недоступна: …`.

## Пользовательский UI и создание тикетов

- В шапке чата — селектор профиля (ровно 3 email — первые 3 пользователя из `/api/crm/users`, первый по умолчанию). У каждого профиля своя история чата: последние 10 сообщений (user+assistant) в `data/history.json`, переключение профиля перерисовывает чат через `GET /api/history`.
- glm-5.3-flash поддерживает OpenAI-style `tools` (проверено probe-запросом с `tool_choice:"auto"` — в ответе есть `tool_calls`). Сервер гоняет tool-calling цикл: LLM → `tool_calls` → исполнение через MCP-клиент → результаты как `role:"tool"` → повтор (максимум 4 итерации) → стрим финального ответа (дельты только для финального текста).
- Не знает ответа — ассистент честно говорит и спрашивает, создать ли тикет; при согласии вызывает `create_ticket` и сообщает ID.
- Служебные панели (RAG-источники, дампы CRM-инструментов, скоринги) из пользовательского чата убраны; RAG-поиск остался внутренним механизмом ответа.

## Переменные окружения (`.env`)

| Переменная | По умолчанию | Описание |
|---|---|---|
| `LLM_BASE_URL` | `https://gpustack.data.lmru.tech` | Базовый URL GPUStack (без `/v1`) |
| `LLM_API_KEY` | *(пусто)* | Ключ GPUStack |
| `LLM_MODEL` | `glm-5.3-flash` | Модель чата |
| `EMBED_MODEL` | `qwen3-vl-embedding-8b` | Модель эмбеддингов (RAG) |
| `PORT` | `8082` | Порт HTTP-сервера |
| `MAX_CONTEXT` | `6` | Максимум фрагментов FAQ в контексте |
| `SIMILARITY_THRESHOLD` | `0.45` | Порог релевантности RAG |

## Файлы

```
support-assistant/
  server.js              HTTP: статика + /api/ask + /api/history + /api/status + /api/health + /api/crm/*
  src/config.js          конфиг из env
  src/llm.js             чат с GPUStack (SSE-стрим) + tool-calling цикл
  src/rag.js             чанкинг FAQ, эмбеддинги, cosine, кэш индекса
  src/mcp-client.js      stdio-клиент MCP (JSON-RPC, spawn)
  mcp/crm-server.js      stdio-сервер MCP: list_users / get_user / get_ticket / search_tickets / create_ticket
  data/users.json        пользователи CRM (заглушка)
  data/tickets.json      тикеты CRM (заглушка)
  data/history.json      история чатов: {email: [{role,text}]}, последние 10 сообщений на профиль
  faq/*.md               база знаний: auth, account, billing, api
  public/                тёмный UI (index.html, app.js, style.css, crm.html, crm.js)
```
