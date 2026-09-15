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

/**
 * Пояснения служебных строк (kind = system): события жизненного цикла сервиса/сессии.
 */
const SYSTEM_EXPLANATIONS: ReadonlyArray<readonly [RegExp, string]> = [
  [/^Остановка бэк-сервиса/, 'Оператор остановил сервис — идущие генерации будут прерваны.'],
  [/^Бэк-сервис остановлен/, 'Сервис остановлен: чат заблокирован до запуска.'],
  [/^Запуск бэк-сервиса/, 'Оператор запускает сервис — ждём готовности.'],
  [/^Бэк-сервис доступен/, 'Сервис отвечает — можно работать.'],
  [/^Бэк-сервис недоступен/, 'Сервис не отвечает — запустите его кнопкой «Старт сервиса».'],
  [/^Бэк-сервис не запустился/, 'Сервис не поднялся за отведённое время — попробуйте ещё раз.'],
  [/^История диалога загружена/, 'Прошлые сообщения диалога загружены из базы.'],
  [/^История диалога пуста/, 'Сохранённых сообщений у сессии нет — начинаем с чистого листа.'],
  [/^Начата новая сессия/, 'Создана новая сессия — диалог начинается заново.'],
  [/^Сессия удалена/, 'Сессия удалена: история стёрта, вкладка закрыта.'],
  [/^Сессия закрыта/, 'Вкладка сессии закрыта.'],
  [/^Создан проект/, 'Создан проект — контейнер сессий с общей рабочей памятью.'],
  [/^Проект удалён/, 'Проект удалён вместе с сессиями и рабочей памятью.'],
  [/^Не удалось/, 'Действие не выполнено — повторите попытку.'],
];

/** Пояснение служебной строки по её тексту; произвольные строки лога — без пояснения. */
function explainSystem(step: StepLogEntry): string | null {
  for (const [pattern, text] of SYSTEM_EXPLANATIONS) {
    if (pattern.test(step.title)) return text;
  }
  return null;
}

/** Строка лога шага агента: время + простой текст; для запросов к LLM — ссылка «Детализация». */
function StepLine({
  step,
  onDetail,
}: {
  step: StepLogEntry;
  onDetail: (step: StepLogEntry) => void;
}) {
  return (
    <div className={`step-line status-${step.status}`}>
      <span className="step-time">{step.time}</span>
      <span className="step-line-text">{step.title}</span>
      {step.prompt && step.prompt.length > 0 ? (
        <button
          type="button"
          className="step-detail"
          title="Показать фактический запрос, отправленный в LLM API"
          onClick={() => onDetail(step)}
        >
          Детализация
        </button>
      ) : null}
    </div>
  );
}

/** Диалог «Детализация»: фактический промпт, отправленный в LLM API (роли + содержимое). */
function DetailModal({ step, onClose }: { step: StepLogEntry; onClose: () => void }) {
  // ESC закрывает диалог наравне с кнопкой «Закрыть» и кликом по затемнению.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="detail-backdrop" onClick={onClose}>
      <div className="detail-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="detail-title">Детализация: фактический запрос в LLM</div>
        <div className="detail-subtitle">{step.title} — сообщения, отправленные в API модели</div>
        {step.prompt?.map((m, i) => (
          <div className="detail-msg" key={`${i}-${m.role}`}>
            <div className="detail-role">{m.role}</div>
            <pre className="detail-code">{m.content}</pre>
          </div>
        ))}
        <div className="detail-actions">
          <button type="button" className="detail-close" onClick={onClose}>
            Закрыть
          </button>
        </div>
      </div>
    </div>
  );
}

/** Служебная строка жизненного цикла (kind = system) + пояснение, если оно известно. */
function SystemRow({ step }: { step: StepLogEntry }) {
  const explanation = explainSystem(step);
  return (
    <div className="step-system">
      <div className="step-system-line">
        <span className="step-time">{step.time}</span>
        <span className="step-system-text">{step.title}</span>
      </div>
      {explanation ? <div className="step-system-hint">{explanation}</div> : null}
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
  /** Шаг, для которого открыт диалог «Детализация» (запрос в LLM). */
  const [detail, setDetail] = useState<StepLogEntry | null>(null);

  // Автопрокрутка к последнему шагу
  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [steps]);

  return (
    <section className="steps-panel">
      <header className="steps-header">
        <h2>Логи</h2>
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
          <div className="steps-empty">Пока нет шагов — начните диалог.</div>
        ) : (
          // Полный хронологический лог: простые текстовые строки шагов + служебные строки.
          steps.map((s) =>
            s.kind === 'system' ? (
              <SystemRow key={s.id} step={s} />
            ) : (
              <StepLine key={s.id} step={s} onDetail={setDetail} />
            ),
          )
        )}
      </div>
      {detail ? <DetailModal step={detail} onClose={() => setDetail(null)} /> : null}
    </section>
  );
}
