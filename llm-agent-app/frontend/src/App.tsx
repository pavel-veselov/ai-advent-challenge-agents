import ChatPanel from './components/ChatPanel';
import StepsLog from './components/StepsLog';
import { useAgentSession } from './hooks/useAgentSession';

export default function App() {
  const session = useAgentSession();
  return (
    <div className="app-shell">
      <ChatPanel
        messages={session.messages}
        isRunning={session.isRunning}
        error={session.error}
        sessionId={session.sessionId}
        onSend={session.sendMessage}
        onStop={session.stopAgent}
        onReset={session.resetSession}
      />
      <StepsLog steps={session.steps} runSettings={session.runSettings} />
    </div>
  );
}
