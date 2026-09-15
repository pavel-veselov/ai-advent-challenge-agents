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
  | 'context_summary_started'
  | 'context_summary_finished'
  | 'facts_updated'
  | 'memory_updated'
  | 'log'
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
  | BaseEvent<'log', { text: string }>
  | BaseEvent<'context_summary_started', { foldCount: number; prompt: PromptMessage[] }>
  | BaseEvent<
      'context_summary_finished',
      {
        foldCount: number;
        promptTokens: number;
        completionTokens: number;
        summary: string;
        /** Оценка размера контекста до/после сжатия (эвристика бэкенда, токены). */
        contextTokensBefore: number;
        contextTokensAfter: number;
      }
    >
  | BaseEvent<'facts_updated', { facts: Record<string, string> }>
  | BaseEvent<'memory_updated', { projectId: number; working: WorkingMemoryState; longTerm: LongTermEntry[] }>
  | BaseEvent<'error', { message: string }>;

// ---- API ----
export interface ChatRequest {
  sessionId: string;
  message: string;
}

export interface HistoryMessage {
  /** id сообщения в истории бэкенда (нужен для ветвления: POST /branches {messageId}); всегда есть по контракту. */
  id: number;
  role: 'user' | 'assistant' | 'system';
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

/**
 * Настройки сжатия контекста одной сессии (GET/PUT /api/sessions/{sessionId}/compression).
 * Когда ничего не сохранено, бэкенд отдаёт дефолты: enabled=false, keepLast=5, summaryEvery=10.
 */
export interface SessionCompression {
  sessionId: string;
  enabled: boolean;
  /** Сколько последних сообщений оставлять «как есть» (1..50). */
  keepLast: number;
  /** Как часто сворачивать старые сообщения в summary (2..100). */
  summaryEvery: number;
}

/** Частичное тело PUT /api/sessions/{sessionId}/compression. */
export interface SessionCompressionPatch {
  enabled?: boolean;
  keepLast?: number;
  summaryEvery?: number;
}

// ---- Стратегия контекста (GET/PUT /api/sessions/{sessionId}/context-strategy) ----

/**
 * Стратегия управления контекстом сессии.
 * - "none" — полная история (без сжатия);
 * - "sliding_window" — скользящее окно: остаются последние N сообщений;
 * - "sticky_facts" — ключевые факты диалога + окно;
 * - "summary" — резюме + хвост (существующее сжатие контекста);
 * - "branching" — ветки диалога (история = активная цепочка ветки).
 */
export type ContextStrategy = 'none' | 'sliding_window' | 'sticky_facts' | 'summary' | 'branching';

/** Ответ GET/PUT /api/sessions/{sessionId}/context-strategy. */
export interface SessionContextStrategyState {
  sessionId: string;
  strategy: ContextStrategy;
  /** Размер окна последних сообщений; значим для sliding_window и sticky_facts (1..50). */
  windowSize: number;
}

/** Частичное тело PUT /api/sessions/{sessionId}/context-strategy (400 на невалидные значения). */
export interface SessionContextStrategyPatch {
  strategy?: ContextStrategy;
  windowSize?: number;
}

// ---- Факты диалога (GET /api/sessions/{sessionId}/facts, событие facts_updated) ----

/** Ответ GET /api/sessions/{sessionId}/facts; порядок ключей = порядок вставки. */
export interface FactsState {
  sessionId: string;
  facts: Record<string, string>;
}

// ---- Память (GET /api/projects/{projectId}/memory, событие memory_updated) ----

/** Запись долговременной памяти (LTM глобальна; sourceSessionId — сессия-источник записи). */
export interface LongTermEntry {
  id: number;
  sourceSessionId: string;
  type: 'profile' | 'decision' | 'knowledge';
  key: string;
  value: string;
  createdAt: string;
  updatedAt: string;
}

/** Рабочая память: текущая задача и заметки к ней. */
export interface WorkingMemoryState {
  task: string | null;
  notes: string[];
}

/** Ответ GET /api/projects/{projectId}/memory; совпадает с пейлоадом события memory_updated.
 * Рабочая память (working) — общая для всех сессий проекта; longTerm — глобальна. */
export interface MemoryState {
  working: WorkingMemoryState;
  longTerm: LongTermEntry[];
}

// ---- Ветки диалога (GET/POST/PUT /api/sessions/{sessionId}/branches) ----

export interface BranchInfo {
  id: number;
  name: string;
  headMessageId: number | null;
  createdAt: string;
}

/** Ответ GET /api/sessions/{sessionId}/branches и PUT (смена активной ветки). */
export interface BranchesState {
  sessionId: string;
  activeBranchId: number | null;
  branches: BranchInfo[];
}

/**
 * Настройки LLM одной сессии (GET/PUT /api/sessions/{sessionId}/llm-settings).
 * Бэкенд отдаёт полный эффективный набор: переопределённые сессией поля + текущие
 * глобальные значения для остальных (наследованные поля не персистятся).
 * provider сюда не входит — он задаётся окружением на сервере.
 */
export interface SessionLlmSettings {
  model: string;
  /** Лимит контекста модели в токенах (следует модели из каталога). */
  contextLimit: number;
  temperature: number;
  topP: number;
  /** null — параметр не уходит в API. */
  topK: number | null;
  /** null — без лимита выходных токенов (используется дефолт бэкенда). */
  maxTokens: number | null;
  timeoutSeconds: number;
  priceInputPer1M: number;
  priceOutputPer1M: number;
  reasoningEnabled: boolean;
}

/**
 * Частичное тело PUT /api/sessions/{sessionId}/llm-settings.
 * null снимает переопределение сессии — поле откатывается к текущему глобальному значению.
 */
export interface SessionLlmSettingsPatch {
  model?: string;
  temperature?: number;
  topP?: number;
  topK?: number | null;
  maxTokens?: number | null;
  timeoutSeconds?: number;
  priceInputPer1M?: number;
  priceOutputPer1M?: number;
  reasoningEnabled?: boolean | null;
}

/** Ответ супервизорного контроля: POST /system-ctrl/stop и /system-ctrl/start. */
export interface ControlResponse {
  stopped?: boolean;
  starting?: boolean;
}

/** Сводка одной сессии из каталога (GET /api/sessions, GET /api/projects/{id}/sessions). */
export interface SessionSummary {
  sessionId: string;
  /** Проект сессии (после day12 сессия всегда принадлежит проекту). */
  projectId: number;
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

// ---- Проекты (сущность над сессиями; GET/POST /api/projects и т.д.) ----

/** Проект: контейнер сессий; рабочая память (WM) живёт на проекте, LTM — глобальна. */
export interface Project {
  id: number;
  name: string;
  createdAt: string;
  updatedAt: string;
  /** Число сессий проекта; бэкенд может не отдавать — undefined. */
  sessionCount?: number;
}

/** Ответ POST /api/projects/{id}/sessions: сессия создана сервером (UUID) внутри проекта. */
export interface ProjectSessionCreated {
  sessionId: string;
  projectId: number;
  /** Заголовок, переданный при создании; null — не задан (fallback — id вкладки). */
  title: string | null;
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
  /**
   * id сообщения в истории бэкенда (HistoryMessage.id), если это сообщение пришло из истории
   * (а не стримится «вживую»). Нужен для ветвления: POST /branches {messageId}.
   * null/отсутствует — разветвить сообщение нельзя (ещё нет id на бэкенде).
   */
  historyId?: number | null;
  /** system — служебная заметка бэкенда (например, информация о сжатии контекста). */
  role: 'user' | 'assistant' | 'system';
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
