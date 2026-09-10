import { useEffect, useRef, useState } from 'react';
import type { RunSettings } from '../types';

/**
 * Каталог моделей UI: id -> окно контекста в токенах (для подписи «NNN K» и отправки
 * contextLimit в PUT при смене модели). Порядок опций — как в ТЗ.
 */
const MODEL_CATALOG = [
  { model: 'glm-5.3-flash', contextLimit: 262144 },
  { model: 'qwen3.8-27b', contextLimit: 202752 },
  { model: 'deepseek-v4-flash', contextLimit: 1048576 },
] as const;

/** Окно контекста в «K» (1K = 1024 токена): 262144 → «256K». */
function contextSizeLabel(limit: number): string {
  return `${Math.round(limit / 1024)}K`;
}

/** число | null/undefined → строка черновика (пустая = не задано). */
function numStr(v: number | null | undefined): string {
  return v != null ? String(v) : '';
}

type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';

interface SaveState {
  status: SaveStatus;
  message: string | null;
}

interface NumFieldProps {
  label: string;
  /** Подсказка-пояснение (title нативный). */
  title: string;
  value: string;
  /** Единица/подсказка справа внутри рамки: «токенов», «сек», «0–2». */
  unit?: string;
  /** Префикс слева внутри рамки: «$» у тарифов. */
  prefix?: string;
  placeholder?: string;
  step?: string;
  min?: string;
  max?: string;
  disabled: boolean;
  onChange: (v: string) => void;
  /** Коммит значения (blur / Enter). */
  onCommit: () => void;
}

/** Числовое поле: подпись сверху, моноширинное значение, единицы внутри рамки ввода. */
function NumField({
  label,
  title,
  value,
  unit,
  prefix,
  placeholder,
  step,
  min,
  max,
  disabled,
  onChange,
  onCommit,
}: NumFieldProps) {
  return (
    <label className="llm-field" title={title}>
      <span className="llm-field-label">{label}</span>
      <span className="llm-num">
        {prefix != null ? <span className="llm-num-affix">{prefix}</span> : null}
        <input
          className="llm-num-input"
          type="number"
          inputMode="decimal"
          step={step}
          min={min}
          max={max}
          placeholder={placeholder}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
          onBlur={onCommit}
          onKeyDown={(e) => {
            if (e.key === 'Enter') e.currentTarget.blur();
          }}
        />
        {unit != null ? <span className="llm-num-affix">{unit}</span> : null}
      </span>
    </label>
  );
}

interface LlmSettingsProps {
  /** Применённые настройки (GET /api/llm-settings при открытии + agent_started на каждом запуске); null — не загружены. */
  settings: RunSettings | null;
  /** true — сервис занят/выключен: редактирование заблокировано. */
  disabled: boolean;
  /** PUT настроек + обновление state (contextLimit пересчитывается в StatsBar). Может бросить — тогда поля откатываются. */
  onUpdate: (patch: Partial<RunSettings>) => Promise<unknown>;
}

/**
 * Блок настроек LLM над логом шагов. Группы: модель (сегментированный выбор с окном
 * контекста + провайдер-чип), генерация (temperature/top_p/top_k/лимит токенов + переключатель
 * рассуждений thinking, для glm-* заблокирован), запрос (таймаут), тарифы ($ за 1M). PUT уходит
 * на каждое изменение (как раньше; reasoningEnabled — с каждым PUT); в шапке — индикатор
 * «сохранение… / сохранено / ошибка», при неудаче черновики откатываются.
 */
