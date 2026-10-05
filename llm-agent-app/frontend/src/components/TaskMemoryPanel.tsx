import type { TaskMemory } from '../types';
import CollapsibleSection from './CollapsibleSection';

/** Обучающий title шапки панели: что такое «Память задачи» и откуда берутся данные. */
const TITLE_PANEL =
  'Структурированная память диалога: цель, что пользователь уже уточнил и зафиксированные ' +
  'ограничения/термины. Бэкенд обновляет её после каждого ответа агента и подставляет в промпт, ' +
  'чтобы в длинном диалоге не терялась цель. Панель обновляется вживую по мере переписки.';

interface TaskMemoryPanelProps {
  /** Память задачи активной сессии (из useAgentSession); null — ещё не загружена или сессии нет. */
  memory: TaskMemory | null;
}

/** Один пункт списка памяти: моно-нумерация слева, текст в тихой карточке (идиома .mem-wm-note). */
function MemoryItem({ item, index }: { item: string; index: number }) {
  return <li className="tmem-item">{`${index + 1}. ${item}`}</li>;
}

/** Список строк секции; пустой список — тихая моно-прочерк (место держится за структурой). */
function MemoryList({ items }: { items: string[] }) {
  if (items.length === 0) {
    return <div className="tmem-none">—</div>;
  }
  return (
    <ol className="tmem-list">
      {items.map((item, i) => (
        <MemoryItem key={i} item={item} index={i} />
      ))}
    </ol>
  );
}

/**
 * Панель «Память задачи» (Day-25): структурированное состояние диалога активной сессии —
 * цель (Цель), что пользователь уже уточнил (Уточнено пользователем) и зафиксированные
 * ограничения/термины (Ограничения и термины). Данные приходят из useAgentSession:
 * первичная загрузка — REST GET /api/sessions/{id}/task-memory при открытии сессии,
 * живое обновление — SSE-событие task_memory_updated после каждого ответа агента.
 * Панель только читает состояние (мутаций нет — память ведёт сам бэкенд), поэтому
 * проп disabled ей не нужен. Всё пусто — дружелюбное «Пока пусто — уточните запрос».
 */
export default function TaskMemoryPanel({ memory }: TaskMemoryPanelProps) {
  const empty =
    memory == null ||
    (memory.goal === '' && memory.clarifications.length === 0 && memory.constraints.length === 0);

  return (
    <CollapsibleSection
      className="llm-settings-block"
      title="Память задачи"
      icon="✦"
      hint="цель диалога, уточнения и ограничения — обновляется по ходу переписки"
      // ⓘ в шапке несёт обучающий summary-title: нативная подсказка «зачем эта панель».
      headerExtra={
        <span className="llm-hint" aria-hidden="true" title={TITLE_PANEL}>
          ⓘ
        </span>
      }
    >
      {empty ? (
        <div className="mem-empty-inline">Пока пусто — уточните запрос</div>
      ) : (
        <div className="tmem-body">
          {/* Цель — герой-блок с акцентной плашкой (идиома .mem-wm-task): главное, что агент держит. */}
          <div className="mem-section">
            <div className="mem-section-label">Цель</div>
            {memory.goal !== '' ? (
              <div className="tmem-goal">{memory.goal}</div>
            ) : (
              <div className="tmem-none">—</div>
            )}
          </div>
          <div className="mem-section">
            <div className="mem-section-label">Уточнено пользователем</div>
            <MemoryList items={memory.clarifications} />
          </div>
          <div className="mem-section">
            <div className="mem-section-label">Ограничения и термины</div>
            <MemoryList items={memory.constraints} />
          </div>
          {/* Время последнего обновления: моно-метаданные (идиома .kb-meta); null — ещё не было. */}
          {memory.updatedAt != null ? (
            <div className="tmem-meta">
              обновлено: {new Date(memory.updatedAt).toLocaleTimeString('ru-RU', { hour12: false })}
            </div>
          ) : null}
        </div>
      )}
    </CollapsibleSection>
  );
}
