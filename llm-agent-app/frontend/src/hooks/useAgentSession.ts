import { useCallback, useEffect, useRef, useState } from 'react';
import {
  deleteSession as deleteSessionApi,
  fetchHistory,
  fetchLlmSettings,
  startBackend as startBackendApi,
  stopBackend as stopBackendApi,
  streamChat,
} from '../api';
import type { AgentEvent, ChatMessage, RunSettings, StepLogEntry } from '../types';

const SESSION_KEY = 'llm-agent-session-id';

function newId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `id-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function getOrCreateSessionId(): string {
  try {
    const existing = localStorage.getItem(SESSION_KEY);
    if (existing) return existing;
    const created = newId();
    localStorage.setItem(SESSION_KEY, created);
    return created;
  } catch {
    return newId();
  }
}

/** Текущее время в HH:MM:SS для колонки времени в логе шагов. */
function fmtTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour12: false });
}

export interface AgentSession {
  sessionId: string;
  messages: ChatMessage[];
  /** Лог шагов текущей сессии (все запуски подряд). */
  steps: StepLogEntry[];
  /** Настройки LLM последнего запуска (сверху лога шагов). */
  runSettings: RunSettings | null;
  isRunning: boolean;
  /** true, пока бэк-сервис остановлен: чат и служебные кнопки заблокированы, показан баннер. */
  backendStopped: boolean;
  /** true, пока бэк-сервис запускается (опрос /api/llm-settings в фоне). */
  backendStarting: boolean;
  error: string | null;
  sendMessage: (text: string) => void;
  stopAgent: () => void;
  /** Удаление сессии: DELETE на бэкенде, новый sessionId, очистка чата и лога шагов. */
  deleteSession: () => void;
  /** Остановка бэк-сервиса по команде супервизора (POST /system-ctrl/stop). */
  stopService: () => void;
  /** Запуск бэк-сервиса с ожиданием готовности (опрос /api/llm-settings). */
  startService: () => void;
}

export function useAgentSession(): AgentSession {
  const [sessionId, setSessionId] = useState<string>(() => getOrCreateSessionId());
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [steps, setSteps] = useState<StepLogEntry[]>([]);
  const [runSettings, setRunSettings] = useState<RunSettings | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [backendStopped, setBackendStopped] = useState(false);
  const [backendStarting, setBackendStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const assistantIdRef = useRef<string>('');
  /** id -> запись лога; порядок вставки = хронология. */
  const stepsRef = useRef<Map<string, StepLogEntry>>(new Map());
  /** Флаг отмены ожидания запуска при размонтировании компонента. */
  const startAbortedRef = useRef(false);

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

  /** Применяем одно событие CONTRACT-а к логу шагов. */
  const handleEvent = useCallback((e: AgentEvent) => {
    const key = `${e.runId}:${e.stepId}`;
    switch (e.type) {
      case 'agent_started': {
        setRunSettings(e.payload.settings);
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
          status: 'running',
          explanation: `Итерация ${it}: агент отправляет в LLM всю историю диалога (раскройте «Промпт в LLM»). Модель решает — ответить текстом или запросить инструмент.`,
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
          explanation:
            e.payload.finishReason === 'stop'
              ? 'LLM решила, что данных достаточно, и дала финальный ответ — цикл агента завершается.'
              : e.payload.finishReason === 'tool_calls'
                ? 'LLM решила, что данных не хватает, и запросила инструмент — следующим шагом агент его выполнит.'
                : e.payload.finishReason === 'length'
                  ? 'Ответ обрезан: достигнут лимит токенов на один ответ модели.'
                  : 'LLM вернула ошибку вместо ответа — цикл прерван.',
        });
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
  }, []);

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
    async (text: string, isRetry: boolean) => {
      const controller = new AbortController();
      abortRef.current = controller;
      let receivedAny = false;
      try {
        await streamChat(sessionId, text, controller.signal, (e) => {
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
          } else if (e.type === 'agent_finished') {
            setIsRunning(false);
            finalizeAssistant(e.payload.finalText);
          } else if (e.type === 'error') {
            setIsRunning(false);
            finalizeAssistant(undefined, true);
            setError(e.payload.message);
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
              void run(text, true);
            }, 1200);
            return;
          }
        }
        setError(err instanceof Error ? err.message : 'Соединение с сервером разорвано');
        finalizeAssistant(undefined, true);
        setIsRunning(false);
      }
    },
    [sessionId, handleEvent],
  );

  const sendMessage = useCallback(
    (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || isRunning) return;
      setError(null);
      setMessages((prev) => [
        ...prev,
        { id: newId(), role: 'user', content: trimmed },
        { id: (assistantIdRef.current = newId()), role: 'assistant', content: '', streaming: true },
      ]);
      setIsRunning(true);
      void run(trimmed, false);
    },
    [isRunning, run],
  );

  const stopAgent = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  /** Удаление сессии: стираем историю на бэкенде и начинаем новую сессию. */
  const deleteSession = useCallback(() => {
    if (isRunning || backendStopped || backendStarting) return;
    void (async () => {
      abortRef.current?.abort();
      try {
        await deleteSessionApi(sessionId);
      } catch {
        /* сессии могло не быть на бэкенде или сеть недоступна — всё равно начинаем новую */
      }
      const fresh = newId();
      try {
        localStorage.setItem(SESSION_KEY, fresh);
      } catch {
        /* localStorage недоступен — просто меняем id в памяти */
      }
      stepsRef.current.clear();
      assistantIdRef.current = '';
      setRunSettings(null);
      setMessages([]);
      setError(null);
      setIsRunning(false);
      setSessionId(fresh);
      pushSystemEvent('Сессия удалена, начата новая сессия');
    })();
  }, [sessionId, isRunning, backendStopped, backendStarting, pushSystemEvent]);

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
          await fetchLlmSettings();
          if (startAbortedRef.current) return;
          pushSystemEvent('Бэк-сервис доступен');
          setBackendStopped(false);
          setBackendStarting(false);
          // Страница могла быть открыта при неработающем сервисе — тогда история
          // при монтировании не загрузилась. Догружаем её после восстановления.
          try {
            const h = await fetchHistory(sessionId);
            setMessages((prev) => {
              if (prev.length > 0) return prev;
              return h.messages.map((m) => ({
                id: newId(),
                role: m.role,
                content: m.content,
                streaming: false,
              }));
            });
            pushSystemEvent(
              h.messages.length > 0
                ? `История диалога загружена (${h.messages.length} сообщений)`
                : 'История диалога пуста',
            );
          } catch {
            /* история недоступна — оставляем чат как есть */
          }
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
  }, [backendStopped, pushSystemEvent, sessionId]);

  // восстановление истории и настроек при монтировании (и при смене sessionId)
  useEffect(() => {
    let cancelled = false;
    fetchHistory(sessionId)
      .then((h) => {
        if (cancelled) return;
        setMessages(
          h.messages.map((m) => ({
            id: newId(),
            role: m.role,
            content: m.content,
            streaming: false,
          })),
        );
        pushSystemEvent(
          h.messages.length > 0
            ? `История диалога загружена (${h.messages.length} сообщений)`
            : 'История диалога пуста',
        );
      })
      .catch(() => {
        /* история недоступна — стартуем пустыми */
      });
    // Настройки LLM показываем сразу при открытии окна, до первого запроса
    fetchLlmSettings()
      .then((s) => {
        if (cancelled) return;
        setRunSettings(s);
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
      abortRef.current?.abort();
      startAbortedRef.current = true;
    };
  }, [sessionId, pushSystemEvent]);

  return {
    sessionId,
    messages,
    steps,
    runSettings,
    isRunning,
    backendStopped,
    backendStarting,
    error,
    sendMessage,
    stopAgent,
    deleteSession,
    stopService,
    startService,
  };
}
