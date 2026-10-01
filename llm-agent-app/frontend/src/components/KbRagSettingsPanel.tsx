import { useEffect, useState } from 'react';
import type { ChangeEvent } from 'react';
import { fetchKbRagSettings, updateKbRagSettings } from '../api';
import type { KbRagSettings } from '../types';
import CollapsibleSection from './CollapsibleSection';

/** Сколько миллисекунд висит индикация «Настройки сохранены» (идиома авто-гаснущих статусов). */
const SUCCESS_VISIBLE_MS = 3000;

// ----- Обучающие подсказки (нативные title-атрибуты): простые пояснения «что это и на что влияет» -----
// Одна и та же строка вешается на два элемента (кнопка+строка у рубильников, label+input у полей),
// поэтому вынесена в константу (идиома документированных констант модуля, как SUCCESS_VISIBLE_MS).

/** Обучающий title шапки панели: что такое «Настройки RAG» и на что влияет блок. */
const TITLE_PANEL =
  'Как агент ищет ответы в базах знаний (RAG): перед ответом он находит в базах подходящие фрагменты ' +
  'текста и подставляет их модели. Здесь настраивается отбор этих фрагментов. Общие для всех баз, ' +
  'действуют после «Сохранить».';

/** Обучающий title рубильника «Фильтр релевантности»: что делает и что будет при выключении. */
const TITLE_FILTER =
  'Отсекает фрагменты, слабо похожие на вопрос: после поиска отбрасываются чанки ниже порога minScore ' +
  '(шкала 0–1). Если ни один не прошёл — агент ответит без базы знаний. Выключено = прежнее поведение ' +
  '(топ-4 без отсечения).';

/** Обучающий title рубильника «Перезапись запроса»: что делает, цена (+1 LLM-вызов) и фолбэк. */
const TITLE_REWRITE =
  'Перед поиском LLM сжимает вопрос пользователя до короткого поискового запроса: убирает вежливость, ' +
  'сохраняет термины и числа — векторному поиску так проще. Цена: +1 вызов LLM (~1–3 с); при ошибке ' +
  'берётся исходный вопрос.';

/** Обучающий title поля minScore: что за порог, когда действует и что рекомендует замер. */
const TITLE_MIN_SCORE =
  'Порог похожести фрагмента на вопрос: 0 — не похож, 1 — совпадение. Отбрасываются чанки ниже порога ' +
  '(только при включённом фильтре, среди candidateK лучших). По замеру: полезные тексты ≥0.58, ' +
  'мусор ≤0.4 — рекомендуется 0.5.';

/** Обучающий title поля candidateK: сколько кандидатов ищется до фильтрации. */
const TITLE_CANDIDATE_K =
  'Сколько лучших кандидатов ищется до фильтрации (1–100). Больше — меньше шанс потерять нужный ' +
  'фрагмент, но больше мусора дойдёт до порога. По умолчанию 8.';

/** Обучающий title поля topK: сколько фрагментов после фильтрации уйдёт в промпт агента. */
const TITLE_TOP_K =
  'Сколько лучших фрагментов после фильтрации попадёт в промпт агента: от 1 до candidateK. ' +
  'Больше — больше контекста, но блок базы знаний обрезается лимитом ~6000 символов. По умолчанию 4.';

/** Обучающий title рубильника отказа (Day-24): что делает, детерминированное условие и компромисс. */
const TITLE_REFUSAL =
  'Если база знаний активна, но фрагментов выше порога minScore не нашлось (или их ноль), ' +
  'агент не вызывает LLM и сразу отвечает «Не знаю — уточните вопрос». Компромисс: ' +
  'разговорные сообщения ниже порога тоже получат отказ. Выключено = агент отвечает ' +
  'из собственных знаний, как раньше.';

/** Строгий разбор неотрицательного целого; пусто/мусор — null (идиома KnowledgeBasePanel). */
function parseNonNegativeInt(raw: string): number | null {
  const t = raw.trim();
  if (!/^\d+$/.test(t)) return null;
  const n = Number.parseInt(t, 10);
  return Number.isSafeInteger(n) ? n : null;
}

