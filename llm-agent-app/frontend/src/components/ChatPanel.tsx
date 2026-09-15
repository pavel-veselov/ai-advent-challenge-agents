import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { BranchesState, ChatMessage, ContextStrategy } from '../types';

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

/** Тип сохранения сообщения: в рабочую память проекта (wm) или долговременную память (ltm). */
type SaveKind = 'wm' | 'ltm';

/** Обратная связь мини-кнопок сохранения: по какому сообщению и с каким статусом. */
interface SaveFeedback {
  msgId: string;
  kind: SaveKind;
  status: 'saving' | 'saved' | 'error';
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
  /** Активный проект: нужен для сохранения в рабочую память; null — кнопка заблокирована. */
  activeProjectId: number | null;
  /** Стратегия контекста активной сессии: ветвление UI видно только при strategy='branching'. */
  strategy: ContextStrategy;
  /** Ветки диалога активной сессии (GET /branches); null — не загружены. */
  branches: BranchesState | null;
  onSend: (text: string) => void;
  onStop: () => void;
  onDeleteSession: () => void;
  /** Сохранить текст сообщения в рабочую память проекта: POST /projects/{id}/memory/notes. */
  onSaveWorkingNote: (note: string) => Promise<void>;
  /** Сохранить текст сообщения в долговременную память: POST /sessions/{id}/memory/long-term. */
  onSaveLongTerm: (note: string) => Promise<void>;
  /** Новая ветка от сообщения истории: POST /branches {messageId}. */
  onForkBranch: (messageId: number) => void;
  /** Переключение активной ветки: PUT /branches {activeBranchId}. */
  onSwitchBranch: (branchId: number) => void;
}

export default function ChatPanel({
  messages,
  isRunning,
  backendStopped,
  backendStarting,
  error,
  sessionId,
  activeProjectId,
  strategy,
  branches,
  onSend,
  onStop,
  onDeleteSession,
  onSaveWorkingNote,
  onSaveLongTerm,
  onForkBranch,
  onSwitchBranch,
}: ChatPanelProps) {
  const [input, setInput] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  const serviceDown = backendStopped || backendStarting;

  // Ветвление доступно только в стратегии «Ветки диалога» и вне запуска (активная сессия),
  // как и прочие сервисные операции — во время генерации ветки не трогаем.
  const branchMode = strategy === 'branching';
  const branchOpsEnabled = branchMode && !isRunning && !serviceDown;

  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, isRunning]);

  // Сохранение сообщений в память — только по явному клику пользователя.
  const [saveFeedback, setSaveFeedback] = useState<SaveFeedback | null>(null);
  const saveTimerRef = useRef<number | null>(null);

  // Размонтирование: гасим отложенный сброс обратной связи сохранения.
  useEffect(
    () => () => {
      if (saveTimerRef.current != null) window.clearTimeout(saveTimerRef.current);
    },
    [],
  );

  /**
   * Клик по мини-кнопке сохранения: отправляем текст сообщения в память и показываем
   * статус под сообщением («✓ Сохранено» / «✕ Ошибка»), который гаснет через 2 секунды.
   */
  const handleSave = (m: ChatMessage, kind: SaveKind) => {
    const note = m.content.trim();
    if (note === '' || m.streaming) return;
    setSaveFeedback({ msgId: m.id, kind, status: 'saving' });
    const settle = (status: 'saved' | 'error') => {
      setSaveFeedback({ msgId: m.id, kind, status });
      if (saveTimerRef.current != null) window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = window.setTimeout(() => setSaveFeedback(null), 2000);
    };
    const promise = kind === 'wm' ? onSaveWorkingNote(note) : onSaveLongTerm(note);
    void promise.then(() => settle('saved')).catch(() => settle('error'));
  };

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
        {branchMode && branches != null && branches.branches.length > 0 ? (
          <label
            className="branch-select-wrap"
            title="Активная ветка диалога — история показывает только её цепочку сообщений"
          >
            <select
              className="branch-select"
              value={branches.activeBranchId ?? ''}
              disabled={!branchOpsEnabled}
              onChange={(e) => onSwitchBranch(Number(e.target.value))}
            >
              {branches.branches.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </select>
          </label>
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
        {messages.map((m) => {
          // Id сообщения в истории бэкенда, если оно пришло из истории (для ветвления).
          const historyId = m.historyId;
          // Мини-кнопки «сохранить в память» — под каждым сообщением, кроме служебных system.
          const savable = m.role !== 'system';
          const saveBusy = saveFeedback?.msgId === m.id && saveFeedback.status === 'saving';
          const saveDone =
            saveFeedback?.msgId === m.id && saveFeedback.status !== 'saving' ? saveFeedback : null;
          return (
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
              ) : m.role === 'system' ? (
                // Служебная заметка бэкенда (например, информация о сжатии контекста) —
                // приглушённая строка между репликами, без пузыря.
                <span className="chat-msg-text chat-msg-system">{m.content}</span>
              ) : (
                <span className="chat-msg-text">
                  {m.content}
                  {m.streaming && <span className="streaming-cursor" />}
                </span>
              )}
              {savable ? (
                <div className="chat-msg-save">
                  <button
                    type="button"
                    className="chat-save-btn"
                    title="Добавить текст сообщения в рабочую память проекта (общая для всех сессий проекта)"
                    onClick={() => handleSave(m, 'wm')}
                    disabled={activeProjectId == null || saveBusy || m.streaming}
                  >
                    <span className="chat-save-icon">⤓</span> В рабочую память
                  </button>
                  <button
                    type="button"
                    className="chat-save-btn"
                    title="Добавить текст сообщения в долговременную память (глобальная база знаний)"
                    onClick={() => handleSave(m, 'ltm')}
                    disabled={sessionId == null || saveBusy || m.streaming}
                  >
                    <span className="chat-save-icon">★</span> В долговременную память
                  </button>
                  {saveDone ? (
                    <span
                      className={`chat-save-state ${saveDone.status === 'saved' ? 'is-ok' : 'is-error'}`}
                    >
                      {saveDone.status === 'saved' ? '✓ Сохранено' : '✕ Ошибка'}
                    </span>
                  ) : null}
                </div>
              ) : null}
              {branchOpsEnabled && historyId != null ? (
                <button
                  type="button"
                  className="chat-msg-fork"
                  title="Создать ветку диалога от этого сообщения — история продолжится с новой цепочкой"
                  onClick={() => onForkBranch(historyId)}
                >
                  Ветка отсюда
                </button>
              ) : null}
            </div>
          );
        })}
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