export default function LlmSettings({ settings, disabled, onUpdate }: LlmSettingsProps) {
  // Локальные черновики редактируемых полей; синхронизируются с применёнными настройками.
  const [model, setModel] = useState(settings?.model ?? '');
  const [temperature, setTemperature] = useState(numStr(settings?.temperature));
  const [topP, setTopP] = useState(numStr(settings?.topP));
  const [topK, setTopK] = useState(numStr(settings?.topK));
  const [maxTokens, setMaxTokens] = useState(numStr(settings?.maxTokens));
  const [timeoutSeconds, setTimeoutSeconds] = useState(numStr(settings?.timeoutSeconds));
  const [priceInput, setPriceInput] = useState(numStr(settings?.priceInputPer1M));
  const [priceOutput, setPriceOutput] = useState(numStr(settings?.priceOutputPer1M));
  /** Переключатель рассуждений (thinking); отсутствует в старых ответах — трактуем как true. */
  const [reasoning, setReasoning] = useState(settings?.reasoningEnabled ?? true);
  const [saveState, setSaveState] = useState<SaveState>({ status: 'idle', message: null });

  // Последние известные применённые настройки (для отката черновиков при ошибке PUT).
  const settingsRef = useRef<RunSettings | null>(settings);
  /** Оптимистичное значение переключателя — уходит в тело каждого PUT. */
  const reasoningRef = useRef<boolean>(settings?.reasoningEnabled ?? true);
  // PUT-ы сериализуются: следующий уходит после завершения предыдущего (без гонок ответов).
  const queueRef = useRef<Promise<unknown>>(Promise.resolve());
  const pendingRef = useRef(0);
  const savedTimerRef = useRef<number | null>(null);

  const applyDrafts = (s: RunSettings | null) => {
    setModel(s?.model ?? '');
    setTemperature(numStr(s?.temperature));
    setTopP(numStr(s?.topP));
    setTopK(numStr(s?.topK));
    setMaxTokens(numStr(s?.maxTokens));
    setTimeoutSeconds(numStr(s?.timeoutSeconds));
    setPriceInput(numStr(s?.priceInputPer1M));
    setPriceOutput(numStr(s?.priceOutputPer1M));
    const nextReasoning = s?.reasoningEnabled ?? true;
    setReasoning(nextReasoning);
    reasoningRef.current = nextReasoning;
  };

  useEffect(() => {
    settingsRef.current = settings;
    // Пока PUT в полёте — не затираем черновики ответом сервера (sync после завершения).
    if (pendingRef.current > 0) return;
    applyDrafts(settings);
  }, [settings]);

  useEffect(
    () => () => {
      if (savedTimerRef.current != null) window.clearTimeout(savedTimerRef.current);
    },
    [],
  );

  const armSavedTimer = () => {
    if (savedTimerRef.current != null) window.clearTimeout(savedTimerRef.current);
    savedTimerRef.current = window.setTimeout(() => {
      setSaveState((prev) => (prev.status === 'saved' ? { status: 'idle', message: null } : prev));
    }, 2000);
  };

  /**
   * PUT изменений в очередь (сериализованно); при неудаче черновики откатываются
   * к применённым настройкам и показывается инлайн-ошибка (без alert()).
   */
  const enqueue = (patch: Partial<RunSettings>) => {
    if (savedTimerRef.current != null) {
      window.clearTimeout(savedTimerRef.current);
      savedTimerRef.current = null;
    }
    pendingRef.current += 1;
    setSaveState({ status: 'saving', message: null });
    // reasoningEnabled уходит с каждым PUT рядом с прочими изменёнными полями
    // (контракт: boolean = установить; переключатель участвует в каждом применении).
    const body: Partial<RunSettings> = { reasoningEnabled: reasoningRef.current, ...patch };
    queueRef.current = queueRef.current.then(() =>
      onUpdate(body).then(
        () => {
          pendingRef.current -= 1;
          if (pendingRef.current === 0) {
            setSaveState({ status: 'saved', message: null });
            armSavedTimer();
          }
        },
        (err: unknown) => {
          pendingRef.current -= 1;
          applyDrafts(settingsRef.current);
          setSaveState({
            status: 'error',
            message: err instanceof Error ? err.message : String(err),
          });
        },
      ),
    );
  };

  const editable = !disabled && settings != null;
  const busy = saveState.status === 'saving';
  /** Для glm-* шлюз оставляет рассуждения принудительно — переключатель не редактируется. */
  const isGlmModel = model.trim().toLowerCase().startsWith('glm');

  const commitModel = (value: string) => {
    if (!editable || busy) return;
    setModel(value);
    const entry = MODEL_CATALOG.find((x) => x.model === value);
    enqueue({ model: value, contextLimit: entry?.contextLimit });
  };

  /**
   * Переключатель рассуждений: значение применяем оптимистично, PUT уходит в общую
   * очередь; при ошибке applyDrafts откатит переключатель к серверной истине.
   */
  const commitReasoning = (next: boolean) => {
    if (!editable || busy || next === reasoning) return;
    setReasoning(next);
    reasoningRef.current = next;
    enqueue({ reasoningEnabled: next });
  };

  /** Обязательное число: пусто/не число — черновик откатывается, PUT не уходит. */
  const commitFloat = (
    draft: string,
    revert: () => void,
    build: (n: number) => Partial<RunSettings>,
  ) => {
    const v = draft.trim();
    const n = Number(v);
    if (v === '' || !Number.isFinite(n)) {
      revert();
      return;
    }
    enqueue(build(n));
  };

  const commitTemperature = () =>
    commitFloat(
      temperature,
      () => setTemperature(numStr(settingsRef.current?.temperature)),
      (n) => ({ temperature: n }),
    );

  const commitTopP = () =>
    commitFloat(
      topP,
      () => setTopP(numStr(settingsRef.current?.topP)),
      (n) => ({ topP: n }),
    );

  const commitTopK = () => {
    const v = topK.trim();
    if (v === '') {
      enqueue({ topK: null });
      return;
    }
    const n = Number(v);
    if (!Number.isFinite(n)) {
      setTopK(numStr(settingsRef.current?.topK));
      return;
    }
    enqueue({ topK: Math.round(n) });
  };

  const commitMaxTokens = () => {
    const v = maxTokens.trim();
    if (v === '') {
      enqueue({ maxTokens: null });
      return;
    }
    const n = Number(v);
    if (!Number.isFinite(n)) {
      setMaxTokens(numStr(settingsRef.current?.maxTokens));
      return;
    }
    enqueue({ maxTokens: Math.round(n) });
  };

  const commitTimeout = () =>
    commitFloat(
      timeoutSeconds,
      () => setTimeoutSeconds(numStr(settingsRef.current?.timeoutSeconds)),
      (n) => ({ timeoutSeconds: n }),
    );

  // Тариф: пусто — не трогаем (контракт не позволяет «сбросить» в null), не число — откат.
  const commitPriceInput = () => {
    const v = priceInput.trim();
    const n = Number(v);
    if (v === '' || !Number.isFinite(n)) {
      setPriceInput(numStr(settingsRef.current?.priceInputPer1M));
      return;
    }
    enqueue({ priceInputPer1M: n });
  };

  const commitPriceOutput = () => {
    const v = priceOutput.trim();
    const n = Number(v);
    if (v === '' || !Number.isFinite(n)) {
      setPriceOutput(numStr(settingsRef.current?.priceOutputPer1M));
      return;
    }
    enqueue({ priceOutputPer1M: n });
  };

  return (
    <section className="llm-settings-block">
      <header className="llm-settings-block-header">
        <h2>Настройки LLM</h2>
        {saveState.status !== 'idle' ? (
          <span
            className={`llm-save-state is-${saveState.status}`}
            role="status"
            aria-live="polite"
          >
            {saveState.status === 'saving'
              ? 'сохранение…'
              : saveState.status === 'saved'
                ? 'сохранено'
                : 'ошибка'}
          </span>
        ) : null}
      </header>

      {saveState.status === 'error' ? (
        <div className="llm-error-line" role="alert">
          не удалось сохранить{saveState.message != null ? `: ${saveState.message}` : ''}
        </div>
      ) : null}

      <div className="llm-group">
        <div className="llm-group-label">модель</div>
        <div className="llm-model-picker" role="radiogroup" aria-label="Модель генерации">
          {MODEL_CATALOG.map((x) => (
            <button
              key={x.model}
              type="button"
              role="radio"
              aria-checked={model === x.model}
              className={`llm-model-option${model === x.model ? ' is-active' : ''}`}
              disabled={!editable || busy}
              title={`${x.model}: окно контекста ${x.contextLimit} токенов`}
              onClick={() => commitModel(x.model)}
            >
              <span className="llm-model-name">{x.model}</span>
              <span className="llm-model-ctx">{contextSizeLabel(x.contextLimit)}</span>
            </button>
          ))}
        </div>
        <div
          className="llm-provider-row"
          title="Провайдер задаётся на сервере (переменные окружения) и не редактируется"
        >
          <span className="llm-field-label">провайдер</span>
          <span className="llm-provider-chip">{settings?.provider ?? '…'}</span>
        </div>
      </div>

      <div className="llm-group">
        <div className="llm-group-label">генерация</div>
        <div className="llm-fields-row">
          <NumField
            label="temperature"
            title="Насколько смело модель отклоняется от самого вероятного слова (0–2)"
            value={temperature}
            unit="0–2"
            step="0.1"
            min="0"
            max="2"
            disabled={!editable}
            onChange={setTemperature}
            onCommit={commitTemperature}
          />
          <NumField
            label="top_p"
            title="Nucleus sampling: доля вероятностной массы, из которой выбирает модель (0–1)"
            value={topP}
            unit="0–1"
            step="0.05"
            min="0"
            max="1"
            disabled={!editable}
            onChange={setTopP}
            onCommit={commitTopP}
          />
          <NumField
            label="top_k"
            title="Top-k sampling; пусто — параметр не уходит в API"
            value={topK}
            placeholder="—"
            step="1"
            min="0"
            disabled={!editable}
            onChange={setTopK}
            onCommit={commitTopK}
          />
          <NumField
            label="макс. ответ"
            title="Максимум токенов на один ответ (max_tokens); пусто — без лимита"
            value={maxTokens}
            unit="токенов"
            placeholder="—"
            step="1"
            min="1"
            disabled={!editable}
            onChange={setMaxTokens}
            onCommit={commitMaxTokens}
          />
        </div>
        <div className="llm-reasoning-row">
          <span className="llm-field-label">Рассуждения (thinking)</span>
          <button
            type="button"
            role="switch"
            aria-checked={reasoning}
            aria-label="Рассуждения (thinking)"
            className={`llm-switch${reasoning ? ' is-on' : ''}`}
            disabled={!editable || busy || isGlmModel}
            title={
              isGlmModel
                ? 'Разрешать модели рассуждать перед ответом (для glm-* шлюз оставляет рассуждения принудительно)'
                : 'Разрешать модели рассуждать перед ответом (шлюз шлёт enable_thinking)'
            }
            onClick={() => commitReasoning(!reasoning)}
          >
            <span className="llm-switch-knob" />
          </button>
        </div>
        {isGlmModel ? (
          <div className="llm-hint">Для glm-* шлюз оставляет рассуждения принудительно</div>
        ) : null}
      </div>

      <div className="llm-groups-row">
        <div className="llm-group">
          <div className="llm-group-label">запрос</div>
          <NumField
            label="таймаут"
            title="Таймаут запроса к LLM в секундах"
            value={timeoutSeconds}
            unit="сек"
            step="1"
            min="1"
            disabled={!editable}
            onChange={setTimeoutSeconds}
            onCommit={commitTimeout}
          />
        </div>
        <div className="llm-group">
          <div className="llm-group-label">тарифы, $ за 1M</div>
          <div className="llm-fields-row two">
            <NumField
              label="вход"
              title="Тариф входных токенов, $ за 1M (для расчёта стоимости, если бэкенд не прислал costUsd)"
              value={priceInput}
              prefix="$"
              step="0.01"
              min="0"
              disabled={!editable}
              onChange={setPriceInput}
              onCommit={commitPriceInput}
            />
            <NumField
              label="выход"
              title="Тариф выходных токенов, $ за 1M (для расчёта стоимости)"
              value={priceOutput}
              prefix="$"
              step="0.01"
              min="0"
              disabled={!editable}
              onChange={setPriceOutput}
              onCommit={commitPriceOutput}
            />
          </div>
        </div>
      </div>
    </section>
  );
}
