import type { RunSettings, StatsResponse, TokenTotals } from '../types';

interface StatsBarProps {
  /** Накопительные токены и стоимость активной сессии. */
  tokenTotals: TokenTotals;
  /** Промпт-токены последнего реального запроса в LLM — текущий размер контекста сессии; null — запросов ещё не было. */
  lastPromptTokens: number | null;
  /** Настройки LLM активной сессии (для лимита контекста); null — настройки не загружены. */
  runSettings: RunSettings | null;
  /** Глобальная статистика по всем сессиям; null — ещё не загружена (показываем «…»). */
  globalStats: StatsResponse | null;
}

/** Нижняя полоса статистики на всю ширину: текущая сессия слева, итоги за всё время справа. */
export default function StatsBar({ tokenTotals, lastPromptTokens, runSettings, globalStats }: StatsBarProps) {
  const contextLimit = runSettings?.contextLimit;
  const limit = contextLimit != null && contextLimit > 0 ? contextLimit : null;
  // Занято/осталось считаются от РАЗМЕРА КОНТЕКСТА последнего запроса (prompt_tokens последнего
  // assistant-сообщения), а не от кумулятивной суммы промпта диалога. Нет ни одного запроса — занято 0%.
  const contextSize = lastPromptTokens ?? 0;
  const pct =
    limit != null ? Math.min(100, Math.max(0, Math.round((contextSize / limit) * 100))) : null;
  const remaining = limit != null && lastPromptTokens != null ? Math.max(0, limit - lastPromptTokens) : null;

  // Итоги за всё время: если бэкенд отдаёт lifetime (переживает удаление сессий) — берём его,
  // иначе (старый ответ) — текущие агрегаты по существующим сессиям.
  const lif = globalStats != null && globalStats.lifetime != null ? globalStats.lifetime : null;
  const allPrompt = lif != null ? lif.promptTokens : globalStats?.promptTokens ?? null;
  const allCompletion = lif != null ? lif.completionTokens : globalStats?.completionTokens ?? null;
  const allCost = lif != null ? lif.costUsd : globalStats?.costUsd ?? null;
  const allTotal = allPrompt != null && allCompletion != null ? allPrompt + allCompletion : null;
  const fmtSlot = (v: number | null) => (v != null ? v : '…');
  const allCostStr = allCost != null ? allCost.toFixed(4) : '…';

  return (
    <footer className="stats-bar">
      <div className="stats-section">
        <span className="stats-label">Текущая сессия:</span>
        <span
          className="stats-badge"
          title="Каждый запрос пересылает историю заново — сумма больше текущего контекста"
        >
          вход <b>{tokenTotals.promptTokens}</b> · ответ <b>{tokenTotals.completionTokens}</b> ·
          всего <b>{tokenTotals.promptTokens + tokenTotals.completionTokens}</b>
        </span>
        {limit != null && pct != null ? (
          <span className="stats-badge" title={`Лимит контекста модели: ${limit} входных токенов`}>
            лимит контекста модели <b>{limit}</b> · занято <b>{pct}%</b>
          </span>
        ) : null}
        {remaining != null ? (
          <span
            className="stats-badge"
            title={`Остаток контекста: ${limit} − ${lastPromptTokens} последнего запроса = ${remaining} токенов`}
          >
            осталось <b>{remaining}</b>
          </span>
        ) : null}
      </div>
      <div className="stats-section">
        <span className="stats-label">За все время:</span>
        <span
          className="stats-badge"
          title="Каждый запрос пересылает историю заново — сумма больше текущего контекста"
        >
          вход <b>{fmtSlot(allPrompt)}</b> · ответ{' '}
          <b>{fmtSlot(allCompletion)}</b> · всего <b>{fmtSlot(allTotal)}</b> · ~$
          <b>{allCostStr}</b>
        </span>
      </div>
    </footer>
  );
}
