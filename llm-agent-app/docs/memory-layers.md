# Память агента (memory layers) — модель, политики, API

Документ описывает реализованную модель памяти агента: три слоя (краткосрочная, рабочая,
долговременная), что в какой слой попадает и кто пишет, как память собирается в контекст
запроса к LLM, SSE-событие `memory_updated`, REST-эндпоинты и панель «Память» в UI.
Поведение описано строго по коду (см. раздел 9 «Исходники»); детальная схема API — в
`CONTRACT.md`, секции «Память агента (memory layers)» и «Стратегии контекста».

## 1. Модель памяти: три слоя

| Слой | Область видимости | Хранение | Жизненный цикл |
|---|---|---|---|
| Краткосрочная (STM) | per-session | `chat_messages` (вся история диалога) | живёт, пока жива сессия; в промпт попадает по стратегии контекста |
| Рабочая (WM) | per-session | `agent_working_memory` (одна строка на сессию) | до «Новая задача» (`POST /memory/new-task`) или `DELETE /api/sessions/{sessionId}` |
| Долговременная (LTM) | **ГЛОБАЛЬНАЯ** (все сессии) | `agent_long_term_memory` | переживает удаление сессий; живёт, пока запись не удалена явно |

### 1.1 Краткосрочная (STM)

Вся история диалога сессии пишется в `chat_messages` автоматически с каждым сообщением
(user/assistant + системные заметки). Что именно из истории попадает в промпт LLM — решает
выбранная для сессии **стратегия контекста** (none / sliding_window / sticky_facts / summary /
branching): вся история, окно последних сообщений, резюме + хвост или цепочка ветки
(см. `CONTRACT.md`, «Стратегии контекста»). STM — автоматический слой: ни агент, ни
пользователь его напрямую не пишут.

### 1.2 Рабочая (WM)

Текущая задача сессии и промежуточные результаты. Таблица `agent_working_memory`
(схема — в `schema.sql`):

| Поле | Тип | Смысл |
|---|---|---|
| `session_id` | TEXT PRIMARY KEY | одна строка на сессию |
| `task` | TEXT (nullable) | текущая задача; NULL — задача ещё не зафиксирована |
| `notes` | TEXT, JSON-массив строк | промежуточные результаты, append в конец |
| `updated_at` | TEXT | время последнего изменения |

Заметка обрезается до **1000 символов** (`NOTE_MAX_LENGTH` в `WorkingMemoryStore`). WM
переживает перезапуск backend (SQLite).

### 1.3 Долговременная (LTM)

Глобальная память на все сессии: записи не скоупятся по сессии, поле `source_session_id`
лишь фиксирует, из какой сессии запись пришла. Таблица `agent_long_term_memory`
(схема — в `schema.sql`):

| Поле | Тип | Смысл |
|---|---|---|
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | id записи |
| `source_session_id` | TEXT NOT NULL | сессия-источник |
| `type` | TEXT, CHECK `profile`/`decision`/`knowledge` | тип факта |
| `key` | TEXT NOT NULL | короткое имя факта |
| `value` | TEXT NOT NULL | содержание (обрезается до **2000 символов** — `VALUE_MAX_LENGTH`) |
| `created_at` / `updated_at` | TEXT | временные метки |
| — | UNIQUE(`type`, `key`) | повторная запись того же (type, key) — **upsert**: перезаписываются value/updated_at/source_session_id, created_at сохраняется |

`listAll()` возвращает записи в порядке **`updated_at DESC`** (свежие сверху). LTM
**не трогает** `DELETE /api/sessions/{sessionId}` — записи переживают удаление сессий.

## 2. Что попадает в каждый слой

