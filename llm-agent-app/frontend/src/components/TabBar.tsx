interface TabBarProps {
  tabs: string[];
  activeId: string | null;
  /** Заголовки вкладок: sessionId -> первое сообщение пользователя (пусто — fallback на id). */
  titles: Record<string, string>;
  /** true — сервис занят (run/стоп/старт): все кнопки вкладок заблокированы. */
  disabled: boolean;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onNew: () => void;
}

const TITLE_MAX = 24;

/** Полоса вкладок сессий над основной областью: переключение, «+», закрытие (×). */
export default function TabBar({ tabs, activeId, titles, disabled, onSelect, onClose, onNew }: TabBarProps) {
  return (
    <div className="tab-bar">
      {tabs.map((id) => {
        const raw = titles[id];
        const hasTitle = raw != null && raw.trim() !== '';
        // Заголовок: первое сообщение пользователя; fallback — первые 8 символов id.
        const title = hasTitle ? raw.trim() : id.slice(0, 8);
        const label = title.length > TITLE_MAX ? `${title.slice(0, TITLE_MAX)}…` : title;
        return (
          <div key={id} className={`tab${id === activeId ? ' active' : ''}`}>
            <button
              type="button"
              className="tab-label"
              title={hasTitle ? title : id}
              onClick={() => onSelect(id)}
              disabled={disabled}
            >
              {label}
            </button>
            <button
              type="button"
              className="tab-close"
              title="Удалить сессию"
              onClick={() => onClose(id)}
              disabled={disabled}
            >
              ×
            </button>
          </div>
        );
      })}
      <button type="button" className="tab-add" title="Новая сессия" onClick={onNew} disabled={disabled}>
        +
      </button>
    </div>
  );
}
