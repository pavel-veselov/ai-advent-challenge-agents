import { useState } from 'react';
import type { ReactNode } from 'react';

interface CollapsibleSectionProps {
  /** Заголовок панели: текст кнопки в шапке, клик сворачивает/разворачивает блок. */
  title: string;
  /** Иконка-глиф блока перед заголовком (монохромный юникод, уникальна у каждой панели). */
  icon?: string;
  /** Подсказка-описание панели: строкой под кнопкой заголовка (llm-hint);
      видна и в свёрнутом состоянии. */
  hint?: string;
  /** Доп. содержимое шапки (индикатор сохранения и т.п.); видимо всегда. */
  headerExtra?: ReactNode;
  /** Состояние при загрузке: true — развёрнута, false (по умолчанию) — свёрнута. */
  defaultOpen?: boolean;
  /** Класс секции-оболочки (стиль приборной карточки): llm-settings-block и т.п. */
  className?: string;
  /** Тело панели: монтируется только в развёрнутом состоянии (как в LlmSettings) —
      скрытое содержимое не попадает в табуляцию. */
  children: ReactNode;
}

/**
 * Кодовые точки с emoji-презентацией по умолчанию (Unicode Emoji_Presentation=YES):
 * без вариационного селектора реальный Chrome/Windows рисует их ЧЕРЕЗ эмодзи-шрифт —
 * цветными (фиолетовый атом, золотые весы, оранжевый трилистник), игнорируя CSS color.
 * VS15 (\uFE0E) форсирует монохромный текстовый глиф. ☉ (U+2609) вне emoji-таблиц —
 * ему селектор не нужен.
 */
const EMOJI_DEFAULT_GLYPHS = new Set(['\u2699', '\u269B', '\u2696', '\u2622']);

/** Единая точка нормализации: панели передают «сырой» символ, DOM получает глиф + VS15. */
function forceTextPresentation(icon: string): string {
  return EMOJI_DEFAULT_GLYPHS.has([...icon][0] ?? '') ? `${icon}\uFE0E` : icon;
}

/**
 * Оболочка сворачиваемой панели колонки (общая механика всех пяти блоков: «Профиль
 * пользователя», «Настройки LLM», «Воркфлоу», «Инварианты», «MCP-серверы»). Шапка —
 * кнопка .llm-settings-toggle со стрелкой-шевроном: ▸ свёрнут, ▾ развёрнут, перед
 * заголовком — иконка-глиф блока (.collapsible-icon). Шапка двухэтажная: сверху строка
 * заголовка (кнопка + headerExtra справа), под ней строка-подсказка .llm-hint.
 * Состояние простое useState внутри оболочки (без персистентности); стартовое задаёт
 * defaultOpen.
 */
export default function CollapsibleSection({
  title,
  icon,
  hint,
  headerExtra,
  defaultOpen = false,
  className = 'llm-settings-block',
  children,
}: CollapsibleSectionProps) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <section className={className}>
      <header className="llm-settings-block-header">
        <div className="llm-settings-block-header-row">
          <h2>
            <button
              type="button"
              className="llm-settings-toggle"
              aria-expanded={open}
              title={open ? 'Свернуть блок' : 'Развернуть блок'}
              onClick={() => setOpen((v) => !v)}
            >
              {icon != null ? (
                <span className="collapsible-icon" aria-hidden="true">
                  {forceTextPresentation(icon)}
                </span>
              ) : null}
              {title}
              <span className="llm-settings-chevron" aria-hidden="true">
                {open ? '▾' : '▸'}
              </span>
            </button>
          </h2>
          {headerExtra}
        </div>
        {hint != null ? <span className="llm-hint">{hint}</span> : null}
      </header>
      {open ? <>{children}</> : null}
    </section>
  );
}
