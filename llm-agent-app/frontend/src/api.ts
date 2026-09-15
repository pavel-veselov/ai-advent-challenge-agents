import type {
  AgentEvent,
  BranchInfo,
  BranchesState,
  ChatRequest,
  ControlResponse,
  DeleteResponse,
  FactsState,
  HistoryResponse,
  MemoryState,
  Project,
  ProjectSessionCreated,
  RunSettings,
  SessionCompression,
  SessionCompressionPatch,
  SessionContextStrategyPatch,
  SessionContextStrategyState,
  SessionLlmSettings,
  SessionLlmSettingsPatch,
  SessionSummary,
  SessionsResponse,
  StatsResponse,
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
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, message } satisfies ChatRequest),
    signal,
  });
  if (!res.ok || !res.body) {
    throw new Error(`chat http ${res.status}`);
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
