import { useRef } from 'react';
import { AgentOption, ChatTab, PendingHandoffContext, TabUiFlags } from '../types/chat';
import { AgentManagementView } from './AgentManagement';
import { DesignSystemView } from './DesignSystem';
import HistoryPanel from './HistoryPanel';
import { KanbanBoardView } from './kanban/KanbanBoardView';
import { McpServersView } from './McpServersView';
import { PromptLibraryView } from './PromptLibraryView';
import { SettingsView } from './SettingsView';
import { SystemInstructionsView } from './SystemInstructionsView';
import ChatSessionView from './chat/ChatSessionView';

interface AppTabContentProps {
  tab: ChatTab;
  isActive: boolean;
  tabs: ChatTab[];
  tabUi: Record<string, TabUiFlags>;
  availableAgents: AgentOption[];
  runnableAgents: AgentOption[];
  pendingHandoff?: PendingHandoffContext;
  onOpenHistory: Parameters<typeof HistoryPanel>[0]['onOpenSession'];
  onSelectTab: (id: string) => void;
  onUserMessageSent: () => void;
  onRenamed: (title: string) => void;
  onAssistantActivity: () => void;
  onAtBottomChange: (isAtBottom: boolean) => void;
  onCanMarkReadChange: (canMarkRead: boolean) => void;
  onPermissionRequestChange: (hasPendingPermission: boolean) => void;
  onProcessingChange: (isProcessing: boolean) => void;
  onStatusChange: (status: string) => void;
  onReviewChange: (hasPendingReview: boolean) => void;
  onSessionMetaChange: (meta: { modelId?: string; adapterDisplayName?: string }) => void;
  onAgentChangeRequest: Parameters<typeof ChatSessionView>[0]['onAgentChangeRequest'];
  onForkRequest: Parameters<typeof ChatSessionView>[0]['onForkRequest'];
  onHandoffConsumed: (handoffId: string) => void;
  onSessionStateChange: Parameters<typeof ChatSessionView>[0]['onSessionStateChange'];
}

export function AppTabContent({
  tab,
  isActive,
  tabs,
  tabUi,
  availableAgents,
  runnableAgents,
  pendingHandoff,
  onOpenHistory,
  onSelectTab,
  onUserMessageSent,
  onRenamed,
  onAssistantActivity,
  onAtBottomChange,
  onCanMarkReadChange,
  onPermissionRequestChange,
  onProcessingChange,
  onStatusChange,
  onReviewChange,
  onSessionMetaChange,
  onAgentChangeRequest,
  onForkRequest,
  onHandoffConsumed,
  onSessionStateChange,
}: AppTabContentProps) {
  // Singleton tabs (non-chat) mount lazily on first activation to avoid eager
  // network requests and polling from tabs the user has not opened yet.
  // Once mounted they remain in the DOM (keep-alive) like chat tabs.
  const hasBeenActiveRef = useRef(false);
  if (isActive) hasBeenActiveRef.current = true;

  return (
    <div className={`absolute inset-0 w-full h-full bg-background ${isActive ? 'z-10 visible' : 'z-0 invisible'}`}>
      {/* Chat tabs always mount immediately — their ACP session may be triggered externally. */}
      {tab.type === 'chat' && (
        <ChatSessionView
          initialAgentId={tab.agentId}
          conversationId={tab.conversationId}
          historySession={tab.historySession}
          pendingHandoff={pendingHandoff}
          initialMessages={tab.initialMessages}
          metadataTitleOverride={tab.metadataTitleOverride}
          inheritedAdapterNames={tab.inheritedAdapterNames}
          forkBase={tab.forkBase}
          availableAgents={runnableAgents}
          isActive={isActive}
          onUserMessageSent={onUserMessageSent}
          onRenamed={onRenamed}
          onAssistantActivity={onAssistantActivity}
          onAtBottomChange={onAtBottomChange}
          onCanMarkReadChange={onCanMarkReadChange}
          onPermissionRequestChange={onPermissionRequestChange}
          onProcessingChange={onProcessingChange}
          onStatusChange={onStatusChange}
          onReviewChange={onReviewChange}
          onSessionMetaChange={onSessionMetaChange}
          onAgentChangeRequest={onAgentChangeRequest}
          onForkRequest={onForkRequest}
          onHandoffConsumed={onHandoffConsumed}
          onSessionStateChange={onSessionStateChange}
        />
      )}
      {tab.type !== 'chat' && hasBeenActiveRef.current && (
        <>
          {tab.type === 'management' && <AgentManagementView initialAgents={availableAgents} isActive={isActive} />}
          {tab.type === 'design' && <DesignSystemView />}
          {tab.type === 'history' && (
            <HistoryPanel availableAgents={availableAgents} onOpenSession={onOpenHistory} />
          )}
          {tab.type === 'mcp' && <McpServersView />}
          {tab.type === 'kanban' && (
            <KanbanBoardView tabs={tabs} tabUi={tabUi} onSelectTab={onSelectTab} onOpenHistorySession={onOpenHistory} />
          )}
          {tab.type === 'prompt-library' && <PromptLibraryView />}
          {tab.type === 'system-instructions' && <SystemInstructionsView />}
          {tab.type === 'settings' && <SettingsView />}
        </>
      )}
    </div>
  );
}