| Источник | Слой | Механизм |
|---|---|---|
| Каждое сообщение диалога | STM | автоматически, `chat_messages` |
| Факт о себе от пользователя («я вегетарианец») | LTM, type=`profile` | tool `memory_save` |
| Важное принятое решение | LTM, type=`decision` | tool `memory_save` |
| Полезное знание для будущих разговоров | LTM, type=`knowledge` | tool `memory_save` |
| Цель многоступенчатой задачи | WM, `task` | код агента на старте run |
| Каждый успешный tool-результат в run | WM, `notes[]` | код агента после tool-вызова |
| Ручное добавление из UI / REST | LTM | `POST /api/sessions/{sessionId}/memory/long-term` |

Граница сознательная: STM — всё, что произошло в диалоге; WM — то, что важно **для
текущей задачи**; LTM — то, что должно пережить сессию. Модель сама решает, когда вызывать
`memory_save` (подсказка — только в description инструмента, `SYSTEM_PROMPT` не меняется);
за WM отвечает код, а не модель.

## 3. Политики записи (явные по дизайну)

| Слой | Кто пишет | Когда |
|---|---|---|
| STM | транспорт | автоматически с каждым сообщением |
| WM `task` | **код агента** | один раз на run, ДО первого LLM-вызова — только если текущая задача пуста (`task.isNullOrBlank()`); последующие сообщения сессии задачу **не перезаписывают** |
| WM `notes` | **код агента** | append `"<toolName>: <result>"` после каждого **успешного** tool-результата (cap 1000 символов) |
| LTM | **только явно** | tool `memory_save` (решает модель) или `POST /memory/long-term` (ручной REST/UI) |

Автодистилляции нет: ни embeddings, ни фоновое «выжимание» фактов из истории, ни
автосохранение LTM по ходу диалога. LTM растёт только от явных записей.

Нюанс по WM `task`: задача фиксируется текстом **первого** сообщения сессии и далее живёт
до «Новая задача». Если задача уже стоит, агент при новых сообщениях WM `task` не трогает —
за заметки в run отвечают только tool-результаты.

## 4. Как память влияет на ответы (сборка контекста)

Порядок сборки контекста в `AgentImpl.run` — для **всех** стратегий контекста, включая
branching:

```
SYSTEM_PROMPT
→ блок WM  «=== РАБОЧАЯ ПАМЯТЬ ===»       (omit, если task пуст И notes пуст)
→ блок LTM «=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===» (omit, если записей нет)
→ блок стратегии (резюме / «Известные факты» и т.п., по разрешённой стратегии)
→ окно истории (по разрешённой стратегии)
```

Формат блоков (точно как в `AgentImpl`):

- **WM** — system-сообщение `=== РАБОЧАЯ ПАМЯТЬ ===`, секции через пустую строку:
  `Текущая задача: <task>` и `Промежуточные результаты:` + нумерованный список
  `1. <note>` по одной заметке на строку. Секция `notes` есть только при непустом списке.
- **LTM** — system-сообщение `=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===`, строки `<type> | <key>: <value>`
  — **top-20** записей из `listAll()` (т.е. 20 самых свежих по `updated_at DESC`);
  при >20 записей дописывается строка `…и ещё N записей в долговременной памяти`
  (N = всего − 20).

Блоки — **context-only**: в `chat_messages` они не пишутся и в `GET /history` не
попадают — только в контекст текущего запроса. Все memory-операции в run — **fail-open**:
нет store (старая обвязка/тесты) или сбой чтения/записи — warn в серверный лог, run
продолжается без соответствующего блока/заметки; память никогда не роняет ответ.

## 5. SSE `memory_updated`

Полный снапшот памяти, вне нумерации итераций: **stepId фиксирован — `"memory"`** (как
у `context-summary` / `facts`). Payload:

```json
{
  "sessionId": "<id сессии>",
  "working": { "task": "<task>|null", "notes": ["<tool>: <result>", "..."] },
  "longTerm": [
    { "id": 1, "sourceSessionId": "...", "type": "profile|decision|knowledge",
      "key": "...", "value": "...", "createdAt": "...", "updatedAt": "..." }
  ]
}
```

`longTerm` — ГЛОБАЛЬНЫЙ список (все записи всех сессий, свежее чтение из БД в момент
события). Агент шлёт событие:

