import type {
  ActiveProfileResponse,
  AgentEvent,
  AgentSchedulerCreateRequest,
  AgentSchedulerJob,
  AgentSchedulerPatch,
  BranchInfo,
  BranchesState,
  ChatRequest,
  ControlResponse,
  DeleteResponse,
  FactsState,
  HistoryResponse,
  Invariant,
  InvariantRequest,
  McpServer,
  McpServerRequest,
  MemoryState,
  Profile,
  ProfileRequest,
  Project,
  ProjectSessionCreated,
  RunSettings,
  SchedulerCreateRequest,
  SchedulerIntervalPatch,
  SchedulerSummary,
  SchedulerTask,
  SessionCompression,
  SessionCompressionPatch,
  SessionContextStrategyPatch,
  SessionContextStrategyState,
  SessionLlmSettings,
  SessionLlmSettingsPatch,
  SessionSummary,
  SessionsResponse,
  StatsResponse,
  TaskState,
  TaskStatePatch,
  WorkflowSettings,
} from './types';

function parseEventData(line: string): AgentEvent | null {
  const t = line.trim();
  if (!t) return null;
  try {
    return JSON.parse(t) as AgentEvent;
  } catch {
    return null;
  }
}

/** URL скачивания файла, сохранённого MCP-пайплайном (Day-19): бэкенд отдаёт байты attachment. */
export function downloadToolFileUrl(filename: string): string {
  return `/api/mcp/file/download?filename=${encodeURIComponent(filename)}`;
}

/** Р—Р°РіСЂСѓР·РєР° РёСЃС‚РѕСЂРёРё РґРёР°Р»РѕРіР° РґР»СЏ РІРѕСЃСЃС‚Р°РЅРѕРІР»РµРЅРёСЏ РїРѕСЃР»Рµ РїРµСЂРµР·Р°РіСЂСѓР·РєРё. */
export async function fetchHistory(sessionId: string): Promise<HistoryResponse> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/history`);
  if (!res.ok) throw new Error(`history http ${res.status}`);
  return (await res.json()) as HistoryResponse;
}

/** РџСЂРёРјРµРЅС‘РЅРЅС‹Рµ РЅР°СЃС‚СЂРѕР№РєРё LLM вЂ” Р·Р°Р±РёСЂР°РµРј РїСЂРё РѕС‚РєСЂС‹С‚РёРё СЃС‚СЂР°РЅРёС†С‹, РґРѕ РїРµСЂРІРѕРіРѕ Р·Р°РїСЂРѕСЃР°. */
export async function fetchLlmSettings(): Promise<RunSettings> {
  const res = await fetch('/api/llm-settings');
  if (!res.ok) throw new Error(`llm-settings http ${res.status}`);
  return (await res.json()) as RunSettings;
}

/**
 * РћР±РЅРѕРІР»РµРЅРёРµ РЅР°СЃС‚СЂРѕРµРє LLM: PUT /api/llm-settings (РёР·РјРµРЅСЏРµРјС‹ РІСЃРµ РїРѕР»СЏ, РєСЂРѕРјРµ РїСЂРѕРІР°Р№РґРµСЂР°).
 * Р’РѕР·РІСЂР°С‰Р°РµС‚ РїРѕР»РЅС‹Р№ РЅР°Р±РѕСЂ РїСЂРёРјРµРЅС‘РЅРЅС‹С… РЅР°СЃС‚СЂРѕРµРє; РїСЂРё РѕС€РёР±РєРµ Р±СЂРѕСЃР°РµС‚ (UI РІРѕР·РІСЂР°С‰Р°РµС‚ РїСЂРµР¶РЅРёРµ Р·РЅР°С‡РµРЅРёСЏ).
 */
export async function updateLlmSettings(patch: Partial<RunSettings>): Promise<RunSettings> {
  const res = await fetch('/api/llm-settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`llm-settings PUT http ${res.status}`);
  return (await res.json()) as RunSettings;
}

/** РЈРґР°Р»РµРЅРёРµ СЃРµСЃСЃРёРё РЅР° Р±СЌРєРµРЅРґРµ (РёСЃС‚РѕСЂРёСЏ СЃС‚РёСЂР°РµС‚СЃСЏ РІ Р‘Р”). */
export async function deleteSession(sessionId: string): Promise<DeleteResponse> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`delete-session http ${res.status}`);
  return (await res.json()) as DeleteResponse;
}

/**
 * Настройки сжатия контекста активной сессии: GET /api/sessions/{sessionId}/compression.
 * Когда ничего не сохранено, бэкенд отдаёт дефолты (enabled=false, keepLast=5, summaryEvery=10).
 */
export async function fetchSessionCompression(sessionId: string): Promise<SessionCompression> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/compression`);
  if (!res.ok) throw new Error(`compression http ${res.status}`);
  return (await res.json()) as SessionCompression;
}

