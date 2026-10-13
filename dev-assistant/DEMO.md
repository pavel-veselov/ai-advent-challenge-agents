# Демо: закрытие задания дня 31 «Ассистент разработчика»

Задание дня 31 — собрать **ассистента разработчика для этого же проекта**: он отвечает на
вопросы о структуре и документации репозитория, опираясь на **RAG по документации**,
**MCP-контекст** (git) и **локальную LLM** (GPUStack), со стримингом ответа и честным
«не знаю» вне документации. Ниже — как каждое требование закрыто, с живыми
доказательствами (выводы API + скриншоты из `demo/`).

## Чеклист «задание → как закрыто»

| # | Требование | Реализация | Доказательство |
|---|---|---|---|
| 1 | RAG по документации проекта | `src/rag.js`: обход `*.md` + `README.md`/`CONTRACT.md`/`*.openapi.yaml` всего репо, чанки ~900 симв. (перекрытие 120), эмбеддинги GPUStack `qwen3-vl-embedding-8b` (4096 измерений), cosine, кэш индекса в `.rag-cache.json` | `{"type":"sources",...}` с оценками 0.603–0.665 (см. ниже), теги источников в UI |
| 2 | MCP-контекст: git-инструменты | `src/mcp-client.js` (stdio JSON-RPC клиент) + `mcp/mcp-git-server.js` (stdio MCP-сервер: `git_branch` / `git_diff` / `list_files`) | `{"type":"tools","tools":[{"tool":"git_branch","result":"Ветка: main ..."},{"tool":"git_diff",...}]}` |
| 3 | Стриминг ответа LLM | `src/llm.js` (SSE к GPUStack `/v1/chat/completions`) → сервер транслирует в NDJSON `{type:"delta"}` → UI печатает с курсором | кадр `demo/demo-2-stream.png` |
| 4 | Веб-UI без зависимостей | `public/` — тёмный UI без CDN: чипы ветки/модели в шапке, чипы-примеры, Enter/Shift+Enter, кнопка «➤» | кадр `demo/demo-1-home.png` |
| 5 | Честный отказ вне доков | порог `SIMILARITY_THRESHOLD=0.5`; модель инструктирована не выдумывать | кадр `demo/demo-4-refusal.png`: «Не знаю — и это честный ответ» |
| 6 | Zero-dep, локальность | ни одного npm-пакета: только Node 20+ (`--env-file`, встроенный `fetch`) и `git` в PATH | `package.json` без `dependencies` |

## Как воспроизвести

```bash
cd dev-assistant
cp .env.example .env          # впишите ключ GPUStack
node --env-file=.env server.js
# откройте http://127.0.0.1:8081
```

Требования: Node 20+, `git` в PATH, доступ к GPUStack. Первый запуск строит RAG-индекс
(десятки секунд), далее — из кэша `.rag-cache.json`.

## API-доказательства (живой прогон 13.10.2026)

**`GET /api/health`**

```json
{"ok":true,"model":"glm-5.3-flash","embedModel":"qwen3-vl-embedding-8b"}
```

**`GET /api/status`** — живой git-контекст и конфигурация:

```json
{"branch":"main","model":"glm-5.3-flash","embedModel":"qwen3-vl-embedding-8b","projectRoot":"<корень репозитория>"}
```

**`POST /api/help`** — вопрос `«Какая структура проекта?»`, NDJSON-стрим (первые строки):

```json
{"type":"sources","sources":["llm-agent-app/CONTRACT.md","demo-rag-v3/README.md","llm-agent-app/backend/data/kb/1/doc.md"],
 "snippets":[{"rel":"llm-agent-app/CONTRACT.md","score":0.665}, ..., {"rel":"llm-agent-app/backend/data/kb/1/doc.md","score":0.603}]}
{"type":"tools","tools":[{"tool":"git_branch","result":"Ветка: main\nИзменённых файлов: 30"},{"tool":"git_diff","result":"(нет изменений)"}]}
{"type":"delta","text":"##"}
{"type":"delta","text":" Структура проекта"}
{"type":"delta","text":"\n\n"}
{"type":"delta","text":"Пол"}
...
{"type":"done"}
```

Порядок событий: **sources → tools → deltas… → done**. RAG нашёл фрагменты
`llm-agent-app/CONTRACT.md` (score 0.665 — топ), MCP верку и статус изменений,
дальше стримятся дельты ответа.

## Скриншоты

1. **`demo/demo-1-home.png`** — стартовый экран: шапка с живыми чипами
   «Текущая ветка: main» и «Модель LLM: glm-5.3-flash» (данные из `GET /api/status`),
   hero с тремя источниками контекста, чипы-примеры, поле ввода.
2. **`demo/demo-2-stream.png`** — вопрос «Что описывает CONTRACT.md?» уходит в стрим:
   ответ печатается по мере получения дельт.
3. **`demo/demo-3-answer.png`** — итог: блок «Источники:» с 5 тегами
   (`llm-agent-app/CONTRACT.md`, `docs/rag-reranking.md`, `docs/memory-layers.md`,
   `README.md`, `demo-task-memory/README.md`), блок результатов MCP-инструментов
   (`git_branch`, `git_diff`), отрендеренный markdown-ответ.
4. **`demo/demo-4-refusal.png`** — вопрос вне документации («Кто выиграл вчера матч
   Спартак — Зенит?»): ассистент отвечает **«Не знаю — и это честный ответ… выдумывать
   счёт я не буду»** — поведение «отказ вместо галлюцинации», реализованное в демо
   самого проекта.

## Итог

Ассистент замыкает весь стек дней 22–30 на новом уровне применения: RAG
(`qwen3-vl-embedding-8b`) собирает контекст из документации репозитория, MCP-сервер
(`git_branch`/`git_diff`/`list_files`) даёт живое состояние git, GPUStack-модель
(`glm-5.3-flash`) стримит ответ в браузер — и всё это без единого npm-пакета.