- после фиксации WM `task` на старте run;
- после каждого append WM note (успешный tool-результат);
- после **успешного** вызова `memory_save` (к тому же в лог шагов уходит строка
  «Сохранено в долговременную память: …»).

**REST-мутации события НЕ отправляют** — после `POST /memory/new-task`,
`POST /memory/long-term` и `DELETE /memory/long-term/{id}` фронтенд сам делает refetch
`GET /api/sessions/{sessionId}/memory`.

## 6. REST API (MemoryController)

| Метод | Путь | Ответ | Смысл |
|---|---|---|---|
| `GET` | `/api/sessions/{sessionId}/memory` | `200` — `{working: {task, notes[]}, longTerm: [...]}` | снапшот; для новой/несуществующей сессии — пустые структуры (`task: null`, `notes: []`, `longTerm: []`) — **никогда не 404** |
| `POST` | `/api/sessions/{sessionId}/memory/new-task` | `200` — `{"cleared": true}` | сброс WM: `task=null`, `notes=[]`; LTM не трогает |
| `POST` | `/api/sessions/{sessionId}/memory/long-term` | `200` — сохранённая запись (longTerm-shape) | upsert по (type, key), `sourceSessionId` = сессия запроса; `400` — невалидный `type` / пустые `key`/`value`; `500` — сбой записи в БД (store вернул `id=-1`) — на транспортном слое fail-closed, чтобы вызывающий узнал о неудаче |
| `DELETE` | `/api/sessions/{sessionId}/memory/long-term/{entryId}` | `200` — `{"deleted": true}` | удаление записи по id; `404` — такой записи нет |

Связь с удалением сессии: `DELETE /api/sessions/{sessionId}` (HistoryController) удаляет
ВСЕ per-session данные, включая строку WM (`workingMemoryStore.deleteBySession`), но **НЕ
трогает** LTM — записи долговременной памяти глобальные и переживают сессию.

## 7. UI: панель «Память»

`MemoryPanel.tsx` — в правой колонке между настройками LLM и логом шагов; App монтирует
панель только при активной сессии. Данные — из `GET /memory` + события `memory_updated`
(состояние в `useAgentSession.ts`, поле `memory`). Три сворачиваемые секции (механика
группы, как у LlmSettings):

| Секция | Содержимое |
|---|---|
| Краткосрочная (диалог) | только пояснение: полная история в `chat_messages`, что попадает в промпт — определяет стратегия контекста |
| Рабочая (текущая задача) | read-only: текущая `task` (или «Задача не активна») + нумерованный список `notes`; кнопка **«Новая задача»** → `POST /memory/new-task` + refetch |
| Долговременная (профиль, решения, знания) | список записей: бейдж типа (**Профиль** / **Решение** / **Знание**), key, value, строка `источник: <sessionId>`, кнопка «×» — удаление записи; форма добавления (select типа + поля Ключ/Значение → `POST /memory/long-term`) |

После любой REST-мутации панель перечитывает память (`refreshMemory` → `GET /memory`):
бэкенд SSE на REST-мутации не шлёт. Ошибки API показываются инлайн на 4 секунды.

## 8. Демо-сценарий (воспроизводимый)

**Шаг 1 — LTM через tool.** Сессия A. Написать:

```
Запомни: я вегетарианец и работаю бэкендером
```

Модель вызывает `memory_save` (type=`profile`, например key=`профессия`/`питание`) →
в панели «Память» в секции Долговременная появляются записи с бейджем **Профиль**; в
панели «Логи» — строка «Сохранено в долговременную память: …»; SSE `memory_updated`
обновляет панель.

**Шаг 2 — LTM глобальна.** Сессия B (новая). Написать:

```
Что ты помнишь обо мне?
```

Ответ тот же: агент называет вегетарианство и бэкенд-профессию — LTM глобальная, блок
`=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===` собирается из записей всех сессий. В панели B записи
видны, и у них `источник: <sessionId сессии A>` — происхождение запомнилось.