/**
 * Обновление настроек сжатия контекста: PUT /api/sessions/{sessionId}/compression
 * (частичное тело). Возвращает полное состояние; при ошибке бросает (UI откатывает черновики).
 */
export async function updateSessionCompression(
  sessionId: string,
  patch: SessionCompressionPatch,
): Promise<SessionCompression> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/compression`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`compression PUT http ${res.status}`);
  return (await res.json()) as SessionCompression;
}

/**
 * Стратегия управления контекстом активной сессии: GET /api/sessions/{sessionId}/context-strategy.
 * Когда ничего не сохранено, бэкенд отдаёт дефолт: strategy="none".
 */
export async function fetchContextStrategy(sessionId: string): Promise<SessionContextStrategyState> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/context-strategy`);
  if (!res.ok) throw new Error(`context-strategy http ${res.status}`);
  return (await res.json()) as SessionContextStrategyState;
}

/**
 * Обновление стратегии контекста: PUT /api/sessions/{sessionId}/context-strategy
 * (частичное тело {strategy?, windowSize?}). 400 — на невалидные значения. Возвращает
 * полное состояние; при ошибке бросает (UI откатывает).
 */
export async function updateContextStrategy(
  sessionId: string,
  patch: SessionContextStrategyPatch,
): Promise<SessionContextStrategyState> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/context-strategy`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`context-strategy PUT http ${res.status}`);
  return (await res.json()) as SessionContextStrategyState;
}

/**
 * Факты диалога сессии: GET /api/sessions/{sessionId}/facts.
 * Порядок ключей = порядок вставки (живёт также в событиях SSE facts_updated).
 */
export async function fetchFacts(sessionId: string): Promise<FactsState> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/facts`);
  if (!res.ok) throw new Error(`facts http ${res.status}`);
  return (await res.json()) as FactsState;
}

/**
 * Состояние задачи сессии: GET /api/sessions/{sessionId}/task-state.
 * 404 — задача не начата (состояние не сохранено) → null; остальные ошибки бросает.
 */
export async function fetchTaskState(sessionId: string): Promise<TaskState | null> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/task-state`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`task-state http ${res.status}`);
  return (await res.json()) as TaskState;
}

/**
 * Обновление состояния задачи: PUT /api/sessions/{sessionId}/task-state (частичное тело:
 * {paused} — пауза/снятие, {stage, currentStep?, expectedAction?} — смена этапа).
 * 400 — недопустимый переход/этап. Возвращает полное состояние; при ошибке бросает.
 * REST-мутации SSE не шлют — панель обновляем из ответа.
 */
export async function updateTaskState(sessionId: string, patch: TaskStatePatch): Promise<TaskState> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/task-state`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`task-state PUT http ${res.status}`);
  return (await res.json()) as TaskState;
}

/**
 * Память проекта (рабочая + долговременная): GET /api/projects/{projectId}/memory.
 * Рабочая память общая для всех сессий проекта; долговременная — глобальна.
 */
export async function fetchProjectMemory(projectId: number): Promise<MemoryState> {
  const res = await fetch(`/api/projects/${projectId}/memory`);
  if (!res.ok) throw new Error(`project memory http ${res.status}`);
  return (await res.json()) as MemoryState;
}

/**
 * Добавить запись долговременной памяти: POST /api/sessions/{sessionId}/memory/long-term.
 * LTM глобальна; sessionId задаёт только источник записи (source_session_id).
 */
