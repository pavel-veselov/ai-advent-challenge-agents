import { useCallback, useEffect, useRef, useState } from 'react';
import {
  addLongTermMemory as addLongTermMemoryApi,
  cancelTaskState,
  createBranch as createBranchApi,
  createProject as createProjectApi,
  createSessionInProject as createSessionInProjectApi,
  deleteProject as deleteProjectApi,
  deleteSession as deleteSessionApi,
  fetchBranches,
  fetchContextStrategy,
  fetchFacts,
  fetchGlobalStats,
  fetchHistory,
  fetchLlmSettings,
  fetchProjectSessions,
  fetchProjects,
  fetchTaskState,
  fetchWorkflowSettings,
  renameProject as renameProjectApi,
  saveWorkingNote as saveWorkingNoteApi,
  setActiveBranch as setActiveBranchApi,
  startBackend as startBackendApi,
  stopBackend as stopBackendApi,
  streamChat,
  streamContinue,
  updateContextStrategy,
  updateLlmSettings as updateLlmSettingsApi,
  updateTaskState as updateTaskStateApi,
  updateWorkflowSettings,
} from '../api';
import type {
  AgentEvent,
  BranchesState,
  ChatMessage,
  ContextStrategy,
  HistoryMessage,
  HistoryResponse,
  Project,
  RunSettings,
  SessionContextStrategyPatch,
  StatsResponse,
  StepLogEntry,
  TaskState,
  TaskStatePatch,
  TokenTotals,
  WorkflowSettings,
} from '../types';

const SESSION_KEY = 'llm-agent-session-id';
const TABS_KEY = 'llm-agent-tabs';
/** Активный проект: переживает перезагрузку страницы (id или пусто). */
const PROJECT_KEY = 'llm-agent-active-project';

/** Дефолтный размер окна последних сообщений (sliding_window/sticky_facts) до ответа GET. */
const DEFAULT_CONTEXT_WINDOW = 10;

