import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import CollapsibleSection from './CollapsibleSection';
import {
  clearLongTermMemory,
  fetchSessionCompression,
  fetchSessionLlmSettings,
  updateSessionCompression,
  updateSessionLlmSettings,
} from '../api';
import type {
  ContextStrategy,
  MemoryState,
  RunSettings,
  SessionCompression,
  SessionContextStrategyPatch,
  SessionLlmSettings,
  SessionLlmSettingsPatch,
} from '../types';

/**
 * Каталог моделей UI: id -> окно контекста в токенах (для подписи «NNN K»).
 * contextLimit в PUT не уходит — бэкенд выводит его из каталога по модели.
 * Порядок опций — как в ТЗ.
 */
const MODEL_CATALOG = [
  { model: 'glm-5.3-flash', contextLimit: 262144 },
  { model: 'qwen3.8-27b', contextLimit: 202752 },
  { model: 'deepseek-v4-flash', contextLimit: 1048576 },
] as const;

/**
 * Дефолты сжатия контекста (совпадают с серверными): показываются, пока GET не вернул
 * сохранённые значения и когда активной сессии нет (к ней применить нечего).
 */
const COMPRESSION_DEFAULTS = { enabled: false, keepLast: 5, summaryEvery: 10 } as const;
/** Допустимые диапазоны (валидация дублируется на сервере: keepLast 1..50, summaryEvery 2..100). */
const KEEP_LAST_MIN = 1;
const KEEP_LAST_MAX = 50;
const SUMMARY_EVERY_MIN = 2;
const SUMMARY_EVERY_MAX = 100;

/** Допустимый диапазон окна последних сообщений скользящего окна/фактов (1..50, как на сервере). */
const WINDOW_MIN = 1;
const WINDOW_MAX = 50;
/** Дефолт окна до ответа GET /context-strategy. */
const WINDOW_DEFAULT = 10;

/**
 * Варианты стратегии контекста — свитчер вместо тумблера сжатия. Короткая метка + описание.
 * Порядок — как в ТЗ: none → sliding_window → sticky_facts → summary → branching.
 */
const STRATEGY_OPTIONS: { value: ContextStrategy; label: string; description?: string }[] = [
  { value: 'none', label: 'Полная история' },
  { value: 'sliding_window', label: 'Скользящее окно', description: 'последние N сообщений' },
  { value: 'sticky_facts', label: 'Факты + окно', description: 'ключевые факты диалога + N сообщений' },
  { value: 'summary', label: 'Резюме + хвост', description: 'сжатие старых сообщений в summary' },
  { value: 'branching', label: 'Ветки диалога', description: 'развилки истории с точки ветвления' },
];

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

/** Общий источник черновиков: RunSettings (глобал) или SessionLlmSettings (сессия). */
type DraftSource = Pick<
  RunSettings,
  | 'model'
  | 'temperature'
  | 'topP'
  | 'topK'
  | 'maxTokens'
  | 'timeoutSeconds'
  | 'priceInputPer1M'
  | 'priceOutputPer1M'
  | 'reasoningEnabled'
>;

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
          onBlur={() => onCommit()}
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
  /** Глобальные настройки (GET /api/llm-settings + agent_started); в сессионном режиме — только чип провайдера и показ при отсутствии сессии. */
  settings: RunSettings | null;
  /** id активной сессии; null — пустое состояние: все поля заблокированы (применять не к чему). */
  sessionId: string | null;
  /** true — сервис занят/выключен: редактирование заблокировано. */
  disabled: boolean;
  /** Стратегия контекста активной сессии (зеркало хука; управляется свитчером ниже). */
  strategy: ContextStrategy;
  /** Размер окна последних сообщений активной сессии (sliding_window/sticky_facts). */
  windowSize: number;
  /** Факты диалога активной сессии (GET /facts + события facts_updated); null — не загружены. */
  facts: Record<string, string> | null;
  /**
   * Смена стратегии/окна активной сессии: PUT /context-strategy через хук (оптимистично,
   * откатывается при ошибке). Резолвится после подтверждения бэкендом.
   */
  onChangeStrategy: (patch: SessionContextStrategyPatch) => Promise<void>;
  /**
   * Снапшот памяти (App: GET /api/projects/{id}/memory): список долговременной памяти
   * в группе «долговременная память». LTM глобальна (все проекты/сессии); null — не загружен.
   */
  memory: MemoryState | null;
  /** Вызывается после успешной очистки LTM — App перечитывает снапшот памяти. */
  onMemoryCleared?: () => void;
}