export async function addLongTermMemory(
  sessionId: string,
  body: { type: string; key: string; value: string },
): Promise<void> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/memory/long-term`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`long-term memory POST http ${res.status}`);
}

/**
 * Удалить запись долговременной памяти: DELETE /api/sessions/{sessionId}/memory/long-term/{entryId}.
 */
export async function deleteLongTermMemory(sessionId: string, entryId: number): Promise<void> {
  const res = await fetch(
    `/api/sessions/${encodeURIComponent(sessionId)}/memory/long-term/${entryId}`,
    { method: 'DELETE' },
  );
  if (!res.ok) throw new Error(`long-term memory DELETE http ${res.status}`);
}

/**
 * Очистка ВСЕЙ долговременной памяти (глобальная: все проекты и сессии):
 * DELETE /api/memory/long-term. Тело ответа не разбираем: успех — 200.
 */
export async function clearLongTermMemory(): Promise<void> {
  const res = await fetch('/api/memory/long-term', { method: 'DELETE' });
  if (!res.ok) throw new Error(`long-term memory clear DELETE http ${res.status}`);
}

/**
 * Новая задача (сброс рабочей памяти проекта): POST /api/projects/{projectId}/memory/new-task.
 */
export async function resetProjectMemory(projectId: number): Promise<void> {
  const res = await fetch(`/api/projects/${projectId}/memory/new-task`, { method: 'POST' });
  if (!res.ok) throw new Error(`project memory new-task POST http ${res.status}`);
}

/**
 * Инварианты проекта: GET /api/projects/{projectId}/invariants.
 * Инварианты — обязательные ограничения ассистента, общие для всех сессий проекта.
 */
export async function fetchInvariants(projectId: number): Promise<Invariant[]> {
  const res = await fetch(`/api/projects/${projectId}/invariants`);
  if (!res.ok) throw new Error(`invariants http ${res.status}`);
  return (await res.json()) as Invariant[];
}

/**
 * Добавить инвариант проекта: POST /api/projects/{projectId}/invariants {category?, text}.
 * 400 — text пустой/blank; 404 — нет проекта; возвращает созданную запись.
 */
export async function addInvariant(projectId: number, body: InvariantRequest): Promise<Invariant> {
  const res = await fetch(`/api/projects/${projectId}/invariants`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`invariants POST http ${res.status}`);
  return (await res.json()) as Invariant;
}

/**
 * Обновить инвариант проекта: PUT /api/projects/{projectId}/invariants/{id} {category?, text}.
 * 400 — text blank; 404 — нет записи; возвращает обновлённую запись.
 */
export async function updateInvariant(
  projectId: number,
  id: number,
  body: InvariantRequest,
): Promise<Invariant> {
  const res = await fetch(`/api/projects/${projectId}/invariants/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`invariants PUT http ${res.status}`);
  return (await res.json()) as Invariant;
}

/**
 * Удалить инвариант проекта: DELETE /api/projects/{projectId}/invariants/{id}.
 * 404 — запись не найдена; тело ответа не разбираем: успех — 200.
 */
export async function deleteInvariant(projectId: number, id: number): Promise<void> {
  const res = await fetch(`/api/projects/${projectId}/invariants/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`invariants DELETE http ${res.status}`);
}

/** Тело POST /api/projects/{projectId}/memory/notes. */
export interface SaveWorkingNoteRequest {
  note: string;
}

/**
 * Добавить заметку в рабочую память проекта: POST /api/projects/{projectId}/memory/notes.
 * Заметка попадает в notes рабочей памяти, общей для всех сессий проекта.
 * Тело запроса не разбираем: успех — 200, ошибка бросает (UI показывает «Ошибка» у кнопки).
 */
export async function saveWorkingNote(projectId: number, note: string): Promise<void> {
  const res = await fetch(`/api/projects/${projectId}/memory/notes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ note } satisfies SaveWorkingNoteRequest),
  });
  if (!res.ok) throw new Error(`project memory notes POST http ${res.status}`);
}

