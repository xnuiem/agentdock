import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { ChatTab, HistorySessionMeta, TabUiFlags } from '../../types/chat';
import { ACPBridge } from '../../utils/bridge';
import { deriveKanbanColumn, KanbanColumn } from './deriveKanbanColumn';

const FALLBACK_UI: TabUiFlags = {
  unread: false,
  atBottom: true,
  canMarkRead: true,
  warning: false,
  processing: false,
  status: 'not started',
  hasPendingReview: false,
};

const COLUMNS: { id: KanbanColumn; label: string }[] = [
  { id: 'ready', label: 'Ready' },
  { id: 'running', label: 'Running' },
  { id: 'question', label: 'Question' },
  { id: 'error', label: 'Error' },
  { id: 'review', label: 'Review' },
  { id: 'done', label: 'Done' },
];

interface LiveCard {
  kind: 'live';
  tab: ChatTab;
}

interface HistoricalCard {
  kind: 'historical';
  item: HistorySessionMeta;
}

type KanbanCard = LiveCard | HistoricalCard;

function formatUpdatedAt(ms: number): string {
  return new Date(ms).toLocaleString();
}

interface KanbanBoardViewProps {
  tabs: ChatTab[];
  tabUi: Record<string, TabUiFlags>;
  onSelectTab: (id: string) => void;
  onOpenHistorySession: (item: HistorySessionMeta) => void;
}

export function KanbanBoardView({ tabs, tabUi, onSelectTab, onOpenHistorySession }: KanbanBoardViewProps) {
  const [historyList, setHistoryList] = useState<HistorySessionMeta[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const unsubscribe = ACPBridge.onHistoryList((e) => {
      setHistoryList(Array.isArray(e.detail.list) ? e.detail.list : []);
      setIsLoading(false);
    });
    ACPBridge.requestHistoryList();
    return unsubscribe;
  }, []);

  const chatTabs = tabs.filter((tab) => tab.type === 'chat');
  const liveConversationKeys = new Set(chatTabs.map((tab) => tab.historySession?.conversationId ?? tab.conversationId));
  const doneHistoryItems = historyList.filter((item) => !liveConversationKeys.has(item.conversationId));

  const cardsByColumn: Record<KanbanColumn, KanbanCard[]> = {
    ready: [],
    running: [],
    question: [],
    error: [],
    review: [],
    done: doneHistoryItems.map((item) => ({ kind: 'historical', item })),
  };

  chatTabs.forEach((tab) => {
    const flags = tabUi[tab.id] ?? FALLBACK_UI;
    const column = deriveKanbanColumn(flags);
    cardsByColumn[column].push({ kind: 'live', tab });
  });

  return (
    <div className="flex flex-col h-full bg-background text-foreground overflow-hidden">
      <div className="flex items-center justify-between min-h-12 px-3 py-1 border-b border-border shrink-0">
        <span className="text-ide-small text-foreground-secondary">Current project</span>
        <button
          onClick={() => { setIsLoading(true); ACPBridge.syncHistoryList(); }}
          disabled={isLoading}
          className={`rounded-[4px] p-1 text-foreground-secondary transition-colors hover:text-foreground
            focus-visible:shadow-[0_0_0_1px_var(--ide-Button-default-focusColor)] focus-visible:outline-none
            ${isLoading ? 'animate-spin' : ''}`}
          aria-label="Refresh board"
        >
          <RefreshCw className="w-4 h-4" />
        </button>
      </div>

      <div className="flex-1 overflow-x-auto overflow-y-hidden">
        <div className="flex h-full min-w-max gap-3 px-3 py-3">
          {COLUMNS.map((column) => {
            const cards = cardsByColumn[column.id];
            return (
              <div key={column.id} className="flex w-[260px] shrink-0 flex-col rounded-ide border border-border bg-background-secondary">
                <div className="flex items-center justify-between px-3 py-2 border-b border-border shrink-0">
                  <span className="text-ide-small font-semibold">{column.label}</span>
                  <span className="text-xs text-foreground-secondary">{cards.length}</span>
                </div>
                <div className="flex-1 overflow-y-auto p-2 space-y-2">
                  {cards.map((card) => (
                    <button
                      key={card.kind === 'live' ? card.tab.id : card.item.conversationId}
                      type="button"
                      onClick={() => card.kind === 'live' ? onSelectTab(card.tab.id) : onOpenHistorySession(card.item)}
                      className="w-full rounded-[4px] border border-[var(--ide-Button-startBorderColor)] bg-background p-2
                        text-left transition-colors hover:bg-hover focus:outline-none
                        focus-visible:shadow-[0_0_0_1px_var(--ide-Button-default-focusColor)]"
                    >
                      <div className="text-ide-small font-medium truncate">
                        {card.kind === 'live' ? card.tab.title : card.item.title}
                      </div>
                      <div className="mt-1 flex items-center gap-2 text-xs text-foreground-secondary">
                        <span className="truncate">
                          {card.kind === 'live' ? (tabUi[card.tab.id]?.adapterDisplayName || card.tab.agentId) : card.item.adapterName}
                        </span>
                        {card.kind === 'live' && tabUi[card.tab.id]?.modelId ? (
                          <>
                            <span className="opacity-50">&bull;</span>
                            <span className="truncate">{tabUi[card.tab.id]?.modelId}</span>
                          </>
                        ) : null}
                      </div>
                      {card.kind === 'historical' ? (
                        <div className="mt-1 text-xs text-foreground-secondary">{formatUpdatedAt(card.item.updatedAt)}</div>
                      ) : null}
                    </button>
                  ))}
                  {cards.length === 0 ? (
                    <div className="px-1 py-2 text-xs text-foreground-secondary">No sessions</div>
                  ) : null}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
