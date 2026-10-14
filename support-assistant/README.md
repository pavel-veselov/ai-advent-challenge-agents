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

Стрим `/api/ask` (тело `{question, email?}`, NDJSON):
`{type:"sources",sources:[{rel,score}]}` → `{type:"tools",tools:[{tool,result}]}` → `{type:"delta",text}*` → `{type:"done",text}`; при обрыве клиента — `{type:"aborted"}`, при ошибке — `{type:"error",message}`.

`GET /api/status` → `{model, embedModel, crmTools:[...]}` · `GET /api/health` → `{ok:true}`.

## MCP-инструменты CRM (`mcp/crm-server.js`, данные в `data/`)

| Инструмент | Аргументы | Что делает |
|---|---|---|
| `get_user` | `email` или `id` | Пользователь CRM: статус, тариф, причина блокировки |
| `get_ticket` | `id` | Тикет по ID: тема, статус, приоритет, заметки |
| `search_tickets` | `userId`/`email`, `status` | Поиск тикетов (без фильтров — все открытые) |
| `create_ticket` | `userId`, `subject`, `priority?` | Создаёт тикет в `data/tickets.json` |

Если email передан в запрос, сервер вызывает `get_user` и `search_tickets` и кладёт результат в контекст LLM; без email — в контексте будет просьба уточнить email/ID тикета. Если MCP недоступен, запрос не падает — в контекст идёт `CRM недоступна: …`.

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
  server.js              HTTP: статика + /api/ask + /api/status + /api/health
  src/config.js          конфиг из env
  src/llm.js             чат с GPUStack (SSE-стрим)
  src/rag.js             чанкинг FAQ, эмбеддинги, cosine, кэш индекса
  src/mcp-client.js      stdio-клиент MCP (JSON-RPC, spawn)
  mcp/crm-server.js      stdio-сервер MCP: get_user / get_ticket / search_tickets / create_ticket
  data/users.json        пользователи CRM (заглушка)
  data/tickets.json      тикеты CRM (заглушка)
  faq/*.md               база знаний: auth, account, billing, api
  public/                тёмный UI (index.html, app.js, style.css)
```