/**
 * Ветки диалога сессии: GET /api/sessions/{sessionId}/branches (активная ветка + список).
 */
export async function fetchBranches(sessionId: string): Promise<BranchesState> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/branches`);
  if (!res.ok) throw new Error(`branches http ${res.status}`);
  return (await res.json()) as BranchesState;
}

/**
 * Новая ветка от сообщения истории: POST /api/sessions/{sessionId}/branches
 * {messageId: <id из истории>}. Новая ветка становится активной.
 */
export async function createBranch(sessionId: string, messageId: number): Promise<BranchInfo> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/branches`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messageId }),
  });
  if (!res.ok) throw new Error(`branches POST http ${res.status}`);
  return (await res.json()) as BranchInfo;
}

/**
 * Переключение активной ветки: PUT /api/sessions/{sessionId}/branches
 * {activeBranchId}; возвращает полный GET-шейп веток.
 */
export async function setActiveBranch(sessionId: string, branchId: number): Promise<BranchesState> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/branches`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ activeBranchId: branchId }),
  });
  if (!res.ok) throw new Error(`branches PUT http ${res.status}`);
  return (await res.json()) as BranchesState;
}

/**
 * Эффективные настройки LLM активной сессии: GET /api/sessions/{sessionId}/llm-settings.
 * Полный набор: переопределённые сессией поля + текущие глобальные для остальных
 * (наследованные поля не персистятся).
 */
export async function fetchSessionLlmSettings(sessionId: string): Promise<SessionLlmSettings> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/llm-settings`);
  if (!res.ok) throw new Error(`session llm-settings http ${res.status}`);
  return (await res.json()) as SessionLlmSettings;
}

/**
 * Обновление настроек LLM сессии: PUT /api/sessions/{sessionId}/llm-settings (частичное тело,
 * null снимает переопределение). Возвращает полный эффективный набор; при ошибке бросает
 * (UI откатывает черновики).
 */
export async function updateSessionLlmSettings(
  sessionId: string,
  patch: SessionLlmSettingsPatch,
): Promise<SessionLlmSettings> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/llm-settings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`session llm-settings PUT http ${res.status}`);
  return (await res.json()) as SessionLlmSettings;
}

/** Каталог проектов: GET /api/projects. */
export async function fetchProjects(): Promise<Project[]> {
  const res = await fetch('/api/projects');
  if (!res.ok) throw new Error(`projects http ${res.status}`);
  return (await res.json()) as Project[];
}

