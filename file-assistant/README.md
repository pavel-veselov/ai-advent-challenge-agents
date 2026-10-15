# File Assistant (день 34)

AI-ассистент, который **активно работает с файлами проекта**: читает, ищет по файлам, анализирует и создаёт/меняет файлы через **MCP-инструменты** и tool-calling цикл LLM. Отвечает локальная LLM из **GPUStack** (glm-5.3-flash, OpenAI-style `tools`).

Zero-deps: только Node 20+ (`fetch`, `node:http`, `node:fs`, `node:child_process`), без npm-пакетов и CDN.

## Запуск

```bash
cd file-assistant
node --env-file=.env server.js
```

Откройте **http://127.0.0.1:8083**.

## Схема

```
Браузер ──/api/ask (NDJSON-стрим)──> server.js (порт 8083)
                                      │
                              ┌───────┴─────────┐
                        MCP stdio           src/llm.js
                   src/mcp-client.js        GPUStack /v1/chat/completions
                  (spawn, JSON-RPC)         tool-calling цикл (до 6 итераций)
                        │
                mcp/fs-server.js
        list_files / read_file / search_files / write_file
        (все пути внутри WORKSPACE_ROOT)
```

Цель приходит на уровне намерения («найди все места, где используется X», «сгенерируй CHANGELOG») — ассистент сам решает, какие файлы посмотреть и какие инструменты вызвать, и в конце отдаёт краткий отчёт: где что находится (`файл:строка`) и что изменено.

## Стрим `/api/ask` (NDJSON)

Тело: `{question}` (email/профили не нужны — агент однопользовательский).

| Событие | Формат | Когда |
|---|---|---|
| `delta` | `{type:"delta",text}` | куски финального ответа (только финальный текст, не вызовы инструментов) |
| `file_change` | `{type:"file_change",path,diff,created}` | сразу после фактической записи файла ассистентом; `diff` — unified diff старое→новое (до 120 строк) |
| `done` | `{type:"done",text}` | полный финальный ответ |
| `aborted` | `{type:"aborted"}` | клиент оборвал соединение |
| `error` | `{type:"error",message}` | ошибка (например, LLM недоступна) |

Последовательность обычного прогона с записью: `delta*` → `file_change` (на каждую запись) → `delta*` → `done`.

Прочее: `GET /api/health` → `{ok:true, model}` · `GET /api/status` → `{model, workspaceRoot, fsTools:[...]}`.

## MCP-инструменты (`mcp/fs-server.js`)

Все пути **джейлятся** на `WORKSPACE_ROOT` (лексический `path.resolve` + startsWith и `realpath`-проверка для существующих файлов); `..`-побеги, абсолютные пути наружу и симлинки отвергаются. Запрещены на чтение/запись: `.git/`, `node_modules/`, `.env*`, `*.log`, файлы >2MB. Бинарные файлы не читаются и не ищутся.

| Инструмент | Аргументы | Что делает |
|---|---|---|
| `list_files` | `pattern?` | Рекурсивный список файлов (glob-lite: подстрока или `*.md`, `src/*`); джанк-директории пропускаются, максимум 500 записей |
| `read_file` | `path`, `offset?`, `limit?` | Текстовое содержимое (по умолчанию до 400 строк / 48KB; `offset` — строка начала с 1) |
| `search_files` | `query`, `glob?` | Поиск подстроки без учёта регистра по текстовым файлам → `путь:строка: фрагмент`, максимум 80 совпадений |
| `write_file` | `path`, `content` | Создать/перезаписать файл внутри джейла → `{path, created, changed, diff}` (unified diff до 120 строк; идентичный контент — `changed:false`) |

Дифф — собственная реализация без зависимостей: общий префикс/суффикс строк + один `@@`-hunk с 3 строками контекста, детерминированно. Новый файл → `--- /dev/null` + все `+`-строки; новый файл без `\n` на конце получает его (детерминизм UTF-8).

Сбой MCP-процесса не роняет запрос: ошибка инструмента превращается в текст для контекста LLM, а клиентский клиент пересоздаётся при следующем вызове.

## Сценарии (проверены E2E)

1. **«найди все места, где используется функция chatWithTools»** → ассистент вызывает `search_files` (+`read_file` при необходимости) и отвечает списком `файл:строка`.
2. **«сгенерируй файл file-assistant/CHANGELOG.md с кратким changelog этого ассистента»** → ассистент вызывает `write_file`; клиент получает `file_change` с диффом, файл появляется на диске.

## Переменные окружения (`.env`)

| Переменная | По умолчанию | Описание |
|---|---|---|
| `LLM_BASE_URL` | `https://gpustack.data.lmru.tech` | Базовый URL GPUStack (без `/v1`) |
| `LLM_API_KEY` | *(пусто)* | Ключ GPUStack |
| `LLM_MODEL` | `glm-5.3-flash` | Модель чата |
| `PORT` | `8083` | Порт HTTP-сервера |
| `WORKSPACE_ROOT` | корень репозитория | Джейл для всех файловых операций |

## Файлы

```
file-assistant/
  server.js              HTTP: статика + /api/ask (NDJSON) + /api/status + /api/health
  src/config.js          конфиг из env
  src/llm.js             чат с GPUStack (SSE) + tool-calling цикл (до 6 итераций)
  src/mcp-client.js      stdio-клиент MCP (JSON-RPC, spawn)
  mcp/fs-server.js       stdio-сервер MCP: list_files / read_file / search_files / write_file
  public/                тёмный UI (index.html, app.js, style.css) с карточками file_change
  .env                   ключи и порт (в git не попадает)
```