/**
 * Блок настроек LLM над логом шагов. Все редактируемые поля привязаны к активной сессии
 * (GET/PUT /api/sessions/{id}/llm-settings): у каждой сессии свой набор (модель, генерация,
 * таймаут, тарифы, thinking); бэкенд отдаёт эффективный набор — переопределения сессии
 * поверх текущих глобальных значений. Без активной сессии блок показывает глобальные
 * значения, но редактирование заблокировано (применять не к чему). PUT уходит на каждое
 * изменение (reasoningEnabled — с каждым PUT); в шапке — метка сессии и индикатор
 * «сохранение… / сохранено / ошибка», при неудаче черновики откатываются.
 * Провайдер задаётся на сервере и показывается отдельным чипом.
 * Первичный контроль контекста — свитчер «Стратегия контекста» (GET/PUT /context-strategy
 * через хук): none / sliding_window / sticky_facts / summary / branching. Поля окна (1..50)
 * показываются для sliding_window и sticky_facts; keepLast+summaryEvery — только для summary
 * и по-прежнему сохраняются через старый GET/PUT /compression (бэкенд синхронизирует его
 * со стратегией). В sticky_facts под свитчером живёт панель фактов диалога.
 */
export default function LlmSettings({
  settings,
  sessionId,
  disabled,
  strategy,
  windowSize,
  facts,
  onChangeStrategy,
  memory,
  onMemoryCleared,
}: LlmSettingsProps) {
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
  /** Индикатор очистки долговременной памяти (saving/saved/error; saved гаснет через 2 с). */
  const [clearState, setClearState] = useState<SaveState>({ status: 'idle', message: null });
  const clearTimerRef = useRef<number | null>(null);

  // Последние известные глобальные настройки (для показа без сессии и отката в глобальном режиме).
  const settingsRef = useRef<RunSettings | null>(settings);
  // Эффективные настройки активной сессии; null — GET ещё не отработал/не удался
  // (в UI это не влияет: редактирование доступно при любой активной сессии).
  const sessionSettingsRef = useRef<SessionLlmSettings | null>(null);
  /** Актуальный sessionId для отбрасывания устаревших ответов GET/PUT после смены сессии. */
  const sessionIdRef = useRef<string | null>(sessionId);
  /** Оптимистичное значение переключателя — уходит в тело каждого PUT. */
  const reasoningRef = useRef<boolean>(settings?.reasoningEnabled ?? true);
  // PUT-ы сериализуются: следующий уходит после завершения предыдущего (без гонок ответов).
  const queueRef = useRef<Promise<unknown>>(Promise.resolve());
  const pendingRef = useRef(0);
  const savedTimerRef = useRef<number | null>(null);

  const applyDrafts = (s: DraftSource | null) => {
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

  /**
   * Источник истины для отката черновиков: сессия — настройки сессии; пока GET не ответил
   * или не удался — глобальные значения (у свежей сессии переопределений ещё нет).
   */
  const currentEffective = (): DraftSource | null =>
    sessionIdRef.current != null
      ? (sessionSettingsRef.current ?? settingsRef.current)
      : settingsRef.current;

  useEffect(() => {
    settingsRef.current = settings;
    // В сессионном режиме черновики ведёт session-эффект: глобальный agent_started
    // их не затирает (у сессии свой набор настроек).
    if (sessionId != null) return;
    // Пока PUT в полёте — не затираем черновики ответом сервера (sync после завершения).
    if (pendingRef.current > 0) return;
    applyDrafts(settings);
  }, [settings, sessionId]);

  useEffect(
    () => () => {
      if (savedTimerRef.current != null) window.clearTimeout(savedTimerRef.current);
    },
    [],
  );

  useEffect(
    () => () => {
      if (clearTimerRef.current != null) window.clearTimeout(clearTimerRef.current);
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
   * PUT изменений в очередь (сериализованно) — на эндпоинт активной сессии; при неудаче
   * черновики откатываются к эффективному набору и показывается инлайн-ошибка (без alert()).
   * Без активной сессии недостижимо (editable = false блокирует все поля).
   */
  const enqueue = (patch: SessionLlmSettingsPatch) => {
    const sid = sessionIdRef.current;
    if (sid == null) return;
    if (savedTimerRef.current != null) {
      window.clearTimeout(savedTimerRef.current);
      savedTimerRef.current = null;
    }
    pendingRef.current += 1;
    setSaveState({ status: 'saving', message: null });
    // reasoningEnabled уходит с каждым PUT рядом с прочими изменёнными полями
    // (контракт: boolean = установить; переключатель участвует в каждом применении).
    const body: SessionLlmSettingsPatch = { reasoningEnabled: reasoningRef.current, ...patch };
    queueRef.current = queueRef.current.then(() =>
      updateSessionLlmSettings(sid, body).then(
        (next) => {
          pendingRef.current -= 1;
          // Сессия сменилась, пока PUT был в полёте — ответ чужой сессии не применяем.
          if (sessionIdRef.current !== sid) return;
          sessionSettingsRef.current = next;
          if (pendingRef.current === 0) {
            setSaveState({ status: 'saved', message: null });
            armSavedTimer();
          }
        },
        (err: unknown) => {
          pendingRef.current -= 1;
          if (sessionIdRef.current !== sid) return;
          applyDrafts(currentEffective());
          setSaveState({
            status: 'error',
            message: err instanceof Error ? err.message : String(err),
          });
        },
      ),
    );
  };

  // Редактирование доступно сразу после создания сессии («+»): GET мог ещё не ответить —
  // черновики уже можно менять, PUT сохранит переопределения сессии на бэкенде.
  const editable = !disabled && sessionId != null;
  const busy = saveState.status === 'saving';
  /** Для glm-* шлюз оставляет рассуждения принудительно — переключатель не редактируется. */
  const isGlmModel = model.trim().toLowerCase().startsWith('glm');

  const commitModel = (value: string) => {
    if (!editable || busy) return;
    setModel(value);
    // contextLimit следует модели из каталога на бэкенде — в PUT уходит только model.
    enqueue({ model: value });
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
    build: (n: number) => SessionLlmSettingsPatch,
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
      () => setTemperature(numStr(currentEffective()?.temperature)),
      (n) => ({ temperature: n }),
    );

  const commitTopP = () =>
    commitFloat(
      topP,
      () => setTopP(numStr(currentEffective()?.topP)),
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
      setTopK(numStr(currentEffective()?.topK));
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
      setMaxTokens(numStr(currentEffective()?.maxTokens));
      return;
    }
    enqueue({ maxTokens: Math.round(n) });
  };

  const commitTimeout = () =>
    commitFloat(
      timeoutSeconds,
      () => setTimeoutSeconds(numStr(currentEffective()?.timeoutSeconds)),
      (n) => ({ timeoutSeconds: n }),
    );

  // Тариф: пусто — не трогаем (контракт не позволяет «сбросить» в null), не число — откат.
  const commitPriceInput = () => {
    const v = priceInput.trim();
    const n = Number(v);
    if (v === '' || !Number.isFinite(n)) {
      setPriceInput(numStr(currentEffective()?.priceInputPer1M));
      return;
    }
    enqueue({ priceInputPer1M: n });
  };

  const commitPriceOutput = () => {
    const v = priceOutput.trim();
    const n = Number(v);
    if (v === '' || !Number.isFinite(n)) {
      setPriceOutput(numStr(currentEffective()?.priceOutputPer1M));
      return;
    }
    enqueue({ priceOutputPer1M: n });
  };

  // --- Сжатие контекста (per-session: GET при монтировании/смене сессии, автосохранение PUT-ом) ---
  /** Черновики секции: переключатель + два числа; синхронизируются с ответами GET/PUT. */
  const [compEnabled, setCompEnabled] = useState<boolean>(COMPRESSION_DEFAULTS.enabled);
  const [compKeepLast, setCompKeepLast] = useState(String(COMPRESSION_DEFAULTS.keepLast));
  const [compSummaryEvery, setCompSummaryEvery] = useState(String(COMPRESSION_DEFAULTS.summaryEvery));
  const [compSave, setCompSave] = useState<SaveState>({ status: 'idle', message: null });
  /** Черновик окна последних сообщений (sliding_window/sticky_facts); синхронизируется с ответами PUT/сменой сессии. */
  const [winSizeDraft, setWinSizeDraft] = useState(String(windowSize ?? WINDOW_DEFAULT));
  /** Последние известные применённые настройки сжатия (для отката черновиков). */
  const compStateRef = useRef<SessionCompression | null>(null);
  const compSavedTimerRef = useRef<number | null>(null);

  const applyCompDrafts = (s: SessionCompression | null) => {
    setCompEnabled(s?.enabled ?? COMPRESSION_DEFAULTS.enabled);
    setCompKeepLast(String(s?.keepLast ?? COMPRESSION_DEFAULTS.keepLast));
    setCompSummaryEvery(String(s?.summaryEvery ?? COMPRESSION_DEFAULTS.summaryEvery));
  };

  // Загрузка значений активной сессии (настройки LLM + сжатие): при монтировании и на
  // каждую смену сессии. Без активной сессии — глобальные значения для показа (редактирование
  // заблокировано) и дефолты сжатия; свежая сессия без сообщений получает глобальный
  // эффективный набор (GET), редактирование доступно сразу.
  useEffect(() => {
    sessionIdRef.current = sessionId;
    if (sessionId == null) {
      compStateRef.current = null;
      sessionSettingsRef.current = null;
      applyCompDrafts(null);
      setCompSave({ status: 'idle', message: null });
      if (pendingRef.current === 0) applyDrafts(settingsRef.current);
      setSaveState({ status: 'idle', message: null });
      return;
    }
    let cancelled = false;
    fetchSessionLlmSettings(sessionId)
      .then((s) => {
        if (cancelled) return;
        sessionSettingsRef.current = s;
        // Пока PUT в полёте — не затираем черновики (ответ PUT применит свежий набор).
        if (pendingRef.current === 0) applyDrafts(s);
      })
      .catch(() => {
        if (cancelled) return;
        // Эндпоинт недоступен (бэкенд ещё не поднялся / сеть) — показываем глобальные
        // значения как черновик; редактирование остаётся доступным, PUT вернёт ошибку инлайном.
        sessionSettingsRef.current = null;
        if (pendingRef.current === 0) applyDrafts(settingsRef.current);
      });
    fetchSessionCompression(sessionId)
      .then((s) => {
        if (cancelled) return;
        compStateRef.current = s;
        applyCompDrafts(s);
      })
      .catch(() => {
        if (cancelled) return;
        // Эндпоинт недоступен или сессия ещё не заведена на бэкенде — показываем дефолты.
        compStateRef.current = null;
        applyCompDrafts(null);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useEffect(
    () => () => {
      if (compSavedTimerRef.current != null) window.clearTimeout(compSavedTimerRef.current);
    },
    [],
  );

  // Черновик окна следует серверной истине (ответы PUT /context-strategy, смена сессии).
  useEffect(() => {
    setWinSizeDraft(String(windowSize ?? WINDOW_DEFAULT));
  }, [windowSize]);

  const armCompSavedTimer = () => {
    if (compSavedTimerRef.current != null) window.clearTimeout(compSavedTimerRef.current);
    compSavedTimerRef.current = window.setTimeout(() => {
      setCompSave((prev) => (prev.status === 'saved' ? { status: 'idle', message: null } : prev));
    }, 2000);
  };

  const compDisabled = disabled || sessionId == null;
  const compBusy = compSave.status === 'saving';

  /**
   * Автосохранение: PUT {keepLast, summaryEvery}; поле enabled уходит только от тумблера.
   * Вызывается тумблером (с явным nextEnabled, применяется оптимистично) и числовыми полями
   * (blur/Enter, без аргумента — enabled в PUT не попадает: бэкенд применяет только переданные
   * поля, поэтому серверное enabled, выставленное сменой стратегии, не затирается устаревшим
   * значением черновика). Значения вне диапазона —
   * черновики откатываются (тумблер тоже), инлайн-ошибка, PUT не уходит; ошибка сети/бэкенда —
   * откат к последним серверным значениям и инлайн-ошибка.
   */
  const commitCompression = (nextEnabled?: boolean) => {
    const sid = sessionIdRef.current;
    if (sid == null || compBusy) return;
    // Оптимистично двигаем тумблер только при явном boolean (клик по переключателю).
    if (typeof nextEnabled === 'boolean' && nextEnabled !== compEnabled) {
      setCompEnabled(nextEnabled);
    }
    const kl = Number(compKeepLast.trim());
    const se = Number(compSummaryEvery.trim());
    const klOk =
      Number.isFinite(kl) &&
      Math.round(kl) >= KEEP_LAST_MIN &&
      Math.round(kl) <= KEEP_LAST_MAX;
    const seOk =
      Number.isFinite(se) &&
      Math.round(se) >= SUMMARY_EVERY_MIN &&
      Math.round(se) <= SUMMARY_EVERY_MAX;
    if (!klOk || !seOk) {
      applyCompDrafts(compStateRef.current);
      setCompSave({
        status: 'error',
        message: `значения вне диапазона: последних ${KEEP_LAST_MIN}–${KEEP_LAST_MAX}, каждых ${SUMMARY_EVERY_MIN}–${SUMMARY_EVERY_MAX}`,
      });
      return;
    }
    if (compSavedTimerRef.current != null) {
      window.clearTimeout(compSavedTimerRef.current);
      compSavedTimerRef.current = null;
    }
    setCompSave({ status: 'saving', message: null });
    updateSessionCompression(sid, {
      ...(typeof nextEnabled === 'boolean' ? { enabled: nextEnabled } : {}),
      keepLast: Math.round(kl),
      summaryEvery: Math.round(se),
    }).then(
      (next) => {
        // Сессия сменилась, пока PUT был в полёте — ответ чужой сессии не применяем.
        if (sessionIdRef.current !== sid) return;
        compStateRef.current = next;
        applyCompDrafts(next);
        setCompSave({ status: 'saved', message: null });
        armCompSavedTimer();
      },
      (err: unknown) => {
        if (sessionIdRef.current !== sid) return;
        applyCompDrafts(compStateRef.current);
        setCompSave({
          status: 'error',
          message: err instanceof Error ? err.message : String(err),
        });
      },
    );
  };

  /** Смена стратегии контекста: клик по опции свитчера → PUT через хук. Индикатор compSave. */
  const commitStrategy = (next: ContextStrategy) => {
    if (compDisabled || compBusy || next === strategy) return;
    setCompSave({ status: 'saving', message: null });
    onChangeStrategy({ strategy: next }).then(
      () => {
        setCompSave({ status: 'saved', message: null });
        armCompSavedTimer();
        // Смена стратегии на бэкенде могла изменить enabled (переход в summary выставляет
        // enabled=true) — перечитываем сжатие, чтобы тумблер показывал серверную истину.
        const sid = sessionIdRef.current;
        if (sid != null) {
          fetchSessionCompression(sid)
            .then((s) => {
              if (sessionIdRef.current !== sid) return;
              compStateRef.current = s;
              applyCompDrafts(s);
            })
            .catch(() => {});
        }
      },
      (err: unknown) => {
        setCompSave({
          status: 'error',
          message: err instanceof Error ? err.message : String(err),
        });
      },
    );
  };

  /**
   * Коммит окна последних сообщений (sliding_window/sticky_facts): blur/Enter → PUT
   * windowSize через хук. Вне диапазона 1..50 / не число — откат черновика, инлайн-ошибка.
   */
  const commitWindowSize = () => {
    if (compDisabled || compBusy) return;
    const v = winSizeDraft.trim();
    const n = Number(v);
    const rounded = Math.round(n);
    if (v === '' || !Number.isFinite(n) || rounded < WINDOW_MIN || rounded > WINDOW_MAX) {
      setWinSizeDraft(String(windowSize ?? WINDOW_DEFAULT));
      setCompSave({
        status: 'error',
        message: `значения вне диапазона: окно последних сообщений ${WINDOW_MIN}–${WINDOW_MAX}`,
      });
      return;
    }
    if (compSavedTimerRef.current != null) {
      window.clearTimeout(compSavedTimerRef.current);
      compSavedTimerRef.current = null;
    }
    setCompSave({ status: 'saving', message: null });
    onChangeStrategy({ windowSize: rounded }).then(
      () => {
        setCompSave({ status: 'saved', message: null });
        armCompSavedTimer();
      },
      (err: unknown) => {
        setWinSizeDraft(String(windowSize ?? WINDOW_DEFAULT));
        setCompSave({
          status: 'error',
          message: err instanceof Error ? err.message : String(err),
        });
      },
    );
  };

  const armClearTimer = () => {
    if (clearTimerRef.current != null) window.clearTimeout(clearTimerRef.current);
    clearTimerRef.current = window.setTimeout(() => {
      setClearState((prev) => (prev.status === 'saved' ? { status: 'idle', message: null } : prev));
    }, 2000);
  };

  /**
   * Очистка ВСЕЙ долговременной памяти (глобальная): confirm → DELETE
   * /api/memory/long-term. Инлайн-статус рядом с кнопкой; при успехе App перечитывает
   * снапшот памяти (onMemoryCleared) — список LTM в группе пустеет.
   */
  const clearMemory = () => {
    if (disabled || clearState.status === 'saving') return;
    if (!window.confirm('Вы уверены, что хотите удалить все данные долговременной памяти?')) return;
    if (clearTimerRef.current != null) {
      window.clearTimeout(clearTimerRef.current);
      clearTimerRef.current = null;
    }
    setClearState({ status: 'saving', message: null });
    clearLongTermMemory().then(
      () => {
        setClearState({ status: 'saved', message: null });
        armClearTimer();
        onMemoryCleared?.();
      },
      (err: unknown) => {
        setClearState({
          status: 'error',
          message: err instanceof Error ? err.message : String(err),
        });
      },
    );
  };

  return (
    // Общая механика сворачивания колонки (CollapsibleSection): по умолчанию блок свёрнут,
    // шеврон ▸/▾ и иконка — как у остальных панелей; подсказка сессии и индикатор
    // сохранения живут в headerExtra — видны и в свёрнутом состоянии.
    <CollapsibleSection
      className="llm-settings-block"
      title="Настройки LLM"
      icon="⚙"
      hint={sessionId != null ? undefined : 'применяется к активной сессии'}
      headerExtra={
        <>
          {sessionId != null ? (
            <span
              className="llm-session-cue"
              title="Все поля применяются к активной сессии — у каждой сессии свой набор"
            >
              сессия ···{sessionId.slice(-8)}
            </span>
          ) : null}
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
        </>
      }
    >
      {/* Тело блока монтируется только в развёрнутом состоянии (по умолчанию блок свёрнут),
          поэтому скрытые поля не попадают в табуляцию. */}
      <>
          {saveState.status === 'error' ? (
            <div className="llm-error-line" role="alert">
              не удалось сохранить{saveState.message != null ? `: ${saveState.message}` : ''}
            </div>
          ) : null}

          <Group label="модель">
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
          </Group>

          <Group label="генерация">
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
          </Group>

          <div className="llm-groups-row">
            <Group label="запрос">
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
            </Group>
            <Group label="тарифы, $ за 1M">
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
            </Group>
          </div>

          <Group label="Стратегия контекста">
            <div className="llm-strategy-picker" role="radiogroup" aria-label="Стратегия контекста">
              {STRATEGY_OPTIONS.map((o) => (
                <button
                  key={o.value}
                  type="button"
                  role="radio"
                  aria-checked={strategy === o.value}
                  className={`llm-strategy-option${strategy === o.value ? ' is-active' : ''}`}
                  disabled={compDisabled || compBusy}
                  title={o.description != null ? `${o.label} — ${o.description}` : o.label}
                  onClick={() => commitStrategy(o.value)}
                >
                  <span className="llm-strategy-name">{o.label}</span>
                  {o.description != null ? (
                    <span className="llm-strategy-desc">{o.description}</span>
                  ) : null}
                </button>
              ))}
            </div>

            {/* Скользящее окно и факты — общее поле «окно последних N сообщений» (1..50) */}
            {(strategy === 'sliding_window' || strategy === 'sticky_facts') ? (
              <div className="llm-fields-row two">
                <NumField
                  label="Окно, последних сообщений"
                  title="Сколько последних сообщений диалога оставлять «как есть» в контексте (1–50)"
                  value={winSizeDraft}
                  unit="1–50"
                  step="1"
                  min="1"
                  max="50"
                  disabled={compDisabled || compBusy}
                  onChange={setWinSizeDraft}
                  onCommit={commitWindowSize}
                />
              </div>
            ) : null}

            {/* Resume + хвост (summary): существующее сжатие, сохраняется через /compression */}
            {strategy === 'summary' ? (
              <div className="llm-fields-row two">
                <NumField
                  label="Последних сообщений как есть"
                  title="Сколько последних сообщений диалога оставлять без сжатия (1–50)"
                  value={compKeepLast}
                  unit="1–50"
                  step="1"
                  min="1"
                  max="50"
                  disabled={compDisabled || compBusy}
                  onChange={setCompKeepLast}
                  onCommit={commitCompression}
                />
                <NumField
                  label="Summary каждые N сообщений"
                  title="Раз в сколько сообщений сворачивать старую историю в summary (2–100)"
                  value={compSummaryEvery}
                  unit="2–100"
                  step="1"
                  min="2"
                  max="100"
                  disabled={compDisabled || compBusy}
                  onChange={setCompSummaryEvery}
                  onCommit={commitCompression}
                />
              </div>
            ) : null}

            {/* Панель фактов (sticky_facts): живые ключ-значение из event'ов facts_updated */}
            {strategy === 'sticky_facts' ? (
              <div className="llm-facts-block">
                <div className="llm-facts-label">Факты диалога</div>
                {facts != null && Object.keys(facts).length > 0 ? (
                  <ul className="llm-facts-list">
                    {Object.entries(facts).map(([k, v]) => (
                      <li key={k} className="llm-fact-row">
                        <span className="llm-fact-key">{k}</span>
                        <span className="llm-fact-value">{v}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <div className="llm-hint">Факты появятся после первого сообщения</div>
                )}
              </div>
            ) : null}

            <div className="llm-compression-row">
              {sessionId == null ? (
                <span className="llm-hint">применяется к активной сессии</span>
              ) : compSave.status !== 'idle' ? (
                <span
                  className={`llm-save-state is-${compSave.status}`}
                  role="status"
                  aria-live="polite"
                >
                  {compSave.status === 'saving'
                    ? 'сохранение…'
                    : compSave.status === 'saved'
                      ? 'сохранено'
                      : 'ошибка'}
                </span>
              ) : null}
            </div>
            {compSave.status === 'error' ? (
              <div className="llm-error-line" role="alert">
                не удалось сохранить{compSave.message != null ? `: ${compSave.message}` : ''}
              </div>
            ) : null}
          </Group>

          <Group label="долговременная память">
            <div className="llm-provider-row">
              <button
                type="button"
                className="project-btn"
                disabled={disabled || clearState.status === 'saving'}
                title="Удалить ВСЕ записи долговременной памяти (глобально: все проекты и сессии)"
                onClick={clearMemory}
              >
                {clearState.status === 'saving' ? 'Удаление…' : 'Очистить долговременную память'}
              </button>
              {clearState.status !== 'idle' ? (
                <span
                  className={`llm-save-state is-${clearState.status}`}
                  role="status"
                  aria-live="polite"
                >
                  {clearState.status === 'saving'
                    ? 'удаление…'
                    : clearState.status === 'saved'
                      ? 'удалено'
                      : 'ошибка'}
                </span>
              ) : null}
            </div>
            {memory == null ? (
              <div className="llm-hint">Загрузка…</div>
            ) : memory.longTerm.length === 0 ? (
              <div className="llm-hint">Записей долговременной памяти нет</div>
            ) : (
              <ul className="llm-facts-list">
                {memory.longTerm.map((entry) => (
                  <li key={entry.id} className="llm-fact-row">
                    <span className="llm-fact-key" title={entry.key}>
                      {entry.key}
                    </span>
                    <span className="llm-fact-value" title={entry.value}>
                      {entry.value.length > 120 ? `${entry.value.slice(0, 120)}…` : entry.value}
                    </span>
                  </li>
                ))}
              </ul>
            )}
            {clearState.status === 'error' ? (
              <div className="llm-error-line" role="alert">
                не удалось очистить долговременную память
                {clearState.message != null ? `: ${clearState.message}` : ''}
              </div>
            ) : null}
          </Group>
        </>
    </CollapsibleSection>
  );
}

/**
 * Статическая группа настроек: uppercase-заголовок-строка + содержимое.
 * Сворачивания внутри группы нет (запрос пользователя) — весь блок «Настройки LLM»
 * сворачивается целиком через шапку секции (CollapsibleSection).
 */
function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="llm-group">
      <div className="llm-group-label">{label}</div>
      {children}
    </div>
  );
}

