import { useEffect, useState } from 'react';
import {
  deleteAgentSchedulerJob,
  fetchAgentScheduler,
  updateAgentSchedulerJob,
} from '../api';
import type { AgentSchedulerJob } from '../types';
import CollapsibleSection from './CollapsibleSection';

/** Сколько миллисекунд висит инлайн-ошибка API, прежде чем погаснуть (идиома McpServersPanel). */
const ERROR_VISIBLE_MS = 4000;
/** Минимальный период задания в секундах — валидирует и бэкенд (меньше → 400). */
const MIN_INTERVAL_SECONDS = 5;
/** Период периодического перечитывания списка (мс): задания создаются/правятся из ЧАТА
 *  (агентские тулы), панель не знает об этом — обновляемся по таймеру без перезагрузки окна. */
const REFRESH_INTERVAL_MS = 10000;

/** Человекочитаемый интервал из секунд: «каждые 30 с», «каждые 2 ч», «каждые 1.5 д». */
function formatInterval(seconds: number): string {
  if (seconds < 60) return `каждые ${seconds} с`;
  if (seconds < 3600) {
    const m = seconds / 60;
    return `каждые ${Number.isInteger(m) ? m : m.toFixed(1)} мин`;
  }
  if (seconds < 86400) {
    const h = seconds / 3600;
    return `каждые ${Number.isInteger(h) ? h : h.toFixed(1)} ч`;
  }
  const d = seconds / 86400;
  return `каждые ${Number.isInteger(d) ? d : d.toFixed(1)} д`;
}

