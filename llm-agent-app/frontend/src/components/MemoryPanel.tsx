import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { addLongTermMemory, deleteLongTermMemory } from '../api';
import type { LongTermEntry, MemoryState } from '../types';

/** Русские метки типов долговременной памяти (бейдж + селект формы добавления). */
const LTM_TYPE_LABELS: Record<LongTermEntry['type'], string> = {
  profile: 'Профиль',
  decision: 'Решение',
  knowledge: 'Знание',
};

/** Порядок типов в селекте формы добавления. */
const LTM_TYPES: LongTermEntry['type'][] = ['profile', 'decision', 'knowledge'];

/** Сколько миллисекунд висит инлайн-ошибка API, прежде чем погаснуть. */
const ERROR_VISIBLE_MS = 4000;

interface MemoryPanelProps {
  /** id активного ПРОЕКТА: рабочая память общая для всех сессий проекта. */
  projectId: number;
  /** id активной сессии — источник записи при добавлении/удалении LTM (LTM глобальна). */
  sessionId: string;
  /** Память активного проекта (GET /projects/{id}/memory + событие memory_updated); null — ещё не загружена. */
  memory: MemoryState | null;
  /** Новая задача: сброс рабочей памяти проекта (POST /projects/{id}/memory/new-task) + локальное перечитывание. */
  newTask: (projectId: number) => Promise<void>;
  /** Перечитать память проекта: REST-мутации бэкенд SSE-событием не сопровождает. */
  refreshMemory: (projectId: number) => Promise<void>;
}

/** Статическая секция панели: заголовок-метка без сворачивания, содержимое видно всегда. */
function Section({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mem-section">
      <div className="mem-section-label">{label}</div>
      {children}
    </div>
  );
}

/**
 * Панель «Память»: три слоя памяти агента.
 * - Краткосрочная — вся история диалога (chat_messages), только пояснение;
 * - Рабочая — память ПРОЕКТА: текущая задача и заметки, общие для всех сессий проекта
 *   (только чтение + кнопка «Сбросить рабочую память»);
 * - Долговременная — записи профиль/решение/знание: просмотр, добавление, удаление
 *   (LTM глобальна; sessionId нужен только как источник новой записи).
 * После любой REST-мутации данные перечитываются (refreshMemory(projectId)): бэкенд не шлёт
 * SSE-событие на мутации памяти.
 */
