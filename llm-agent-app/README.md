# LLM-агент — backend (Spring Boot 3 WebFlux / Kotlin) + frontend (React 18)

Web-приложение «LLM-агент»: пользователь пишет запрос в чат, отдельный backend-агент инкапсулирует
общение с LLM через API (цикл tool-calling, пакет `agent`) и умеет вызывать инструменты
(`get_current_datetime`, `calculator`). Фронтенд в реальном времени показывает переписку со стримингом
ответа и граф workflow агента. Транспорт (контроллеры) не знает про LLM API — он общается только с `Agent`.

## Структура

```
llm-agent-app/
├─ backend/    Kotlin + Spring Boot 3 (WebFlux), WebClient к LLM, SSE-транспорт, история в SQLite (JdbcTemplate)
├─ scripts/    скрипты запуска (run-backend.ps1 — супервизор backend на Windows)
└─ frontend/   React 18 + TypeScript + Vite, @xyflow/react (граф), тёмный UI
```

Контракт событий (типы `agent_started`, `llm_request_started`, `llm_token`, `llm_response_finished`,
`tool_call_started`, `tool_call_finished`, `agent_finished`, `error`) зафиксирован в `CONTRACT.md`.

## Требования

- JDK 21 (Corretto 21 / OpenJDK 21)
- Node.js 18+ и npm

## Backend

```bash
cd backend
./gradlew bootRun            # Linux/macOS
gradlew.bat bootRun          # Windows
```

По умолчанию используется `LLM_PROVIDER=mock` — детерминированный фейковый LLM без ключа.
История диалогов хранится в SQLite-файле (`./data/llm-agent.db` по умолчанию, см. ниже) и
переживает перезапуск backend.

### Персистентность (SQLite)

- Хранилище истории — локальный SQLite-файл; таблица `chat_messages` создаётся автоматически
  при старте из `backend/src/main/resources/schema.sql` (`spring.sql.init.mode=always`).
- Путь к файлу БД: `spring.datasource.url = jdbc:sqlite:${SQLITE_DB_PATH:./data/llm-agent.db}`.
- Каталог `data/` игнорируется git (см. `llm-agent-app/.gitignore`).
- Настройка соединения по умолчанию НЕ требуется: драйвер `org.sqlite.JDBC` подключается
  автоматически.

### Супервизор запуска (Windows)

Скрипт `scripts/run-backend.ps1` держит backend «живым»: собирается готовый jar,
скрипт запускает его в `while ($true)` и после любого завершения JVM ждёт 1 секунду
и перезапускает.

Остановка/запуск backend — через контрольный сервер супервизора на `127.0.0.1:8081`
(встроен в скрипт, отдельный in-process runspace; управление стоп-флагом
`%TEMP%\opencode\llm-agent.stop`):

| Метод | Путь | Ответ | Действие |
|-------|------|-------|----------|
| `POST` | `127.0.0.1:8081/stop` | `{"stopped": true}` | создаёт стоп-флаг и убивает процесс java; перезапуск блокируется |
| `POST` | `127.0.0.1:8081/start` | `{"starting": true}` | снимает стоп-флаг; супервизор поднимает backend заново |
| `GET` | `127.0.0.1:8081/status` | `{"stopped": bool, "java": bool}` | состояние флага и процесса java |

- Сервер слушает только `127.0.0.1`; любой другой путь/метод → `404`. Все ответы — `Connection: close`.
- Свежий запуск супервизора снимает стоп-флаг — сервис должен работать.
- В dev-режиме фронтенд обращается к супервизору через Vite-прокси **`/system-ctrl`**
  (`/system-ctrl/stop` → `127.0.0.1:8081/stop`, префикс срезается), а не через порт backend.

```powershell
# параметры: -JavaHome (путь к JDK 21), -JarPath (путь к собранному jar)
powershell -ExecutionPolicy Bypass -File llm-agent-app\scripts\run-backend.ps1

# пример с явными параметрами
powershell -ExecutionPolicy Bypass -File scripts\run-backend.ps1 `
  -JavaHome "C:\Users\60128627\.jdks\corretto-21.0.9" `
  -JarPath "$env:TEMP\llm-agent-build\llm-agent-backend\libs\llm-agent-backend-0.0.1-SNAPSHOT.jar"
```

