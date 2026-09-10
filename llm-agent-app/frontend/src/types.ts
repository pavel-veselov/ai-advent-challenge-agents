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
  /** Лимит контекста модели в токенах (все входные токены); не задан — лимит неизвестен. */
  contextLimit?: number;
  /**
   * Рассуждения (thinking): true — модель может рассуждать перед ответом; при false шлюз
   * отправляет chat_template_kwargs.enable_thinking=false (действует для qwen* / deepseek*;
   * glm-* шлюз оставляет принудительно). Отсутствует в старых ответах — трактуем как true.
   */
  reasoningEnabled?: boolean;
  /** Тариф входных токенов, $ за 1M токенов (для расчёта стоимости, если бэкенд не прислал costUsd). */
  priceInputPer1M?: number;
  /** Тариф выходных токенов, $ за 1M токенов. */
  priceOutputPer1M?: number;
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
  | BaseEvent<'llm_request_started', { iteration: number; prompt: PromptMessage[]; estimatedRequestTokens?: number }>
  | BaseEvent<'llm_token', { delta: string }>
  | BaseEvent<
      'llm_response_finished',
      {
        finishReason: FinishReason;
        usage?: { inputTokens: number; outputTokens: number };
        estimatedRequestTokens?: number | null;
        costUsd?: number | null;
      }
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
  /** Токены промпта сообщения (бэкенд отдаёт для user-сообщений); null/undefined — неизвестно. */
  promptTokens?: number | null;
  /** Токены ответа ассистента; null/undefined — неизвестно. */
  completionTokens?: number | null;
}

export interface HistoryResponse {
  sessionId: string;
  messages: HistoryMessage[];
  /** Итоговые суммарные токены и стоимость диалога; null — бэкенд не накопил итоги. */
  totals?: { promptTokens: number; completionTokens: number; costUsd: number } | null;
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

/** Сводка одной сессии из каталога (GET /api/sessions). */
export interface SessionSummary {
  sessionId: string;
  messageCount: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
  lastActivity: string;
  /** Первое сообщение пользователя — заголовок вкладки; null — сообщений ещё не было. */
  firstUserMessage?: string | null;
}

/** Ответ GET /api/sessions: каталог всех сессий. */
export interface SessionsResponse {
  sessions: SessionSummary[];
}

/** Итоги за всё время существования хранилища (GET /api/stats → lifetime): кумулятивны, переживают удаление сессий. */
export interface LifetimeStats {
  sessionCount: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
}

/** Глобальная статистика по всем сессиям (GET /api/stats). */
export interface StatsResponse {
  sessionCount: number;
  messageCount: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
  /** Итоги за всё время; null/undefined — бэкенд ещё не отдаёт lifetime (старый ответ) — показывать агрегаты. */
  lifetime?: LifetimeStats | null;
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
  /**
   * Структурированные данные о токенах LLM-вызова (см. useAgentSession):
   * estimatedRequestTokens пишется на llm_request_started, остальное — на llm_response_finished.
   */
  tokenUsage?: {
    inputTokens?: number | null;
    outputTokens?: number | null;
    estimatedRequestTokens?: number | null;
    costUsd?: number | null;
  };
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
  /** Ошибка агента/LLM, привязанная к этому сообщению (рисуется строкой под пузырём). */
  error?: string;
  /**
   * Токены промпта, породившего этот ответ ассистента (usage.inputTokens финального
   * LLM-вызова запуска; из истории — prompt_tokens). null/undefined — неизвестно.
   */
  promptTokens?: number | null;
  /** Токены ответа ассистента (usage.outputTokens; из истории — completion_tokens). */
  completionTokens?: number | null;
}

/**
 * Накопительные итоги диалога по токенам: суммируются в useAgentSession на каждом
 * llm_response_finished и восстанавливаются из history.totals при старте/перезагрузке.
 */
export interface TokenTotals {
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
}
