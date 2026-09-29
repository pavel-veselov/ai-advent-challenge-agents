import { useEffect, useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  fetchKnowledgeBases,
  fetchKbModels,
  setKnowledgeBaseActive,
} from '../api';
import type {
  KbChunkingStrategy,
  KbEmbeddingModel,
  KnowledgeBase,
  KnowledgeBaseStatus,
} from '../types';
import CollapsibleSection from './CollapsibleSection';

/** Сколько миллисекунд висит инлайн-ошибка API, прежде чем погаснуть (идиома McpServersPanel). */
const ERROR_VISIBLE_MS = 4000;
/** Период опроса GET /api/kb, пока есть базы в статусе indexing (мс). */
const POLL_INTERVAL_MS = 2000;
/** Допустимые расширения файлов документов (accept у input type=file). */
const KB_FILE_ACCEPT = '.md,.txt,.pdf,.kt,.ts,.js,.json,.csv';
/** Дефолты и минимумы формы фиксированного chunking (валидация согласована с бэкендом). */
const DEFAULT_CHUNK_SIZE = 100;
const DEFAULT_OVERLAP = 50;
const MIN_CHUNK_SIZE = 20;

/** Радиокнопки стратегии chunking: подпись + описание (идиома сегментов воркфлоу). */
const STRATEGY_OPTIONS: { value: KbChunkingStrategy; label: string; description: string }[] = [
  { value: 'fixed', label: 'По фиксированному размеру', description: 'чанки по N символов с перекрытием' },
  { value: 'structural', label: 'По структуре', description: 'заголовки / разделы / файлы' },
];

/** Статус-лейблы карточки базы (тексты согласованы с задачей). */
const STATUS_LABELS: Record<KnowledgeBaseStatus, string> = {
  indexing: 'Индексируется',
  indexed: 'Проиндексировано',
  failed: 'Ошибка',
};

/** Подсказка к статусу (title у лейбла; для failed — ошибка бэкенда). */
function statusTitle(kb: KnowledgeBase): string {
  if (kb.status === 'failed' && kb.error != null && kb.error !== '') return kb.error;
  if (kb.status === 'indexing') return 'Идёт индексация: документы режутся на чанки и векторизуются';
  return 'База готова: индекс построен';
}

/** «осталось ~45 сек» / «осталось ~2 мин»; null — оценки нет (печатаем только проценты). */
function formatEta(seconds: number | null): string | null {
  if (seconds == null || !Number.isFinite(seconds) || seconds <= 0) return null;
  if (seconds < 60) return `осталось ~${Math.max(1, Math.round(seconds))} сек`;
  return `осталось ~${Math.round(seconds / 60)} мин`;
}

/** Строгий разбор неотрицательного целого; пусто/мусор — null (валидация числовых полей). */
function parseNonNegativeInt(raw: string): number | null {
  const t = raw.trim();
  if (!/^\d+$/.test(t)) return null;
  const n = Number.parseInt(t, 10);
  return Number.isSafeInteger(n) ? n : null;
}

interface KnowledgeBasePanelProps {
  /** true — сервис остановлен/запускается: кнопки и действия заблокированы (идиома панелей). */
  disabled: boolean;
}

/**
 * Панель «База знаний» (Day-22, RAG): управление базами документов для ответов агента.
 * Глобальная сущность (вне проектов/сессий) — панель сама грузит список и каталог
 * моделей эмбеддингов на монтировании. Кнопка «Добавить» открывает диалог создания:
 * имя, файлы (.md/.txt/.pdf/.kt/.ts/.js/.json/.csv, multi-select со списком и удалением
 * до отправки), стратегия chunking («По фиксированному размеру» с размером/перекрытием
 * и валидацией size ≥ 20, 0 ≤ overlap < size; «По структуре» — без полей) и модель
 * эмбеддингов (GET /api/kb/models). Список баз: имя, статус-лейбл (indexing →
 * «Индексируется» с прогресс-баром и ETA, indexed → «Проиндексировано», failed →
 * «Ошибка» с текстом ошибки), переключатель «Активна/Неактивна» (агент использует
 * базу при ответах) и удаление с confirm. Пока есть indexing — список поллится
 * каждые ~2 c (интервал снимается при unmount и когда индексация закончилась).
 * Toggle — optimistic с откатом при ошибке; ошибки показываются инлайном, без alert.
 */