Перед запуском соберите jar:

```powershell
cd llm-agent-app\backend
cmd /c "set JAVA_HOME=C:\Users\60128627\.jdks\corretto-21.0.9&& gradlew.bat bootJar --console=plain"
```

Скрипт не хранит и не выводит секреты — LLM-настройки берутся из окружения и наследуются
JVM-процессом.

### Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `LLM_PROVIDER` | `mock` | `mock` или `gpustack` |
| `LLM_BASE_URL` | — | Адрес GPUStack-сервера (БЕЗ `/v1`) |
| `LLM_API_KEY` | — | Ключ GPUStack (Bearer) |
| `LLM_MODEL` | `default-coding` | Идентификатор модели GPUStack (без префикса провайдера) |
| `LLM_TEMPERATURE` | `0.7` | Температура |
| `LLM_TIMEOUT_SECONDS` | `60` | Таймаут запроса к LLM |
| `AGENT_MAX_ITERATIONS` | `8` | Лимит итераций tool-calling цикла |
| `SERVER_PORT` | `8080` | Порт сервера |
| `SQLITE_DB_PATH` | `./data/llm-agent.db` | Путь к файлу SQLite-БД с историей диалогов |

### Реальный GPUStack

Запуск с реальным GPUStack (значения берите из настроек opencode):

```bash
LLM_PROVIDER=gpustack LLM_BASE_URL=<GPUStack-URL> LLM_API_KEY=<ваш ключ> ./gradlew bootRun
```

Где взять значения — файл `~/.config/opencode/opencode.jsonc`, секция провайдера `gpustack` (поля
`baseURL` / `apiKey`):

- `LLM_BASE_URL` = `baseURL` без завершающего `/v1`
- `LLM_API_KEY` = `apiKey`
- `LLM_MODEL` = модель GPUStack (по умолчанию `default-coding`; имя БЕЗ префикса провайдера — не `gpustack/<модель>`, как в opencode)

Клиент обращается к `{LLM_BASE_URL}/v1/chat/completions` (OpenAI-совместимая схема, `stream: true`).

### Mock-режим (сценарии)

`MockLlmClient` отвечает детерминированно:

- если в сообщении есть арифметическое выражение (например «сколько будет 2+2?») — вызывает
  инструмент `calculator`;
- если есть слова «дата/время/time/date» — вызывает `get_current_datetime`;
- если в истории последнее сообщение от инструмента — отвечает «Результат: …»;
- иначе отвечает эхом исходного сообщения.

## Frontend

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173 (прокси /api → localhost:8080)
npm run build    # проверка типов tsc + сборка vite
```

## Пример запроса

```bash
curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo","message":"сколько будет 2+2?"}'
```

Ожидаемая SSE-последовательность (mock): `agent_started` → `llm_request_started` (итерация 1) →
`llm_response_finished` (tool_calls) → `tool_call_started` (calculator) → `tool_call_finished`
(результат) → `llm_request_started` (итерация 2) → `llm_response_finished` (stop) → `agent_finished`.

История диалога: `GET /api/sessions/{sessionId}/history` (хранится в SQLite, переживает перезапуск backend).
Удаление истории: `DELETE /api/sessions/{sessionId}` → `{"deleted": true}`.

Управление сервисом — через супервизор (см. раздел «Супервизор запуска»): `POST 127.0.0.1:8081/stop`
останавливает backend и блокирует перезапуск, `POST 127.0.0.1:8081/start` запускает его снова
(в dev-режиме доступно через Vite-прокси `/system-ctrl`).

## Тесты

```bash
cd backend
./gradlew test     # unit-тесты агентского слоя и SQLite-хранилища, GPUStack-клиента (MockWebServer), транспорта (WebTestClient)
```

Тесты интеграции (WebTestClient) работают на отдельном временном SQLite-файле и не трогают рабочую
БД `./data`. `SqliteSessionStoreTest` проверяет переживание данных «перезапуска» хранилища на том же
файле БД.

Кнопка Stop в UI отменяет генерацию (AbortController + отмена SSE). При разрыве SSE клиент
автоматически переподключается один раз. Auth отсутствует намеренно; единственное хранилище — локальный
SQLite-файл с историей диалогов.

---
Ключи и URL GPUStack в код не зашиваются — только через переменные окружения.
