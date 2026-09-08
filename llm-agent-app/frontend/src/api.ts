import type {
  AgentEvent,
  ChatRequest,
  ControlResponse,
  DeleteResponse,
  HistoryResponse,
  RunSettings,
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

/** Загрузка истории диалога для восстановления после перезагрузки. */
export async function fetchHistory(sessionId: string): Promise<HistoryResponse> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/history`);
  if (!res.ok) throw new Error(`history http ${res.status}`);
  return (await res.json()) as HistoryResponse;
}

/** Применённые настройки LLM — забираем при открытии страницы, до первого запроса. */
export async function fetchLlmSettings(): Promise<RunSettings> {
  const res = await fetch('/api/llm-settings');
  if (!res.ok) throw new Error(`llm-settings http ${res.status}`);
  return (await res.json()) as RunSettings;
}

/** Удаление сессии на бэкенде (история стирается в БД). */
export async function deleteSession(sessionId: string): Promise<DeleteResponse> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`delete-session http ${res.status}`);
  return (await res.json()) as DeleteResponse;
}

/**
 * Остановка бэк-сервиса: супервизорный контроль на 8081. Бэкенд может умереть
 * до ответа — ошибку сети игнорируем (готовность далее проверяется опросом).
 */
export async function stopBackend(): Promise<ControlResponse> {
  const res = await fetch('/system-ctrl/stop', { method: 'POST' });
  if (!res.ok) throw new Error(`stop http ${res.status}`);
  return (await res.json()) as ControlResponse;
}

/**
 * Запуск бэк-сервиса: команда супервизору, возвращается сразу. Готовность
 * сервиса проверяется отдельно опросом GET /api/llm-settings.
 */
export async function startBackend(): Promise<ControlResponse> {
  const res = await fetch('/system-ctrl/start', { method: 'POST' });
  if (!res.ok) throw new Error(`start http ${res.status}`);
  return (await res.json()) as ControlResponse;
}

/**
 * POST /api/chat со стримингом SSE через fetch + ReadableStream.
 * Каждый разобранный `data:`-блок передаётся в onEvent.
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
