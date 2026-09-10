import { useEffect, useRef, useState } from 'react';
import type { StepLogEntry } from '../types';

interface StepsLogProps {
  steps: StepLogEntry[];
  /** true, пока бэк-сервис остановлен — кнопка «Стоп сервиса» заблокирована. */
  backendStopped: boolean;
  /** true, пока бэк-сервис запускается — обе кнопки заблокированы. */
  backendStarting: boolean;
  onStop: () => void;
  onStart: () => void;
}

/** Служебная строка жизненного цикла (kind = system): приглушённая, без статусов и раскрытия. */
function SystemRow({ step }: { step: StepLogEntry }) {
  return (
    <div className="step-system">
      <span className="step-time">{step.time}</span>
      <span className="step-system-text">{step.title}</span>
    </div>
  );
}

function StepRow({ step }: { step: StepLogEntry }) {
  const [open, setOpen] = useState(false);
  const promptText = step.prompt?.map((m) => `[${m.role}] ${m.content}`).join('\n\n');
  const argsText = step.args ? JSON.stringify(step.args, null, 2) : undefined;
  const hasDetails = Boolean(promptText || argsText || step.result || step.content || step.detail);

  return (
    <div className={`step-row status-${step.status}`}>
      <button
        type="button"
        className={`step-head${hasDetails ? ' clickable' : ''}`}
        onClick={() => hasDetails && setOpen((v) => !v)}
        disabled={!hasDetails}
      >
        <span className={`step-dot ${step.status}`} aria-hidden="true" />
        <span className="step-time">{step.time}</span>
        <span className="step-title">
          {step.title}
          {step.detail ? <span className="step-detail"> · {step.detail}</span> : null}
        </span>
        {hasDetails ? <span className="step-toggle">{open ? '−' : '+'}</span> : null}
      </button>
      {step.explanation ? <div className="step-explanation">{step.explanation}</div> : null}
      {open ? (
        <div className="step-body">
          {promptText ? (
            <div className="step-block">
              <div className="step-block-title">Контекст запроса в LLM</div>
              <pre className="step-pre">{promptText}</pre>
            </div>
          ) : null}
          {argsText ? (
            <div className="step-block">
              <div className="step-block-title">Аргументы</div>
              <pre className="step-pre">{argsText}</pre>
            </div>
          ) : null}
          {step.result ? (
            <div className="step-block">
              <div className="step-block-title">Результат</div>
              <pre className="step-pre">{step.result}</pre>
            </div>
          ) : null}
          {step.content ? (
            <div className="step-block">
              <div className="step-block-title">Ответ</div>
              <pre className="step-pre">{step.content}</pre>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

export default function StepsLog({
  steps,
  backendStopped,
  backendStarting,
  onStop,
  onStart,
}: StepsLogProps) {
  const listRef = useRef<HTMLDivElement | null>(null);

  // Автопрокрутка к последнему шагу
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [steps]);

  return (
    <section className="steps-panel">
      <header className="steps-header">
        <h2>Лог шагов</h2>
        <span className="debug-badge" title="Лог — только для отладки: на работу агента не влияет">
          debug
        </span>
        <button
          type="button"
          className="btn-stop"
          title="Остановить бэк-сервис: POST /system-ctrl/stop"
          onClick={onStop}
          disabled={!(!backendStopped && !backendStarting)}
        >
          Стоп сервиса
        </button>
        <button
          type="button"
          className="btn-start"
          title="Запустить бэк-сервис: POST /system-ctrl/start, готовность проверяется опросом /api/llm-settings"
          onClick={onStart}
          disabled={!(backendStopped && !backendStarting)}
        >
          Старт сервиса
        </button>
      </header>
      <div className="steps-list" ref={listRef}>
        {steps.length === 0 ? (
          <div className="steps-empty">Шагов пока нет — отправьте сообщение в чат.</div>
        ) : (
          steps.map((s) => (s.kind === 'system' ? <SystemRow key={s.id} step={s} /> : <StepRow key={s.id} step={s} />))
        )}
      </div>
    </section>
  );
}
