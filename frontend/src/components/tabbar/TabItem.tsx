import { useEffect, useRef, useState } from 'react';
import { Loader2, X } from 'lucide-react';
import { AgentOption, ChatTab } from '../../types/chat';
import { getTabIcon } from './TabIcons';

interface TabItemProps {
  tab: ChatTab;
  agents: AgentOption[];
  isActive: boolean;
  isKeyboardFocused: boolean;
  hasWarning: boolean;
  hasUnread: boolean;
  hasProcessing: boolean;
  isIslandsTheme: boolean;
  titleClassName: string;
  onSelectTab: (id: string) => void;
  onPointerDown: (id: string, event: React.PointerEvent<HTMLDivElement>) => void;
  shouldSuppressClick: (id: string) => boolean;
  onCloseTab: (id: string) => void;
  onRenameTab: (id: string, newTitle: string) => void;
  onFocusTab: (id: string) => void;
  onBlurTab: (id: string) => void;
  dropIndicator: 'before' | 'after' | null;
}

export function TabItem({
  tab,
  agents,
  isActive,
  isKeyboardFocused,
  hasWarning,
  hasUnread,
  hasProcessing,
  isIslandsTheme,
  titleClassName,
  onSelectTab,
  onPointerDown,
  shouldSuppressClick,
  onCloseTab,
  onRenameTab,
  onFocusTab,
  onBlurTab,
  dropIndicator
}: TabItemProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState(tab.title);
  const editInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isEditing) {
      editInputRef.current?.focus();
      editInputRef.current?.select();
    }
  }, [isEditing]);

  const startEditing = () => {
    if (tab.type !== 'chat') return;
    setEditValue(tab.title);
    setIsEditing(true);
  };

  const commitEdit = () => {
    const trimmed = editValue.trim();
    if (trimmed && trimmed !== tab.title) {
      onRenameTab(tab.id, trimmed);
    }
    setIsEditing(false);
  };

  const cancelEdit = () => {
    setEditValue(tab.title);
    setIsEditing(false);
  };
  const activeClassName = isActive
    ? isIslandsTheme
      ? 'text-foreground before:absolute before:inset-[3px_3px] before:rounded-[6px] before:bg-background before:[filter:var(--ide-surface-active-filter)] before:shadow-[inset_0_0_0_1px_var(--ide-Button-startBorderColor)]'
      : 'text-foreground before:absolute before:inset-0 before:bg-background before:[filter:var(--ide-surface-active-filter)] after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[3px] after:bg-[var(--ide-Button-default-focusColor)]'
    : '';
  const tabRadiusClassName = isIslandsTheme ? 'rounded-[6px]' : 'rounded-[4px]';
  const tabHeightClassName = isIslandsTheme ? 'h-[32px] self-center' : 'h-full';
  const buttonPaddingClassName = isIslandsTheme
    ? 'pl-[calc(0.25rem+5px)] pr-1'
    : 'px-1';
  const iconOffsetClassName = isIslandsTheme ? 'top-[1px]' : '';
  const closeButtonClassName = isIslandsTheme
    ? 'ml-1 mr-0.5 mt-0'
    : 'ml-2 mr-0.5 -mt-0.5';

  return (
    <div
      data-tab-id={tab.id}
      onPointerDown={(event) => onPointerDown(tab.id, event)}
      className={`text-foreground group relative pl-1 pr-2 flex ${tabHeightClassName} max-w-[180px] shrink items-center ${tabRadiusClassName}
        bg-background cursor-grab active:cursor-grabbing ${activeClassName}`}
    >
      {dropIndicator === 'before' ? (
        <span aria-hidden="true" className="pointer-events-none absolute bottom-1 left-0 top-1 z-30 w-px bg-primary" />
      ) : null}
      {dropIndicator === 'after' ? (
        <span aria-hidden="true" className="pointer-events-none absolute bottom-1 right-0 top-1 z-30 w-px bg-primary" />
      ) : null}
      {isKeyboardFocused ? (<span aria-hidden="true"
          className="pointer-events-none absolute inset-[1px] z-20 rounded-[3px] shadow-[inset_0_0_0_1px_var(--ide-Button-default-focusColor)]"
        />
      ) : null}
      <button
        type="button"
        role="tab"
        aria-selected={isActive}
        onClick={(event) => {
          if (isEditing || shouldSuppressClick(tab.id)) {
            event.preventDefault();
            return;
          }
          onSelectTab(tab.id);
        }}
        onDoubleClick={(event) => {
          event.preventDefault();
          startEditing();
        }}
        onKeyDown={(event) => {
          if (isEditing) return;
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            onSelectTab(tab.id);
          }
        }}
        onFocus={() => onFocusTab(tab.id)}
        onBlur={() => onBlurTab(tab.id)}
        className={`w-full h-full ${buttonPaddingClassName} pb-0.5 relative z-10 flex min-w-0 flex-1 items-center gap-2 overflow-hidden
          ${tabRadiusClassName} text-left cursor-default focus:outline-none`}
      >
        <div className={`flex shrink-0 items-center relative left-[1px] ${iconOffsetClassName} opacity-80`}>
          {hasProcessing ? <Loader2 className="w-4 h-4 animate-spin" /> : getTabIcon(tab, agents)}
        </div>
        <div className={`min-w-0 flex-1 overflow-hidden ${titleClassName}`}>
          {isEditing ? (
            <input
              ref={editInputRef}
              value={editValue}
              onChange={(event) => setEditValue(event.target.value)}
              onClick={(event) => event.stopPropagation()}
              onPointerDown={(event) => event.stopPropagation()}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  event.stopPropagation();
                  commitEdit();
                } else if (event.key === 'Escape') {
                  event.preventDefault();
                  event.stopPropagation();
                  cancelEdit();
                }
              }}
              onBlur={commitEdit}
              className="w-full truncate rounded-[2px] bg-background-secondary text-ide-small relative top-[1px] outline-none focus-visible:shadow-[0_0_0_1px_var(--ide-Button-default-focusColor)]"
            />
          ) : (
            <div
              onDoubleClick={(event) => {
                event.preventDefault();
                event.stopPropagation();
                startEditing();
              }}
              className={`truncate text-ide-small relative top-[1px] ${hasProcessing ? 'tab-shimmer-text' : ''}`}
            >
              {tab.title}
            </div>
          )}
        </div>
      </button>
      {hasWarning ? (
        <span className="relative z-10 ml-1 -mt-0.5 h-2 w-2 flex-shrink-0 rounded-full bg-warning" />
      ) : hasUnread ? (
        <span className="relative z-10 ml-1 -mt-0.5 h-2 w-2 flex-shrink-0 rounded-full bg-sky-500" />
      ) : null}

      <button
        data-close-tab="true"
        onPointerDown={(event) => event.stopPropagation()}
        onClick={(e) => {
          e.stopPropagation();
          onCloseTab(tab.id);
        }}
        className={`relative z-10 ${closeButtonClassName} shrink-0 rounded-sm opacity-0 cursor-pointer
          focus:outline-none focus-visible:shadow-[0_0_0_1px_var(--ide-Button-default-focusColor)]
          ${isActive ? 'opacity-100' : 'group-hover:opacity-100 group-focus-within:opacity-100'}
        `}
      >
        <X size={12} aria-hidden="true" />
      </button>
    </div>
  );
}