**Шаг 3 — WM на многоступенчатой задаче.** Написать (в любой сессии):

```
вычисли (12*3)+7 и скажи, какой сейчас год
```

Агент делает 2 tool-вызова (`calculator` + `get_current_datetime`). На старте run WM `task`
зафиксирована текстом вопроса (если до этого не было задачи); после каждого успешного
tool-результата — заметка вида `calculator: 43` и `get_current_datetime: …` (cap 1000).
В секции Рабочая видны задача и нумерованный список из 2 заметок.

**Шаг 4 — «Новая задача».** Нажать кнопку в секции Рабочая → WM очищена
(`task=null`, `notes=[]`), секция показывает «Задача не активна». LTM при этом
**не тронута** — записи из шага 1 на месте.

**Шаг 5 — удаление сессии.** `DELETE /api/sessions/<A>` → история и WM сессии A
удалены; записи LTM (с `sourceSessionId` = A) **остаются** и видны в сессии B —
долговременная память переживает удаление сессий.

> Контрольный итог: STM и WM умирают вместе с сессией, LTM — вечна, пока её явно не
> удалить (`DELETE /memory/long-term/{entryId}` или «×» в панели).

## 9. Исходники

Backend (`backend/src/main/kotlin/com/example/llmagent/`):

| Файл | Роль |
|---|---|
| `agent/AgentImpl.kt` | фиксация WM `task` на старте run, `wmNote` (append note + снапшот), контекстные блоки `=== РАБОЧАЯ ПАМЯТЬ ===` / `=== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ===` (top-20), `memorySnapshot` fail-open, log-строка «Сохранено в долговременную память: …» после `memory_save` |
| `agent/WorkingMemoryStore.kt` | `agent_working_memory`: `get`/`setTask`/`appendNote` (cap 1000)/`clear`/`deleteBySession`, fail-open |
| `agent/LongTermMemoryStore.kt` | `agent_long_term_memory`: `upsert` (cap 2000, UNIQUE(type,key)), `listAll` (ORDER BY updated_at DESC), `delete`, fail-open |
| `agent/tools/MemorySaveTool.kt` | tool `memory_save(type, key, value)`; валидация — строкой модели, не исключением; sessionId из coroutine context |
| `agent/ToolSessionContext.kt` | per-run sessionId в coroutine context (передаётся через `withContext` перед `tool.execute`) |
| `agent/Event.kt` | `MemoryUpdated` (type `memory_updated`, stepId `"memory"`) |
| `transport/MemoryController.kt` | GET /memory, POST /memory/new-task, POST /memory/long-term (400/500), DELETE /memory/long-term/{entryId} (404) |
| `transport/HistoryController.kt` | `DELETE /api/sessions/{id}` — хук `workingMemoryStore.deleteBySession` (LTM не трогается) |
| `src/main/resources/schema.sql` | таблицы `agent_working_memory`, `agent_long_term_memory` |

Frontend (`frontend/src/`):

| Файл | Роль |
|---|---|
| `components/MemoryPanel.tsx` | панель «Память»: 3 секции, бейджи Профиль/Решение/Знание, «Новая задача», форма добавления, удаление |
| `hooks/useAgentSession.ts` | состояние `memory`, обработка `memory_updated`, `refreshMemory`/`newTask` (refetch GET /memory) |

Контракт: `CONTRACT.md` — секции «Память агента (memory layers)» (таблицы эндпоинтов и
события `memory_updated`, порядок блоков контекста, tool `memory_save`), «Стратегии
контекста» (STM: что из истории попадает в промпт).

Тесты: `AgentMemoryLayersTest` (инъекция блоков WM/LTM, fail-open), `MemoryControllerTest`
(эндпоинты, 400/404/500), `WorkingMemoryStoreTest` / `LongTermMemoryStoreTest`
(хранилища: upsert, cap, порядок, удаление).
