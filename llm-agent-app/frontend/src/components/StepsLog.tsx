import { useEffect, useRef } from 'react';
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
 * Пояснение «для чайников» по типу шага — дополняет explanation из useAgentSession
 * (та заполнена не для всех случаев; здесь — человекочитаемые подсказки карточек шагов).
 */
function explainByKind(step: StepLogEntry): string | null {
  switch (step.kind) {
    case 'user':
      return 'Пользователь написал сообщение — агент принял его в работу.';
    case 'llm':
      return step.title.startsWith('Сжатие контекста')
        ? 'Старые сообщения диалога сворачиваются в краткое резюме, чтобы уложиться в лимит контекста.'
        : 'Агент отправляет диалог модели и ждёт: она ответит текстом или попросит инструмент.';
    case 'tool':
      if (step.status === 'running') return 'Агент вызывает инструмент, который попросила модель…';
      return step.status === 'error'
        ? 'Инструмент завершился ошибкой — агент сообщит о ней модели.'
        : 'Инструмент что-то вычислил и вернул результат агенту.';
    case 'answer':
      return 'Ответ завершён — финальный текст доставлен в чат.';
    case 'error':
      return 'Что-то пошло не так — работа агента остановлена.';
    default:
      return null;
  }
}

/** Пояснения служебных строк (kind = system): события жизненного цикла сервиса/сессии. */
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

/** Карточка шага агента: точка статуса + время + название + пояснение «для чайников». */
function StepCard({ step }: { step: StepLogEntry }) {
  const explanation = step.explanation ?? explainByKind(step);
  return (
    <div className={`step-row status-${step.status}`}>
      <div className="step-head">
        <span className={`step-dot ${step.status}`} />
        <span className="step-time">{step.time}</span>
        <span className="step-title">{step.title}</span>
      </div>
      {explanation ? <div className="step-explanation">{explanation}</div> : null}
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
          // Полный хронологический лог: карточки шагов агента + служебные строки.
          steps.map((s) =>
            s.kind === 'system' ? <SystemRow key={s.id} step={s} /> : <StepCard key={s.id} step={s} />,
          )
        )}
      </div>
    </section>
  );
}