export default function MemoryPanel({
  projectId,
  sessionId,
  memory,
  newTask,
  refreshMemory,
}: MemoryPanelProps) {
  const [formType, setFormType] = useState<LongTermEntry['type']>('profile');
  const [formKey, setFormKey] = useState('');
  const [formValue, setFormValue] = useState('');
  const [error, setError] = useState<string | null>(null);

  // Ошибка API показывается «коротко»: гасим через ERROR_VISIBLE_MS (вместо alert).
  useEffect(() => {
    if (error == null) return;
    const timer = window.setTimeout(() => setError(null), ERROR_VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [error]);

  /** Добавление записи долговременной памяти, затем перечитывание памяти проекта. */
  const handleAdd = async () => {
    const key = formKey.trim();
    const value = formValue.trim();
    if (key === '' || value === '') return;
    try {
      await addLongTermMemory(sessionId, { type: formType, key, value });
      setFormKey('');
      setFormValue('');
      await refreshMemory(projectId);
    } catch (e) {
      setError(`Не удалось сохранить запись${e instanceof Error ? `: ${e.message}` : ''}`);
    }
  };

  /** Удаление записи долговременной памяти, затем перечитывание памяти проекта. */
  const handleDelete = async (entryId: number) => {
    try {
      await deleteLongTermMemory(sessionId, entryId);
      await refreshMemory(projectId);
    } catch (e) {
      setError(`Не удалось удалить запись${e instanceof Error ? `: ${e.message}` : ''}`);
    }
  };

  /** Новая задача: сброс рабочей памяти проекта (ошибку глотает сам хук). */
  const handleNewTask = () => {
    void newTask(projectId);
  };

  const canSubmit = formKey.trim() !== '' && formValue.trim() !== '';

  return (
    <section className="mem-panel steps-panel">
      <header className="steps-header">
        <h2>Память</h2>
      </header>
      <div className="mem-body">
        <Section label="Краткосрочная (диалог)">
          <div className="mem-hint">
            Полная история диалога сессии хранится на бэкенде (таблица chat_messages) и
            передаётся модели целиком. Что именно попадает в промпт — определяет стратегия
            контекста (см. блок «Настройки LLM»).
          </div>
        </Section>

        <Section label="Рабочая память (проект)">
          {memory === null ? (
            <div className="mem-loading">Загрузка…</div>
          ) : (
            <>
              <div className="mem-wm-row">
                {memory.working.task ? (
                  <div className="mem-wm-task">{memory.working.task}</div>
                ) : (
                  <div className="mem-empty-inline">Задача не активна</div>
                )}
                <button
                  type="button"
                  className="mem-btn"
                  title="Сбросить рабочую память проекта: задача и заметки очищаются, начинается новая задача"
                  onClick={handleNewTask}
                >
                  Сбросить рабочую память
                </button>
              </div>
              {memory.working.notes.length > 0 ? (
                <ol className="mem-wm-notes">
                  {memory.working.notes.map((note, i) => (
                    <li key={i} className="mem-wm-note">
                      {note}
                    </li>
                  ))}
                </ol>
              ) : null}
            </>
          )}
        </Section>

        <Section label="Долговременная (профиль, решения, знания)">
          {memory === null ? (
            <div className="mem-loading">Загрузка…</div>
          ) : memory.longTerm.length === 0 ? (
            <div className="mem-empty-inline">
              Записей пока нет — агент сохранит важное через инструмент, или добавьте вручную
            </div>
          ) : (
            <div className="mem-entries">
              {memory.longTerm.map((entry) => (
                <div key={entry.id} className="mem-entry">
                  <div className="mem-entry-top">
                    <span className={`mem-type-badge is-${entry.type}`}>
                      {LTM_TYPE_LABELS[entry.type]}
                    </span>
                    <span className="mem-entry-key" title={entry.key}>
                      {entry.key}
                    </span>
                    <button
                      type="button"
                      className="mem-entry-del"
                      title="Удалить запись долговременной памяти"
                      onClick={() => void handleDelete(entry.id)}
                    >
                      ×
                    </button>
                  </div>
                  <div className="mem-entry-value">{entry.value}</div>
                  <div className="mem-entry-src">источник: {entry.sourceSessionId}</div>
                </div>
              ))}
            </div>
          )}

          <form
            className="mem-form"
            onSubmit={(e) => {
              e.preventDefault();
              void handleAdd();
            }}
          >
            {error != null ? (
              <div className="mem-error" role="alert">
                {error}
              </div>
            ) : null}
            <div className="mem-form-row">
              <select
                className="mem-input mem-form-type"
                value={formType}
                onChange={(e) => setFormType(e.target.value as LongTermEntry['type'])}
                aria-label="Тип записи"
              >
                {LTM_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {LTM_TYPE_LABELS[t]}
                  </option>
                ))}
              </select>
              <input
                className="mem-input"
                placeholder="Ключ"
                value={formKey}
                onChange={(e) => setFormKey(e.target.value)}
                aria-label="Ключ записи"
              />
            </div>
            <input
              className="mem-input"
              placeholder="Значение"
              value={formValue}
              onChange={(e) => setFormValue(e.target.value)}
              aria-label="Значение записи"
            />
            <div className="mem-form-row">
              <button
                type="submit"
                className="mem-btn mem-btn-primary"
                disabled={!canSubmit}
                title={
                  canSubmit
                    ? 'Добавить запись: POST /api/sessions/{id}/memory/long-term'
                    : 'Заполните ключ и значение'
                }
              >
                Сохранить
              </button>
            </div>
          </form>
        </Section>
      </div>
    </section>
  );
}
