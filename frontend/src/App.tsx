import { useEffect } from 'react';
import TabBar from './components/TabBar';
import { AppTabContent } from './components/AppTabContent';
import { EmptyStateView } from './components/EmptyStateView';
import ConfirmationModal from './components/ConfirmationModal';
import { WorkspaceRail } from './components/workspace/WorkspaceRail';
import { useAppController } from './hooks/app/useAppController';
import { ACPBridge } from './utils/bridge';

function App() {
  useEffect(() => {
    const userMessageBgMap: Record<string, string> = {
      'default': 'var(--ide-user-message-default-bg)',
      'blue': 'var(--ide-user-message-blue-bg)',
      'background-secondary': 'var(--ide-background-secondary)',
      'primary': 'var(--ide-Button-default-startBackground)',
      'secondary': 'var(--ide-Button-startBackground)',
      'accent': 'var(--ide-List-selectionBackground)',
      'input': 'var(--ide-TextField-background)',
      'editor-bg': 'var(--ide-editor-bg)',
    };

    const applyGlobalSettings = (payload: { settings?: { uiFontSizeOffsetPx?: number; userMessageBackgroundStyle?: string } } | undefined) => {
      const offset = payload?.settings?.uiFontSizeOffsetPx ?? 0;
      document.documentElement.style.setProperty('--ui-font-size-offset', `${offset}px`);

      const styleId = payload?.settings?.userMessageBackgroundStyle ?? 'default';
      const bg = userMessageBgMap[styleId] ?? userMessageBgMap['default'];
      document.documentElement.style.setProperty('--user-message-bg', bg);
    };

    const cleanup = ACPBridge.onGlobalSettings((e) => {
      applyGlobalSettings(e.detail?.payload);
    });

    const requestSettings = () => ACPBridge.loadGlobalSettings();

    if (window.__settingsBridgeReady) {
      requestSettings();
    } else {
      window.addEventListener('settings-bridge-ready', requestSettings);
    }

    return () => {
      cleanup();
      window.removeEventListener('settings-bridge-ready', requestSettings);
    };
  }, []);

  const {
    tabs,
    visibleTabs,
    activeTabId,
    tabUi,
    workspaces,
    activeWorkspaceId,
    workspaceAddError,
    clearWorkspaceAddError,
    handleSelectWorkspace,
    handleRenameWorkspace,
    handleRemoveWorkspace,
    handleAddWorkspace,
    availableAgents,
    runnableAgents,
    agentAvailabilityResolved,
    pendingAgentSwitch,
    pendingAgentName,
    pendingHandoffsByTab,
    handleSelectTab,
    handleReorderTabs,
    handleCloseTab,
    handleCloseAllTabs,
    handleNewTab,
    handleOpenHistory,
    openSingletonTab,
    handleUserMessageSent,
    handleTabRenamed,
    handleAssistantActivity,
    handleAtBottomChange,
    handleCanMarkReadChange,
    handlePermissionRequestChange,
    handleProcessingChange,
    handleStatusChange,
    handleReviewChange,
    handleSessionMetaChange,
    requestAgentSwitch,
    handleHandoffConsumed,
    handleForkRequest,
    handleChatSessionStateChange,
    handleContinueInNewTab,
    handleContinueInCurrentConversation,
    handleCancelAgentSwitch,
  } = useAppController();

  return (
    <div className="h-screen bg-background text-foreground overflow-hidden flex flex-row">
      <WorkspaceRail
        workspaces={workspaces}
        activeWorkspaceId={activeWorkspaceId}
        tabs={tabs}
        tabUi={tabUi}
        addError={workspaceAddError}
        onClearAddError={clearWorkspaceAddError}
        onSelectWorkspace={handleSelectWorkspace}
        onAddWorkspace={handleAddWorkspace}
        onRenameWorkspace={handleRenameWorkspace}
        onRemoveWorkspace={handleRemoveWorkspace}
      />
      <div className="flex-1 min-w-0 flex flex-col">
      <TabBar
        tabs={visibleTabs}
        activeTabId={activeTabId}
        tabUi={tabUi}
        onSelectTab={handleSelectTab}
        onReorderTabs={handleReorderTabs}
        onCloseTab={handleCloseTab}
        onRenameTab={(tabId, newTitle) => {
          const tab = tabs.find((t) => t.id === tabId);
          handleTabRenamed(tabId, newTitle);
          if (tab) {
            const conversationId = tab.historySession?.conversationId ?? tab.conversationId;
            ACPBridge.renameHistoryConversation(tab.historySession?.projectPath, conversationId, newTitle);
          }
        }}
        onCloseAllTabs={handleCloseAllTabs}
        onNewTab={() => handleNewTab()}
        onNewTabWithAgent={(agentId) => handleNewTab(agentId)}
        agents={availableAgents}
        onOpenHistory={() => openSingletonTab('history', 'History')}
        onOpenManagement={() => openSingletonTab('management', 'Service Providers')}
        onOpenDesignSystem={() => openSingletonTab('design', 'Design System')}
        onOpenMcp={() => openSingletonTab('mcp', 'MCP Servers')}
        onOpenLsp={() => openSingletonTab('lsp', 'LSPs')}
        onOpenPromptLibrary={() => openSingletonTab('prompt-library', 'Prompt Library')}
        onOpenSystemInstructions={() => openSingletonTab('system-instructions', 'System Instructions')}
        onOpenSettings={() => openSingletonTab('settings', 'Settings')}
        onOpenKanban={() => openSingletonTab('kanban', 'Kanban Board')}
      />

      <div className="flex-1 relative min-h-0">
        {/* All tabs -- keep mounted for state preservation, toggle visibility */}
        {tabs.map((tab) => {
          const isTabActive = tab.id === activeTabId && tab.workspaceId === activeWorkspaceId;

          return (
            <AppTabContent
              key={tab.id}
              tab={tab}
              isActive={isTabActive}
              tabs={visibleTabs}
              tabUi={tabUi}
              availableAgents={availableAgents}
              runnableAgents={runnableAgents}
              pendingHandoff={pendingHandoffsByTab[tab.id]}
              onOpenHistory={handleOpenHistory}
              onSelectTab={handleSelectTab}
              onUserMessageSent={() => handleUserMessageSent(tab.id)}
              onRenamed={(title) => handleTabRenamed(tab.id, title)}
              onAssistantActivity={() => handleAssistantActivity(tab.id)}
              onAtBottomChange={(isAtBottom) => handleAtBottomChange(tab.id, isAtBottom)}
              onCanMarkReadChange={(canMarkRead) => handleCanMarkReadChange(tab.id, canMarkRead)}
              onPermissionRequestChange={(hasPendingPermission) => handlePermissionRequestChange(tab.id, hasPendingPermission)}
              onProcessingChange={(isProcessing) => handleProcessingChange(tab.id, isProcessing)}
              onStatusChange={(status) => handleStatusChange(tab.id, status)}
              onReviewChange={(hasPendingReview) => handleReviewChange(tab.id, hasPendingReview)}
              onSessionMetaChange={(meta) => handleSessionMetaChange(tab.id, meta)}
              onAgentChangeRequest={(payload) => requestAgentSwitch(tab.id, payload)}
              onForkRequest={(payload) => handleForkRequest(tab.id, payload)}
              onHandoffConsumed={(handoffId) => handleHandoffConsumed(tab.id, handoffId)}
              onSessionStateChange={(state) => handleChatSessionStateChange(tab.id, state)}
            />
          );
        })}

        {/* Empty state */}
        {visibleTabs.length === 0 && (
          <EmptyStateView
            availableAgents={availableAgents}
            runnableAgents={runnableAgents}
            adaptersResolved={agentAvailabilityResolved}
            onStartWithAgent={handleNewTab}
            onOpenRecentConversation={handleOpenHistory}
            onOpenHistory={() => openSingletonTab('history', 'History')}
            onOpenManagement={() => openSingletonTab('management', 'Service Providers')}
          />
        )}
      </div>
      </div>

      <ConfirmationModal
        isOpen={pendingAgentSwitch !== null}
        title={`Switch to ${pendingAgentName}`}
        message={`Click "Continue" to pass the current chat context to ${pendingAgentName}.` + "\n" + `Click "Start New" to begin a new separate chat.`}
        confirmLabel="Continue"
        
        secondaryActionLabel="Start New"
        onSecondaryAction={handleContinueInNewTab}
        showCancelButton={false}
        onConfirm={handleContinueInCurrentConversation}
        onCancel={handleCancelAgentSwitch}
      />
    </div>
  );
}

export default App;