/** Создание проекта: POST /api/projects {name}; возвращает созданный Project. */
export async function createProject(name: string): Promise<Project> {
  const res = await fetch('/api/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error(`projects POST http ${res.status}`);
  return (await res.json()) as Project;
}

/** Переименование проекта: PATCH /api/projects/{id} {name}; возвращает обновлённый Project. */
export async function renameProject(id: number, name: string): Promise<Project> {
  const res = await fetch(`/api/projects/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error(`projects PATCH http ${res.status}`);
  return (await res.json()) as Project;
}

/**
 * Удаление проекта: DELETE /api/projects/{id}. Каскад на бэкенде: сессии проекта
 * (вместе с историями) и рабочая память проекта; долговременная память не трогается.
 */
export async function deleteProject(id: number): Promise<DeleteResponse> {
  const res = await fetch(`/api/projects/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`projects DELETE http ${res.status}`);
  return (await res.json()) as DeleteResponse;
}

/** Сессии проекта: GET /api/projects/{id}/sessions. */
export async function fetchProjectSessions(projectId: number): Promise<SessionSummary[]> {
  const res = await fetch(`/api/projects/${projectId}/sessions`);
  if (!res.ok) throw new Error(`project sessions http ${res.status}`);
  return (await res.json()) as SessionSummary[];
}

/**
 * Создание сессии внутри проекта: POST /api/projects/{id}/sessions {title?}.
 * id сессии генерирует сервер (UUID); возвращает {sessionId, projectId, title}.
 */
export async function createSessionInProject(
  projectId: number,
  title?: string,
): Promise<ProjectSessionCreated> {
  const res = await fetch(`/api/projects/${projectId}/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(title != null ? { title } : {}),
  });
  if (!res.ok) throw new Error(`project sessions POST http ${res.status}`);
  return (await res.json()) as ProjectSessionCreated;
}

/** РљР°С‚Р°Р»РѕРі РІСЃРµС… СЃРµСЃСЃРёР№ СЃ Р°РіСЂРµРіРёСЂРѕРІР°РЅРЅС‹РјРё РјРµС‚СЂРёРєР°РјРё (GET /api/sessions). */
export async function fetchSessions(): Promise<SessionsResponse> {
  const res = await fetch('/api/sessions');
  if (!res.ok) throw new Error(`sessions http ${res.status}`);
  return (await res.json()) as SessionsResponse;
}

/** Р“Р»РѕР±Р°Р»СЊРЅР°СЏ СЃС‚Р°С‚РёСЃС‚РёРєР° РїРѕ РІСЃРµРј СЃРµСЃСЃРёСЏРј (GET /api/stats). */
export async function fetchGlobalStats(): Promise<StatsResponse> {
  const res = await fetch('/api/stats');
  if (!res.ok) throw new Error(`stats http ${res.status}`);
  return (await res.json()) as StatsResponse;
}


/**
 * РћСЃС‚Р°РЅРѕРІРєР° Р±СЌРє-СЃРµСЂРІРёСЃР°: СЃСѓРїРµСЂРІРёР·РѕСЂРЅС‹Р№ РєРѕРЅС‚СЂРѕР»СЊ РЅР° 8081. Р‘СЌРєРµРЅРґ РјРѕР¶РµС‚ СѓРјРµСЂРµС‚СЊ
 * РґРѕ РѕС‚РІРµС‚Р° вЂ” РѕС€РёР±РєСѓ СЃРµС‚Рё РёРіРЅРѕСЂРёСЂСѓРµРј (РіРѕС‚РѕРІРЅРѕСЃС‚СЊ РґР°Р»РµРµ РїСЂРѕРІРµСЂСЏРµС‚СЃСЏ РѕРїСЂРѕСЃРѕРј).
 */
export async function stopBackend(): Promise<ControlResponse> {
  const res = await fetch('/system-ctrl/stop', { method: 'POST' });
  if (!res.ok) throw new Error(`stop http ${res.status}`);
  return (await res.json()) as ControlResponse;
}

/**
 * Р—Р°РїСѓСЃРє Р±СЌРє-СЃРµСЂРІРёСЃР°: РєРѕРјР°РЅРґР° СЃСѓРїРµСЂРІРёР·РѕСЂСѓ, РІРѕР·РІСЂР°С‰Р°РµС‚СЃСЏ СЃСЂР°Р·Сѓ. Р“РѕС‚РѕРІРЅРѕСЃС‚СЊ
 * СЃРµСЂРІРёСЃР° РїСЂРѕРІРµСЂСЏРµС‚СЃСЏ РѕС‚РґРµР»СЊРЅРѕ РѕРїСЂРѕСЃРѕРј GET /api/llm-settings.
 */
export async function startBackend(): Promise<ControlResponse> {
  const res = await fetch('/system-ctrl/start', { method: 'POST' });
  if (!res.ok) throw new Error(`start http ${res.status}`);
  return (await res.json()) as ControlResponse;
}

/**
 * POST /api/chat СЃРѕ СЃС‚СЂРёРјРёРЅРіРѕРј SSE С‡РµСЂРµР· fetch + ReadableStream.
 * РљР°Р¶РґС‹Р№ СЂР°Р·РѕР±СЂР°РЅРЅС‹Р№ `data:`-Р±Р»РѕРє РїРµСЂРµРґР°С‘С‚СЃСЏ РІ onEvent.
 */
export async function streamChat(
  sessionId: string,
  message: string,
  signal: AbortSignal,
  onEvent: (e: AgentEvent) => void,
): Promise<void> {
  await streamSse(
    '/api/chat',
    JSON.stringify({ sessionId, message } satisfies ChatRequest),
    signal,
    onEvent,
  );
}

/**
 * Читает SSE-поток произвольного POST-эндпоинта (fetch + ReadableStream):
 * тело запроса [body] (строка; может быть пустой), каждый `data:`-блок — в onEvent.
 * Общая функция для /api/chat и /api/sessions/{id}/task-state/continue.
 */
async function streamSse(
  url: string,
  body: string,
  signal: AbortSignal,
  onEvent: (e: AgentEvent) => void,
): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    signal,
  });
  if (!res.ok || !res.body) {
    throw new Error(`http ${res.status}`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      const dataLine = block.split('\n').find((l) => l.startsWith('data:'));
      if (!dataLine) continue;
      const ev = parseEventData(dataLine.slice('data:'.length));
      if (ev) onEvent(ev);
    }
  }
}

/**
 * Продолжение воркфлоу (Day-14, кнопка «Продолжить»): POST /api/sessions/{sid}/task-state/continue
 * возвращает SSE-поток следующего этапа агента (контекст продолжается). Каждое событие — в onEvent.
 */
export async function streamContinue(
  sessionId: string,
  signal: AbortSignal,
  onEvent: (e: AgentEvent) => void,
): Promise<void> {
  await streamSse(
    `/api/sessions/${encodeURIComponent(sessionId)}/task-state/continue`,
    '{}',
    signal,
    onEvent,
  );
}

/** Отмена воркфлоу (Day-14, кнопка «Отмена»): POST /api/sessions/{sid}/task-state/cancel.
 *  Агент не запускается — возвращает обновлённое состояние задачи (JSON, без SSE). */
export async function cancelTaskState(sessionId: string): Promise<TaskState> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/task-state/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  });
  if (!res.ok) throw new Error(`task-state cancel POST http ${res.status}`);
  return (await res.json()) as TaskState;
}

// ---- Профили пользователя (персонализация агента; глобальный справочник) ----

/** Справочник профилей пользователя: GET /api/profiles (глобальный, вне проектов/сессий). */
export async function fetchProfiles(): Promise<Profile[]> {
  const res = await fetch('/api/profiles');
  if (!res.ok) throw new Error(`profiles http ${res.status}`);
  return (await res.json()) as Profile[];
}

/** Активный профиль — ГЛОБАЛЬНАЯ настройка приложения: GET /api/profiles/active. */
export async function fetchActiveProfile(): Promise<ActiveProfileResponse> {
  const res = await fetch('/api/profiles/active');
  if (!res.ok) throw new Error(`profiles active http ${res.status}`);
  return (await res.json()) as ActiveProfileResponse;
}

/**
 * Создание профиля: POST /api/profiles {name, position?, responseFormat?, preferences?, constraints?}.
 * 400 — пустое имя, 409 — имя уже занято; при ошибке бросает (UI показывает сообщение в диалоге).
 */
export async function createProfile(body: ProfileRequest): Promise<Profile> {
  const res = await fetch('/api/profiles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`profiles POST http ${res.status}`);
  return (await res.json()) as Profile;
}

/**
 * Обновление профиля: PUT /api/profiles/{id} {name, position?, responseFormat?, preferences?, constraints?}.
 * 404 — нет профиля, 400 — пустое имя, 409 — новое имя занято другим профилем;
 * при ошибке бросает (UI показывает сообщение в диалоге).
 */
export async function updateProfile(id: number, body: ProfileRequest): Promise<Profile> {
  const res = await fetch(`/api/profiles/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`profiles PUT http ${res.status}`);
  return (await res.json()) as Profile;
}

/**
 * Удаление профиля: DELETE /api/profiles/{id}. Если удаляемый был активным, бэкенд
 * сбрасывает глобальную активную ссылку на «Без профиля».
 */
export async function deleteProfile(id: number): Promise<DeleteResponse> {
  const res = await fetch(`/api/profiles/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`profiles DELETE http ${res.status}`);
  return (await res.json()) as DeleteResponse;
}

/**
 * Выбор активного профиля: PUT /api/profiles/active {profileId: number | null}.
 * null — «Без профиля»; 404 — профиль не существует. Возвращает эхо activeProfileId.
 */
export async function setActiveProfile(profileId: number | null): Promise<ActiveProfileResponse> {
  const res = await fetch('/api/profiles/active', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ profileId }),
  });
  if (!res.ok) throw new Error(`profiles active PUT http ${res.status}`);
  return (await res.json()) as ActiveProfileResponse;
}

// ---- Воркфлоу (Day-14): GET/PUT /api/workflow-settings ----

/** Настройки воркфлоу: GET /api/workflow-settings (глобальные, app_settings). */
export async function fetchWorkflowSettings(): Promise<WorkflowSettings> {
  const res = await fetch('/api/workflow-settings');
  if (!res.ok) throw new Error(`workflow-settings http ${res.status}`);
  return (await res.json()) as WorkflowSettings;
}

/**
 * Обновление настроек воркфлоу: PUT /api/workflow-settings {enabled, mode}.
 * 400 — mode не в {manual,auto}; возвращает сохранённый набор. При ошибке бросает
 * (UI откатывает переключатель/режим к прежним значениям).
 */
export async function updateWorkflowSettings(patch: WorkflowSettings): Promise<WorkflowSettings> {
  const res = await fetch('/api/workflow-settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!res.ok) throw new Error(`workflow-settings PUT http ${res.status}`);
  return (await res.json()) as WorkflowSettings;
}

// ---- MCP-серверы (GET/POST /api/mcp-servers, PUT .../{id}/enabled, DELETE /{id}) ----

/**
 * Разбирает JSON-ошибку бэкенда вида {error: "..."} и возвращает Error с её текстом;
 * тело не JSON / error пустой — fallback «<prefix> http <status>» (идиома остальных
 * функций файла, но с текстом бэкенда, который нужно показывать пользователю).
 */
async function raiseMcpError(res: Response, prefix: string): Promise<never> {
  let message = `${prefix} http ${res.status}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (typeof body.error === 'string' && body.error !== '') message = body.error;
  } catch {
    /* тело не разбирается — остаётся fallback */
  }
  throw new Error(message);
}

/** Каталог MCP-серверов: GET /api/mcp-servers. */
export async function fetchMcpServers(): Promise<McpServer[]> {
  const res = await fetch('/api/mcp-servers');
  if (!res.ok) await raiseMcpError(res, 'mcp-servers');
  return (await res.json()) as McpServer[];
}

/**
 * Добавление MCP-сервера: POST /api/mcp-servers {name, url}.
 * Создаётся неактивным (enabled=false); 400 — пустые поля, 409 — имя занято.
 */
export async function createMcpServer(body: McpServerRequest): Promise<McpServer> {
  const res = await fetch('/api/mcp-servers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) await raiseMcpError(res, 'mcp-servers POST');
  return (await res.json()) as McpServer;
}

/**
 * Активация/деактивация MCP-сервера: PUT /api/mcp-servers/{id}/enabled {enabled}.
 * При активации бэкенд подключается к серверу и возвращает обновлённую запись
 * (tools заполнены); 404 — сервер не найден.
 */
export async function setMcpServerEnabled(id: number, enabled: boolean): Promise<McpServer> {
  const res = await fetch(`/api/mcp-servers/${id}/enabled`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  });
  if (!res.ok) await raiseMcpError(res, 'mcp-servers enabled PUT');
  return (await res.json()) as McpServer;
}

/**
 * Удаление MCP-сервера: DELETE /api/mcp-servers/{id} → {deleted: true};
 * 404 — сервер не найден.
 */
export async function deleteMcpServer(id: number): Promise<DeleteResponse> {
  const res = await fetch(`/api/mcp-servers/${id}`, { method: 'DELETE' });
  if (!res.ok) await raiseMcpError(res, 'mcp-servers DELETE');
  return (await res.json()) as DeleteResponse;
}

// ---- Планировщик (Day-17): периодические задачи сбора данных + сводка ----
// Контракт планировщика — snake_case (см. SchedulerTask/SchedulerCreateRequest в types.ts).

/** Список периодических задач: GET /api/scheduler/tasks. */
export async function fetchSchedulerTasks(): Promise<SchedulerTask[]> {
  const res = await fetch('/api/scheduler/tasks');
  if (!res.ok) await raiseMcpError(res, 'scheduler tasks');
  return (await res.json()) as SchedulerTask[];
}

/**
 * Создание периодической задачи: POST /api/scheduler/tasks
 * {name, source, interval_seconds, params?}; 400 — пустое имя/невалидный период.
 */
export async function createSchedulerTask(body: SchedulerCreateRequest): Promise<SchedulerTask> {
  const res = await fetch('/api/scheduler/tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) await raiseMcpError(res, 'scheduler tasks POST');
  return (await res.json()) as SchedulerTask;
}

/**
 * Смена периода задачи: PATCH /api/scheduler/tasks/{id}/interval {interval_seconds};
 * 404 — задача не найдена, 400 — период меньше минимума. Возвращает обновлённую задачу.
 */
export async function setSchedulerTaskInterval(
  id: number,
  body: SchedulerIntervalPatch,
): Promise<SchedulerTask> {
  const res = await fetch(`/api/scheduler/tasks/${id}/interval`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) await raiseMcpError(res, 'scheduler interval PATCH');
  return (await res.json()) as SchedulerTask;
}

/** Удаление задачи: DELETE /api/scheduler/tasks/{id} → {deleted: true}; 404 — не найдена. */
export async function deleteSchedulerTask(id: number): Promise<DeleteResponse> {
  const res = await fetch(`/api/scheduler/tasks/${id}`, { method: 'DELETE' });
  if (!res.ok) await raiseMcpError(res, 'scheduler tasks DELETE');
  return (await res.json()) as DeleteResponse;
}

/**
 * Сборка сводки: POST /api/scheduler/summary {task_id?, since_hours?}.
 * task_id null — сводка по всем задачам; вернёт агрегаты + человекочитаемый текст.
 */
export async function fetchSchedulerSummary(
  body: { task_id?: number; since_hours?: number } = {},
): Promise<SchedulerSummary> {
  const res = await fetch('/api/scheduler/summary', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) await raiseMcpError(res, 'scheduler summary POST');
  return (await res.json()) as SchedulerSummary;
}

// ---- Планировщик заданий агента (agent-scheduler): периодические промпты агента ----
// Контракт — snake_case (см. AgentSchedulerJob в types.ts). Базовый путь /api/agent-scheduler.

/** Список заданий агента: GET /api/agent-scheduler. */
export async function fetchAgentScheduler(): Promise<AgentSchedulerJob[]> {
  const res = await fetch('/api/agent-scheduler');
  if (!res.ok) await raiseMcpError(res, 'agent-scheduler');
  return (await res.json()) as AgentSchedulerJob[];
}

/**
 * Создание задания агента: POST /api/agent-scheduler
 * {name, interval_seconds, prompt}; 400 — пустое имя/невалидный период.
 */
export async function createAgentSchedulerJob(
  body: AgentSchedulerCreateRequest,
): Promise<AgentSchedulerJob> {
  const res = await fetch('/api/agent-scheduler', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) await raiseMcpError(res, 'agent-scheduler POST');
  return (await res.json()) as AgentSchedulerJob;
}

/**
 * Обновление задания агента: PATCH /api/agent-scheduler/{id} (частичное тело
 * {name?, interval_seconds?, prompt?, enabled?}); 404 — задание не найдено.
 */
export async function updateAgentSchedulerJob(
  id: number,
  body: AgentSchedulerPatch,
): Promise<AgentSchedulerJob> {
  const res = await fetch(`/api/agent-scheduler/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) await raiseMcpError(res, 'agent-scheduler PATCH');
  return (await res.json()) as AgentSchedulerJob;
}

/** Удаление задания агента: DELETE /api/agent-scheduler/{id} → { ok: boolean }; 404 — не найдено. */
export async function deleteAgentSchedulerJob(id: number): Promise<{ ok: boolean }> {
  const res = await fetch(`/api/agent-scheduler/${id}`, { method: 'DELETE' });
  if (!res.ok) await raiseMcpError(res, 'agent-scheduler DELETE');
  return (await res.json()) as { ok: boolean };
}
