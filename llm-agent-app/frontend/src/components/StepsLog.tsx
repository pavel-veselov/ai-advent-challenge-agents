import { useEffect, useRef, useState } from 'react';
import type { RunSettings, StepLogEntry } from '../types';

interface StepsLogProps {
  steps: StepLogEntry[];
  runSettings: RunSettings | null;
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
              <div className="step-block-title">Промпт в LLM</div>
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

interface SettingsItem {
  label: string;
  value: string;
  hint: string;
}

function settingsItems(s: RunSettings): SettingsItem[] {
  return [
    {
      label: 'провайдер',
      value: s.provider,
      hint: 'Сервис, к которому агент обращается за генерацией: mock — локальная заглушка без сети, gpustack — реальный OpenAI-совместимый API.',
    },
    {
      label: 'модель',
      value: s.model,
      hint: 'Конкретная LLM, которая генерирует ответ (задаётся переменной окружения LLM_MODEL).',
    },
    {
      label: 'temperature',
      value: String(s.temperature),
      hint: 'Насколько смело модель отклоняется от самого вероятного слова: 0 — всегда самый вероятный токен, значения выше — ответы разнообразнее и креативнее.',
    },
    {
      label: 'top_p',
      value: String(s.topP),
      hint: 'Nucleus sampling: модель выбирает из наиболее вероятных токенов, пока их суммарная вероятность не достигнет top_p; 1 — без отсечения.',
    },
    {
      label: 'top_k',
      value: s.topK == null ? '—' : String(s.topK),
      hint: 'Модель выбирает только из K самых вероятных токенов на каждом шаге; «—» — не задано, параметр не уходит в API.',
    },
    {
      label: 'лимит токенов',
      value: s.maxTokens == null ? '—' : String(s.maxTokens),
      hint: 'Максимум токенов на один ответ (max_tokens) — защита от бесконечной генерации; «—» — без лимита.',
    },
    {
      label: 'инструменты',
      value: s.tools.join(', '),
      hint: 'Функции, которые модель может вызывать сама по ходу ответа (например, калькулятор); их список уходит в API.',
    },
    {
      label: 'макс. итераций',
      value: String(s.maxToolCallIterations),
      hint: 'Предел цикла «LLM → инструменты → LLM» на один запрос — защита от зацикливания.',
    },
  ];
}

export default function StepsLog({ steps, runSettings }: StepsLogProps) {
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
      </header>
      {runSettings ? (
        <div className="llm-settings" title="Применённые настройки LLM (GET /api/llm-settings при открытии, agent_started на каждом запуске)">
          <span className="llm-settings-title">Настройки LLM</span>
          {settingsItems(runSettings).map((it) => (
            <span className="llm-settings-item" key={it.label} title={it.hint}>
              {it.label}: <b>{it.value}</b>
            </span>
          ))}
        </div>
      ) : null}
      <div className="steps-list" ref={listRef}>
        {steps.length === 0 ? (
          <div className="steps-empty">Шагов пока нет — отправьте сообщение в чат.</div>
        ) : (
          steps.map((s) => <StepRow key={s.id} step={s} />)
        )}
      </div>
    </section>
  );
}
