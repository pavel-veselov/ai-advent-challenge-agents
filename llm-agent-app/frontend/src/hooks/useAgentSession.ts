import { useCallback, useEffect, useRef, useState } from 'react';
import {
  deleteSession as deleteSessionApi,
  fetchGlobalStats,
  fetchHistory,
  fetchLlmSettings,
  fetchSessions,
  startBackend as startBackendApi,
  stopBackend as stopBackendApi,
  streamChat,
  updateLlmSettings as updateLlmSettingsApi,
} from '../api';
import type {
  AgentEvent,
  ChatMessage,
  RunSettings,
  StatsResponse,
  StepLogEntry,
  TokenTotals,
} from '../types';

const SESSION_KEY = 'llm-agent-session-id';
const TABS_KEY = 'llm-agent-tabs';

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
  sendMessage: (text: string) => void;
  stopAgent: () => void;
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

  const abortRef = useRef<AbortController | null>(null);
  const assistantIdRef = useRef<string>('');
  /** id сессии, которой принадлежит текущий стрим (уходящая сессия в cleanup). */
  const runSidRef = useRef<string | null>(null);
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

  /** Создаёт или обновляет запись лога шагов и синкает состояние в React. */
  const touchStep = (stepId: string, ts: string, patch: Partial<StepLogEntry>) => {
    const id = stepId;
    const existing = stepsRef.current.get(id);
    if (!existing) {
      stepsRef.current.set(id, {
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
      stepsRef.current.set(id, { ...existing, ...patch });
    }
    setSteps(Array.from(stepsRef.current.values()));
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
    assistantIdRef.current = '';
    setError(null);
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
    if (isRunning) return;
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
  }, [tabs, activeId, isRunning]);

  /** Применяем одно событие CONTRACT-а к логу шагов. */
  const handleEvent = useCallback((e: AgentEvent) => {
    const key = `${e.runId}:${e.stepId}`;
    switch (e.type) {
      case 'agent_started': {
        setRunSettings(e.payload.settings);
        runSettingsRef.current = e.payload.settings;
        touchStep(`${e.runId}:user`, e.timestamp, {
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
        touchStep(key, e.timestamp, {
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
        touchStep(key, e.timestamp, {
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
        // Накопительные итоги диалога: суммируем usage и стоимость каждого ответа LLM.
        setTokenTotals((prev) => accumulateTotals(prev, u, e.payload.costUsd));
        // Текущий размер контекста = входные токены последнего реального запроса в LLM
        // (те же значения бэкенд пишет в prompt_tokens последнего assistant-сообщения истории).
        const inputTokens = u?.inputTokens;
        if (inputTokens != null) setLastPromptTokens(inputTokens);
        refreshGlobalStats();
        syncSessions();
        return;
      }
      case 'tool_call_started': {
        const tool = /^tool-(.+)-\d+$/.exec(e.stepId);
        touchStep(key, e.timestamp, {
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
        touchStep(key, e.timestamp, {
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
        touchStep(key, e.timestamp, {
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
      case 'error': {
        touchStep(`${e.runId}:error`, e.timestamp, {
          kind: 'error',
          title: 'Ошибка',
          status: 'error',
          result: e.payload.message,
          explanation: 'Непредвиденная ошибка — работа агента остановлена.',
        });
        return;
      }
      default:
        return;
    }
  }, [refreshGlobalStats, syncSessions]);

  const finalizeAssistant = (finalText?: string, removeIfEmpty = false) => {
    setMessages((prev) => {
      const idx = prev.findIndex((m) => m.id === assistantIdRef.current);
      if (idx === -1) return prev;
      const target = prev[idx];
      const content = finalText !== undefined ? finalText : target.content;
      if (removeIfEmpty && content.trim() === '') {
        return prev.filter((_, i) => i !== idx);
      }
      const next = [...prev];
      next[idx] = { ...target, content, streaming: false };
      return next;
    });
  };

  const run = useCallback(
    async (sid: string, text: string, isRetry: boolean) => {
      const controller = new AbortController();
      abortRef.current = controller;
      runSidRef.current = sid;
      let receivedAny = false;
      try {
        await streamChat(sid, text, controller.signal, (e) => {
          receivedAny = true;
          handleEvent(e);
          if (e.type === 'llm_token') {
            setMessages((prev) => {
              const idx = prev.findIndex((m) => m.id === assistantIdRef.current);
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
            setMessages((prev) => {
              const idx = prev.findIndex((m) => m.id === assistantIdRef.current);
              if (idx === -1) return prev;
              const next = [...prev];
              next[idx] = {
                ...next[idx],
                promptTokens: u?.inputTokens ?? null,
                completionTokens: u?.outputTokens ?? null,
              };
              return next;
            });
          } else if (e.type === 'agent_finished') {
            setIsRunning(false);
            finalizeAssistant(e.payload.finalText);
          } else if (e.type === 'error') {
            setIsRunning(false);
            // Ошибка агента/LLM (в т.ч. «Ошибка LLM (finishReason=…)»): строка ошибки крепится
            // под пузырём ассистента запуска, а не в верхний баннер. Пустой пузырь не убираем —
            // строка ошибки должна остаться видимой. Верхний баннер — только для ошибок соединения.
            setMessages((prev) => {
              const idx = prev.findIndex((m) => m.id === assistantIdRef.current);
              if (idx === -1) return prev;
              const next = [...prev];
              next[idx] = { ...next[idx], streaming: false, error: e.payload.message };
              return next;
            });
          }
        });
        if (!controller.signal.aborted) {
          // поток закрылся штатно после agent_finished/error
          setIsRunning(false);
        }
      } catch (err) {
        if (controller.signal.aborted) {
          finalizeAssistant(undefined, true);
          setIsRunning(false);
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
        finalizeAssistant(undefined, true);
        setIsRunning(false);
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
      }
      setError(null);
      setMessages((prev) => [
        ...prev,
        { id: newId(), role: 'user', content: trimmed },
        { id: (assistantIdRef.current = newId()), role: 'assistant', content: '', streaming: true },
      ]);
      setIsRunning(true);
      void run(sid, trimmed, false);
    },
    [isRunning, sessionId, run],
  );

  const stopAgent = useCallback(() => {
    abortRef.current?.abort();
  }, []);

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

  /** Переключение на другую вкладку: сохраняет steps активной, восстанавливает target. */
  const switchSession = useCallback(
    (id: string) => {
      if (isRunning || backendStopped || backendStarting) return;
      if (id === activeId) return;
      if (activeId != null) {
        stepsCacheRef.current.set(activeId, Array.from(stepsRef.current.values()));
      }
      // Итоги токенов и сообщения пер-сессионны: чужие данные предыдущей вкладки не
      // должны мелькать (и тем более «протекать» при сбое загрузки истории). Реальные
      // значения догружает эффект sessionId из истории новой вкладки; для локально
      // созданной записи на бэкенде ещё нет — остаётся пустое состояние.
      setMessages([]);
      setTokenTotals({ promptTokens: 0, completionTokens: 0, costUsd: 0 });
      setLastPromptTokens(null);
      assistantIdRef.current = '';
      restoreSteps(id);
      setTabsState((prev) => {
        const ids = prev.ids.includes(id) ? prev.ids : [...prev.ids, id];
        persistTabs(ids, id);
        return { ids, activeId: id };
      });
    },
    [activeId, isRunning, backendStopped, backendStarting],
  );

  /** Новая сессия: добавляет вкладку справа и переключает на неё. */
  const newSession = useCallback(() => {
    if (isRunning || backendStopped || backendStarting) return;
    const fresh = newId();
    if (activeId != null) {
      stepsCacheRef.current.set(activeId, Array.from(stepsRef.current.values()));
    }
    // Новая вкладка ещё не существует на бэкенде — помечаем её как локально созданную,
    // чтобы эффект sessionId не дёргал историю впустую (см. localOnlyRef).
    localOnlyRef.current.add(fresh);
    // Итоги токенов пер-сессионны: новая сессия стартует с нуля, а не с суммы предыдущей.
    resetSessionView();
    setTabsState((prev) => {
      const ids = [...prev.ids, fresh];
      persistTabs(ids, fresh);
      return { ids, activeId: fresh };
    });
    pushSystemEvent('Начата новая сессия');
  }, [activeId, isRunning, backendStopped, backendStarting, pushSystemEvent]);

  /**
   * Закрытие вкладки сессии: DELETE на бэкенде, убрать из вкладок. Если закрыли
   * активную — активировать соседа слева; если вкладок не осталось — пустое состояние.
   * Заблокировано при isRunning || backendStopped || backendStarting.
   */
  const closeSession = useCallback(
    (id: string) => {
      if (isRunning || backendStopped || backendStarting) return;
      void (async () => {
        const wasActive = id === activeId;
        if (wasActive) {
          abortRef.current?.abort();
        }
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
          // сосед догружается эффектом sessionId из своей собственной истории.
          setMessages([]);
          setTokenTotals({ promptTokens: 0, completionTokens: 0, costUsd: 0 });
          setLastPromptTokens(null);
          assistantIdRef.current = '';
          restoreSteps(nextActive);
        }
        if (nextActive === null) {
          resetSessionView();
        }
        setError(null);
        setIsRunning(false);
        setTabsState({ ids, activeId: nextActive });
        persistTabs(ids, nextActive);
        refreshGlobalStats();
        syncSessions();
        pushSystemEvent(wasActive ? 'Сессия удалена' : 'Сессия закрыта');
      })();
    },
    [tabs, activeId, isRunning, backendStopped, backendStarting, refreshGlobalStats, syncSessions, pushSystemEvent],
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
      abortRef.current?.abort(); // останавливаем активный стрим — бэкенд сейчас погаснет
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
          // при монтировании не загрузилась. Догружаем её после восстановления.
          if (sessionId != null) {
            try {
              const h = await fetchHistory(sessionId);
              setMessages((prev) => {
                if (prev.length > 0) return prev;
                return h.messages.map((m) => ({
                  id: newId(),
                  role: m.role,
                  content: m.content,
                  streaming: false,
                  promptTokens: m.promptTokens ?? null,
                  completionTokens: m.completionTokens ?? null,
                }));
              });
              // Догруженные итоги токенов: перезаписываем (не суммируем); нет totals — нули.
              setTokenTotals(
                h.totals
                  ? {
                      promptTokens: h.totals.promptTokens,
                      completionTokens: h.totals.completionTokens,
                      costUsd: h.totals.costUsd,
                    }
                  : { promptTokens: 0, completionTokens: 0, costUsd: 0 },
              );
              const lastAssistant = [...h.messages]
                .reverse()
                .find((m) => m.role === 'assistant' && m.promptTokens != null);
              setLastPromptTokens(lastAssistant?.promptTokens ?? null);
              pushSystemEvent(
                h.messages.length > 0
                  ? `История диалога загружена (${h.messages.length} сообщений)`
                  : 'История диалога пуста',
              );
            } catch {
              /* история недоступна — оставляем чат как есть */
            }
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
  }, [backendStopped, pushSystemEvent, sessionId, refreshGlobalStats, syncSessions]);

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
          setMessages(
            h.messages.map((m) => ({
              id: newId(),
              role: m.role,
              content: m.content,
              streaming: false,
              promptTokens: m.promptTokens ?? null,
              completionTokens: m.completionTokens ?? null,
            })),
          );
          // Приведение накопительных итогов токенов к серверной истине сессии (перезапись,
          // не суммирование). Бэкенд не отдаёт totals (null) для сессий без единого токена —
          // приравниваем к нулям, чтобы не «протекали» итоги другой вкладки.
          setTokenTotals(
            h.totals
              ? {
                  promptTokens: h.totals.promptTokens,
                  completionTokens: h.totals.completionTokens,
                  costUsd: h.totals.costUsd,
                }
              : { promptTokens: 0, completionTokens: 0, costUsd: 0 },
          );
          // Текущий размер контекста: prompt_tokens последнего assistant-сообщения истории
          // (== входные токены последнего запроса в LLM на бэкенде).
          const lastAssistant = [...h.messages]
            .reverse()
            .find((m) => m.role === 'assistant' && m.promptTokens != null);
          setLastPromptTokens(lastAssistant?.promptTokens ?? null);
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
    }
    return () => {
      cancelled = true;
      startAbortedRef.current = true;
      // Прерываем стрим, принадлежащий уходящей сессии (при размонтировании или
      // переключении). При ленивом создании во время отправки стрим не трогаем:
      // он относится к новой сессии и только что стартовал.
      if (runSidRef.current == null || runSidRef.current === sessionId) {
        abortRef.current?.abort();
      }
    };
  }, [sessionId, pushSystemEvent]);

  // Глобальная статистика + сверка вкладок с бэкендом при монтировании окна.
  useEffect(() => {
    refreshGlobalStats();
    syncSessions();
  }, [refreshGlobalStats, syncSessions]);

  // После завершения генерации сверяем вкладки ещё раз: во время run чистка
  // пропускается (guard isRunning), поэтому ловим переход isRunning -> false.
  useEffect(() => {
    if (!isRunning) syncSessions();
  }, [isRunning, syncSessions]);

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
    sendMessage,
    stopAgent,
    deleteSession,
    switchSession,
    newSession,
    closeSession,
    stopService,
    startService,
    updateLlmSettings,
  };
}