function newId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `id-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Следующий линейный этап воркфлоу Day-14 (planning → execution → validation → done);
 * для done/неизвестного — сам этап (переходить дальше некуда). Зеркало
 * TaskStateStore.nextWorkflowStage на бэке.
 */
function nextWorkflowStage(stage: TaskState['stage']): TaskState['stage'] {
  switch (stage) {
    case 'planning':
      return 'execution';
    case 'execution':
      return 'validation';
    case 'validation':
      return 'done';
    default:
      return stage;
  }
}

/**
 * Сетевой ли это сбой (fetch/SSE не дочитали поток, браузер отдаёт бессодержательный
 * TypeError «Failed to fetch»). Такие ошибки — это обрыв соединения, а НЕ ошибка LLM/агента:
 * показываем своё сообщение «Нет связи с сервером», а не сырой текст браузера.
 */
function isNetworkFailure(err: unknown): boolean {
  if (err instanceof TypeError) return true;
  const msg = err instanceof Error ? err.message : String(err);
  return /failed to fetch|networkerror|load failed|connection|network error/i.test(msg);
}

/** Человекочитаемый текст ошибки: сетевые сбои — «Нет связи с сервером», остальное — дословно. */
function describeStreamError(err: unknown): string {
  return isNetworkFailure(err) ? 'Нет связи с сервером' : err instanceof Error ? err.message : 'Нет связи с сервером';
}

interface TabsStateData {
  ids: string[];
  activeId: string | null;
}

/**
 * Буфер состояния выполнения одной сессии. Каждая сессия ведёт собственные сообщения,
 * итоги токенов и свой AbortController: параллельные запуски разных сессий не мешают
 * друг другу. Активная вкладка показывает состояние только своей сессии, остальные
 * пишутся в буфер и «всплывают» в живой вид при переключении.
 */
interface SessionRunState {
  messages: ChatMessage[];
  tokenTotals: TokenTotals;
  lastPromptTokens: number | null;
  isRunning: boolean;
  /** id ассистентского сообщения текущего стрима сессии (для приклейки токенов). */
  assistantId: string;
  controller: AbortController | null;
  /** Стратегия контекста сессии (GET/PUT /context-strategy); 'none' — полная история. */
  strategy: ContextStrategy;
  /** Размер окна последних сообщений стратегии (sliding_window/sticky_facts; 1..50). */
  windowSize: number;
  /** Ключевые факты диалога сессии (GET /facts, события facts_updated); null — ещё не загружены. */
  facts: Record<string, string> | null;
  /** Ветки диалога сессии (GET/PUT /branches); null — ещё не загружены. */
  branches: BranchesState | null;
  /** Состояние задачи сессии (GET/PUT /task-state, событие task_state_changed); null — задача не начата. */
  taskState: TaskState | null;
}

/** Преобразует сообщения истории бэкенда в сообщения чата UI (свежие id, без streaming). */
function historyToMessages(msgs: HistoryMessage[]): ChatMessage[] {
  return msgs.map((m) => ({
    id: newId(),
    historyId: m.id,
    role: m.role,
    content: m.content,
    streaming: false,
    promptTokens: m.promptTokens ?? null,
    completionTokens: m.completionTokens ?? null,
  }));
}

/**
 * Пишет обе клавиши localStorage: вкладки + активная (старый ключ ради совместимости).
 * Пустое состояние (нет сессий) — активной нет, старый ключ очищаем.
 */
function persistTabs(ids: string[], activeId: string | null): void {
  try {
    localStorage.setItem(TABS_KEY, JSON.stringify({ ids, activeId }));
    if (activeId) {
      localStorage.setItem(SESSION_KEY, activeId);
    } else {
      localStorage.removeItem(SESSION_KEY);
    }
  } catch {
    /* localStorage недоступен — изменения живут только в памяти */
  }
}

/**
 * Инициализация вкладок при монтировании: восстанавливаем сохранённый список
 * (валидность id проверит сверка с сессиями активного проекта — syncSessions).
 * Старый ключ 'llm-agent-session-id' больше не сеет вкладку: с day12 сессии
 * создаются только на сервере внутри проекта, клиентских UUID больше нет.
 */
function readTabs(): TabsStateData {
  try {
    const raw = localStorage.getItem(TABS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as TabsStateData;
      if (
        Array.isArray(parsed.ids) &&
        parsed.ids.every((x) => typeof x === 'string') &&
        (parsed.activeId === null ||
          (typeof parsed.activeId === 'string' && parsed.ids.includes(parsed.activeId)))
      ) {
        return parsed;
      }
    }
    const empty: TabsStateData = { ids: [], activeId: null };
    persistTabs(empty.ids, empty.activeId);
    return empty;
  } catch {
    return { ids: [], activeId: null };
  }
}

/** Читает сохранённый id активного проекта; мусор — null. */
function readPersistedProjectId(): number | null {
  try {
    const raw = localStorage.getItem(PROJECT_KEY);
    if (raw == null) return null;
    const parsed = Number.parseInt(raw, 10);
    return Number.isFinite(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

/** Пишет id активного проекта; null — проект не выбран (ключ стирается). */
function persistActiveProjectId(id: number | null): void {
  try {
    if (id != null) {
      localStorage.setItem(PROJECT_KEY, String(id));
    } else {
      localStorage.removeItem(PROJECT_KEY);
    }
  } catch {
    /* localStorage недоступен — изменения живут только в памяти */
  }
}

/** Текущее время в HH:MM:SS для колонки времени в логе шагов. */
function fmtTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour12: false });
}

export interface AgentSession {
  /** id активной вкладки (== activeId); null — сессии ещё нет (пустое состояние). */
  sessionId: string | null;
  messages: ChatMessage[];
  /** Лог шагов текущей сессии (все запуски подряд). */
  steps: StepLogEntry[];
  /**
   * Глобальные применённые настройки LLM (GET /api/llm-settings при монтировании —
   * независимо от наличия сессий, PUT при изменении, agent_started на каждом запуске);
   * null — ещё не загружены (первый GET в полёте или сервис недоступен).
   */
  runSettings: RunSettings | null;
  /** Накопительные токены и стоимость диалога (см. State). */
  tokenTotals: TokenTotals;
  /** Промпт-токены последнего реального запроса в LLM = текущий размер контекста сессии; null — запросов ещё не было. */
  lastPromptTokens: number | null;
  isRunning: boolean;
  /** true, пока бэк-сервис остановлен: чат и служебные кнопки заблокированы, показан баннер. */
  backendStopped: boolean;
  /** true, пока бэк-сервис запускается (опрос /api/llm-settings в фоне). */
  backendStarting: boolean;
  error: string | null;
  /** Проекты (GET /api/projects); null-списка нет — пустой массив означает «ещё не загружены или нет». */
  projects: Project[];
  /** id активного проекта; null — проектов нет или ещё не выбран (пустое состояние «Создать проект»). */
  activeProjectId: number | null;
  /** Вкладки сессий активного проекта (id); может быть пустым — сессия появится через «+». */
  tabs: string[];
  /** id активной вкладки (== sessionId); null — нет активной сессии. */
  activeId: string | null;
  /** Заголовки вкладок: sessionId -> первое сообщение пользователя (из каталога/истории). */
  titles: Record<string, string>;
  /** Глобальная статистика по всем сессиям (GET /api/stats); null — ещё не загружена. */
  globalStats: StatsResponse | null;
  /** Стратегия контекста активной сессии (свитчер в LlmSettings); 'none' — полная история. */
  strategy: ContextStrategy;
  /** Размер окна последних сообщений активной сессии (sliding_window/sticky_facts). */
  windowSize: number;
  /** Факты диалога активной сессии (GET /facts + события facts_updated); null — не загружены. */
  facts: Record<string, string> | null;
  /** Ветки диалога активной сессии (GET /branches); null — не загружены. */
  branches: BranchesState | null;
  /** Состояние задачи активной сессии (FSM task_state); null — задача не начата. */
  taskState: TaskState | null;
  /**
   * Сохранение сообщения в рабочую память ПРОЕКТА: POST /projects/{id}/memory/notes {note}.
   * Заметка общая для всех сессий проекта. Ошибка пробрасывается — индикацию у кнопки чата.
   */
  saveWorkingNote: (projectId: number, note: string) => Promise<void>;
  /**
   * Сохранение сообщения в долговременную память: POST /sessions/{id}/memory/long-term
   * {type:'knowledge', key, value}; LTM глобальна, sessionId задаёт источник записи.
   * key — первые 40 символов сообщения. Ошибка пробрасывается — индикацию у кнопки чата.
   */
  saveLongTerm: (sessionId: string, note: string) => Promise<void>;
  sendMessage: (text: string) => void;
  stopAgent: () => void;
  /**
   * Загрузка каталога проектов (GET /api/projects) с перепроверкой активного:
   * если активный проект исчез — переключение на первый доступный или в пустое состояние.
   */
  loadProjects: () => Promise<void>;
  /** Выбор активного проекта: перезагружает вкладки сессиями проекта (активной становится первая). */
  selectProject: (id: number) => void;
  /** Создание проекта (POST /api/projects {name}); после успеха проект выбирается активным. */
  createProject: (name: string) => Promise<void>;
  /** Переименование проекта (PATCH /api/projects/{id} {name}); после успеха перечитывает каталог. */
  renameProject: (id: number, name: string) => Promise<void>;
  /**
   * Удаление проекта (DELETE /api/projects/{id}; каскад сессий и WM на бэкенде).
   * Если удалили активный — переключение на следующий доступный или в пустое состояние.
   */
  deleteProject: (id: number) => Promise<void>;
  /**
   * Смена стратегии контекста сессии: PUT /context-strategy (оптимистично применяется,
   * при ошибке откатывается), затем перечитывает историю (стратегия меняет вид сообщений).
   */
  changeContextStrategy: (sessionId: string, patch: SessionContextStrategyPatch) => Promise<void>;
  /**
   * Обновление состояния задачи: PUT /task-state (частичное тело {paused} или
   * {stage, currentStep?, expectedAction?}). REST-мутации SSE не шлют — панель
   * обновляется из ответа. Ошибка (400/сеть) пробрасывается — индикация у кнопки.
   */
  changeTaskState: (sessionId: string, patch: TaskStatePatch) => Promise<TaskState>;
  /**
   * Обновление настроек воркфлоу Day-14 (PUT /api/workflow-settings {enabled, mode}).
   * При ошибке бросает — UI откатывает переключатель/режим к прежним значениям.
   */
  changeWorkflowSettings: (patch: WorkflowSettings) => Promise<WorkflowSettings>;
  /**
   * Продолжение воркфлоу Day-14 (кнопка «Продолжить»): запускает следующий этап
   * (SSE-поток /continue). Новое user-сообщение не создаётся — агент читает историю.
   */
  continueWorkflow: (sessionId: string) => void;
  /** Отмена воркфлоу Day-14 (кнопка «Отмена»): POST /cancel — пауза + сброс ожидания. */
  cancelWorkflow: (sessionId: string) => Promise<TaskState>;
  /** Настройки воркфлоу Day-14 (GET/PUT /api/workflow-settings, глобальные); null — не загружены. */
  workflowSettings: WorkflowSettings | null;
  /**
   * Ветка сессии от сообщения истории: POST /branches {messageId}, затем обновляет список
   * веток и перечитывает историю (новая ветка становится активной).
   */
  forkBranch: (sessionId: string, messageId: number) => Promise<void>;
  /**
   * Переключение активной ветки: PUT /branches {activeBranchId}, затем обновляет список веток
   * и заменяет сообщения сессии историей новой активной ветки.
   */
  switchBranch: (sessionId: string, branchId: number) => Promise<void>;
  /** Удаление активной сессии: DELETE на бэкенде + закрытие вкладки (синоним closeSession(activeId)). */
  deleteSession: () => void;
  switchSession: (id: string) => void;
  /** Новая сессия в активном проекте: сервер создаёт сессию (UUID), вкладка открывается сразу. */
  newSession: () => void;
  closeSession: (id: string) => void;
  /** Остановка бэк-сервиса по команде супервизора (POST /system-ctrl/stop). */
  stopService: () => void;
  /** Запуск бэк-сервиса с ожиданием готовности (опрос /api/llm-settings). */
  startService: () => void;
  /**
   * Сохранение настроек LLM (PUT /api/llm-settings): изменяемы все поля, кроме провайдера.
   * После успеха обновляет runSettings (contextLimit — новый лимит выбранной модели) и возвращает
   * применённые настройки; при ошибке бросает — UI оставляет прежние значения.
   */
  updateLlmSettings: (patch: Partial<RunSettings>) => Promise<RunSettings>;
}

export function useAgentSession(): AgentSession {
  /** Вкладки сессий + активная; источник истины для tabs/activeId/sessionId. */
  const [tabsState, setTabsState] = useState<TabsStateData>(readTabs);
  const tabs = tabsState.ids;
  const activeId = tabsState.activeId;
  const sessionId = activeId;
  /** Заголовки вкладок (первое сообщение пользователя); пусто — fallback на id. */
  const [titles, setTitles] = useState<Record<string, string>>({});
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [steps, setSteps] = useState<StepLogEntry[]>([]);
  const [runSettings, setRunSettings] = useState<RunSettings | null>(null);
  /** Накопительные итоги по токенам диалога (промпт/ответ/условная стоимость). */
  const [tokenTotals, setTokenTotals] = useState<TokenTotals>({
    promptTokens: 0,
    completionTokens: 0,
    costUsd: 0,
  });
  const [isRunning, setIsRunning] = useState(false);
  const [backendStopped, setBackendStopped] = useState(false);
  const [backendStarting, setBackendStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Промпт-токены последнего LLM-запроса сессии (текущий размер контекста для «занято/осталось»). */
  const [lastPromptTokens, setLastPromptTokens] = useState<number | null>(null);
  /** Глобальная статистика по всем сессиям (GET /api/stats); null — ещё не загружена. */
  const [globalStats, setGlobalStats] = useState<StatsResponse | null>(null);
  /** Стратегия контекста активной сессии (зеркало буфера сессии). */
  const [strategy, setStrategy] = useState<ContextStrategy>('none');
  /** Размер окна последних сообщений активной сессии (зеркало буфера сессии). */
  const [windowSize, setWindowSize] = useState(DEFAULT_CONTEXT_WINDOW);
  /** Факты диалога активной сессии (зеркало буфера сессии); null — не загружены. */
  const [facts, setFacts] = useState<Record<string, string> | null>(null);
  /** Ветки диалога активной сессии (зеркало буфера сессии); null — не загружены. */
  const [branches, setBranches] = useState<BranchesState | null>(null);
  /** Состояние задачи активной сессии (зеркало буфера сессии); null — задача не начата. */
  const [taskState, setTaskState] = useState<TaskState | null>(null);
  /** Настройки воркфлоу Day-14 (GET/PUT /api/workflow-settings, глобальные); null — не загружены. */
  const [workflowSettings, setWorkflowSettings] = useState<WorkflowSettings | null>(null);
  /** Каталог проектов (GET /api/projects); пустой массив — ещё не загружен или проектов нет. */
  const [projects, setProjects] = useState<Project[]>([]);
  /** Активный проект (id сохраняется в localStorage — переживает перезагрузку). */
  const [activeProjectId, setActiveProjectId] = useState<number | null>(readPersistedProjectId);

  /** sessionId -> буфер состояния выполнения (сообщения/итоги/стрим каждой сессии). */
  const runStateRef = useRef<Map<string, SessionRunState>>(new Map());
  /** id сессий с незавершённым стримом (guard сверки вкладок и «живой» буфер истории). */
  const runningSidsRef = useRef<Set<string>>(new Set());
  /** Число выполняющихся стримов: при падении в 0 триггерим сверку вкладок с бэкендом. */
  const [runningCount, setRunningCount] = useState(0);
  /** Актуальный activeId для колбэков фоновых стримов (замыкания не должны читать устаревший). */
  const activeIdRef = useRef<string | null>(activeId);
  /** Актуальный activeProjectId для колбэков (фоновые стримы, загрузки каталога). */
  const activeProjectIdRef = useRef<number | null>(activeProjectId);
  /** id -> запись лога; порядок вставки = хронология. */
  const stepsRef = useRef<Map<string, StepLogEntry>>(new Map());
  /**
   * Лог шагов каждой сессии в памяти (SSE-шаги не лежат в истории бэкенда):
   * sessionId -> массив записей. При переключении вкладки здесь
   * сохраняются steps, при возврате — восстанавливаются.
   */
  const stepsCacheRef = useRef<Map<string, StepLogEntry[]>>(new Map());
  /**
   * Актуальные настройки LLM — доступны из handleEvent (стабильная функция без зависимостей)
   * для расчёта стоимости, если бэкенд не прислал costUsd.
   */
  const runSettingsRef = useRef<RunSettings | null>(null);
  /** Флаг отмены ожидания запуска при размонтировании компонента. */
  const startAbortedRef = useRef(false);
  /** false до первой синхронизации вкладок с бэкендом (на mount). */
  const syncedOnceRef = useRef(false);

  /** Буфер состояния выполнения сессии (создаёт запись при первом обращении). */
  const getRunState = (sid: string): SessionRunState => {
    let st = runStateRef.current.get(sid);
    if (!st) {
      st = {
        messages: [],
        tokenTotals: { promptTokens: 0, completionTokens: 0, costUsd: 0 },
        lastPromptTokens: null,
        isRunning: false,
        assistantId: '',
        controller: null,
        strategy: 'none',
        windowSize: DEFAULT_CONTEXT_WINDOW,
        facts: null,
        branches: null,
        taskState: null,
      };
      runStateRef.current.set(sid, st);
    }
    return st;
  };

  /**
   * Обновляет сообщения сессии sid. Если сессия активна — результат сразу рисуется
   * в живой вид; если запуск фоновый — остаётся в буфере до переключения вкладки.
   */
  const updateSessionMessages = (
    sid: string,
    updater: (prev: ChatMessage[], st: SessionRunState) => ChatMessage[],
  ) => {
    const st = runStateRef.current.get(sid);
    if (!st) return;
    st.messages = updater(st.messages, st);
    if (sid === activeIdRef.current) setMessages(st.messages);
  };

  /**
   * Обновляет факты диалога сессии sid (событие facts_updated / GET /facts).
   * Та же дисциплина роутинга, что и у updateSessionMessages: активная сессия — сразу
   * в живой вид, фоновая — в буфер до переключения вкладки.
   */
  const updateSessionFacts = (sid: string, next: Record<string, string>) => {
    const st = runStateRef.current.get(sid);
    if (!st) return;
    st.facts = next;
    if (sid === activeIdRef.current) setFacts(next);
  };

  /**
   * Обновляет состояние задачи сессии sid (событие task_state_changed / GET/PUT /task-state).
   * Та же дисциплина роутинга, что и у updateSessionFacts: активная сессия — сразу
   * в живой вид панели, фоновая — в буфер до переключения вкладки.
   */
  const updateSessionTaskState = (sid: string, next: TaskState | null) => {
    const st = runStateRef.current.get(sid);
    if (!st) return;
    st.taskState = next;
    if (sid === activeIdRef.current) setTaskState(next);
  };

  /**
   * Накладывает ответ истории бэкенда на буфер сессии sid: сообщения, накопительные итоги
   * токенов, текущий размер контекста (prompt_tokens последнего ответа ассистента). Активная
   * сессия — сразу в живой вид; фоновая — в буфер. Общая функция для открытия сессии,
   * восстановления после рестарта сервиса и перечитывания после смены стратегии/ветки.
   */
  const applyHistoryToRunState = useCallback((sid: string, h: HistoryResponse) => {
    const st = getRunState(sid);
    st.messages = historyToMessages(h.messages);
    st.tokenTotals = h.totals
      ? {
          promptTokens: h.totals.promptTokens,
          completionTokens: h.totals.completionTokens,
          costUsd: h.totals.costUsd,
        }
      : { promptTokens: 0, completionTokens: 0, costUsd: 0 };
    const lastAssistant = [...h.messages]
      .reverse()
      .find((m) => m.role === 'assistant' && m.promptTokens != null);
    st.lastPromptTokens = lastAssistant?.promptTokens ?? null;
    if (sid === activeIdRef.current) {
      setMessages(st.messages);
      setTokenTotals(st.tokenTotals);
      setLastPromptTokens(st.lastPromptTokens);
    }
  }, []);

  /**
   * Перечитывает историю сессии sid с бэкенда и заменяет её сообщения (после смены ветки
   * или стратегии). Замена идёт через тот же буферный роутинг, что и открытие сессии:
   * активная вкладка — сразу в живой вид, фоновая — в буфер. Сессия с активным стримом
   * уже наполнила буфер свежими сообщениями — серверная история на этот момент устарела,
   * живому буферу отдаём приоритет (тот же guard, что и в эффекте открытия сессии).
   * Ошибки чтения молча игнорируются — остаётся прежнее состояние.
   */
  const refreshSessionHistory = useCallback((sid: string): Promise<void> => {
    return fetchHistory(sid)
      .then((h) => {
        const live = runStateRef.current.get(sid);
        if (runningSidsRef.current.has(sid) && live && live.messages.length > 0) return;
        applyHistoryToRunState(sid, h);
      })
      .catch(() => {});
  }, [applyHistoryToRunState]);

  /** Перечитывает факты сессии sid (GET /facts); ошибки игнорируются. */
  const refreshFacts = useCallback((sid: string): Promise<void> => {
    return fetchFacts(sid)
      .then((f) => updateSessionFacts(sid, f.facts))
      .catch(() => {});
  }, []);

  /** Перечитывает ветки сессии sid (GET /branches); ошибки игнорируются. */
  const refreshBranches = useCallback((sid: string): Promise<void> => {
    return fetchBranches(sid)
      .then((b) => {
        const st = getRunState(sid);
        st.branches = b;
        if (sid === activeIdRef.current) setBranches(b);
      })
      .catch(() => {});
  }, []);

  /**
   * Загружает стратегию контекста, факты и ветки сессии (вместе с первичной загрузкой
   * истории). Память здесь НЕ грузится: она проектная — живёт в эффекте activeProjectId.
   * isStale — предикат отмены (unmount/смена сессии): если вернул true, ответ
   * отбрасывается (защита от гонок при быстром переключении вкладок).
   */
  const loadPerSessionExtras = (sid: string, isStale: () => boolean = () => false) => {
    fetchContextStrategy(sid)
      .then((s) => {
        if (isStale()) return;
        const st = getRunState(sid);
        st.strategy = s.strategy;
        st.windowSize = s.windowSize;
        if (sid === activeIdRef.current) {
          setStrategy(s.strategy);
          setWindowSize(s.windowSize);
        }
      })
      .catch(() => {
        /* эндпоинт недоступен — остаются дефолты ('none' / DEFAULT_CONTEXT_WINDOW) */
      });
    fetchFacts(sid)
      .then((f) => {
        if (isStale()) return;
        updateSessionFacts(sid, f.facts);
      })
      .catch(() => {
        /* фактов ещё нет — остаётся null */
      });
    fetchBranches(sid)
      .then((b) => {
        if (isStale()) return;
        const st = getRunState(sid);
        st.branches = b;
        if (sid === activeIdRef.current) setBranches(b);
      })
      .catch(() => {
        /* веток ещё нет — остаётся null */
      });
    fetchTaskState(sid)
      .then((ts) => {
        if (isStale()) return;
        updateSessionTaskState(sid, ts);
      })
      .catch(() => {
        /* состояния задачи ещё нет (404) — остаётся null */
      });
  };

  /**
   * Смена стратегии контекста сессии: PUT /context-strategy. Применяется оптимистично
   * (переключатель реагирует сразу), PUT подтверждает ответом сервера; при ошибке — откат
   * к прежним значениям и повторный throw (UI показывает ошибку сохранения). После успеха
   * перечитываем факты/ветки/историю — стратегия меняет вид сообщений (сжатие, цепочки веток).
   */
  const changeContextStrategy = useCallback(
    async (sid: string, patch: SessionContextStrategyPatch): Promise<void> => {
      const st = getRunState(sid);
      const prevStrategy = st.strategy;
      const prevWindowSize = st.windowSize;
      if (patch.strategy !== undefined && patch.strategy !== st.strategy) {
        st.strategy = patch.strategy;
        if (sid === activeIdRef.current) setStrategy(patch.strategy);
      }
      if (patch.windowSize != null) {
        st.windowSize = patch.windowSize;
        if (sid === activeIdRef.current) setWindowSize(patch.windowSize);
      }
      try {
        const next = await updateContextStrategy(sid, patch);
        st.strategy = next.strategy;
        st.windowSize = next.windowSize;
        if (sid === activeIdRef.current) {
          setStrategy(next.strategy);
          setWindowSize(next.windowSize);
        }
        void refreshBranches(sid);
        void refreshFacts(sid);
        void refreshSessionHistory(sid);
      } catch (err) {
        st.strategy = prevStrategy;
        st.windowSize = prevWindowSize;
        if (sid === activeIdRef.current) {
          setStrategy(prevStrategy);
          setWindowSize(prevWindowSize);
        }
        throw err;
      }
    },
    [refreshBranches, refreshFacts, refreshSessionHistory],
  );

  /** Ветка от сообщения истории: POST /branches, затем свежие ветки и история (новая — активна). */
  const forkBranch = useCallback(
    async (sid: string, messageId: number): Promise<void> => {
      try {
        await createBranchApi(sid, messageId);
        await refreshBranches(sid);
        await refreshSessionHistory(sid);
      } catch {
        /* ошибка ветвления молча игнорируется — состояние не меняем */
      }
    },
    [refreshBranches, refreshSessionHistory],
  );

  /** Переключение активной ветки: PUT /branches, затем свежие ветки и история активной ветки. */
  const switchBranch = useCallback(
    async (sid: string, branchId: number): Promise<void> => {
      try {
        await setActiveBranchApi(sid, branchId);
        await refreshBranches(sid);
        await refreshSessionHistory(sid);
      } catch {
        /* ошибка переключения молча игнорируется — состояние не меняем */
      }
    },
    [refreshBranches, refreshSessionHistory],
  );

  /**
   * Обновление состояния задачи (FSM task_state): PUT /task-state (частичное тело:
   * {paused} — пауза/снятие, {stage, currentStep?, expectedAction?} — смена этапа).
   * REST-мутации SSE не шлют — панель обновляем из ответа PUT. Ошибка (400 на
   * недопустимый переход, сеть) пробрасывается — индикация у кнопки панели.
   */
  const changeTaskState = useCallback(
    async (sid: string, patch: TaskStatePatch): Promise<TaskState> => {
      const next = await updateTaskStateApi(sid, patch);
      updateSessionTaskState(sid, next);
      return next;
    },
    [],
  );

  /** Отмена воркфлоу Day-14 (кнопка «Отмена»): POST /cancel — пауза + сброс ожидания. */
  const cancelWorkflow = useCallback(
    async (sid: string): Promise<TaskState> => {
      const next = await cancelTaskState(sid);
      updateSessionTaskState(sid, next);
      return next;
    },
    [],
  );

  /**
   * Обновление настроек воркфлоу Day-14 (PUT /api/workflow-settings {enabled, mode}).
   * При ошибке бросает — UI откатывает переключатель/режим к прежним значениям.
   */
  const changeWorkflowSettings = useCallback(
    async (patch: WorkflowSettings): Promise<WorkflowSettings> => {
      const next = await updateWorkflowSettings(patch);
      setWorkflowSettings(next);
      return next;
    },
    [],
  );

  /**
   * Сохранение сообщения в рабочую память ПРОЕКТА pid: POST /projects/{id}/memory/notes {note}.
   * Ошибку пробрасываем — результат показывает индикация у кнопки в чате.
   */
  const saveWorkingNote = useCallback(async (pid: number, note: string): Promise<void> => {
    await saveWorkingNoteApi(pid, note);
  }, []);

  /**
   * Сохранение сообщения в долговременную память: POST /sessions/{id}/memory/long-term
   * {type:'knowledge', key, value}. LTM глобальна, sessionId задаёт источник записи;
   * key — первые 40 символов сообщения (короткий заголовок записи).
   */
  const saveLongTerm = useCallback(async (sid: string, note: string): Promise<void> => {
    const trimmed = note.trim();
    await addLongTermMemoryApi(sid, {
      type: 'knowledge',
      key: trimmed.slice(0, 40),
      value: trimmed,
    });
  }, []);

  /** Меняет лог шагов сессии: активной — сразу в живой вид, фоновой — в её кэш шагов. */
  const mutateSessionSteps = (sid: string, mutate: (map: Map<string, StepLogEntry>) => void) => {
    if (sid === activeIdRef.current) {
      mutate(stepsRef.current);
      setSteps(Array.from(stepsRef.current.values()));
    } else {
      const prev = stepsCacheRef.current.get(sid);
      const map = new Map(prev ? prev.map((e) => [e.id, e]) : []);
      mutate(map);
      stepsCacheRef.current.set(sid, Array.from(map.values()));
    }
  };

  /** Снимает флаг выполнения со стрима сессии и обновляет счётчик активных стримов. */
  const finalizeRun = useCallback((sid: string) => {
    const st = runStateRef.current.get(sid);
    if (st) {
      st.isRunning = false;
      st.controller = null;
      if (sid === activeIdRef.current) setIsRunning(false);
    }
    if (runningSidsRef.current.delete(sid)) setRunningCount((c) => c - 1);
    // Живые сообщения не несут historyId (его присваивает бэкенд при сохранении истории).
    // Перечитываем историю сразу после завершения прогона — иначе кнопки ветвления
    // (им нужен historyId) не появятся до перезагрузки страницы или смены стратегии.
    void refreshSessionHistory(sid);
  }, [refreshSessionHistory]);

  /** Добавляет служебное событие жизненного цикла UI в лог шагов (kind = system). */
  const pushSystemEvent = useCallback((title: string) => {
    const id = `system-${newId()}`;
    stepsRef.current.set(id, {
      id,
      time: fmtTime(new Date().toISOString()),
      kind: 'system',
      title,
      status: 'success',
    });
    setSteps(Array.from(stepsRef.current.values()));
  }, []);

  /** Создаёт или обновляет запись лога шагов сессии sid и синкает состояние в React. */
  const touchStep = (sid: string, stepId: string, ts: string, patch: Partial<StepLogEntry>) => {
    const id = stepId;
    mutateSessionSteps(sid, (map) => {
      const existing = map.get(id);
      if (!existing) {
        map.set(id, {
          id,
          time: fmtTime(ts),
          kind: patch.kind ?? 'llm',
          title: patch.title ?? id,
          status: patch.status ?? 'running',
          iteration: patch.iteration,
          toolName: patch.toolName,
          prompt: patch.prompt,
          args: patch.args,
          result: patch.result,
          detail: patch.detail,
          explanation: patch.explanation,
          // Сырые тела запроса/ответа LLM API: шаг создаётся первым событием (llm_request_started),
          // поэтому requestBody должен попадать в объект УЖЕ при создании, а не только при merge.
          requestBody: patch.requestBody,
          responseBody: patch.responseBody,
        });
      } else {
        map.set(id, { ...existing, ...patch });
      }
    });
  };

  /**
   * Складывает usage одного LLM-вызова в накопительные итоги диалога.
   * Стоимость: приоритет у costUsd от бэкенда, иначе — расчёт по тарифам
   * runSettings (priceXXXPer1M, $ за 1M токенов).
   */
  const accumulateTotals = useCallback(
    (
      prev: TokenTotals,
      usage: { inputTokens: number; outputTokens: number } | undefined,
      costUsd: number | null | undefined,
    ): TokenTotals => {
      const inputTokens = usage?.inputTokens ?? 0;
      const outputTokens = usage?.outputTokens ?? 0;
      let cost = prev.costUsd;
      if (costUsd != null) {
        cost += costUsd;
      } else {
        const s = runSettingsRef.current;
        if (s) {
          if (s.priceInputPer1M != null) cost += (inputTokens / 1_000_000) * s.priceInputPer1M;
          if (s.priceOutputPer1M != null) cost += (outputTokens / 1_000_000) * s.priceOutputPer1M;
        }
      }
      return {
        promptTokens: prev.promptTokens + inputTokens,
        completionTokens: prev.completionTokens + outputTokens,
        costUsd: cost,
      };
    },
    [],
  );

  /**
   * Обновление глобальной статистики (GET /api/stats) — fire-and-forget:
   * ошибки сети и бэкенда молча игнорируются (статистика остаётся прошлой).
   */
  const refreshGlobalStats = useCallback(() => {
    fetchGlobalStats().then(setGlobalStats).catch(() => {});
  }, []);

  /**
   * Сбрасывает состояние активного диалога (вызов при уходе в пустое состояние).
   * Настройки LLM глобальны и от сессии не зависят — не трогаем их, чтобы блок
   * настроек оставался заполненным и активным даже без единой сессии.
   */
  const resetSessionView = () => {
    stepsRef.current.clear();
    setSteps([]);
    setMessages([]);
    setTokenTotals({ promptTokens: 0, completionTokens: 0, costUsd: 0 });
    setLastPromptTokens(null);
    setIsRunning(false);
    setError(null);
    // Пер-сессионные стратегия/факты/ветки/состояние задачи: новая сессия и пустое
    // состояние стартуют начисто.
    setStrategy('none');
    setWindowSize(DEFAULT_CONTEXT_WINDOW);
    setFacts(null);
    setBranches(null);
    setTaskState(null);
  };

  /**
   * Сверка вкладок с сессиями АКТИВНОГО ПРОЕКТА (GET /api/projects/{id}/sessions) —
   * fire-and-forget. При монтировании вычищаем ВСЕ вкладки, отсутствующие в проекте;
   * если вкладок не осталось — НЕ создаём новую (пустое состояние проекта легально).
   * При последующих обновлениях убираем вкладку T, только если её нет в проекте И она
   * не активна И в её кэше шагов нет записей. Во время генерации чистка пропускается.
   * Заодно обновляет заголовки вкладок из первого сообщения сессий.
   */
  const syncSessions = useCallback(() => {
    // Пока выполняется хоть один стрим (любой вкладки) — чистка вкладок пропускается:
    // незавершённую сессию каталог мог ещё не подтвердить.
    if (runningCount > 0) return;
    const pid = activeProjectIdRef.current;
    if (pid == null) {
      // Нет активного проекта — вкладок быть не должно.
      if (tabs.length > 0 || activeId != null) {
        setTabsState({ ids: [], activeId: null });
        persistTabs([], null);
      }
      syncedOnceRef.current = true;
      return;
    }
    fetchProjectSessions(pid)
      .then((sessions) => {
        const known = new Set(sessions.map((s) => s.sessionId));
        // Заголовки вкладок из каталога: первое сообщение пользователя.
        const titleUpdates: Record<string, string> = {};
        for (const s of sessions) {
          const t = s.firstUserMessage;
          if (t && t.trim() !== '') titleUpdates[s.sessionId] = t.trim();
        }
        setTitles((prev) => {
          let changed = false;
          const next = { ...prev };
          for (const [id, title] of Object.entries(titleUpdates)) {
            if (next[id] !== title) {
              next[id] = title;
              changed = true;
            }
          }
          return changed ? next : prev;
        });
        let ids: string[];
        if (!syncedOnceRef.current) {
          // Первичная синхронизация при монтировании — жёсткая чистка.
          ids = tabs.filter((id) => known.has(id));
        } else {
          // Последующие — только неактивные вкладки без сохранённых шагов.
          ids = tabs.filter((id) => {
            if (known.has(id)) return true;
            if (id === activeId) return true;
            const cached = stepsCacheRef.current.get(id);
            return cached != null && cached.length > 0;
          });
        }
        let nextActive = activeId;
        if (ids.length === 0) {
          // Нет вкладок — пустое состояние проекта (сессия появится через «+»).
          nextActive = null;
        } else if (nextActive == null || !ids.includes(nextActive)) {
          nextActive = ids[0];
        }
        syncedOnceRef.current = true;
        if (ids.length === tabs.length && nextActive === activeId) return;
        if (nextActive !== null && nextActive !== activeId) {
          // Показываем буфер активируемой сессии (сообщения/итоги/флаг выполнения);
          // историю при необходимости догрузит эффект sessionId.
          const t = getRunState(nextActive);
          setMessages(t.messages);
          setTokenTotals(t.tokenTotals);
          setLastPromptTokens(t.lastPromptTokens);
          setIsRunning(t.isRunning);
          activeIdRef.current = nextActive;
          const cached = stepsCacheRef.current.get(nextActive);
          if (cached) {
            stepsRef.current = new Map(cached.map((e) => [e.id, e]));
            setSteps(cached);
          } else {
            stepsRef.current.clear();
            setSteps([]);
          }
        }
        if (nextActive === null) {
          resetSessionView();
        }
        setTabsState({ ids, activeId: nextActive });
        persistTabs(ids, nextActive);
      })
      .catch(() => {
        /* каталог недоступен — вкладки оставляем как есть */
        syncedOnceRef.current = true;
      });
  }, [tabs, activeId, runningCount]);

  /** Применяем одно событие CONTRACT-а к логу шагов и итогам токенов сессии sid. */
  const handleEvent = useCallback((e: AgentEvent, sid: string) => {
    const key = `${e.runId}:${e.stepId}`;
    switch (e.type) {
      case 'log': {
        // «Обычная» строка лога действия агента — служебная запись без раскрытия.
        pushSystemEvent(e.payload.text);
        break;
      }
      case 'agent_started': {
        // Сообщение пользователя видно в чате — в лог его не дублируем.
        // Лог сфокусирован на потоке запросов в LLM (детализация — по кнопке у строки).
        setRunSettings(e.payload.settings);
        runSettingsRef.current = e.payload.settings;
        return;
      }
      case 'llm_request_started': {
        const it = e.payload.iteration;
        touchStep(sid, key, e.timestamp, {
          kind: 'llm',
          title: `LLM (итерация ${it})`,
          iteration: it,
          prompt: e.payload.prompt,
          requestBody: e.payload.requestBody,
          // Оценка токенов запроса приходит до самого запроса — сохраняем структурно сразу.
          tokenUsage: {
            estimatedRequestTokens: e.payload.estimatedRequestTokens ?? null,
          },
          status: 'running',
        });
        return;
      }
      case 'llm_response_finished': {
        const ok = e.payload.finishReason === 'stop' || e.payload.finishReason === 'tool_calls';
        const u = e.payload.usage;
        touchStep(sid, key, e.timestamp, {
          status: ok ? 'success' : 'error',
          // Токены/стоимость в текст лога не дублируем: только служебный finish_reason,
          // численные значения остаются в tokenUsage для сводки по сессии.
          detail: `finish_reason: ${e.payload.finishReason}`,
          responseBody: e.payload.responseBody,
          // Данные о токенах храним структурно (для итогов токенов сессии).
          tokenUsage: {
            inputTokens: u?.inputTokens ?? null,
            outputTokens: u?.outputTokens ?? null,
            estimatedRequestTokens: e.payload.estimatedRequestTokens ?? null,
            costUsd: e.payload.costUsd ?? null,
          },
        });
        // Накопительные итоги диалога сессии: суммируем usage и стоимость каждого ответа LLM.
        const st = runStateRef.current.get(sid);
        if (st) {
          st.tokenTotals = accumulateTotals(st.tokenTotals, u, e.payload.costUsd);
          // Текущий размер контекста = входные токены последнего реального запроса в LLM
          // (те же значения бэкенд пишет в prompt_tokens последнего assistant-сообщения истории).
          const inputTokens = u?.inputTokens;
          if (inputTokens != null) st.lastPromptTokens = inputTokens;
          if (sid === activeIdRef.current) {
            setTokenTotals(st.tokenTotals);
            setLastPromptTokens(st.lastPromptTokens);
          }
        }
        refreshGlobalStats();
        syncSessions();
        return;
      }
      case 'tool_call_started': {
        const tool = /^tool-(.+)-\d+$/.exec(e.stepId);
        touchStep(sid, key, e.timestamp, {
          kind: 'tool',
          title: `Инструмент: ${tool ? tool[1] : e.stepId}`,
          toolName: tool ? tool[1] : undefined,
          args: e.payload.args,
          status: 'running',
        });
        return;
      }
      case 'tool_call_finished': {
        // Имя инструмента восстанавливаем из stepId (tool-<name>-<idx>), чтобы
        // строка лога была самодостаточной: «Инструмент: calculator — готово».
        const tool = /^tool-(.+)-\d+$/.exec(e.stepId);
        const toolLabel = tool ? tool[1] : e.stepId;
        touchStep(sid, key, e.timestamp, {
          title: `Инструмент: ${toolLabel} — ${e.payload.status === 'success' ? 'готово' : 'ошибка'}`,
          result: e.payload.result,
          status: e.payload.status === 'success' ? 'success' : 'error',
        });
        return;
      }
      case 'agent_finished': {
        // Финальный ответ виден в чате — в лог пишем только маркер завершения,
        // без текста ответа (см. также kind 'answer' в StepLogEntry).
        touchStep(sid, key, e.timestamp, {
          kind: 'answer',
          title: 'Готово',
          status: 'success',
        });
        refreshGlobalStats();
        syncSessions();
        return;
      }
      case 'context_summary_started': {
        // Сжатие контекста: шаг показывает и сам запрос к LLM — какие сообщения уходят
        // в резюмирование (раскройте «Контекст запроса в LLM»).
        touchStep(sid, key, e.timestamp, {
          kind: 'llm',
          title: `Сжатие контекста: сворачиваю ${e.payload.foldCount} сообщений…`,
          status: 'running',
          prompt: e.payload.prompt,
        });
        return;
      }
      case 'context_summary_finished': {
        touchStep(sid, key, e.timestamp, {
          kind: 'llm',
          // Токены в текст лога не выносим — только факт свёртки истории.
          title: `Сжатие контекста выполнено: ${e.payload.foldCount} сообщений → резюме`,
          status: 'success',
          // Ответ LLM на запрос резюмирования — это и есть новое summary сессии.
          result: e.payload.summary,
        });
        return;
      }
      case 'error': {
        touchStep(sid, `${e.runId}:error`, e.timestamp, {
          kind: 'error',
          title: `Ошибка: ${e.payload.message}`,
          status: 'error',
          result: e.payload.message,
        });
        return;
      }
      case 'facts_updated': {
        // Живое обновление фактов диалога (sticky_facts): пишем в буфер сессии,
        // активная — сразу в живой вид панели фактов.
        updateSessionFacts(sid, e.payload.facts);
        return;
      }
      case 'task_state_changed': {
        // Живое обновление состояния задачи (инструмент task_state): пишем в буфер сессии,
        // активная — сразу в живой вид панели состояния. Строку «Состояние задачи обновлено»
        // в лог шагов кладёт сам бэкенд (событие log) — здесь только панель.
        updateSessionTaskState(sid, {
          sessionId: sid,
          stage: e.payload.stage,
          currentStep: e.payload.currentStep ?? null,
          expectedAction: e.payload.expectedAction ?? null,
          paused: e.payload.paused,
          plan: e.payload.plan ?? null,
          implementation: e.payload.implementation ?? null,
          validation: e.payload.validation ?? null,
          awaitConfirmation: e.payload.awaitConfirmation ?? false,
          updatedAt: e.timestamp,
        });
        return;
      }
      case 'workflow_paused': {
        // Воркфлоу Day-14 (ручной режим): агент завершил этап и ждёт подтверждения —
        // кладём результат этапа в состояние задачи (нужная колонка) и поднимаем флаг
        // ожидания, чтобы панель показала кнопки «Продолжить»/«Отмена».
        const prev = runStateRef.current.get(sid)?.taskState;
        updateSessionTaskState(sid, {
          sessionId: sid,
          stage: e.payload.stage,
          currentStep: prev?.currentStep ?? null,
          expectedAction: prev?.expectedAction ?? null,
          paused: prev?.paused ?? false,
          plan: e.payload.stage === 'planning' ? e.payload.output : (prev?.plan ?? null),
          implementation: e.payload.stage === 'execution' ? e.payload.output : (prev?.implementation ?? null),
          validation: e.payload.stage === 'validation' ? e.payload.output : (prev?.validation ?? null),
          awaitConfirmation: e.payload.await,
          updatedAt: e.timestamp,
        });
        return;
      }
      case 'workflow_stage_finished': {
        // Воркфлоу Day-14 (авто-режим): повествование только что завершённого этапа закоммичено
        // (и в историю, и как результат этапа). Пишем его в нужную колонку состояния задачи
        // (текст приходит после task_state_changed, поэтому колонка заполняется актуально).
        // Текущий этап уже сменён на следующий (в task_state_changed) — этап остаётся прежним.
        const prev = runStateRef.current.get(sid)?.taskState;
        updateSessionTaskState(sid, {
          sessionId: sid,
          stage: prev?.stage ?? e.payload.stage,
          currentStep: prev?.currentStep ?? null,
          expectedAction: prev?.expectedAction ?? null,
          paused: prev?.paused ?? false,
          plan: e.payload.stage === 'planning' ? e.payload.output : (prev?.plan ?? null),
          implementation: e.payload.stage === 'execution' ? e.payload.output : (prev?.implementation ?? null),
          validation: e.payload.stage === 'validation' ? e.payload.output : (prev?.validation ?? null),
          awaitConfirmation: prev?.awaitConfirmation ?? false,
          updatedAt: e.timestamp,
        });
        return;
      }
      default:
        return;
    }
  }, [refreshGlobalStats, syncSessions]);

  const finalizeAssistant = (sid: string, finalText?: string, removeIfEmpty = false) => {
    const st = runStateRef.current.get(sid);
    if (!st) return;
    const idx = st.messages.findIndex((m) => m.id === st.assistantId);
    if (idx === -1) return;
    const target = st.messages[idx];
    // finalText переписывает пузырь (убирает промежуточные реплики tool-цикла), но НЕ пишет
    // в пустой пузырь текст ПРОШЛОГО этапа (внешняя пауза на только что начатом этапе):
    // финализируем без перезаписи, чтобы этап остался без чужого повествования.
    const content =
      finalText !== undefined && target.content.trim() !== '' ? finalText : target.content;
    if (removeIfEmpty && content.trim() === '') {
      st.messages = st.messages.filter((_, i) => i !== idx);
    } else {
      const next = [...st.messages];
      next[idx] = { ...target, content, streaming: false };
      st.messages = next;
    }
    if (sid === activeIdRef.current) setMessages(st.messages);
  };

  /**
   * Общий runner SSE-стрима сессии: заводит AbortController, разбирает события,
   * обновляет живое сообщение ассистента (токены/итоги/заметки сжатия), завершает
   * run. [startStream] абстрагирует эндпоинт — обычный /api/chat или /continue
   * (воркфлоу Day-14). Каждая сессия владеет собственным стримом: параллельные
   * запуски разных вкладок живут в своих AbortController'ах и буферах.
   */
  const runStream = useCallback(
    async (
      sid: string,
      startStream: (signal: AbortSignal, onEvent: (e: AgentEvent) => void) => Promise<void>,
      isRetry: boolean,
    ): Promise<void> => {
      const st = getRunState(sid);
      const controller = new AbortController();
      st.controller = controller;
      runningSidsRef.current.add(sid);
      setRunningCount((c) => c + 1);
      let receivedAny = false;
      try {
        await startStream(controller.signal, (e) => {
          receivedAny = true;
          handleEvent(e, sid);
          if (e.type === 'llm_token') {
            updateSessionMessages(sid, (prev, state) => {
              const idx = prev.findIndex((m) => m.id === state.assistantId);
              if (idx === -1) return prev;
              const next = [...prev];
              next[idx] = { ...next[idx], content: next[idx].content + e.payload.delta, streaming: true };
              return next;
            });
          } else if (e.type === 'llm_response_finished') {
            // Токены завершившегося LLM-вызова — прямо в живое сообщение ассистента.
            // Финальное событие запуска (finishReason=stop) перезаписывает промежуточные
            // (tool_calls) — бэкенд сохраняет в сообщение usage последнего вызова, значения
            // совпадают с тем, что потом вернёт история.
            const u = e.payload.usage;
            updateSessionMessages(sid, (prev, state) => {
              const idx = prev.findIndex((m) => m.id === state.assistantId);
              if (idx === -1) return prev;
              const next = [...prev];
              next[idx] = {
                ...next[idx],
                promptTokens: u?.inputTokens ?? null,
                completionTokens: u?.outputTokens ?? null,
              };
              return next;
            });
          } else if (e.type === 'context_summary_finished') {
            // Информация о сжатии — заметкой в самом чате, перед стримящимся ответом
            // ассистента. Тот же текст, что бэкенд сохраняет в историю (role "system"),
            // поэтому после перезагрузки заметка выглядит так же.
            const p = e.payload;
            const notice =
              `Сжатие контекста: ${p.foldCount} старых сообщений свернуто в резюме. ` +
              `Контекст: ${p.contextTokensBefore} → ${p.contextTokensAfter} токенов.`;
            updateSessionMessages(sid, (prev, state) => {
              const idx = prev.findIndex((m) => m.id === state.assistantId);
              const noticeMsg: ChatMessage = { id: newId(), role: 'system', content: notice };
              if (idx === -1) return [...prev, noticeMsg];
              const next = [...prev];
              next.splice(idx, 0, noticeMsg);
              return next;
            });
          } else if (e.type === 'agent_finished') {
            finalizeRun(sid);
            finalizeAssistant(sid, e.payload.finalText);
          } else if (e.type === 'workflow_stage_finished') {
            // Воркфлоу (auto): БЭКЕНД только что закоммитил повествование завершённого этапа
            // (в историю и как результат этапа) — это НАДЁЖНАЯ граница этапа. Финализируем
            // пузырь только что завершённого этапа (streaming=false, контент сохранён) и
            // открываем новый пустой пузырь для следующего этапа — каждая стадия в live-виде
            // отдельным сообщением ассистента, а не одним затираемым пузырём. В отличие от
            // task_state_changed (срабатывает и на том же этапе), это событие приходит ТОЛЬКО
            // когда этап реально завершился и повествование сохранено. Ручной режим не затронут
            // (там событие не эмитится — этап завершается финальным ответом + workflow_paused).
            finalizeAssistant(sid);
            const nextAssistantId = newId();
            st.assistantId = nextAssistantId;
            updateSessionMessages(sid, (prev) => [
              ...prev,
              { id: nextAssistantId, role: 'assistant', content: '', streaming: true },
            ]);
          } else if (e.type === 'error') {
            finalizeRun(sid);
            // Ошибка агента/LLM (в т.ч. «Ошибка LLM (finishReason=…)»): строка ошибки крепится
            // под пузырём ассистента запуска, а не в верхний баннер. Пустой пузырь не убираем —
            // строка ошибки должна остаться видимой. Верхний баннер — только для ошибок соединения.
            updateSessionMessages(sid, (prev, state) => {
              const idx = prev.findIndex((m) => m.id === state.assistantId);
              if (idx === -1) return prev;
              const next = [...prev];
              next[idx] = { ...next[idx], streaming: false, error: e.payload.message };
              return next;
            });
          }
        });
        if (!controller.signal.aborted) {
          // поток закрылся штатно после agent_finished/error
          finalizeRun(sid);
        }
      } catch (err) {
        if (controller.signal.aborted) {
          finalizeAssistant(sid, undefined, true);
          finalizeRun(sid);
          return;
        }
        // Разрыв SSE: если ни одного события не получили — авто-переподключение (1 попытка)
        if (!receivedAny) {
          if (!isRetry) {
            setError('Соединение с сервером разорвано. Переподключение…');
            setTimeout(() => {
              void runStream(sid, startStream, true);
            }, 1200);
            return;
          }
        }
        // Сетевой обрыв (TypeError «Failed to fetch») показываем как «Нет связи с сервером»,
        // а не сырым текстом браузера; ошибки с понятным текстом — дословно.
        setError(describeStreamError(err));
        finalizeAssistant(sid, undefined, true);
        finalizeRun(sid);
      }
    },
    [handleEvent],
  );

  const run = useCallback(
    (sid: string, text: string, isRetry: boolean): Promise<void> =>
      runStream(sid, (signal, onEvent) => streamChat(sid, text, signal, onEvent), isRetry),
    [runStream],
  );

  /** Фактический запуск стрима: сообщение + заглушка ассистента кладутся в буфер сессии sid. */
  const runSend = useCallback(
    (sid: string, trimmed: string) => {
      setError(null);
      // Заголовок вкладки — первое сообщение пользователя (если ещё не задан каталогом).
      setTitles((prev) => (prev[sid]?.trim() ? prev : { ...prev, [sid]: trimmed }));
      const st = getRunState(sid);
      const assistantId = newId();
      st.assistantId = assistantId;
      st.messages = [
        ...st.messages,
        { id: newId(), role: 'user', content: trimmed },
        { id: assistantId, role: 'assistant', content: '', streaming: true },
      ];
      st.isRunning = true;
      // Оптимистичная подсветка текущего этапа: для первой задачи воркфлоу (строки состояния
      // ещё нет в буфере) агент начинает с «Планирования» — чип загорается сразу, не дожидаясь,
      // пока агент вызовет инструмент task_state и закоммитит состояние. Иначе до первого
      // task_state_changed чип был бы пустым/застывшим. Для не-воркфлоу панель состояния скрыта.
      if (st.taskState == null && workflowSettings?.enabled === true) {
        updateSessionTaskState(sid, {
          sessionId: sid,
          stage: 'planning',
          currentStep: null,
          expectedAction: null,
          paused: false,
          plan: null,
          implementation: null,
          validation: null,
          awaitConfirmation: false,
          updatedAt: new Date().toISOString(),
        });
      }
      if (sid === activeIdRef.current) {
        setMessages(st.messages);
        setIsRunning(true);
      }
      void run(sid, trimmed, false);
    },
    [run, workflowSettings],
  );

  /**
   * Продолжение воркфлоу Day-14 (кнопка «Продолжить»): запускает СЛЕДУЮЩИЙ этап,
   * читая сохранённую историю + состояние задачи без нового user-сообщения. Заглушка
   * ассистента кладётся в буфер (без пузыря пользователя), ожидание подтверждения
   * сбрасывается сразу — кнопки «Продолжить»/«Отмена» прячутся.
   */
  const runContinue = useCallback(
    (sid: string) => {
      setError(null);
      const st = getRunState(sid);
      const assistantId = newId();
      st.assistantId = assistantId;
      st.messages = [...st.messages, { id: assistantId, role: 'assistant', content: '', streaming: true }];
      st.isRunning = true;
      if (st.taskState != null) {
        // «Продолжить»/«Выполнить» в ручном режиме — переход к СЛЕДУЮЩЕМУ линейному этапу
        // (planning→execution→validation→done): сразу подсвечиваем его, чтобы чип загорелся,
        // а не остался на прошлом завершённом этапе. «Снять паузу» в авто — возобновление с
        // ТЕКУЩЕГО этапа (пауза не снята): этап не меняем. Ожидание и паузу сбрасываем всегда.
        const isManualAdvance = st.taskState.awaitConfirmation === true && st.taskState.paused !== true;
        const stage = isManualAdvance ? nextWorkflowStage(st.taskState.stage) : st.taskState.stage;
        updateSessionTaskState(sid, { ...st.taskState, stage, awaitConfirmation: false, paused: false });
      }
      if (sid === activeIdRef.current) {
        setMessages(st.messages);
        setIsRunning(true);
      }
      void runStream(sid, (signal, onEvent) => streamContinue(sid, signal, onEvent), false);
    },
    [runStream],
  );

  /** Продолжение воркфлоу Day-14 (кнопка «Продолжить»): запускает следующий этап (SSE). */
  const continueWorkflow = useCallback(
    (sid: string) => {
      runContinue(sid);
    },
    [runContinue],
  );

  const sendMessage = useCallback(
    (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || isRunning) return;
      if (sessionId != null) {
        runSend(sessionId, trimmed);
        return;
      }
      // Ленивое создание: сессию создаёт сервер внутри активного проекта (client UUID больше
      // не существует). Нет активного проекта — отправка невозможна (пустое состояние «Создать проект»).
      const pid = activeProjectIdRef.current;
      if (pid == null) return;
      createSessionInProjectApi(pid)
        .then((created) => {
          if (activeProjectIdRef.current !== pid) return;
          activeIdRef.current = created.sessionId;
          const t = created.title;
          if (t != null && t.trim() !== '') {
            setTitles((prev) => ({ ...prev, [created.sessionId]: t }));
          }
          setTabsState((prev) => {
            const ids = [...prev.ids, created.sessionId];
            persistTabs(ids, created.sessionId);
            return { ids, activeId: created.sessionId };
          });
          runSend(created.sessionId, trimmed);
        })
        .catch(() => setError('Не удалось создать сессию'));
    },
    [isRunning, sessionId, runSend],
  );

  /** Останавливает стрим Активной сессии (только её — чужие запуски не трогаем). */
  const stopAgent = useCallback(() => {
    if (sessionId == null) return;
    runStateRef.current.get(sessionId)?.controller?.abort();
  }, [sessionId]);

  /** Восстанавливает лог шагов сессии из кэша (или очищает, если кэша нет). */
  const restoreSteps = (id: string) => {
    const cached = stepsCacheRef.current.get(id);
    if (cached) {
      stepsRef.current = new Map(cached.map((e) => [e.id, e]));
      setSteps(cached);
    } else {
      stepsRef.current.clear();
      setSteps([]);
    }
  };

  /**
   * Переключение на другую вкладку: сохраняет steps активной, восстанавливает target.
   * Разрешено всегда (пока бэкенд работает) — переключение не гасит чужие стримы:
   * фоновые запуски продолжают писать в свои буферы и «всплывут» при возврате.
   */
  const switchSession = useCallback(
    (id: string) => {
      if (backendStopped || backendStarting) return;
      if (id === activeId) return;
      if (activeId != null) {
        stepsCacheRef.current.set(activeId, Array.from(stepsRef.current.values()));
      }
      // Буфер сессии — источник истины её сообщений/итогов токенов: отдаём его в живой вид.
      // Для вкладки без буфера (никогда не открывалась) стартуем пусто — историю догрузит
      // эффект sessionId; локально созданной записи на бэкенде ещё нет — пустое состояние.
      const st = getRunState(id);
      setMessages(st.messages);
      setTokenTotals(st.tokenTotals);
      setLastPromptTokens(st.lastPromptTokens);
      setIsRunning(st.isRunning);
      restoreSteps(id);
      activeIdRef.current = id;
      setTabsState((prev) => {
        const ids = prev.ids.includes(id) ? prev.ids : [...prev.ids, id];
        persistTabs(ids, id);
        return { ids, activeId: id };
      });
    },
    [activeId, backendStopped, backendStarting],
  );

  /**
   * Новая сессия: сервер создаёт сессию внутри активного проекта (POST /projects/{id}/sessions,
   * UUID генерирует бэкенд), вкладка добавляется справа и сразу становится активной —
   * в любой момент, даже при чужих запусках. Нет активного проекта — no-op
   * (пустое состояние показывает «Создать проект»).
   */
  const newSession = useCallback(() => {
    if (backendStopped || backendStarting) return;
    const pid = activeProjectIdRef.current;
    if (pid == null) return;
    void (async () => {
      try {
        const created = await createSessionInProjectApi(pid);
        if (activeProjectIdRef.current !== pid) return;
        if (activeId != null) {
          stepsCacheRef.current.set(activeId, Array.from(stepsRef.current.values()));
        }
        // Итоги токенов пер-сессионны: новая сессия стартует с нуля, а не с суммы предыдущей.
        resetSessionView();
        activeIdRef.current = created.sessionId;
        setTabsState((prev) => {
          const ids = [...prev.ids, created.sessionId];
          persistTabs(ids, created.sessionId);
          return { ids, activeId: created.sessionId };
        });
        pushSystemEvent('Начата новая сессия');
      } catch {
        setError('Не удалось создать сессию');
      }
    })();
  }, [activeId, backendStopped, backendStarting, pushSystemEvent]);

  /**
   * Закрытие вкладки сессии: DELETE на бэкенде, убрать из вкладок. Если закрыли
   * активную — активировать соседа слева; если вкладок не осталось — пустое состояние.
   * Разрешено всегда (пока бэкенд работает): стрим закрываемой сессии прерывается,
   * запуски других сессий продолжают идти параллельно.
   */
  const closeSession = useCallback(
    (id: string) => {
      if (backendStopped || backendStarting) return;
      void (async () => {
        const wasActive = id === activeId;
        // Стрим закрываемой сессии глушим независимо от того, активна она или нет;
        // остальные буферы и стримы не трогаем.
        const closing = runStateRef.current.get(id);
        closing?.controller?.abort();
        runStateRef.current.delete(id);
        if (runningSidsRef.current.delete(id)) setRunningCount((c) => c - 1);
        try {
          await deleteSessionApi(id);
        } catch {
          /* сессии могло не быть на бэкенде или сеть недоступна — вкладку закрываем всё равно */
        }
        if (!tabs.includes(id)) return;
        stepsCacheRef.current.delete(id);
        const ids = tabs.filter((x) => x !== id);
        let nextActive = activeId;
        if (wasActive) {
          const idx = tabs.indexOf(id);
          const left = idx > 0 ? tabs[idx - 1] : undefined;
          if (left) {
            nextActive = left;
          } else {
            // Закрыта последняя вкладка — возвращаемся в пустое состояние.
            nextActive = null;
          }
        }
        if (nextActive !== null && nextActive !== activeId) {
          // Удалённая сессия не должна оставлять свои сообщения/итоги токенов на экране:
          // сосед показывает свой буфер (в т.ч. флаг выполнения, если сосед «думает»);
          // историю при необходимости догрузит эффект sessionId.
          const t = getRunState(nextActive);
          setMessages(t.messages);
          setTokenTotals(t.tokenTotals);
          setLastPromptTokens(t.lastPromptTokens);
          setIsRunning(t.isRunning);
          restoreSteps(nextActive);
        }
        if (nextActive === null) {
          resetSessionView();
        }
        setError(null);
        activeIdRef.current = nextActive;
        setTabsState({ ids, activeId: nextActive });
        persistTabs(ids, nextActive);
        refreshGlobalStats();
        syncSessions();
        pushSystemEvent(wasActive ? 'Сессия удалена' : 'Сессия закрыта');
      })();
    },
    [tabs, activeId, backendStopped, backendStarting, refreshGlobalStats, syncSessions, pushSystemEvent],
  );

  /** Удаление активной сессии (кнопка в ChatPanel) — синоним closeSession(activeId). */
  const deleteSession = useCallback(() => {
    if (activeId == null) return;
    closeSession(activeId);
  }, [activeId, closeSession]);

  /**
   * Загружает сессии проекта pid в вкладки: активной становится первая сессия проекта
   * (или пустое состояние, если сессий нет). Ошибки каталога молча игнорируются.
   */
  const loadProjectTabs = useCallback((pid: number) => {
    void (async () => {
      try {
        const sessions = await fetchProjectSessions(pid);
        if (activeProjectIdRef.current !== pid) return; // проект уже сменили — ответ устарел
        const ids = sessions.map((s) => s.sessionId);
        setTitles((prev) => {
          let changed = false;
          const next = { ...prev };
          for (const s of sessions) {
            const t = s.firstUserMessage;
            if (t && t.trim() !== '' && next[s.sessionId] !== t.trim()) {
              next[s.sessionId] = t.trim();
              changed = true;
            }
          }
          return changed ? next : prev;
        });
        const nextActive = ids[0] ?? null;
        if (nextActive == null) {
          resetSessionView();
          activeIdRef.current = null;
          setTabsState({ ids, activeId: null });
          persistTabs(ids, null);
          return;
        }
        // Показываем буфер активируемой сессии; историю догрузит эффект sessionId.
        const st = getRunState(nextActive);
        setMessages(st.messages);
        setTokenTotals(st.tokenTotals);
        setLastPromptTokens(st.lastPromptTokens);
        setIsRunning(st.isRunning);
        restoreSteps(nextActive);
        activeIdRef.current = nextActive;
        setTabsState({ ids, activeId: nextActive });
        persistTabs(ids, nextActive);
      } catch {
        /* каталог сессий недоступен — вкладки остаются как есть */
      }
    })();
  }, []);

  /**
   * Выбор активного проекта: запоминаем id (+localStorage), сбрасываем вид текущей
   * сессии и перезагружаем вкладки сессиями выбранного проекта. Память проекта
   * подхватит эффект activeProjectId.
   */
  const selectProject = useCallback(
    (id: number) => {
      if (backendStopped || backendStarting) return;
      if (id === activeProjectIdRef.current) return;
      // Сохраняем лог шагов уходящей сессии — возврат на проект должен его восстановить.
      if (activeId != null) {
        stepsCacheRef.current.set(activeId, Array.from(stepsRef.current.values()));
      }
      persistActiveProjectId(id);
      activeProjectIdRef.current = id;
      setActiveProjectId(id);
      activeIdRef.current = null;
      resetSessionView();
      setTabsState({ ids: [], activeId: null });
      loadProjectTabs(id);
    },
    [activeId, backendStopped, backendStarting, loadProjectTabs],
  );

  /** Каталог проектов: GET /api/projects + перепроверка активного (исчез — переключаемся). */
  const loadProjects = useCallback(async (): Promise<void> => {
    let list: Project[];
    try {
      list = await fetchProjects();
    } catch {
      /* сервис недоступен — каталог проектов остаётся прежним */
      return;
    }
    setProjects(list);
    const current = activeProjectIdRef.current;
    if (current != null && list.some((p) => p.id === current)) return;
    const next = list[0]?.id ?? null;
    if (next != null) {
      persistActiveProjectId(next);
      activeProjectIdRef.current = next;
      setActiveProjectId(next);
      loadProjectTabs(next);
    } else if (current != null) {
      // Проектов не осталось — пустое состояние «Создать проект».
      persistActiveProjectId(null);
      activeProjectIdRef.current = null;
      setActiveProjectId(null);
      activeIdRef.current = null;
      resetSessionView();
      setTabsState({ ids: [], activeId: null });
      persistTabs([], null);
    }
  }, [loadProjectTabs]);

  /** Создание проекта: POST + перечитывание каталога + выбор созданного активным. */
  const createProject = useCallback(
    async (name: string): Promise<void> => {
      const trimmed = name.trim();
      if (trimmed === '') return;
      try {
        const created = await createProjectApi(trimmed);
        pushSystemEvent(`Создан проект «${created.name}»`);
        await loadProjects();
        // loadProjects сохранит уже активный; выбираем созданный явно.
        if (activeProjectIdRef.current !== created.id) {
          if (backendStopped || backendStarting) return;
          persistActiveProjectId(created.id);
          activeProjectIdRef.current = created.id;
          setActiveProjectId(created.id);
          activeIdRef.current = null;
          resetSessionView();
          setTabsState({ ids: [], activeId: null });
          loadProjectTabs(created.id);
        }
      } catch {
        pushSystemEvent('Не удалось создать проект');
      }
    },
    [backendStopped, backendStarting, loadProjects, loadProjectTabs, pushSystemEvent],
  );

  /** Переименование проекта: PATCH + перечитывание каталога (имя обновится в панели). */
  const renameProject = useCallback(
    async (id: number, name: string): Promise<void> => {
      const trimmed = name.trim();
      if (trimmed === '') return;
      await renameProjectApi(id, trimmed);
      await loadProjects();
    },
    [loadProjects],
  );

  /**
   * Удаление проекта: DELETE (бэкенд каскадом стирает сессии проекта и его рабочую
   * память; LTM не трогается) + перечитывание каталога. Если удалили активный проект —
   * переключение на следующий доступный или в пустое состояние.
   */
  const deleteProject = useCallback(
    async (id: number): Promise<void> => {
      try {
        await deleteProjectApi(id);
      } catch {
        pushSystemEvent('Не удалось удалить проект');
        return;
      }
      pushSystemEvent('Проект удалён (сессии и рабочая память стёрты каскадом)');
      const wasActive = id === activeProjectIdRef.current;
      let list: Project[] = [];
      try {
        list = await fetchProjects();
      } catch {
        /* каталог недоступен — уберём проект локально */
        list = projects.filter((p) => p.id !== id);
      }
      setProjects(list);
      if (!wasActive) return;
      const next = list[0]?.id ?? null;
      if (next != null && next !== id) {
        persistActiveProjectId(next);
        activeProjectIdRef.current = next;
        setActiveProjectId(next);
        activeIdRef.current = null;
        resetSessionView();
        setTabsState({ ids: [], activeId: null });
        loadProjectTabs(next);
      } else {
        persistActiveProjectId(null);
        activeProjectIdRef.current = null;
        setActiveProjectId(null);
        activeIdRef.current = null;
        resetSessionView();
        setTabsState({ ids: [], activeId: null });
        persistTabs([], null);
      }
      refreshGlobalStats();
    },
    [projects, loadProjectTabs, pushSystemEvent, refreshGlobalStats],
  );

  /**
   * Остановка бэк-сервиса: POST /system-ctrl/stop по команде супервизору.
   * Бэкенд может умереть до ответа — ошибку сети не считаем провалом.
   */
  const stopService = useCallback(() => {
    if (backendStopped || backendStarting) return;
    void (async () => {
      // Бэкенд сейчас погаснет — прерываем ВСЕ активные стримы всех вкладок.
      for (const s of runStateRef.current.values()) s.controller?.abort();
      pushSystemEvent('Остановка бэк-сервиса…');
      try {
        await stopBackendApi();
      } catch {
        /* бэкенд может быть уже мёртв — считаем остановленным, дальше опрос готовности */
        pushSystemEvent('Не удалось связаться с сервисом');
      }
      setBackendStopped(true);
      pushSystemEvent('Бэк-сервис остановлен');
    })();
  }, [backendStopped, backendStarting, pushSystemEvent]);

  /**
   * Запуск бэк-сервиса: POST /system-ctrl/start + ожидание готовности
   * опросом /api/llm-settings каждую секунду (максимум 60 попыток).
   */
  const startService = useCallback(() => {
    if (!backendStopped) return;
    startAbortedRef.current = false;
    setBackendStarting(true);
    pushSystemEvent('Запуск бэк-сервиса…');
    void (async () => {
      try {
        await startBackendApi();
      } catch {
        /* сервис может быть мёртв — готовность проверяем опросом ниже */
      }
      // Готовность: раз в секунду пробуем GET /api/llm-settings, максимум 60 попыток (~60 с)
      for (let attempt = 0; attempt < 60; attempt++) {
        if (startAbortedRef.current) return;
        try {
          // Первый успешный GET — это и есть применённые настройки: применяем их,
          // чтобы блок настроек стал активным сразу после восстановления сервиса.
          const s = await fetchLlmSettings();
          if (startAbortedRef.current) return;
          pushSystemEvent('Бэк-сервис доступен');
          setBackendStopped(false);
          setBackendStarting(false);
          setRunSettings(s);
          runSettingsRef.current = s;
          // Страница могла быть открыта при неработающем сервисе — тогда история
          // при монтировании не загрузилась. Догружаем её после восстановления,
          // но только если буфер сессии ещё пуст (активный стрим не затираем).
          if (sessionId != null) {
            try {
              const h = await fetchHistory(sessionId);
              const st = getRunState(sessionId);
              if (st.messages.length === 0) {
                // Сообщения, итоги токенов и размер контекста — общей функцией наложения истории.
                applyHistoryToRunState(sessionId, h);
              }
              pushSystemEvent(
                h.messages.length > 0
                  ? `История диалога загружена (${h.messages.length} сообщений)`
                  : 'История диалога пуста',
              );
            } catch {
              /* история недоступна — оставляем чат как есть */
            }
            // Стратегия/факты/ветки тоже не загрузились при неработающем сервисе — догружаем.
            loadPerSessionExtras(sessionId);
          }
          // После восстановления сервиса обновляем глобальную статистику и сверяем вкладки.
          refreshGlobalStats();
          syncSessions();
          return;
        } catch {
          /* сервис ещё поднимается — ждём секунду и пробуем снова */
        }
        await new Promise((resolve) => setTimeout(resolve, 1000));
      }
      if (startAbortedRef.current) return;
      // За 60 с сервис не поднялся — снимаем блокировку запуска, баннер остаётся
      pushSystemEvent('Бэк-сервис не запустился — попробуйте ещё раз');
      setBackendStarting(false);
    })();
  }, [backendStopped, pushSystemEvent, sessionId, applyHistoryToRunState, refreshGlobalStats, syncSessions]);

  /**
   * Сохранение настроек LLM: PUT + обновление общей state-настройки, чтобы contextLimit
   * (и прочие поля) пересчитались везде — StatsBar «лимит/занято/осталось» и провайдер.
   * После успешного PUT перечитываем GET: ответ PUT может содержать null для сброшенных
   * полей (например maxTokens), тогда как сервер применяет дефолт — показываем серверную истину.
   */
  const updateLlmSettings = useCallback(
    async (patch: Partial<RunSettings>): Promise<RunSettings> => {
      await updateLlmSettingsApi(patch);
      const next = await fetchLlmSettings();
      setRunSettings(next);
      runSettingsRef.current = next;
      return next;
    },
    [],
  );

  // Настройки LLM глобальны (эндпоинт не требует sessionId): загружаем при монтировании,
  // независимо от наличия сессий — блок настроек активен даже в пустом состоянии.
  useEffect(() => {
    let cancelled = false;
    fetchLlmSettings()
      .then((s) => {
        if (cancelled) return;
        setRunSettings(s);
        runSettingsRef.current = s;
        pushSystemEvent('Бэк-сервис доступен, настройки загружены');
      })
      .catch(() => {
        if (cancelled) return;
        // сервис недоступен — блокируем чат и показываем баннер с предложением запуска
        setBackendStopped(true);
        pushSystemEvent('Бэк-сервис недоступен — нажмите «Старт сервиса»');
      });
    return () => {
      cancelled = true;
    };
  }, [pushSystemEvent]);

  // Автовосстановление: если сервис был недоступен при загрузке страницы (например,
  // бэк кратковременно перезапускался), периодически опрашиваем /api/llm-settings и
  // снимаем блокировку кнопок (backendStopped → false), как только бэк снова отвечает.
  // Это позволяет разблокировать UI без ручного клика «Старт сервиса».
  useEffect(() => {
    if (!backendStopped || backendStarting) return;
    let cancelled = false;
    let interval: ReturnType<typeof setInterval> | undefined;
    const tryOnce = async () => {
      try {
        const s = await fetchLlmSettings();
        if (cancelled) return;
        setBackendStopped(false);
        setRunSettings(s);
        runSettingsRef.current = s;
        pushSystemEvent('Бэк-сервис доступен — интерфейс разблокирован');
        refreshGlobalStats();
        syncSessions();
        if (interval) clearInterval(interval);
      } catch {
        /* сервис ещё недоступен — пробуем на следующем тике */
      }
    };
    interval = setInterval(tryOnce, 3000);
    void tryOnce();
    return () => {
      cancelled = true;
      if (interval) clearInterval(interval);
    };
  }, [backendStopped, backendStarting, pushSystemEvent, refreshGlobalStats, syncSessions]);

  // Настройки воркфлоу Day-14 глобальны (эндпоинт не требует sessionId): загружаем при
  // монтировании — переключатель «Следовать воркфлоу» и режим в правой колонке.
  useEffect(() => {
    let cancelled = false;
    fetchWorkflowSettings()
      .then((s) => {
        if (cancelled) return;
        setWorkflowSettings(s);
      })
      .catch(() => {
        /* сервис недоступен — переключатель остаётся в неопределённом состоянии до перечитывания */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // восстановление истории активной сессии при монтировании (и при смене sessionId)
  useEffect(() => {
    if (sessionId == null) return;
    let cancelled = false;
    fetchHistory(sessionId)
      .then((h) => {
        if (cancelled) return;
        // Сессия с активным стримом уже наполнила свой буфер свежими сообщениями:
        // серверная история на этот момент устарела — живому буферу отдаём приоритет,
        // чтобы возврат на вкладку не «откатил» только что накопленные токены.
        const live = runStateRef.current.get(sessionId);
        if (runningSidsRef.current.has(sessionId) && live && live.messages.length > 0) return;
        // Сообщения, накопительные итоги токенов и размер контекста — общей функцией
        // наложения истории на буфер сессии (используется и при смене ветки/стратегии).
        applyHistoryToRunState(sessionId, h);
        // Fallback заголовка вкладки: первое сообщение пользователя из истории.
        const firstUser = h.messages.find((m) => m.role === 'user');
        if (firstUser && firstUser.content.trim() !== '') {
          setTitles((prev) => {
            const t = firstUser.content.trim();
            if (prev[sessionId] && prev[sessionId].trim() !== '') return prev;
            return { ...prev, [sessionId]: t };
          });
        }
        pushSystemEvent(
          h.messages.length > 0
            ? `История диалога загружена (${h.messages.length} сообщений)`
            : 'История диалога пуста',
        );
      })
      .catch(() => {
        /* история недоступна — стартуем пустыми */
      });
    // Стратегия контекста, факты и ветки сессии — вместе с первичной загрузкой истории
    // (isStale отбрасывает ответы, пришедшие после переключения вкладки/размонтирования).
    loadPerSessionExtras(sessionId, () => cancelled);
    return () => {
      cancelled = true;
      startAbortedRef.current = true;
      // НЕ прерываем стрим: переключение вкладки не должно гасить фоновые запуски —
      // стрим прерывается только stopAgent / closeSession / stopService.
    };
  }, [sessionId, applyHistoryToRunState, pushSystemEvent]);

  // Глобальная статистика + сверка вкладок с бэкендом при монтировании окна.
  useEffect(() => {
    refreshGlobalStats();
    syncSessions();
  }, [refreshGlobalStats, syncSessions]);

  // После завершения ВСЕХ генераций сверяем вкладки ещё раз: во время любого run
  // чистка пропускается (guard runningCount), поэтому ловим переход runningCount -> 0.
  useEffect(() => {
    if (runningCount === 0) syncSessions();
  }, [runningCount, syncSessions]);

  // Зеркала стратегии/фактов/веток активной сессии: при смене вкладки копируем из её буфера.
  // Пер-сессионный источник истины этих значений — SessionRunState, как и у сообщений.
  useEffect(() => {
    if (sessionId == null) {
      resetSessionView();
      return;
    }
    const st = getRunState(sessionId);
    setStrategy(st.strategy);
    setWindowSize(st.windowSize);
    setFacts(st.facts);
    setBranches(st.branches);
    setTaskState(st.taskState);
  }, [sessionId]);

  // Зеркало активной вкладки для колбэков фоновых стримов (маршрутизация событий).
  useEffect(() => {
    activeIdRef.current = activeId;
  }, [activeId]);

  // Зеркало активного проекта для колбэков (фоновые стримы, загрузки каталога).
  useEffect(() => {
    activeProjectIdRef.current = activeProjectId;
  }, [activeProjectId]);

  // Каталог проектов при монтировании: восстановление активного проекта из localStorage
  // (или первый доступный), затем вкладки сессий выбранного проекта (loadProjectTabs).
  const projectsBootedRef = useRef(false);
  useEffect(() => {
    if (projectsBootedRef.current) return;
    projectsBootedRef.current = true;
    void loadProjects();
  }, [loadProjects]);

  return {
    sessionId,
    messages,
    steps,
    runSettings,
    tokenTotals,
    lastPromptTokens,
    isRunning,
    backendStopped,
    backendStarting,
    error,
    projects,
    activeProjectId,
    tabs,
    activeId,
    titles,
    globalStats,
    strategy,
    windowSize,
    facts,
    branches,
    taskState,
    saveWorkingNote,
    saveLongTerm,
    sendMessage,
    stopAgent,
    loadProjects,
    selectProject,
    createProject,
    renameProject,
    deleteProject,
    changeContextStrategy,
    changeTaskState,
    changeWorkflowSettings,
    continueWorkflow,
    cancelWorkflow,
    workflowSettings,
    forkBranch,
    switchBranch,
    deleteSession,
    switchSession,
    newSession,
    closeSession,
    stopService,
    startService,
    updateLlmSettings,
  };
}
