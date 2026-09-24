import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import TaskStatePanel from './TaskStatePanel';
import type { BranchesState, ChatMessage, ContextStrategy, TaskState } from '../types';
import { downloadToolFileUrl } from '../api';

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
  /**
   * Воркфлоу Day-14 (ручной режим): true — агент завершил этап и ждёт подтверждения
   * перехода — под последним сообщением ассистента рендерятся кнопки «Продолжить»/«Отмена».
   */
  workflowPending: boolean;
  /** Воркфлоу Day-14: кнопка «Продолжить» — запускает следующий этап (SSE /continue). */
  onWorkflowContinue: () => void;
  /** Воркфлоу Day-14: кнопка «Отмена» — пауза задачи и сброс ожидания. */
  onWorkflowCancel: () => void;
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
  /** Состояние задачи активной сессии (Day-13 FSM) — для полосы состояния над журналом. */
  taskState: TaskState | null;
  /** true — сервис остановлен/запускается: кнопка паузы на полосе состояния заблокирована. */
  taskDisabled: boolean;
  /** Пауза/снятие паузы задачи: PUT /task-state {paused} (кнопка на полосе состояния). */
  onChangePaused: (paused: boolean) => Promise<TaskState>;
  /** Воркфлоу включён — полоса состояния задачи показывается. */
  workflowEnabled: boolean;
  /** Показывать кнопку паузы на полосе состояния — только в авто-режиме. */
  showPause: boolean;
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
  workflowPending,
  onWorkflowContinue,
  onWorkflowCancel,
  onSend,
  onStop,
  onDeleteSession,
  onSaveWorkingNote,
  onSaveLongTerm,
  onForkBranch,
  onSwitchBranch,
  taskState,
  taskDisabled,
  onChangePaused,
  workflowEnabled,
  showPause,
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

      {/* Полоса состояния задачи (Day-13 FSM): ряд этапов ведущего паза — узкая рейка
          между служебными баннерами и журналом. Показывается только при включённом
          воркфлоу; кнопка паузы — только в авто-режиме. */}
      <TaskStatePanel
        taskState={taskState}
        sessionId={sessionId}
        disabled={taskDisabled}
        workflowPending={workflowPending}
        workflowEnabled={workflowEnabled}
        showPause={showPause}
        onResume={onWorkflowContinue}
        onChangePaused={onChangePaused}
      />

      <div className="chat-list" ref={listRef}>
        {messages.map((m, index) => {
          // Id сообщения в истории бэкенда, если оно пришло из истории (для ветвления).
          const historyId = m.historyId;
          // Мини-кнопки «сохранить в память» — под каждым сообщением, кроме служебных system.
          const savable = m.role !== 'system';
          const saveBusy = saveFeedback?.msgId === m.id && saveFeedback.status === 'saving';
          const saveDone =
            saveFeedback?.msgId === m.id && saveFeedback.status !== 'saving' ? saveFeedback : null;
          // Воркфлоу Day-14: кнопки подтверждения/«Отмена» под ПОСЛЕДНИМ сообщением ассистента,
          // когда агент завершил этап и ждёт подтверждения перехода (ручной режим).
          // Подпись главной кнопки зависит от текущего этапа FSM (задача пользователя №1):
          //   planning  → «Выполнить»      (подтвердить выполнение плана)
          //   execution → «Проверить задачу» (подтвердить проверку)
          //   validation → «Завершить»     (задача выполнена)
          //   done / null → «Продолжить»   (fallback)
          const workflowBtns =
            workflowPending && m.role === 'assistant' && index === messages.length - 1 && !m.streaming;
          const confirmStage = taskState?.stage ?? null;
          const confirmLabel =
            confirmStage === 'planning'
              ? 'Выполнить'
              : confirmStage === 'execution'
                ? 'Проверить задачу'
                : confirmStage === 'validation'
                  ? 'Завершить'
                  : 'Продолжить';
          const confirmTitle =
            confirmStage === 'planning'
              ? 'Подтвердить выполнение плана — перейти к этапу выполнения'
              : confirmStage === 'execution'
                ? 'Подтвердить проверку — перейти к этапу проверки'
                : confirmStage === 'validation'
                  ? 'Подтвердить завершение — задача выполнена'
                  : 'Подтвердить переход к следующему этапу воркфлоу';
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
                  {m.file ? (
                    <a
                      className="chat-msg-file"
                      href={downloadToolFileUrl(m.file.filename)}
                      download
                      title="Скачать файл, созданный пайплайном"
                    >
                      ⤓ Скачать файл
                    </a>
                  ) : null}
                  {m.error ? <div className="chat-msg-error">{m.error}</div> : null}
                  {workflowBtns ? (
                    <div className="workflow-confirm-row">
                      <button
                        type="button"
                        className="project-btn"
                        title={confirmTitle}
                        onClick={onWorkflowContinue}
                        disabled={isRunning || serviceDown}
                      >
                        {confirmLabel}
                      </button>
                      <button
                        type="button"
                        className="project-btn"
                        title="Отменить воркфлоу — поставить задачу на паузу"
                        onClick={onWorkflowCancel}
                        disabled={isRunning || serviceDown}
                      >
                        Отмена
                      </button>
                    </div>
                  ) : null}
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
