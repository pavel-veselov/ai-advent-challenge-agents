# rag — индексация документов (день 21)

Автономный Kotlin-CLI модуль RAG-пайплайна: полный цикл extract → chunk → embed → store
(манифест корпуса: markdown + исходники + PDF, чанкование по двум стратегиям, эмбеддинги,
SQLite-индекс) и векторный поиск top-k по косинусной близости. Итог сравнения стратегий —
в `REPORT.md` (генерируется командой `compare`).

## Сборка и запуск

```powershell
cd rag
.\gradlew.bat --no-daemon test          # тесты (PDFBox-извлечение обеих статей и т.д.)
.\gradlew.bat --no-daemon run --args "corpus"   # статистика извлечения корпуса
```

Требуется JDK 21 (`JAVA_HOME=C:\Users\60128627\.jdks\corretto-21.0.9`).

## Команды CLI

| Команда | Статус | Описание |
|---|---|---|
| `corpus` | работает | манифест корпуса + извлечение всех документов + статистика |
| `index` | работает | полный пайплайн extract → chunk → embed → store в `data/index.db`; `--strategy fixed\|structural` (по умолчанию `fixed`) |
| `search` | работает | `search "запрос" --topk N --strategy ...` — эмбеддинг запроса, top-k по косинусной близости (без `--strategy` ищет по всем стратегиям, topk по умолчанию 5) |
| `compare` | работает | прогоняет ОБЕ стратегии, печатает метрики чанков + retrieval-тест hit@1/hit@3 и записывает markdown-отчёт `REPORT.md` (`--report путь` — свой путь) |
| `export` | работает | выгрузка чанков индекса в JSON в `data/index-export.json` (`--strategy`, `--with-embeddings`, `--out путь`) |

## Корпус

- **Markdown**: README/CONTRACT проекта `llm-agent-app`, `docs/memory-layers.md`, README `mcp/` и `mcp2/` — 5 файлов.
- **Код**: ключевые .kt-файлы агента (`AgentImpl`, `McpServersStore`, `McpServersController`, `ToolRegistry`), коллектор `HttpSourceCollector` из `mcp/`, `LocationTool` из `mcp2/` — 6 файлов.
- **PDF** (`corpus/pdf/`): две arXiv-статьи — *Attention Is All You Need* (1706.03762) и RAG-статья (2005.11401).

Списки файлов — в `config/CorpusConfig.kt` (`CorpusConfig.DEFAULT`); PDF-страницы склеиваются
маркерами `[PAGE n]` для структурного чанкера.

## Конфигурация

- Эмбеддинги: env `LLM_BASE_URL`, `LLM_API_KEY` (не печатать!), модель `qwen3-vl-embedding-8b`, размерность 4096.
- Чанки (fixed): `maxChars=1000`, `overlap=200`.
- Индекс: `data/index.db` (SQLite, каталог появляется при первой индексации).

## Особенности сборки

- Кириллический путь workspace: buildDir уходит в `%TEMP%\rag-build` (переопределяется `-Drag.build=<путь>`).
- Форки тестов получают `-Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8`.
- Без Spring: чистый Kotlin + `java.net.http` (позже), PDFBox, sqlite-jdbc, kotlinx-serialization.
- Запрос в `search` передаётся БЕЗ кавычек: `--args` в PowerShell 5.1 ненадёжно передаёт кавычки внутрь, а CLI сам склеивает все позиционные аргументы пробелом (многословный запрос — обычное дело).
- Mojibake кириллицы в выводе PS-консоли лечится: `$env:JAVA_TOOL_OPTIONS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"`.
