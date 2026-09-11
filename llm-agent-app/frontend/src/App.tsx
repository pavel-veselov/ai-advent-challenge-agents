import ChatPanel from './components/ChatPanel';
import LlmSettings from './components/LlmSettings';
import StatsBar from './components/StatsBar';
import StepsLog from './components/StepsLog';
import TabBar from './components/TabBar';
import { useAgentSession } from './hooks/useAgentSession';

export default function App() {
  const session = useAgentSession();
  // Вкладки блокируем только при неработающем/запускающемся бэкенде: переключение и
  // создание сессий разрешены всегда, даже пока какая-то сессия стримит.
  const tabDisabled = session.backendStopped || session.backendStarting;
  // Настройки LLM: блокируем при неработающем бэкенде ИЛИ пока активная сессия «думает»
  // (менять настройки посреди выполнения собственного запуска сомнительно).
  const llmSettingsDisabled = tabDisabled || session.isRunning;
  return (
    <div className="app-shell">
      <TabBar
        tabs={session.tabs}
        activeId={session.activeId}
        titles={session.titles}
        disabled={tabDisabled}
        onSelect={session.switchSession}
        onClose={session.closeSession}
        onNew={session.newSession}
      />
      <div className="app-main">
        {session.tabs.length === 0 ? (
          <div className="app-empty">
            <p className="app-empty-title">Сессий пока нет</p>
            <p className="app-empty-hint">
              Создайте сессию — после этого станет доступен чат, а настройки LLM можно
              менять сразу, до первого сообщения.
            </p>
            <button
              type="button"
              className="app-empty-btn"
              onClick={session.newSession}
              disabled={tabDisabled}
            >
              Создать сессию
            </button>
          </div>
        ) : (
          <ChatPanel
            messages={session.messages}
            isRunning={session.isRunning}
            backendStopped={session.backendStopped}
            backendStarting={session.backendStarting}
            error={session.error}
            sessionId={session.sessionId}
            onSend={session.sendMessage}
            onStop={session.stopAgent}
            onDeleteSession={session.deleteSession}
          />
        )}
        <div className="steps-column">
          <LlmSettings
            settings={session.runSettings}
            sessionId={session.sessionId}
            disabled={llmSettingsDisabled}
          />
          <StepsLog
            steps={session.steps}
            backendStopped={session.backendStopped}
            backendStarting={session.backendStarting}
            onStop={session.stopService}
            onStart={session.startService}
          />
        </div>
      </div>
      <StatsBar
        tokenTotals={session.tokenTotals}
        lastPromptTokens={session.lastPromptTokens}
        runSettings={session.runSettings}
        globalStats={session.globalStats}
      />
    </div>
  );
}
