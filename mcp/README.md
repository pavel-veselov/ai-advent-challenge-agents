# «Папкин помощник» — MCP-сервер на Kotlin

**Papkin Helper** — Model Context Protocol (MCP) сервер на **Kotlin + Spring Boot 3 (WebFlux)** поверх
официального Java SDK для MCP. Работает по транспорту **Streamable HTTP**, поэтому может быть развёрнут в
Docker и доступен как обычный HTTP-хост в интернете (не stdio).

Сервер обращается к реальным публичным API без ключей.

## Инструменты

| Инструмент | Описание | Источник |
|---|---|---|
| `get_exchange_rate` | Курс валюты к рублю; без кода — все курсы за день | ЦБ РФ (`cbr-xml-daily.ru`) |
| `get_weather` | Текущая погода в городе (температура, влажность, ветер, код погоды) | Open-Meteo (+ геокодер) |
| `get_top_news` | Топ новостей (заголовки + ссылки) | Hacker News (Firebase API) |
| `scheduler_add_task` | Создать задачу периодического сбора (`weather`/`currency`/`news`); `interval_seconds` (мин. 5), `params` — параметры источника | SQLite `scheduled_tasks` |
| `scheduler_list_tasks` | Список задач: интервал, активность, число запусков | SQLite `scheduled_tasks` + `task_runs` |
| `scheduler_update_interval` | Изменить период опроса задачи (`interval_seconds` >= 5); исполнитель перенастраивается | SQLite `scheduled_tasks` |
| `scheduler_remove_task` | Отменить и удалить задачу; историю запусков не трогает | SQLite `scheduled_tasks` |
| `scheduler_summary` | Агрегировать собранные данные за окно (`since_hours`, по умолчанию 24): weather — min/max/avg температуры, currency — последние курсы, news — последние заголовки | SQLite `task_runs` |
| `search` | Поиск новостей по запросу (`query`), с лимитом `limit` (1–25) | Hacker News |
| `summarize` | Собрать сводку по списку результатов (`items`), взять топ-N (`top`, по умолчанию 5) по баллам | Локальная агрегация |
| `save_to_file` | Записать текст в файл (`content`, имя `filename`, по умолчанию `papkin-helper.out`) в папку `papkin-helper-out` | Файловая система (в `%TEMP%`) |
| `run_pipeline` | Сквозной пайплайн: `search` → `summarize` → `save_to_file` за один вызов | Hacker News + локальные шаги |
| `read_file` | Прочитать содержимое файла из `papkin-helper-out` по имени (для скачивания в браузере) | Файловая система |

## Требования

- JDK 21
- Gradle 8.14.3 (wrapper в репозитории)
- Docker (для контейнерного запуска)
- Исходящий доступ в интернет (инструменты ходят во внешние API)

## Локальный запуск

```bash
cd mcp
./gradlew bootRun            # Linux/macOS
gradlew.bat bootRun          # Windows
```

Сервер слушает `http://localhost:8080`; Streamable HTTP endpoint — `http://localhost:8080/mcp`.

## Docker

```bash
docker build -t papkin-helper:0.1.0 .
docker run -d -p 8787:8080 --name papkin-helper papkin-helper:0.1.0
```

или через compose:

```bash
docker compose up -d --build
```

## Публичный доступ в интернет

Контейнер пробрасывает порт: хост `8787` → контейнер `8080`. Чтобы MCP-клиент (Claude, opencode и
др.) подключился, `http://<публичный-хост>:8787/mcp` должен быть доступен извне:

1. Откройте порт `8787` в файрволе хостинга/облака, настройте security group.
2. (Опционально) поднимите reverse-proxy (nginx/Caddy) и выдайте TLS по `https://<домен>/mcp`.
3. В настройках MCP-клиента укажите URL: `http://<публичный-хост>:8787/mcp` или `https://<домен>/mcp`.
4. Убедитесь, что у контейнера есть исходящий доступ в интернет — иначе инструменты вернут ошибку.

## Пример вызова инструмента

```bash
curl -X POST http://localhost:8787/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_exchange_rate","arguments":{"code":"USD"}},"id":1}'
```

## Конфигурация (переменные окружения)

| Переменная | По умолчанию | Описание |
|---|---|---|
| `SERVER_PORT` | `8080` | Порт сервера |
| `SPRING_AI_MCP_SERVER_NAME` | `papkin-helper` | Имя сервера (видно клиентам) |
| `SPRING_AI_MCP_SERVER_VERSION` | `0.1.0` | Версия сервера |

Публичные ключи/токены не требуются — все три источника бесплатные и открытые.
