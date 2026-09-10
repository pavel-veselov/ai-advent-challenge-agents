import type {
  AgentEvent,
  ChatRequest,
  ControlResponse,
  DeleteResponse,
  HistoryResponse,
  RunSettings,
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
