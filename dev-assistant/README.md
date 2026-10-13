# Dev Assistant

Ассистент разработчика для проекта **ai-advent-challenge-agents**: отвечает на вопросы о структуре и документации проекта. Отвечает локальная LLM из **GPUStack**, контекст собирается через **RAG** (README + docs + API-контракты) и **MCP** (git-инструменты).

Работает полностью локально: ни одного npm-пакета, ни внешних CDN — только Node и `git` в PATH.

## Что делает

Спросите `/help`-вопрос (в поле ввода или через чипы-примеры):

- **RAG** — ищет релевантные фрагменты по README (корневому и `llm-agent-app/`), документации в `docs/` и описаниям API (`CONTRACT.md`, `*.openapi.yaml`).
- **MCP** — получает живой контекст проекта: текущую git-ветку, изменения и список файлов.
- **LLM** — собирает всё это в один запрос и стримит ответ из GPUStack.

## Архитектура

```
Браузер ──/api/help (NDJSON-стрим)──> server.js
                                        │
                                ┌───────┴────────┐
                          RAG                    MCP
                        src/rag.js            src/mcp-client.js
                     (README+docs)→          (spawn stdio JSON-RPC)
                      эмбеддинги →             mcp/mcp-git-server.js
                        GPUStack               git_branch / git_diff / list_files
                                        │
                                ┌───────┴────────┐
                                src/llm.js → GPUStack /v1/chat/completions
```

## Запуск локально

```bash
cd dev-assistant
# создайте .env из примера и впишите свой ключ GPUStack
cp .env.example .env
node server.js
```

Откройте **http://127.0.0.1:8081**.

## Переменные окружения (`.env`)

| Переменная | По умолчанию | Описание |
|---|---|---|
| `LLM_BASE_URL` | `https://gpustack.data.lmru.tech` | Базовый URL GPUStack (без `/v1`) |
| `LLM_API_KEY` | *(пусто)* | Ключ GPUStack (заголовок `Authorization: Bearer`) |
| `LLM_MODEL` | `glm-5.3-flash` | Модель для чата |
| `EMBED_MODEL` | `qwen3-vl-embedding-8b` | Модель для эмбеддингов (RAG) |
| `PORT` | `8081` | Порт HTTP-сервера |
| `PROJECT_ROOT` | *(корень репо)* | Каталог проекта, который сканирует RAG и к которому подключается MCP |
| `MAX_CONTEXT` | `8` | Максимум фрагментов документации для контекста |
| `SIMILARITY_THRESHOLD` | `0.5` | Порог релевантности для ответа «не знаю» |

## Требования

- Node.js 20+ (используется `node --env-file` и встроенный `fetch`).
- `git` в PATH (для MCP-инструментов).
- Доступ к GPUStack (ключ в `.env`, файл добавлен в `.gitignore`).

## Проект (файлы)

```
dev-assistant/
  server.js            HTTP-сервер: статика + /api/status + /api/help
  src/config.js        конфиг из env
  src/llm.js           чат с GPUStack (SSE-стрим)
  src/rag.js           чанкинг, эмбеддинги, cosine, кэш индекса
  src/mcp-client.js    stdio-клиент MCP (JSON-RPC, spawn)
  mcp/mcp-git-server.js stdio-сервер MCP: git_branch / git_diff / list_files
  public/              тёмный UI (index.html, app.js, style.css, favicon.svg)
  .env.example         пример переменных
```

## Как устроен RAG

1. Обход каталога проекта: все `*.md`, а также `README.md`, `CONTRACT.md` и `*.openapi.yaml`/`*.swagger.json` из корня.
2. Текст режется на чанки (~900 симв. с перекрытием 120).
3. Каждый чанк эмбеддится в GPUStack (`qwen3-vl-embedding-8b`, 4096 измерений).
4. Индекс и его сигнатура (время изменения файлов) кэшируются в `.rag-cache.json` — при повторном запуске не пересчитывается.
5. На вопрос: эмбеддинг запроса → cosine-похожесть к каждому чанку → топ-K → в контекст LLM.

## Ограничения

- Если по документации ничего не найдено (ниже порога), ассистент честно говорит, что ответа в доках нет.
- RAG-индекс строится при первом запуске (может занять десятки секунд на эмбеддингах).
- Ответ зависит от доступности GPUStack — без ключа/сети вернётся ошибка.
