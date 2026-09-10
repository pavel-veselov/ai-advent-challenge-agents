import ChatPanel from './components/ChatPanel';
import LlmSettings from './components/LlmSettings';
import StatsBar from './components/StatsBar';
import StepsLog from './components/StepsLog';
import TabBar from './components/TabBar';
import { useAgentSession } from './hooks/useAgentSession';

export default function App() {
  const session = useAgentSession();
  const tabDisabled = session.isRunning || session.backendStopped || session.backendStarting;
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
        <div className="steps-column">
          <LlmSettings
            settings={session.runSettings}
            disabled={tabDisabled}
            onUpdate={session.updateLlmSettings}
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
