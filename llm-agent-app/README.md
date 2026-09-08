# LLM-агент — backend (Spring Boot 3 WebFlux / Kotlin) + frontend (React 18)

Web-приложение «LLM-агент»: пользователь пишет запрос в чат, отдельный backend-агент инкапсулирует
общение с LLM через API (цикл tool-calling, пакет `agent`) и умеет вызывать инструменты
(`get_current_datetime`, `calculator`). Фронтенд в реальном времени показывает переписку со стримингом
ответа и граф workflow агента. Транспорт (контроллеры) не знает про LLM API — он общается только с `Agent`.

## Структура

```
llm-agent-app/
├─ backend/   Kotlin + Spring Boot 3 (WebFlux), WebClient к LLM, SSE-транспорт
└─ frontend/  React 18 + TypeScript + Vite, @xyflow/react (граф), тёмный UI
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

История диалога: `GET /api/sessions/{sessionId}/history` (хранится в памяти по `sessionId`).

## Тесты

```bash
cd backend
./gradlew test     # unit-тесты агентского слоя, GPUStack-клиента (MockWebServer), транспорта (WebTestClient)
```

Кнопка Stop в UI отменяет генерацию (AbortController + отмена SSE). При разрыве SSE клиент
автоматически переподключается один раз. БД и auth отсутствуют намеренно.

---
Ключи и URL GPUStack в код не зашиваются — только через переменные окружения.
