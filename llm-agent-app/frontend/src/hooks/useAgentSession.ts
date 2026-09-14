import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createBranch as createBranchApi,
  deleteSession as deleteSessionApi,
  fetchBranches,
  fetchContextStrategy,
  fetchFacts,
  fetchGlobalStats,
  fetchHistory,
  fetchLlmSettings,
  fetchSessions,
  setActiveBranch as setActiveBranchApi,
  startBackend as startBackendApi,
  stopBackend as stopBackendApi,
  streamChat,
  updateContextStrategy,
  updateLlmSettings as updateLlmSettingsApi,
} from '../api';
import type {
  AgentEvent,
  BranchesState,
  ChatMessage,
  ContextStrategy,
  HistoryMessage,
  HistoryResponse,
  RunSettings,
  SessionContextStrategyPatch,
  StatsResponse,
  StepLogEntry,
  TokenTotals,
} from '../types';

const SESSION_KEY = 'llm-agent-session-id';
const TABS_KEY = 'llm-agent-tabs';

/** Дефолтный размер окна последних сообщений (sliding_window/sticky_facts) до ответа GET. */
const DEFAULT_CONTEXT_WINDOW = 10;

function newId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `id-${Date.now()}-${Math.random().toString(36).slice(2)}`;
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
 * Инициализация вкладок при монтировании:
 * 1. Если есть 'llm-agent-tabs' и он валиден — берём его (ids может быть пустым).
 * 2. Иначе миграция: есть старый 'llm-agent-session-id' — засеиваем им единственную вкладку.
 * 3. Иначе — пустое состояние: сессия появится при первом сообщении или через «+».
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
    const old = localStorage.getItem(SESSION_KEY);
    if (old) {
      const seeded: TabsStateData = { ids: [old], activeId: old };
      persistTabs(seeded.ids, seeded.activeId);
      return seeded;
    }
    const empty: TabsStateData = { ids: [], activeId: null };
    persistTabs(empty.ids, empty.activeId);
    return empty;
  } catch {
    return { ids: [], activeId: null };
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
  /** Вкладки сессий (id); может быть пустым — сессия появится по первому сообщению. */
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
  sendMessage: (text: string) => void;
  stopAgent: () => void;
  /**
   * Смена стратегии контекста сессии: PUT /context-strategy (оптимистично применяется,
   * при ошибке откатывается), затем перечитывает историю (стратегия меняет вид сообщений).
   */
  changeContextStrategy: (sessionId: string, patch: SessionContextStrategyPatch) => Promise<void>;
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

  /** sessionId -> буфер состояния выполнения (сообщения/итоги/стрим каждой сессии). */
  const runStateRef = useRef<Map<string, SessionRunState>>(new Map());
  /** id сессий с незавершённым стримом (guard сверки вкладок и «живой» буфер истории). */
  const runningSidsRef = useRef<Set<string>>(new Set());
  /** Число выполняющихся стримов: при падении в 0 триггерим сверку вкладок с бэкендом. */
  const [runningCount, setRunningCount] = useState(0);
  /** Актуальный activeId для колбэков фоновых стримов (замыкания не должны читать устаревший). */
  const activeIdRef = useRef<string | null>(activeId);
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
  /**
   * Сессии, созданные локально перед первой отправкой и ещё не подтверждённые
   * каталогом бэкенда: для них mount-effect не дёргает историю (её ещё нет).
   */
  const localOnlyRef = useRef<Set<string>>(new Set());

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
   * Загружает стратегию контекста, факты и ветки сессии (всё вместе с первичной загрузкой
   * истории). isStale — предикат отмены (unmount/смена сессии): если вернул true, ответ
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
          content: patch.content,
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
    // Пер-сессионные стратегия/факты/ветки: новая сессия и пустое состояние стартуют начисто.
    setStrategy('none');
    setWindowSize(DEFAULT_CONTEXT_WINDOW);
    setFacts(null);
    setBranches(null);
  };

  /**
   * Сверка вкладок с каталогом бэкенда (GET /api/sessions) — fire-and-forget.
   * При монтировании вычищаем ВСЕ вкладки, отсутствующие на бэкенде; если вкладок
   * не осталось — НЕ создаём новую (пустое состояние легально). При последующих
   * обновлениях убираем вкладку T, только если её нет на бэкенде И она не активна
   * И в её кэше шагов нет записей. Во время генерации чистка пропускается.
   * Заодно обновляет заголовки вкладок из первого сообщения сессий.
   */
  const syncSessions = useCallback(() => {
    // Пока выполняется хоть один стрим (любой вкладки) — чистка вкладок пропускается:
    // незавершённую сессию каталог мог ещё не подтвердить.
    if (runningCount > 0) return;
    fetchSessions()
      .then((res) => {
        const known = new Set(res.sessions.map((s) => s.sessionId));
        // Снимаем флаг «создана локально» для подтверждённых бэкендом сессий.
        for (const sid of Array.from(localOnlyRef.current)) {
          if (known.has(sid)) localOnlyRef.current.delete(sid);
        }
        // Заголовки вкладок из каталога: первое сообщение пользователя.
        const titleUpdates: Record<string, string> = {};
        for (const s of res.sessions) {
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
          // Нет вкладок — пустое состояние (сессия появится по первому сообщению).
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
      case 'agent_started': {
        setRunSettings(e.payload.settings);
        runSettingsRef.current = e.payload.settings;
        touchStep(sid, `${e.runId}:user`, e.timestamp, {
          kind: 'user',
          title: 'Запрос пользователя',
          status: 'success',
          result: e.payload.userMessage,
          explanation:
            'Агент принял сообщение и запускает цикл: LLM → (если нужно) инструменты → финальный ответ.',
        });
        return;
      }
      case 'llm_request_started': {
        const it = e.payload.iteration;
        touchStep(sid, key, e.timestamp, {
          kind: 'llm',
          title: `LLM (итерация ${it})`,
          iteration: it,
          prompt: e.payload.prompt,
          // Оценка токенов запроса приходит до самого запроса — сохраняем структурно сразу.
          tokenUsage: {
            estimatedRequestTokens: e.payload.estimatedRequestTokens ?? null,
          },
          status: 'running',
          explanation: `Итерация ${it}: агент отправляет в LLM всю историю диалога (раскройте «Контекст запроса в LLM»). Модель решает — ответить текстом или запросить инструмент.`,
        });
        return;
      }
      case 'llm_response_finished': {
        const ok = e.payload.finishReason === 'stop' || e.payload.finishReason === 'tool_calls';
        const u = e.payload.usage;
        const tokens = u ? ` · токены: вход ${u.inputTokens} · выход ${u.outputTokens}` : '';
        touchStep(sid, key, e.timestamp, {
          status: ok ? 'success' : 'error',
          detail: `finish_reason: ${e.payload.finishReason}${tokens}`,
          // Данные о токенах храним структурно (строку detail выше оставляем для читаемости).
          tokenUsage: {
            inputTokens: u?.inputTokens ?? null,
            outputTokens: u?.outputTokens ?? null,
            estimatedRequestTokens: e.payload.estimatedRequestTokens ?? null,
            costUsd: e.payload.costUsd ?? null,
          },
          explanation:
            e.payload.finishReason === 'stop'
              ? 'LLM решила, что данных достаточно, и дала финальный ответ — цикл агента завершается.'
              : e.payload.finishReason === 'tool_calls'
                ? 'LLM решила, что данных не хватает, и запросила инструмент — следующим шагом агент его выполнит.'
                : e.payload.finishReason === 'length'
                  ? 'Ответ обрезан: достигнут лимит токенов на один ответ модели.'
                  : 'LLM вернула ошибку вместо ответа — цикл прерван.',
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
          explanation:
            'LLM попросила этот инструмент — агент вызывает его с аргументами, которые сгенерировала модель.',
        });
        return;
      }
      case 'tool_call_finished': {
        touchStep(sid, key, e.timestamp, {
          result: e.payload.result,
          status: e.payload.status === 'success' ? 'success' : 'error',
          explanation:
            e.payload.status === 'success'
              ? 'Инструмент вернул результат — на следующей итерации агент передаст его в LLM.'
              : 'Инструмент завершился ошибкой — текст ошибки уйдёт в LLM, чтобы она скорректировала запрос.',
        });
        return;
      }
      case 'agent_finished': {
        touchStep(sid, key, e.timestamp, {
          kind: 'answer',
          title: 'Ответ',
          status: 'success',
          content: e.payload.finalText,
          explanation: 'Цикл завершён — финальный ответ доставлен пользователю.',
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
          explanation:
            'Старые сообщения диалога сворачиваются в краткое summary: LLM получает прежнее резюме (если было), сами сообщения и в конце простой запрос «Сожми историю нашего диалога.» (с пометкой, что это служебное сообщение и запоминать его не нужно). Раскройте «Контекст запроса в LLM», чтобы увидеть точный промпт.',
        });
        return;
      }
      case 'context_summary_finished': {
        touchStep(sid, key, e.timestamp, {
          kind: 'llm',
          title: `Сжатие контекста выполнено: ${e.payload.foldCount} сообщений → summary (LLM: вход ${e.payload.promptTokens} · выход ${e.payload.completionTokens} токенов)`,
          status: 'success',
          // Ответ LLM на запрос резюмирования — это и есть новое summary сессии.
          result: e.payload.summary,
          explanation:
            'История свернута в summary («Результат» — дословный ответ LLM): следующий запрос к LLM уйдёт со сжатым контекстом вместо полных сообщений.',
        });
        return;
      }
      case 'error': {
        touchStep(sid, `${e.runId}:error`, e.timestamp, {
          kind: 'error',
          title: 'Ошибка',
          status: 'error',
          result: e.payload.message,
          explanation: 'Непредвиденная ошибка — работа агента остановлена.',
        });
        return;
      }
      case 'facts_updated': {
        // Живое обновление фактов диалога (sticky_facts): пишем в буфер сессии,
        // активная — сразу в живой вид панели фактов.
        updateSessionFacts(sid, e.payload.facts);
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
    const content = finalText !== undefined ? finalText : target.content;
    if (removeIfEmpty && content.trim() === '') {
      st.messages = st.messages.filter((_, i) => i !== idx);
    } else {
      const next = [...st.messages];
      next[idx] = { ...target, content, streaming: false };
      st.messages = next;
    }
    if (sid === activeIdRef.current) setMessages(st.messages);
  };

  const run = useCallback(
    async (sid: string, text: string, isRetry: boolean) => {
      // Каждая сессия владеет собственным стримом: параллельные запуски разных вкладок
      // живут в своих AbortController'ах и буферах, независимо от активной вкладки.
      const st = getRunState(sid);
      const controller = new AbortController();
      st.controller = controller;
      runningSidsRef.current.add(sid);
      setRunningCount((c) => c + 1);
      let receivedAny = false;
      try {
        await streamChat(sid, text, controller.signal, (e) => {
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
              void run(sid, text, true);
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

  const sendMessage = useCallback(
    (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || isRunning) return;
      // Ленивое создание сессии при первом сообщении: активной ещё нет.
      const sid = sessionId ?? newId();
      if (sessionId == null) {
        localOnlyRef.current.add(sid);
        setTabsState((prev) => {
          const ids = [...prev.ids, sid];
          persistTabs(ids, sid);
          return { ids, activeId: sid };
        });
        // Заголовок вкладки — первое сообщение пользователя.
        setTitles((prev) => ({ ...prev, [sid]: trimmed }));
        activeIdRef.current = sid;
      }
      setError(null);
      // Сообщение и заглушка ассистента кладутся в буфер СВОЕЙ сессии; если она активна —
      // сразу отражаются в живом виде.
      const st = getRunState(sid);
      const assistantId = newId();
      st.assistantId = assistantId;
      st.messages = [
        ...st.messages,
        { id: newId(), role: 'user', content: trimmed },
        { id: assistantId, role: 'assistant', content: '', streaming: true },
      ];
      st.isRunning = true;
      if (sid === activeIdRef.current) {
        setMessages(st.messages);
        setIsRunning(true);
      }
      void run(sid, trimmed, false);
    },
    [isRunning, sessionId, run],
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

  /** Новая сессия: добавляет вкладку справа и переключает на неё (в любой момент, даже при чужих запусках). */
  const newSession = useCallback(() => {
    if (backendStopped || backendStarting) return;
    const fresh = newId();
    if (activeId != null) {
      stepsCacheRef.current.set(activeId, Array.from(stepsRef.current.values()));
    }
    // Новая вкладка ещё не существует на бэкенде — помечаем её как локально созданную,
    // чтобы эффект sessionId не дёргал историю впустую (см. localOnlyRef).
    localOnlyRef.current.add(fresh);
    // Итоги токенов пер-сессионны: новая сессия стартует с нуля, а не с суммы предыдущей.
    resetSessionView();
    activeIdRef.current = fresh;
    setTabsState((prev) => {
      const ids = [...prev.ids, fresh];
      persistTabs(ids, fresh);
      return { ids, activeId: fresh };
    });
    pushSystemEvent('Начата новая сессия');
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
        localOnlyRef.current.delete(id);
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

  // восстановление истории активной сессии при монтировании (и при смене sessionId)
  // восстановление истории активной сессии при монтировании (и при смене sessionId)
  useEffect(() => {
    if (sessionId == null) return;
    let cancelled = false;
    // Сессия, созданная локально перед первой отправкой: истории на бэкенде ещё нет,
    // запрос пропускаем (иначе пустой ответ затёр бы только что добавленное сообщение).
    const localOnly = localOnlyRef.current.has(sessionId);
    if (!localOnly) {
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
    }
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
  }, [sessionId]);

  // Зеркало активной вкладки для колбэков фоновых стримов (маршрутизация событий).
  useEffect(() => {
    activeIdRef.current = activeId;
  }, [activeId]);

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
    tabs,
    activeId,
    titles,
    globalStats,
    strategy,
    windowSize,
    facts,
    branches,
    sendMessage,
    stopAgent,
    changeContextStrategy,
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
