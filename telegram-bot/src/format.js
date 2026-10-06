/**
 * Конвертация Markdown → Telegram HTML (parse_mode: "HTML").
 * Порядок важен: fenced-блоки → inline-код → экранирование → остальная разметка.
 */

/** Экранирование спецсимволов HTML. */
function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

/** Конвертирует markdown-текст в Telegram-совместимый HTML. */
export function markdownToTelegramHtml(text) {
  if (!text) return "";

  const stash = [];
  const stashHtml = (html) => {
    stash.push(html);
    return `\u0000${stash.length - 1}\u0000`;
  };

  let result = String(text);

  // 1. Fenced-блоки кода: ```lang\n...\n``` → <pre><code class="language-lang">
  result = result.replace(/```([^\n`]*)\n?([\s\S]*?)```/g, (_m, lang, code) => {
    const language = String(lang).trim();
    const cls = language ? ` class="language-${escapeHtml(language)}"` : "";
    return stashHtml(`<pre><code${cls}>${escapeHtml(code)}</code></pre>`);
  });

  // 2. Inline-код: `код` → <code>
  result = result.replace(/`([^`\n]+)`/g, (_m, code) => stashHtml(`<code>${escapeHtml(code)}</code>`));

  // 3. Экранируем оставшийся текст
  result = escapeHtml(result);

  // 4. Разметка: ссылки, жирный, маркеры списка, курсив, зачёркнутый, заголовки
  result = result.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2">$1</a>');
  result = result.replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>");
  result = result.replace(/^([ \t]*)(?:- |\* )/gm, "$1• ");
  result = result.replace(/\*([^*\n]+)\*/g, "<i>$1</i>");
  result = result.replace(/~~([^~]+)~~/g, "<s>$1</s>");
  result = result.replace(/^#{1,6} (.+)$/gm, "<b>$1</b>");

  // 5. Восстанавливаем защищённые фрагменты
  result = result.replace(/\u0000(\d+)\u0000/g, (_m, index) => stash[Number(index)] ?? "");

  return result;
}