/** ISO-время → локальная дата и время; null/невалидное — «—». */
function formatTimestamp(iso: string | null): string {
  if (iso == null) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Обрезает текст до max символов с многоточием (подпись промпта в карточке). */
function truncate(text: string, max = 120): string {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

interface SchedulerPanelProps {
  /** true — сервис остановлен/запускается: форма и действия заблокированы. */
  disabled?: boolean;
}

/**
 * Панель «Планировщик»: периодические задания агента (GET /api/agent-scheduler,
 * PATCH/DELETE /api/agent-scheduler/{id}). Глобальная сущность вне проектов/сессий.
 * Задания создаются ТОЛЬКО из чата агентскими тулами (agent_scheduler_*) — формы
 * добавления в панели нет. Панель грузит список на монтировании и периодически
 * перечитывает его (см. REFRESH_INTERVAL_MS), чтобы добавленное через чат задание
 * появилось без перезагрузки окна. Список карточек: имя, состояние «Активен/Отключён»,
 * текст промпта, человекочитаемый интервал, времена последнего/следующего запуска и
 * создания, редактирование периода на месте (число + ✓ → PATCH …/{id}) и кнопка удаления.
 * Результат выполненного задания приходит в чат через SSE (событие scheduler_result) —
 * см. useAgentSession. Ошибки бэкенда ({error: "…"}) показываются инлайном, без alert.
 */
export default function SchedulerPanel({ disabled = false }: SchedulerPanelProps) {
  const [jobs, setJobs] = useState<AgentSchedulerJob[] | null>(null);
  /** id задания, над которым идёт удаление — блокируем только его строку. */
  const [rowBusyId, setRowBusyId] = useState<number | null>(null);
  /** id задания, для которого идёт сохранение периода (PATCH …/{id}). */
  const [intervalsBusyId, setIntervalsBusyId] = useState<number | null>(null);
  /** Черновики периода по заданию (редактирование на месте); нет записи — текущее значение. */
  const [intervalDrafts, setIntervalDrafts] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);

  // Ошибка API показывается «коротко»: гасим через ERROR_VISIBLE_MS (вместо alert).
  useEffect(() => {
    if (error == null) return;
    const timer = window.setTimeout(() => setError(null), ERROR_VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [error]);

  /** Перечитать список заданий; ошибки загрузки не забивают интерфейс — старый список остаётся. */
  const refresh = () =>
    fetchAgentScheduler()
      .then(setJobs)
      .catch(() => {
        /* сервис недоступен — список остаётся прежним (или null до первой загрузки) */
      });

  // Список грузим на монтировании + периодически перечитываем: задания создаются/правятся
  // ИЗ ЧАТА агентскими тулами (agent_scheduler_*), панель об этом не знает — обновляемся
  // по таймеру, чтобы добавленное через чат задание появилось без перезагрузки окна.
  useEffect(() => {
    let cancelled = false;
    fetchAgentScheduler()
      .then((list) => {
        if (!cancelled) setJobs(list);
      })
      .catch(() => {
        /* сервис недоступен — пусто до следующего обновления */
      });
    const timer = window.setInterval(() => void refresh(), REFRESH_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  /** Сохранение нового периода: PATCH …/{id}; черновик чистим после успеха. */
  const handleIntervalSave = async (job: AgentSchedulerJob) => {
    const draft = intervalDrafts[job.id];
    const parsed = draft == null ? Number.NaN : Number.parseInt(draft, 10);
    if (!Number.isFinite(parsed) || parsed < MIN_INTERVAL_SECONDS) return;
    setIntervalsBusyId(job.id);
    setError(null);
    try {
      await updateAgentSchedulerJob(job.id, { interval_seconds: parsed });
      setIntervalDrafts((prev) => {
        const next = { ...prev };
        delete next[job.id];
        return next;
      });
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setIntervalsBusyId(null);
    }
  };

  /** Удаление задания: DELETE …/{id}; после успеха — перечитывание списка. */
  const handleDelete = async (id: number) => {
    setRowBusyId(id);
    setError(null);
    try {
      await deleteAgentSchedulerJob(id);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRowBusyId(null);
    }
  };

  return (
    <CollapsibleSection
      className="llm-settings-block"
      title="Планировщик"
      icon="◷"
      hint="задания агента"
      defaultOpen={false}
    >
      {error != null ? (
        <div className="mem-error" role="alert">
          {error}
        </div>
      ) : null}
      {/* Список заданий: имя + состояние + промпт + интервал + времена + удаление.
          Период редактируется на месте (число + «✓» → PATCH …/{id}). */}
      {jobs === null ? (
        <div className="mem-hint">Загрузка…</div>
      ) : jobs.length === 0 ? (
        <div className="mem-empty-inline">Заданий нет</div>
      ) : (
        <div className="mcp-servers">
          {jobs.map((job) => {
            const rowBusy = rowBusyId === job.id;
            const intervalBusy = intervalsBusyId === job.id;
            const draft = intervalDrafts[job.id] ?? String(job.interval_seconds);
            const parsedDraft = Number.parseInt(draft, 10);
            const draftValid =
              Number.isFinite(parsedDraft) &&
              parsedDraft >= MIN_INTERVAL_SECONDS &&
              parsedDraft !== job.interval_seconds;
            return (
              <div key={job.id} className="mcp-server">
                <div className="mcp-server-top">
                  <span
                    className={`mcp-state${job.enabled ? ' is-on' : ''}`}
                    title={job.enabled ? 'Задание активно' : 'Задание отключено'}
                  >
                    {job.enabled ? 'Активен' : 'Отключён'}
                  </span>
                  <span className="mcp-server-name" title={job.name}>
                    {job.name}
                  </span>
                  <button
                    type="button"
                    className="mem-entry-del"
                    title="Удалить задание"
                    aria-label={`Удалить задание «${job.name}»`}
                    disabled={disabled || rowBusy || intervalBusy}
                    onClick={() => void handleDelete(job.id)}
                  >
                    ×
                  </button>
                </div>
                {/* Текст промпта задания — обрезан с многоточием, полный текст в title. */}
                <div className="mcp-server-url" title={job.prompt}>
                  {truncate(job.prompt)}
                </div>
                <div className="mcp-server-url">
                  {formatInterval(job.interval_seconds)} · последний запуск:{' '}
                  {formatTimestamp(job.last_run_at)} · следующий:{' '}
                  {formatTimestamp(job.next_run_at)} · создано: {formatTimestamp(job.created_at)}
                </div>
                {/* Период — редактируется на месте: число секунд + подтверждение «✓».
                    Кнопка активна только при валидном ИЗМЕНЁННОМ значении (per-row guard). */}
                <div className="scheduler-task-meta">
                  <label className="scheduler-interval-label">
                    каждые
                    <input
                      type="number"
                      className="mem-input scheduler-interval-input"
                      min={MIN_INTERVAL_SECONDS}
                      step={5}
                      value={draft}
                      aria-label={`Период задания «${job.name}», секунд`}
                      disabled={disabled || intervalBusy || rowBusy}
                      onChange={(e) =>
                        setIntervalDrafts((prev) => ({ ...prev, [job.id]: e.target.value }))
                      }
                    />
                    с
                  </label>
                  <button
                    type="button"
                    className="mem-btn scheduler-interval-save"
                    disabled={disabled || intervalBusy || rowBusy || !draftValid}
                    title={
                      draftValid
                        ? `Сохранить период: ${parsedDraft} с (PATCH /api/agent-scheduler/${job.id})`
                        : `Введите период от ${MIN_INTERVAL_SECONDS} с`
                    }
                    onClick={() => void handleIntervalSave(job)}
                  >
                    {intervalBusy ? '…' : '✓'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </CollapsibleSection>
  );
}
