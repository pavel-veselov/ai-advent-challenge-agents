import { useState } from 'react';
import type { TaskState, TaskStage } from '../types';

/** Порядок этапов FSM для лампового ряда (planning → execution → validation → done). */
const STAGES: { value: TaskStage; label: string }[] = [
  { value: 'planning', label: 'Планирование' },
  { value: 'execution', label: 'Выполнение' },
  { value: 'validation', label: 'Проверка' },
  { value: 'done', label: 'Готово' },
];

type SaveStatus = 'idle' | 'saving' | 'error';

interface TaskStatePanelProps {
  /** Состояние задачи активной сессии (GET/PUT /task-state + событие task_state_changed); null — задача не начата. */
  taskState: TaskState | null;
  /** id активной сессии; null — сессии ещё нет. */
  sessionId: string | null;
  /** true — сервис остановлен/запускается: кнопка паузы заблокирована. */
  disabled: boolean;
  /**
   * Воркфлоу (ручной режим) ждёт подтверждения перехода (App: enabled + manual +
   * awaitConfirmation) — полоса подсвечивается янтарём, координируясь с кнопками
   * подтверждения в журнале чата.
   */
  workflowPending?: boolean;
  /** Воркфлоу включён (workflow.enabled=true). При false полосу состояния задачи НЕ показываем. */
  workflowEnabled: boolean;
  /** Показывать кнопку паузы/снятия паузы — только в режиме «Авто» (ручной — подтверждение кнопками в чате). */
  showPause: boolean;
  /**
   * «Снять паузу» в авто-режиме: возобновляет агента с ТЕКУЩЕГО этапа (POST /continue).
   * Паузу ставим через [onChangePaused], снимаем через [onResume] (иначе /continue переключил бы этап).
   */
  onResume: () => void;
  /**
   * Пауза/снятие паузы: PUT /task-state {paused} через хук. REST-мутации SSE не шлют —
   * полоса обновляется из ответа PUT. Ошибка (400/сеть) пробрасывается — показываем инлайн.
   */
  onChangePaused: (paused: boolean) => Promise<TaskState>;
}

/**
 * Инструментальная полоса «Состояние задачи» внутри панели чата (Day-13 FSM task_state):
 * узкая рейка над журналом сообщений — ламповый ряд этапов planning → execution →
 * validation → done, бейджи «ЗАДАЧА НА ПАУЗЕ»/«ОЖИДАЕТ ПОДТВЕРЖДЕНИЯ» и кнопка паузы
 * (в авто-режиме). Показывается ТОЛЬКО при включённом воркфлоу. Компактная полоса
 * (без шага/действия и без раскрываемых деталей): результаты этапов воркфлоу видны
 * в самом чате как отдельные ответы ассистента. Паузу ставит/снимает ТОЛЬКО
 * пользователь: инструмент task_state флаг сохраняет неизменным, поэтому кнопка —
 * единственный вход. Обновления приходят живьём по SSE (task_state_changed),
 * при открытии сессии состояние восстанавливает GET /task-state.
 */
export default function TaskStatePanel({
  taskState,
  sessionId,
  disabled,
  workflowPending = false,
  workflowEnabled,
  showPause,
  onResume,
  onChangePaused,
}: TaskStatePanelProps) {
  const [saveState, setSaveState] = useState<SaveStatus>('idle');
  /** Последняя ошибка смены паузы (инлайн; гаснет при следующей попытке). */
  const [error, setError] = useState<string | null>(null);

  const busy = saveState === 'saving';
  const paused = taskState?.paused === true;
  const awaitConfirmation = taskState?.awaitConfirmation === true;
  const awaitActive = awaitConfirmation || workflowPending;

  const hasTask = sessionId != null && taskState != null;

  /** Пауза (PUT {paused:true}) или снятие паузы (возобновление с текущего этапа). */
  const onPauseClick = () => {
    if (sessionId == null || disabled || busy || taskState == null) return;
    if (paused) {
      // «Снять паузу» — возобновляем агента с текущего этапа (auto). Ошибки обрабатывает run-стрим.
      onResume();
      return;
    }
    setSaveState('saving');
    setError(null);
    onChangePaused(true).then(
      () => setSaveState('idle'),
      (err: unknown) => {
        setSaveState('error');
        setError(err instanceof Error ? err.message : String(err));
      },
    );
  };

  // Воркфлоу выключен — полосу состояния задачи не показываем вовсе.
  if (!workflowEnabled) return null;

  return (
    <section className={`task-strip${paused ? ' is-paused' : ''}${awaitActive ? ' is-await' : ''}`}>
      <div className="task-strip-main">
        <span className="task-strip-label">Состояние задачи</span>
        {/* Ламповый ряд этапов FSM: активный этап — янтарная лампа, остальные приглушены. */}
        <span className="task-strip-stages" role="list" aria-label="Этапы задачи">
          {STAGES.map((s) => (
            <span
              key={s.value}
              role="listitem"
              className={`task-stage-chip${taskState?.stage === s.value ? ' is-active' : ''}`}
              title={`Этап «${s.label}»${taskState?.stage === s.value ? ' — текущий' : ''}`}
            >
              <span className="task-stage-lamp" aria-hidden="true" />
              {s.label}
            </span>
          ))}
        </span>
        <span className="task-strip-spacer" />
        {paused ? (
          <span className="task-badge is-paused" title="ЗАДАЧА НА ПАУЗЕ — шаги не выполняются">
            ЗАДАЧА НА ПАУЗЕ
          </span>
        ) : null}
        {awaitActive ? (
          <span className="task-badge is-await" title="Ожидает подтверждения перехода — кнопки в чате">
            ОЖИДАЕТ ПОДТВЕРЖДЕНИЯ
          </span>
        ) : null}
        {showPause && hasTask && !awaitActive ? (
          <button
            type="button"
            className="project-btn task-strip-pause"
            disabled={disabled || busy}
            title={
              paused
                ? 'Снять паузу: агент продолжит выполнять шаги задачи с текущего этапа'
                : 'Поставить на паузу: агент не будет выполнять шаги задачи (этап и шаги сохраняются)'
            }
            onClick={onPauseClick}
          >
            {busy ? 'Сохранение…' : paused ? 'Снять паузу' : 'Пауза'}
          </button>
        ) : null}
      </div>

      {error != null ? (
        <div className="task-strip-error" role="alert">
          не удалось обновить паузу: {error}
        </div>
      ) : null}
    </section>
  );
}