/** Разбор дробного порога 0..1; запятая допустима как разделитель; пусто/мусор — null. */
function parseScore(raw: string): number | null {
  const t = raw.trim().replace(',', '.');
  if (t === '') return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

interface KbRagSettingsPanelProps {
  /** true — сервис остановлен/запускается: переключатели и сохранение заблокированы (идиома панелей). */
  disabled: boolean;
}

/**
 * Панель «Настройки RAG» (Day-23): funnel реранкинга/фильтрации при поиске по базам знаний.
 * Загружает GET /api/kb/settings на монтировании; форма: рубильники «Фильтр релевантности»,
 * «Перезапись запроса (query rewrite)» и «Отказ при низкой релевантности («не знаю»)» (Day-24)
 * (общий .llm-switch) + числовые поля воронки —
 * minScore (0..1, шаг 0.05), candidateK (целое 1..100), topK (целое 1..candidateK).
 * Валидация клиентская, согласована с KbRagSettingsService (ошибки инлайном .kb-field-error,
 * без alert). «Сохранить» (PUT /api/kb/settings, полный объект) активна только при изменениях
 * относительно загруженного и валидности; 400 бэкенда с {message} показывается инлайном
 * (raiseKbError уже вытащил русский текст). После успеха — «Настройки сохранены» с лампой
 * .llm-save-state, гаснет через ~3 c; перечитывание не нужно — PUT возвращает эхо сохранённого.
 * Воронка (порядок не менять): сортировка чанков по релевантности → срез candidateK →
 * порог minScore (если фильтр включён) → topK чанков в промпт.
 */
export default function KbRagSettingsPanel({ disabled }: KbRagSettingsPanelProps) {
  /** Настройки, фактически лежащие на бэкенде (эталон для dirty-сравнения). */
  const [loaded, setLoaded] = useState<KbRagSettings | null>(null);
  /** true — первая загрузка не удалась (backend недоступен/эндпоинт ещё не поднят). */
  const [loadFailed, setLoadFailed] = useState(false);
  /** true — PUT в полёте: кнопка «Сохранить» показывает «Сохранение…». */
  const [busy, setBusy] = useState(false);
  /** Ошибка сохранения (в т.ч. 400 с русским сообщением бэкенда) — инлайн .llm-error-line. */
  const [saveError, setSaveError] = useState<string | null>(null);
  /** true — после успешного PUT: лампа «Настройки сохранены», гаснет через SUCCESS_VISIBLE_MS. */
  const [savedVisible, setSavedVisible] = useState(false);

  // ----- Черновик формы (числовые поля хранятся строками — идиома KnowledgeBasePanel) -----
  const [filterEnabled, setFilterEnabled] = useState(false);
  const [rewriteEnabled, setRewriteEnabled] = useState(false);
  // Day-24: дефолт true — до загрузки настройки бэкенд по контракту держит отказ включённым.
  const [refusalEnabled, setRefusalEnabled] = useState(true);
  const [minScoreStr, setMinScoreStr] = useState('0.35');
  const [candidateKStr, setCandidateKStr] = useState('8');
  const [topKStr, setTopKStr] = useState('4');

  /** Синхронизация черновика с объектом настроек (после загрузки и после эха PUT). */
  const applySettings = (s: KbRagSettings) => {
    setFilterEnabled(s.filterEnabled);
    setRewriteEnabled(s.rewriteEnabled);
    setRefusalEnabled(s.refusalEnabled);
    setMinScoreStr(String(s.minScore));
    setCandidateKStr(String(s.candidateK));
    setTopKStr(String(s.topK));
  };

  /** Перечитать настройки (монтирование и кнопка «Повторить»); ошибка — состояние loadFailed. */
  const loadSettings = () => {
    setLoadFailed(false);
    fetchKbRagSettings()
      .then((s) => {
        setLoaded(s);
        setLoadFailed(false);
        applySettings(s);
      })
      .catch(() => {
        setLoaded(null);
        setLoadFailed(true);
      });
  };

  // Первая загрузка настроек при монтировании панели.
  useEffect(() => {
    loadSettings();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- разовая загрузка на монтировании
  }, []);

  // Индикация успеха гаснет автоматически (идиома авто-гаснущих ошибок KnowledgeBasePanel).
  useEffect(() => {
    if (!savedVisible) return;
    const timer = window.setTimeout(() => setSavedVisible(false), SUCCESS_VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [savedVisible]);

  // ----- Валидация (диапазоны согласованы с бэкендом: KbRagSettingsService.validate) -----
  const minScoreNum = parseScore(minScoreStr);
  const candidateKNum = parseNonNegativeInt(candidateKStr);
  const topKNum = parseNonNegativeInt(topKStr);

  const minScoreError =
    minScoreNum == null || minScoreNum < 0 || minScoreNum > 1 ? 'число от 0 до 1' : null;
  const candidateKError =
    candidateKNum == null || candidateKNum < 1 || candidateKNum > 100
      ? 'целое число от 1 до 100'
      : null;
  const topKError =
    topKNum == null || topKNum < 1
      ? candidateKNum == null
        ? 'целое число'
        : `целое число от 1 до ${candidateKNum}`
      : candidateKNum != null && topKNum > candidateKNum
        ? `не больше candidateK (${candidateKNum})`
        : null;

  const valid = minScoreError == null && candidateKError == null && topKError == null;

  /** Dirty: черновик отличается от загруженных настроек (пока валиден — иначе не сравнить). */
  const dirty =
    loaded != null &&
    (filterEnabled !== loaded.filterEnabled ||
      rewriteEnabled !== loaded.rewriteEnabled ||
      refusalEnabled !== loaded.refusalEnabled ||
      minScoreNum !== loaded.minScore ||
      candidateKNum !== loaded.candidateK ||
      topKNum !== loaded.topK);

  const canSave = !disabled && !busy && loaded != null && valid && dirty;

  /** Редактирование любого поля гасит прежнюю ошибку сохранения (она относилась к старым данным). */
  const clearSaveError = () => {
    if (saveError != null) setSaveError(null);
  };

  /** Сохранение: PUT /api/kb/settings полным объектом; успех — эхо становится новым эталоном. */
  const handleSave = async () => {
    if (!canSave || minScoreNum == null || candidateKNum == null || topKNum == null) return;
    setBusy(true);
    setSaveError(null);
    setSavedVisible(false);
    try {
      const saved = await updateKbRagSettings({
        filterEnabled,
        minScore: minScoreNum,
        candidateK: candidateKNum,
        topK: topKNum,
        rewriteEnabled,
        refusalEnabled,
      });
      setLoaded(saved);
      applySettings(saved); // перечитывание не нужно: PUT возвращает эхо применённых значений
      setSavedVisible(true);
    } catch (e) {
      // 400 с {message}: raiseKbError уже вытащил русский текст — показываем инлайном.
      setSaveError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <CollapsibleSection
      className="llm-settings-block"
      title="Настройки RAG"
      icon="◈"
      hint="фильтр релевантности и перезапись запроса при поиске по базам"
      // ⓘ в шапке несёт обучающий summary-title: нативная подсказка «зачем эта панель».
      headerExtra={
        <span className="llm-hint" aria-hidden="true" title={TITLE_PANEL}>
          ⓘ
        </span>
      }
    >
      {loaded === null ? (
        loadFailed ? (
          <div className="kb-load-failed">
            <div className="mem-error" role="alert">
              Не удалось загрузить настройки RAG (сервис недоступен или эндпоинт ещё не поднят)
            </div>
            <button type="button" className="mem-btn" onClick={loadSettings}>
              Повторить
            </button>
          </div>
        ) : (
          <div className="mem-hint">Загрузка…</div>
        )
      ) : (
        <>
          {/* Рубильник фильтра: строка-идиома .kb-active-row (подпись+описание слева, свитч справа). */}
          <div className="kb-active-row" title={TITLE_FILTER}>
            <button
              type="button"
              role="switch"
              aria-checked={filterEnabled}
              aria-label="Фильтр релевантности"
              className={`llm-switch${filterEnabled ? ' is-on' : ''}`}
              disabled={disabled || busy}
              title={TITLE_FILTER}
              onClick={() => setFilterEnabled((v) => !v)}
            >
              <span className="llm-switch-knob" />
            </button>
            <span className="kb-active-text">
              <span className="kb-active-title">Фильтр релевантности</span>
              <span className="llm-hint">не подставлять чанки ниже порога minScore</span>
            </span>
          </div>

          {/* Рубильник перезаписи запроса: вопрос переформулируется вспомогательной LLM перед поиском. */}
          <div className="kb-active-row" title={TITLE_REWRITE}>
            <button
              type="button"
              role="switch"
              aria-checked={rewriteEnabled}
              aria-label="Перезапись запроса (query rewrite)"
              className={`llm-switch${rewriteEnabled ? ' is-on' : ''}`}
              disabled={disabled || busy}
              title={TITLE_REWRITE}
              onClick={() => setRewriteEnabled((v) => !v)}
            >
              <span className="llm-switch-knob" />
            </button>
            <span className="kb-active-text">
              <span className="kb-active-title">Перезапись запроса (query rewrite)</span>
              <span className="llm-hint">переформулировать вопрос перед поиском</span>
            </span>
          </div>

          {/* Рубильник отказа (Day-24): нет фрагментов выше порога — агент отвечает «Не знаю». */}
          <div className="kb-active-row" title={TITLE_REFUSAL}>
            <button
              type="button"
              role="switch"
              aria-checked={refusalEnabled}
              aria-label="Отказ при низкой релевантности"
              className={`llm-switch${refusalEnabled ? ' is-on' : ''}`}
              disabled={disabled || busy}
              title={TITLE_REFUSAL}
              onClick={() => setRefusalEnabled((v) => !v)}
            >
              <span className="llm-switch-knob" />
            </button>
            <span className="kb-active-text">
              <span className="kb-active-title">Отказ при низкой релевантности («не знаю»)</span>
              <span className="llm-hint">без фрагментов выше порога — «не знаю», а не ответ из головы</span>
            </span>
          </div>

          {/* Порядок воронки — моно-гравировка (идиома .kb-meta); соответствует бэкенду. */}
          <div className="kb-meta">
            воронка: сортировка по релевантности → candidateK → порог minScore → topK в промпт
          </div>

          {/* Числовые поля воронки в ряд (идиома .kb-nums из диалога создания базы). */}
          <div className="kb-nums">
            <label className="profile-field kb-num-field" title={TITLE_MIN_SCORE}>
              <span className="profile-field-label">Порог релевантности (minScore)</span>
              <input
                className="profile-input"
                type="number"
                step={0.05}
                min={0}
                max={1}
                value={minScoreStr}
                onChange={(e: ChangeEvent<HTMLInputElement>) => {
                  setMinScoreStr(e.target.value);
                  clearSaveError();
                }}
                aria-label="Порог релевантности (minScore)"
                title={TITLE_MIN_SCORE}
              />
              {minScoreError != null ? <span className="kb-field-error">{minScoreError}</span> : null}
            </label>
            <label className="profile-field kb-num-field" title={TITLE_CANDIDATE_K}>
              <span className="profile-field-label">Кандидатов до фильтра (candidateK)</span>
              <input
                className="profile-input"
                type="number"
                step={1}
                min={1}
                max={100}
                value={candidateKStr}
                onChange={(e: ChangeEvent<HTMLInputElement>) => {
                  setCandidateKStr(e.target.value);
                  clearSaveError();
                }}
                aria-label="Кандидатов до фильтра (candidateK)"
                title={TITLE_CANDIDATE_K}
              />
              {candidateKError != null ? (
                <span className="kb-field-error">{candidateKError}</span>
              ) : null}
            </label>
            <label className="profile-field kb-num-field" title={TITLE_TOP_K}>
              <span className="profile-field-label">Чанков в промпт (topK)</span>
              <input
                className="profile-input"
                type="number"
                step={1}
                min={1}
                max={candidateKNum ?? 100}
                value={topKStr}
                onChange={(e: ChangeEvent<HTMLInputElement>) => {
                  setTopKStr(e.target.value);
                  clearSaveError();
                }}
                aria-label="Чанков в промпт (topK)"
                title={TITLE_TOP_K}
              />
              {topKError != null ? <span className="kb-field-error">{topKError}</span> : null}
            </label>
          </div>

          {/* Ошибка сохранения (в т.ч. русский текст 400 от бэкенда) — инлайн, без alert. */}
          {saveError != null ? (
            <div className="llm-error-line" role="alert">
              не удалось сохранить: {saveError}
            </div>
          ) : null}

          {/* Кнопка активна только при изменениях и валидности; лампа успеха — справа. */}
          <div className="kb-toolbar">
            <button
              type="button"
              className="project-btn"
              disabled={!canSave}
              title="PUT /api/kb/settings: применить настройки ко всем ответам агента"
              onClick={() => void handleSave()}
            >
              {busy ? 'Сохранение…' : 'Сохранить'}
            </button>
            {savedVisible ? (
              <span className="llm-save-state is-saved" role="status" aria-live="polite">
                Настройки сохранены
              </span>
            ) : null}
          </div>
        </>
      )}
    </CollapsibleSection>
  );
}
