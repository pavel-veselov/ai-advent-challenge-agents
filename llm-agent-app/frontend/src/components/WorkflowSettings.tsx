import { useState } from 'react';
import type { WorkflowMode, WorkflowSettings as WorkflowSettingsType } from '../types';

type SaveStatus = 'idle' | 'saving' | 'error';

/**
 * Режимы воркфлоу: подпись + короткое описание поведения агента.
 * Ручное — пауза на границе этапа и подтверждение кнопками «Продолжить»/«Отмена» в чате;
 * Авто — все этапы подряд за один запуск, подтверждение не требуется.
 */
const MODE_OPTIONS: { value: WorkflowMode; label: string; description: string }[] = [
  { value: 'manual', label: 'Ручное', description: 'подтверждение на каждом этапе' },
  { value: 'auto', label: 'Авто', description: 'все этапы за один запуск' },
];

/** Пояснение активного режима (под свитчером выбора) — что именно сделает агент. */
function modeHint(enabled: boolean, mode: WorkflowMode): string {
  if (!enabled) return 'Воркфлоу выключен — агент ведёт обычный диалог, без этапов и подтверждений';
  return mode === 'manual'
    ? 'Ручное: агент ждёт подтверждения на границе этапа — кнопки «Продолжить»/«Отмена» появятся в чате'
    : 'Авто: агент выполняет все этапы подряд за один запуск — подтверждение не требуется';
}

interface WorkflowSettingsProps {
  /** Текущие настройки воркфлоу (GET /api/workflow-settings); null — ещё не загружены. */
  settings: WorkflowSettingsType | null;
  /** Сохранение изменений: PUT /api/workflow-settings (полный набор). Ошибка бросает. */
  onChange: (next: WorkflowSettingsType) => Promise<WorkflowSettingsType>;
  /** true — сервис остановлен/запускается: переключатель и режим заблокированы. */
  disabled: boolean;
}

/**
 * Блок настроек human-in-the-loop воркфлоу (Day-14) в правой колонке (над
 * «Настройками LLM»). Приборная карточка в стиле блока профиля/настроек LLM:
 * рубильник «Следовать воркфлоу» (общий .llm-switch, как у thinking) и сегментированный
 * выбор режима «Ручное/Авто» (в стиле свитчера стратегии контекста). Селектор режима
 * активен только при включённом воркфлоу; под ним — пояснение выбранного режима.
 * При выключенном воркфлоу агент ведёт себя как раньше. Изменения сохраняются сразу
 * (PUT); ошибка показывается инлайном, без alert.
 */
export default function WorkflowSettings({
  settings,
  onChange,
  disabled,
}: WorkflowSettingsProps) {
  const [saveState, setSaveState] = useState<SaveStatus>('idle');
  const [error, setError] = useState<string | null>(null);

  const busy = saveState === 'saving';
  const enabled = settings?.enabled ?? false;
  const mode = settings?.mode ?? 'manual';

  const save = (next: WorkflowSettingsType) => {
    setSaveState('saving');
    setError(null);
    onChange({ ...next })
      .then(() => setSaveState('idle'))
      .catch((err: unknown) => {
        setSaveState('error');
        setError(err instanceof Error ? err.message : String(err));
      });
  };

  const handleEnabledChange = () => {
    if (settings == null || busy) return;
    save({ ...settings, enabled: !enabled });
  };

  const handleModeChange = (nextMode: WorkflowMode) => {
    if (settings == null || busy) return;
    save({ ...settings, mode: nextMode });
  };

  return (
    <section className="workflow-settings-block">
      <header className="llm-settings-block-header">
        <h2>Воркфлоу</h2>
        <span className="llm-hint">планирование → выполнение → проверка → готово</span>
        {saveState !== 'idle' ? (
          <span className={`llm-save-state is-${saveState}`} role="status" aria-live="polite">
            {saveState === 'saving' ? 'сохранение…' : 'ошибка'}
          </span>
        ) : null}
      </header>

      {/* Рубильник воркфлоу: общий с thinking переключатель .llm-switch + подпись с описанием */}
      <div className="workflow-main-row">
        <span className="workflow-main-text">
          <span
            className="workflow-main-title"
            title="Следовать воркфлоу: агент идёт по этапам и ждёт подтверждения (ручной) или проходит их подряд (авто)"
          >
            Следовать воркфлоу
          </span>
          <span className="llm-hint">этапы задачи и подтверждение переходов</span>
        </span>
        <button
          type="button"
          role="switch"
          aria-checked={enabled}
          aria-label="Следовать воркфлоу"
          className={`llm-switch${enabled ? ' is-on' : ''}`}
          disabled={disabled || busy || settings == null}
          onClick={handleEnabledChange}
        >
          <span className="llm-switch-knob" />
        </button>
      </div>

      {/* Режим воркфлоу: сегментированный выбор, активен только при включённом воркфлоу */}
      <div
        className="workflow-mode-picker"
        role="radiogroup"
        aria-label="Режим воркфлоу"
        title={enabled ? undefined : 'Включите воркфлоу, чтобы выбрать режим'}
      >
        {MODE_OPTIONS.map((option) => (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={mode === option.value}
            className={`workflow-mode-option${mode === option.value ? ' is-active' : ''}`}
            disabled={disabled || busy || !enabled}
            title={option.description}
            onClick={() => handleModeChange(option.value)}
          >
            <span className="workflow-mode-name">{option.label}</span>
            <span className="workflow-mode-desc">{option.description}</span>
          </button>
        ))}
      </div>
      <div className="llm-hint">{modeHint(enabled, mode)}</div>

      {error != null ? (
        <div className="llm-error-line" role="alert">
          не удалось применить воркфлоу: {error}
        </div>
      ) : null}
    </section>
  );
}
