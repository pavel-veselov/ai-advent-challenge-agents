import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { ChatMessage } from '../types';

/**
 * Строка «контекст запроса: N · ответ: M» для пузыря ассистента.
 * «Контекст запроса» — входные токены единственного LLM-запроса, породившего это
 * сообщение, то есть вся история, отправленная модели на момент ответа.
 * Только успешные сообщения (m.error рисуется как раньше, без токенов) и только
 * когда бэкенд прислал хотя бы одно из двух значений (user-сообщения — null).
 */
function tokenLabel(m: ChatMessage): string | null {
  if (m.error) return null;
  const parts: string[] = [];
  if (m.promptTokens != null) parts.push(`контекст запроса: ${m.promptTokens}`);
  if (m.completionTokens != null) parts.push(`ответ: ${m.completionTokens}`);
  return parts.length > 0 ? parts.join(' · ') : null;
}

interface ChatPanelProps {
  messages: ChatMessage[];
  isRunning: boolean;
  /** true, пока бэк-сервис остановлен — блокируем чат и служебные кнопки, показываем баннер. */
  backendStopped: boolean;
  /** true, пока бэк-сервис запускается — блокируем чат и служебные кнопки. */
  backendStarting: boolean;
  error: string | null;
  sessionId: string | null;
  onSend: (text: string) => void;
  onStop: () => void;
  onDeleteSession: () => void;
}

export default function ChatPanel({
  messages,
  isRunning,
  backendStopped,
  backendStarting,
  error,
  sessionId,
  onSend,
  onStop,
  onDeleteSession,
}: ChatPanelProps) {
  const [input, setInput] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  const serviceDown = backendStopped || backendStarting;

  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, isRunning]);

  const submit = () => {
    if (!input.trim() || isRunning || serviceDown) return;
    onSend(input);
    setInput('');
  };

  return (
    <aside className="panel chat-panel">
      <div className="panel-header">
        <span>Чат</span>
        {sessionId ? (
          <span className="session-chip" title="sessionId">{sessionId.slice(0, 8)}</span>
        ) : null}
        <button
          type="button"
          className="btn-reset"
          title="Удалить сессию на бэкенде: очистить чат и лог шагов, начать новую сессию"
          onClick={onDeleteSession}
          disabled={isRunning || serviceDown || sessionId === null}
        >
          Удалить сессию
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {backendStopped && (
        <div className="service-banner service-banner-down">
          Сервис недоступен. Нажмите «Старт сервиса», чтобы запустить.
        </div>
      )}
      {backendStarting && (
        <div className="service-banner service-banner-starting">Сервис запускается…</div>
      )}

      <div className="chat-list" ref={listRef}>
        {messages.map((m) => (
          <div key={m.id} className={`chat-msg ${m.role}`}>
            {m.role === 'assistant' ? (
              <>
                <div className={`chat-msg-text chat-md${m.streaming ? ' streaming' : ''}`}>
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.content}</ReactMarkdown>
                  {!m.content && m.streaming && <span className="streaming-cursor" />}
                </div>
                {tokenLabel(m) ? (
                  <div
                    className="chat-msg-tokens"
                    title="Вход последнего запроса = вся история на момент ответа"
                  >
                    {tokenLabel(m)}
                  </div>
                ) : null}
                {m.error ? <div className="chat-msg-error">{m.error}</div> : null}
              </>
            ) : (
              <span className="chat-msg-text">
                {m.content}
                {m.streaming && <span className="streaming-cursor" />}
              </span>
            )}
          </div>
        ))}
        {isRunning && (
          <div className="thinking">
            <span className="thinking-text">агент думает</span>
            <span className="thinking-dots"><i /><i /><i /></span>
          </div>
        )}
      </div>

      <div className="chat-input-area">
        <div className="chat-input-bar">
          <textarea
            className="chat-input"
            rows={1}
            placeholder={
              backendStopped
                ? 'Бэк-сервис остановлен…'
                : backendStarting
                  ? 'Бэк-сервис запускается…'
                  : 'Напишите сообщение… (Enter — отправить)'
            }
            value={input}
            disabled={serviceDown}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
          />
          {isRunning ? (
            <button type="button" className="btn-stop" onClick={onStop} disabled={serviceDown}>
              ⏹ Стоп
            </button>
          ) : (
            <button
              type="button"
              className="btn-send"
              onClick={submit}
              disabled={!input.trim() || serviceDown}
              title="Отправить сообщение"
            >
              <span className="btn-send-arrow">➤</span>
            </button>
          )}
        </div>
      </div>
    </aside>
  );
}
