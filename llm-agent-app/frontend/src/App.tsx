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
        backendStopped={session.backendStopped}
        backendStarting={session.backendStarting}
        error={session.error}
        sessionId={session.sessionId}
        onSend={session.sendMessage}
        onStop={session.stopAgent}
        onDeleteSession={session.deleteSession}
      />
      <StepsLog
        steps={session.steps}
        runSettings={session.runSettings}
        backendStopped={session.backendStopped}
        backendStarting={session.backendStarting}
        onStop={session.stopService}
        onStart={session.startService}
      />
    </div>
  );
}
