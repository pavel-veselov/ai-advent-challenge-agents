// Контракт событий агента — зеркало CONTRACT.md.
// НЕ менять без синхронизации с бэкендом.

export type FinishReason = 'stop' | 'tool_calls' | 'length' | 'error';

/**
 * Применённые настройки LLM (событие agent_started и GET /api/llm-settings).
 * Зеркало LlmSettingsProvider на бэкенде; apiKey/baseUrl наружу не отдаются.
 */
export interface RunSettings {
  provider: string;
  model: string;
  temperature: number;
  /** Nucleus sampling (top_p) — уходит в API всегда. */
  topP: number;
  /** Top-k sampling; null — не задано (параметр не уходит в API). */
  topK: number | null;
  /** Лимит выходных токенов (max_tokens); null — без лимита. */
  maxTokens: number | null;
  timeoutSeconds: number;
  maxToolCallIterations: number;
  tools: string[];
}

/** Одно сообщение промпта, отправленного в LLM (событие llm_request_started). */
export interface PromptMessage {
  role: string;
  content: string;
}

export type AgentEventType =
  | 'agent_started'
  | 'llm_request_started'
  | 'llm_token'
  | 'llm_response_finished'
  | 'tool_call_started'
  | 'tool_call_finished'
  | 'agent_finished'
  | 'error';

export interface BaseEvent<TType extends AgentEventType, TPayload> {
  type: TType;
  runId: string;
  stepId: string;
  timestamp: string;
  payload: TPayload;
  sequence: number;
}

export type AgentEvent =
  | BaseEvent<'agent_started', { userMessage: string; settings: RunSettings }>
  | BaseEvent<'llm_request_started', { iteration: number; prompt: PromptMessage[] }>
  | BaseEvent<'llm_token', { delta: string }>
  | BaseEvent<
      'llm_response_finished',
      { finishReason: FinishReason; usage?: { inputTokens: number; outputTokens: number } }
    >
  | BaseEvent<'tool_call_started', { toolName: string; args: Record<string, unknown> }>
  | BaseEvent<'tool_call_finished', { result: string; status: 'success' | 'error' }>
  | BaseEvent<'agent_finished', { finalText: string }>
  | BaseEvent<'error', { message: string }>;

// ---- API ----
export interface ChatRequest {
  sessionId: string;
  message: string;
}

export interface HistoryMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface HistoryResponse {
  sessionId: string;
  messages: HistoryMessage[];
}

/** Ответ DELETE /api/sessions/{sessionId}. */
export interface DeleteResponse {
  deleted: boolean;
}

/** Ответ супервизорного контроля: POST /system-ctrl/stop и /system-ctrl/start. */
export interface ControlResponse {
  stopped?: boolean;
  starting?: boolean;
}

// ---- Состояние UI ----

/** Одна запись в логе шагов (панель «Лог шагов» справа). */
export interface StepLogEntry {
  /** `${runId}:${stepId}` — стабильный ключ записи. */
  id: string;
  /** Время события в формате HH:MM:SS. */
  time: string;
  /** kind — тип записи; system — служебные события жизненного цикла UI (не от SSE). */
  kind: 'user' | 'llm' | 'tool' | 'answer' | 'error' | 'system';
  title: string;
  status: 'running' | 'success' | 'error';
  iteration?: number;
  toolName?: string;
  /** Промпт, отправленный в LLM (событие llm_request_started). */
  prompt?: PromptMessage[];
  /** Аргументы вызова инструмента. */
  args?: Record<string, unknown>;
  /** Результат инструмента или текст ошибки. */
  result?: string;
  /** Служебная строка: finish_reason + входные/выходные токены. */
  detail?: string;
  /** Человеческое пояснение шага: что происходит и зачем (обучающий слой UI). */
  explanation?: string;
  /** Финальный ответ агента (событие agent_finished). */
  content?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  streaming?: boolean;
}
