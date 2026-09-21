import { useEffect, useState } from 'react';
import { addInvariant, deleteInvariant } from '../api';
import type { Invariant } from '../types';
import CollapsibleSection from './CollapsibleSection';

/** Предустановленные категории инвариантов (селект формы добавления) + «без категории» и «другая». */
const CATEGORY_PRESETS = ['Архитектура', 'Техническое решение', 'Стек', 'Бизнес-правило'] as const;

/** Значение селекта «другая категория» (вводится свободной строкой). */
const CATEGORY_OTHER = '__other__';
/** Значение селекта «без категории» (не задавать category). */
const CATEGORY_NONE = '__none__';

/** Сколько миллисекунд висит инлайн-ошибка API, прежде чем погаснуть. */
const ERROR_VISIBLE_MS = 4000;

interface InvariantsPanelProps {
  /** id активного ПРОЕКТА: инварианты общие для всех сессий проекта. */
  projectId: number;
  /** Инварианты активного проекта (GET /projects/{id}/invariants); null — ещё не загружены. */
  invariants: Invariant[] | null;
  /** Перечитать инварианты проекта: REST-мутации бэкенд SSE-событием не сопровождает. */
  refreshInvariants: (projectId: number) => Promise<void>;
  /** true — сервис остановлен/запускается: форма добавления заблокирована. */
  disabled: boolean;
}

/**
 * Панель «Инварианты» (Day-14): инварианты проекта — обязательные ограничения ассистента
 * (архитектура, технические решения, стек, бизнес-правила), которые он не имеет права
 * нарушать. Список инвариантов (категория + текст + кнопка удаления) и форма добавления:
 * селект категории (предустановки / «без категории» / «другая» — свободная строка) +
 * textarea текста. После любой REST-мутации данные перечитываются (refreshInvariants):
 * бэкенд не шлёт SSE-событие на мутации инвариантов.
 */
export default function InvariantsPanel({
  projectId,
  invariants,
  refreshInvariants,
  disabled,
}: InvariantsPanelProps) {
  const [categorySelect, setCategorySelect] = useState<string>(CATEGORY_NONE);
  const [customCategory, setCustomCategory] = useState('');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Ошибка API показывается «коротко»: гасим через ERROR_VISIBLE_MS (вместо alert).
  useEffect(() => {
    if (error == null) return;
    const timer = window.setTimeout(() => setError(null), ERROR_VISIBLE_MS);
    return () => window.clearTimeout(timer);
  }, [error]);

  /** Итоговая категория для API: null — «без категории»; «другая» — свободная строка. */
  const resolveCategory = (): string | null => {
    if (categorySelect === CATEGORY_NONE) return null;
    if (categorySelect === CATEGORY_OTHER) return customCategory.trim() || null;
    return categorySelect;
  };

  const canSubmit = text.trim() !== '' && !busy;

  const handleAdd = async () => {
    const body = text.trim();
    if (body === '') return;
    setBusy(true);
    try {
      await addInvariant(projectId, { category: resolveCategory(), text: body });
      setText('');
      setCategorySelect(CATEGORY_NONE);
      setCustomCategory('');
      await refreshInvariants(projectId);
    } catch (e) {
      setError(`Не удалось сохранить инвариант${e instanceof Error ? `: ${e.message}` : ''}`);
    } finally {
      setBusy(false);
    }
  };

  const handleDelete = async (id: number) => {
    setBusy(true);
    try {
      await deleteInvariant(projectId, id);
      await refreshInvariants(projectId);
    } catch (e) {
      setError(`Не удалось удалить инвариант${e instanceof Error ? `: ${e.message}` : ''}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <CollapsibleSection
      className="llm-settings-block"
      title="Инварианты"
      icon="⚖"
      hint="обязательные ограничения ассистента"
    >
      {/* Список инвариантов проекта (категория + текст + удаление). */}
      {invariants === null ? (
        <div className="mem-hint">Загрузка…</div>
      ) : invariants.length === 0 ? (
        <div className="mem-empty-inline">Инвариантов нет</div>
      ) : (
        <div className="mem-entries">
          {invariants.map((inv) => (
            <div key={inv.id} className="mem-entry">
              <div className="mem-entry-top">
                {inv.category != null ? (
                  <span className="mem-type-badge is-knowledge" title={inv.category}>
                    {inv.category}
                  </span>
                ) : (
                  <span className="mem-hint">без категории</span>
                )}
                <button
                  type="button"
                  className="mem-entry-del"
                  title="Удалить инвариант"
                  aria-label="Удалить инвариант"
                  disabled={busy}
                  onClick={() => void handleDelete(inv.id)}
                >
                  ×
                </button>
              </div>
              <div className="mem-entry-value">{inv.text}</div>
            </div>
          ))}
        </div>
      )}

      {/* Форма добавления инварианта: категория (селект) + текст (textarea). */}
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
        <select
          className="mem-input mem-form-type"
          value={categorySelect}
          onChange={(e) => setCategorySelect(e.target.value)}
          disabled={disabled || busy}
          aria-label="Категория инварианта"
        >
          <option value={CATEGORY_NONE}>— без категории —</option>
          {CATEGORY_PRESETS.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
          <option value={CATEGORY_OTHER}>другая…</option>
        </select>
        {categorySelect === CATEGORY_OTHER ? (
          <input
            className="mem-input"
            placeholder="Своя категория"
            value={customCategory}
            onChange={(e) => setCustomCategory(e.target.value)}
            disabled={disabled || busy}
            aria-label="Своя категория инварианта"
            maxLength={120}
          />
        ) : null}
        <textarea
          className="mem-input"
          placeholder="Текст инварианта"
          value={text}
          onChange={(e) => setText(e.target.value)}
          disabled={disabled || busy}
          aria-label="Текст инварианта"
          rows={3}
          maxLength={2000}
        />
        <button
          type="submit"
          className="mem-btn mem-btn-primary"
          disabled={!canSubmit || disabled}
          title={canSubmit ? 'Добавить инвариант: POST /api/projects/{id}/invariants' : 'Введите текст инварианта'}
        >
          {busy ? 'Сохранение…' : 'Добавить'}
        </button>
      </form>
    </CollapsibleSection>
  );
}
