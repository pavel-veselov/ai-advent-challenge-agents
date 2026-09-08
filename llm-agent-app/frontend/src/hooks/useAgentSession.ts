import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchHistory, fetchLlmSettings, streamChat } from '../api';
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
  error: string | null;
  sendMessage: (text: string) => void;
  stopAgent: () => void;
  /** Сброс сессии: новый sessionId, очистка чата и лога шагов. */
  resetSession: () => void;
}

export function useAgentSession(): AgentSession {
  const [sessionId, setSessionId] = useState<string>(() => getOrCreateSessionId());
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [steps, setSteps] = useState<StepLogEntry[]>([]);
  const [runSettings, setRunSettings] = useState<RunSettings | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const assistantIdRef = useRef<string>('');
  /** id -> запись лога; порядок вставки = хронология. */
  const stepsRef = useRef<Map<string, StepLogEntry>>(new Map());

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

  /** Полный сброс: новый sessionId, пустой чат и пустой лог шагов. */
  const resetSession = useCallback(() => {
    abortRef.current?.abort();
    const fresh = newId();
    try {
      localStorage.setItem(SESSION_KEY, fresh);
    } catch {
      /* localStorage недоступен — просто меняем id в памяти */
    }
    stepsRef.current.clear();
    assistantIdRef.current = '';
    setSteps([]);
    setRunSettings(null);
    setMessages([]);
    setError(null);
    setIsRunning(false);
    setSessionId(fresh);
  }, []);

  // восстановление истории и настроек при монтировании
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
      })
      .catch(() => {
        /* история недоступна — стартуем пустыми */
      });
    // Настройки LLM показываем сразу при открытии окна, до первого запроса
    fetchLlmSettings()
      .then((s) => {
        if (!cancelled) setRunSettings(s);
      })
      .catch(() => {
        /* настройки недоступны — блок появится после первого agent_started */
      });
    return () => {
      cancelled = true;
      abortRef.current?.abort();
    };
  }, [sessionId]);

  return { sessionId, messages, steps, runSettings, isRunning, error, sendMessage, stopAgent, resetSession };
}