export default function KnowledgeBasePanel({ disabled }: KnowledgeBasePanelProps) {
  const [bases, setBases] = useState<KnowledgeBase[] | null>(null);
  /** true — первая загрузка списка не удалась (backend недоступен/эндпоинта ещё нет). */
  const [loadFailed, setLoadFailed] = useState(false);
  const [models, setModels] = useState<KbEmbeddingModel[]>([]);
  /** Инлайн-ошибка операций списка (toggle/delete) — гаснет через ERROR_VISIBLE_MS. */
  const [error, setError] = useState<string | null>(null);
  /** id базы, над которой идёт мутация (toggle/delete) — блокируем только её строку. */
  const [rowBusyId, setRowBusyId] = useState<number | null>(null);

  // ----- Диалог создания -----
  const [dialogOpen, setDialogOpen] = useState(false);
  const [submitBusy, setSubmitBusy] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [strategy, setStrategy] = useState<KbChunkingStrategy>('fixed');
  const [chunkSizeStr, setChunkSizeStr] = useState(String(DEFAULT_CHUNK_SIZE));
  const [overlapStr, setOverlapStr] = useState(String(DEFAULT_OVERLAP));
  const [embeddingModel, setEmbeddingModel] = useState('');
  const [files, setFiles] = useState<File[]>([]);

  // Ошибка API списка показывается «коротко» (идиома McpServersPanel — вместо alert).
  useEffect(() => {
    if (error == null) return;
    const timer = window.setTimeout(() => setError(null), ERROR_VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [error]);

  /** Перечитать каталог баз; тихо: ошибки фонового обновления не забивают интерфейс. */
  const refresh = () =>
    fetchKnowledgeBases()
      .then((list) => {
        setBases(list);
        setLoadFailed(false);
      })
      .catch(() => {
        /* сервис недоступен — список остаётся прежним (или null до первой загрузки) */
      });

  // Первая загрузка: каталог баз + каталог моделей эмбеддингов (для селекта диалога).
  useEffect(() => {
    let cancelled = false;
    fetchKnowledgeBases()
      .then((list) => {
        if (!cancelled) setBases(list);
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      });
    fetchKbModels()
      .then((list) => {
        if (!cancelled) setModels(list);
      })
      .catch(() => {
        /* каталог моделей недоступен — селект останется пустым, поле не уйдёт в POST */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Модель эмбеддингов по умолчанию: первая из каталога (сейчас одна).
  useEffect(() => {
    if (embeddingModel === '' && models.length > 0) setEmbeddingModel(models[0]!.id);
  }, [models, embeddingModel]);

  // Polling: пока среди баз есть indexing — опрашиваем GET /api/kb каждые ~2 c.
  // Интервал снимается автоматически, когда индексация закончилась или панель unmount.
  const hasIndexing = bases?.some((kb) => kb.status === 'indexing') ?? false;
  useEffect(() => {
    if (!hasIndexing) return;
    const timer = window.setInterval(() => {
      fetchKnowledgeBases()
        .then(setBases)
        .catch(() => {
          /* тик пропущен — прогресс подхватится на следующем */
        });
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [hasIndexing]);

  /** Активная/неактивная (optimistic): флаг переключаем сразу, при ошибке откат + инлайн-ошибка. */
  const handleToggle = async (kb: KnowledgeBase) => {
    const next = !kb.active;
    setRowBusyId(kb.id);
    setError(null);
    setBases((prev) => prev?.map((b) => (b.id === kb.id ? { ...b, active: next } : b)) ?? prev);
    try {
      await setKnowledgeBaseActive(kb.id, next);
    } catch (e) {
      // Откат optimistic-значения к фактическому состоянию базы.
      setBases((prev) => prev?.map((b) => (b.id === kb.id ? { ...b, active: kb.active } : b)) ?? prev);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRowBusyId(null);
    }
  };

  /** Удаление базы (с документами и индексом) — подтверждаем явно, как проект в App. */
  const handleDelete = async (kb: KnowledgeBase) => {
    if (!window.confirm(`Удалить базу «${kb.name}» вместе с документами и индексом?`)) return;
    setRowBusyId(kb.id);
    setError(null);
    try {
      await deleteKnowledgeBase(kb.id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRowBusyId(null);
    }
  };

  // ----- Диалог: открытие/закрытие -----
  const openDialog = () => {
    setName('');
    setStrategy('fixed');
    setChunkSizeStr(String(DEFAULT_CHUNK_SIZE));
    setOverlapStr(String(DEFAULT_OVERLAP));
    setEmbeddingModel(models[0]?.id ?? '');
    setFiles([]);
    setSubmitError(null);
    setDialogOpen(true);
  };

  const closeDialog = () => {
    setDialogOpen(false);
    setSubmitError(null);
  };

  /** Добавление выбранных файлов в черновик (multi-select, накоплением). */
  const handleFilesChosen = (e: ChangeEvent<HTMLInputElement>) => {
    const chosen = Array.from(e.target.files ?? []);
    if (chosen.length > 0) {
      setFiles((prev) => {
        // Дубликат по имени+размеру не добавляем повторно.
        const known = new Set(prev.map((f) => `${f.name}:${f.size}`));
        return [...prev, ...chosen.filter((f) => !known.has(`${f.name}:${f.size}`))];
      });
    }
    e.target.value = ''; // повторный выбор того же файла снова срабатывает
  };

  const removeFile = (index: number) => {
    setFiles((prev) => prev.filter((_, i) => i !== index));
  };

  // Валидация числовых полей (только strategy=fixed): size ≥ 20, 0 ≤ overlap < size, целые.
  const chunkSizeNum = parseNonNegativeInt(chunkSizeStr);
  const overlapNum = parseNonNegativeInt(overlapStr);
  const chunkSizeError =
    strategy !== 'fixed'
      ? null
      : chunkSizeNum == null
        ? 'целое число'
        : chunkSizeNum < MIN_CHUNK_SIZE
          ? `не меньше ${MIN_CHUNK_SIZE}`
          : null;
  const overlapError =
    strategy !== 'fixed' || chunkSizeNum == null
      ? null
      : overlapNum == null
        ? 'целое число ≥ 0'
        : overlapNum >= chunkSizeNum
          ? 'меньше размера чанка'
          : null;

  const canSubmit =
    !disabled &&
    !submitBusy &&
    name.trim() !== '' &&
    files.length > 0 &&
    chunkSizeError == null &&
    overlapError == null;

  /** Запуск индексации: POST /api/kb (multipart собран в api.ts). После успеха диалог
   *  закрывается, список перечитывается — новая база вернётся со статусом indexing. */
  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitBusy(true);
    setSubmitError(null);
    try {
      await createKnowledgeBase({
        name: name.trim(),
        strategy,
        chunkSize: strategy === 'fixed' ? chunkSizeNum ?? undefined : undefined,
        overlap: strategy === 'fixed' ? overlapNum ?? undefined : undefined,
        embeddingModel: embeddingModel === '' ? undefined : embeddingModel,
        files,
      });
      setDialogOpen(false);
      await refresh();
    } catch (err) {
      // 400 с {message} показываем в диалоге — форму не закрываем.
      setSubmitError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitBusy(false);
    }
  };

  return (
    <CollapsibleSection
      className="llm-settings-block"
      title="База знаний"
      icon="❖"
      hint="RAG: документы для ответов агента"
    >
      {/* Кнопка «Добавить» открывает диалог создания (создание = запуск индексации). */}
      <div className="kb-toolbar">
        <button
          type="button"
          className="mem-btn mem-btn-primary kb-add-btn"
          disabled={disabled}
          title="Новая база знаний: файлы → чанки → векторный индекс"
          onClick={openDialog}
        >
          Добавить
        </button>
      </div>

      {error != null ? (
        <div className="mem-error" role="alert">
          {error}
        </div>
      ) : null}

      {/* Список баз: карточка = имя + статус (+прогресс) + метаданные + переключатель + удаление. */}
      {bases === null ? (
        loadFailed ? (
          <div className="kb-load-failed">
            <div className="mem-error" role="alert">
              Не удалось загрузить базы знаний (сервис недоступен или эндпоинт ещё не поднят)
            </div>
            <button type="button" className="mem-btn" onClick={() => void refresh()}>
              Повторить
            </button>
          </div>
        ) : (
          <div className="mem-hint">Загрузка…</div>
        )
      ) : bases.length === 0 ? (
        <div className="mem-empty-inline">Баз знаний нет</div>
      ) : (
        <div className="kb-bases">
          {bases.map((kb) => {
            const rowBusy = rowBusyId === kb.id;
            const eta = formatEta(kb.progress?.etaSeconds ?? null);
            return (
              <div key={kb.id} className="kb-base">
                <div className="kb-base-top">
                  <span className="kb-base-name" title={kb.name}>
                    {kb.name}
                  </span>
                  <span
                    className={`kb-state is-${kb.status}`}
                    title={statusTitle(kb)}
                  >
                    {STATUS_LABELS[kb.status]}
                  </span>
                  <button
                    type="button"
                    className="mem-entry-del"
                    title="Удалить базу вместе с документами и индексом"
                    aria-label={`Удалить базу «${kb.name}»`}
                    disabled={disabled || rowBusy || kb.status === 'indexing'}
                    onClick={() => void handleDelete(kb)}
                  >
                    ×
                  </button>
                </div>

                {/* Прогресс индексации: рейка + проценты + обработано документов + ETA. */}
                {kb.status === 'indexing' && kb.progress != null ? (
                  <div className="kb-progress-wrap">
                    <div
                      className="kb-progress"
                      role="progressbar"
                      aria-label={`Индексация «${kb.name}»`}
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={Math.min(100, Math.max(0, kb.progress.percent))}
                    >
                      <div
                        className="kb-progress-fill"
                        style={{ width: `${Math.min(100, Math.max(0, kb.progress.percent))}%` }}
                      />
                    </div>
                    <div className="kb-progress-text">
                      {Math.min(100, Math.max(0, Math.round(kb.progress.percent)))}%
                      {` · ${kb.progress.processedDocs}/${kb.progress.totalDocs} док`}
                      {eta != null ? ` · ${eta}` : ''}
                    </div>
                  </div>
                ) : null}

                {/* Ошибка индексации: короткий лейбл в title, полный текст — раскрывается. */}
                {kb.status === 'failed' && kb.error != null && kb.error !== '' ? (
                  <details className="kb-error-details">
                    <summary className="kb-error-summary" title="Показать текст ошибки">
                      причина ошибки
                    </summary>
                    <div className="kb-error-text">{kb.error}</div>
                  </details>
                ) : null}

                {/* Метаданные базы: стратегия/чанки и состав индекса (моно-гравировка). */}
                <div className="kb-meta">
                  {kb.strategy === 'fixed'
                    ? `fixed · чанк ${kb.chunkSize ?? '—'} / перекрытие ${kb.overlap ?? '—'}`
                    : 'structural · по заголовкам/разделам/файлам'}
                  {` · док ${kb.documentsCount} · чанков ${kb.chunksCount ?? '—'}`}
                </div>
                <div className="kb-meta" title={`Модель эмбеддингов: ${kb.embeddingModel}`}>
                  {kb.embeddingModel}
                </div>

                {/* Toggle активной базы: переключатель + подпись смысла + «Активна/Неактивна». */}
                <div className="kb-active-row">
                  <button
                    type="button"
                    role="switch"
                    aria-checked={kb.active}
                    aria-label={`База «${kb.name}»: ${kb.active ? 'активна' : 'неактивна'}`}
                    className={`llm-switch kb-switch${kb.active ? ' is-on' : ''}`}
                    disabled={disabled || rowBusy || kb.status === 'indexing'}
                    title={
                      kb.status === 'indexing'
                        ? 'Идёт индексация: переключение станет доступно после её завершения'
                        : kb.active
                          ? 'Исключить базу из ответов агента'
                          : 'Включить: агент будет использовать базу при ответах'
                    }
                    onClick={() => void handleToggle(kb)}
                  >
                    <span className="llm-switch-knob" />
                  </button>
                  <span className="kb-active-text">
                    <span className="kb-active-title">Агент использует базу при ответах</span>
                    <span className={`kb-active-state${kb.active ? ' is-on' : ''}`}>
                      {kb.active ? 'Активна' : 'Неактивна'}
                    </span>
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {dialogOpen ? (
        // Без onClick на затемнении: диалог закрывается только кнопками
        // «Отмена»/«Запустить индексацию» (идиома диалога профиля).
        <div className="profile-modal-backdrop">
          <form className="profile-modal" onSubmit={(e: FormEvent<HTMLFormElement>) => void handleSubmit(e)}>
            <div className="profile-modal-title">Новая база знаний</div>

            <label className="profile-field">
              <span className="profile-field-label">Имя базы знаний</span>
              <input
                className="profile-input"
                placeholder="Например: Инструкции проекта"
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={120}
                autoFocus
              />
            </label>

            <label className="profile-field">
              <span className="profile-field-label">Файлы документов</span>
              <input
                className="profile-input kb-file-input"
                type="file"
                multiple
                accept={KB_FILE_ACCEPT}
                onChange={handleFilesChosen}
                aria-label="Файлы документов базы знаний"
                title={`Допустимые типы: ${KB_FILE_ACCEPT.split(',').join(', ')}`}
              />
            </label>
            {files.length > 0 ? (
              <div className="kb-files">
                {files.map((file, index) => (
                  <span key={`${file.name}:${file.size}`} className="kb-file-chip" title={file.name}>
                    <span className="kb-file-name">{file.name}</span>
                    <button
                      type="button"
                      className="kb-file-del"
                      aria-label={`Убрать файл ${file.name}`}
                      title="Убрать файл из списка"
                      onClick={() => removeFile(index)}
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>
            ) : (
              <div className="llm-hint">файлы не выбраны — выберите хотя бы один</div>
            )}

            {/* Стратегия chunking: радио-сегменты (идиома выбора режима воркфлоу). */}
            <div className="profile-field">
              <span className="profile-field-label">Стратегия chunking</span>
              <div className="kb-strategy-picker" role="radiogroup" aria-label="Стратегия chunking">
                {STRATEGY_OPTIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    role="radio"
                    aria-checked={strategy === option.value}
                    className={`workflow-mode-option${strategy === option.value ? ' is-active' : ''}`}
                    title={option.description}
                    onClick={() => setStrategy(option.value)}
                  >
                    <span className="workflow-mode-name">{option.label}</span>
                    <span className="workflow-mode-desc">{option.description}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* Числовые поля фиксированной стратегии: чанк ≥ 20, 0 ≤ перекрытие < чанк. */}
            {strategy === 'fixed' ? (
              <div className="kb-nums">
                <label className="profile-field kb-num-field">
                  <span className="profile-field-label">Размер чанка</span>
                  <input
                    className="profile-input"
                    inputMode="numeric"
                    value={chunkSizeStr}
                    onChange={(e) => setChunkSizeStr(e.target.value)}
                    aria-label="Размер чанка"
                    title={`Целое число, минимум ${MIN_CHUNK_SIZE}`}
                  />
                  {chunkSizeError != null ? <span className="kb-field-error">{chunkSizeError}</span> : null}
                </label>
                <label className="profile-field kb-num-field">
                  <span className="profile-field-label">Перекрытие</span>
                  <input
                    className="profile-input"
                    inputMode="numeric"
                    value={overlapStr}
                    onChange={(e) => setOverlapStr(e.target.value)}
                    aria-label="Перекрытие чанков"
                    title="Целое число от 0 до «размер чанка − 1»"
                  />
                  {overlapError != null ? <span className="kb-field-error">{overlapError}</span> : null}
                </label>
              </div>
            ) : null}

            <label className="profile-field">
              <span className="profile-field-label">Модель эмбеддингов</span>
              {models.length > 0 ? (
                <select
                  className="profile-select"
                  value={embeddingModel}
                  onChange={(e) => setEmbeddingModel(e.target.value)}
                  aria-label="Модель эмбеддингов"
                  title="Векторизация чанков (каталог GET /api/kb/models)"
                >
                  {models.map((model) => (
                    <option key={model.id} value={model.id}>
                      {model.id} · {model.dimension}d
                    </option>
                  ))}
                </select>
              ) : (
                <div className="llm-hint">каталог моделей недоступен — модель выберет бэкенд</div>
              )}
            </label>

            {submitError != null ? (
              <div className="llm-error-line" role="alert">
                не удалось запустить индексацию: {submitError}
              </div>
            ) : null}

            <div className="profile-modal-actions">
              <button
                type="submit"
                className="project-btn"
                disabled={!canSubmit}
                title="POST /api/kb: создать базу и запустить индексацию файлов"
              >
                {submitBusy ? 'Запуск…' : 'Запустить индексацию'}
              </button>
              <button type="button" className="project-btn" onClick={closeDialog} disabled={submitBusy}>
                Отмена
              </button>
            </div>
          </form>
        </div>
      ) : null}
    </CollapsibleSection>
  );
}
