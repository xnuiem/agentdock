import { useEffect, useMemo, useRef, useState } from 'react';
import { ChatTab, TabUiFlags, Workspace } from '../../types/chat';
import { ACPBridge } from '../../utils/bridge';
import { deriveKanbanColumn, KanbanColumn } from '../kanban/deriveKanbanColumn';

// Reuse the exact status taxonomy + colors the Kanban board already uses ("the same types we use today").
const DOT_CLASS: Record<KanbanColumn, string> = {
  ready: 'bg-foreground-secondary',
  running: 'bg-sky-500',
  question: 'bg-warning',
  error: 'bg-error',
  review: 'bg-violet-500',
  done: 'bg-success',
};

// Higher wins when a workspace has multiple tabs in different states.
const COLUMN_PRIORITY: Record<KanbanColumn, number> = {
  error: 5,
  question: 4,
  review: 3,
  running: 2,
  ready: 1,
  done: 0,
};

const DAY_MS = 24 * 60 * 60 * 1000;

function hueFromPath(path: string): number {
  let hash = 0;
  for (let i = 0; i < path.length; i++) hash = (hash * 31 + path.charCodeAt(i)) | 0;
  return Math.abs(hash) % 360;
}

interface WorkspaceRailProps {
  workspaces: Workspace[];
  activeWorkspaceId: string;
  tabs: ChatTab[];
  tabUi: Record<string, TabUiFlags>;
  addError: string | null;
  onClearAddError: () => void;
  onSelectWorkspace: (id: string) => void;
  onAddWorkspace: (onAdded?: (ws: Workspace) => void) => void;
  onRenameWorkspace: (id: string, name: string) => void;
  onRemoveWorkspace: (id: string) => void;
}

export function WorkspaceRail({
  workspaces,
  activeWorkspaceId,
  tabs,
  tabUi,
  addError,
  onClearAddError,
  onSelectWorkspace,
  onAddWorkspace,
  onRenameWorkspace,
  onRemoveWorkspace,
}: WorkspaceRailProps) {
  const [activityByRoot, setActivityByRoot] = useState<Record<string, number>>({});
  const [menu, setMenu] = useState<{ id: string; x: number; y: number } | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const renameInputRef = useRef<HTMLInputElement | null>(null);

  // Learn each workspace's last session activity (drives grey-vs-colored for closed workspaces too).
  useEffect(() => {
    return ACPBridge.onWorkspaceActivity((e) => {
      setActivityByRoot((prev) => {
        const next = { ...prev };
        e.detail.payload.forEach((a) => { next[a.rootPath] = a.lastActivityMillis; });
        return next;
      });
    });
  }, []);

  const rootsKey = workspaces.map((w) => w.rootPath).join('|');
  useEffect(() => {
    const roots = rootsKey ? rootsKey.split('|') : [];
    if (roots.length === 0) return;
    ACPBridge.requestWorkspaceActivity(roots);
    const timer = window.setInterval(() => ACPBridge.requestWorkspaceActivity(roots), 60_000);
    return () => window.clearInterval(timer);
  }, [rootsKey]);

  useEffect(() => {
    if (renamingId && renameInputRef.current) {
      renameInputRef.current.focus();
      renameInputRef.current.select();
    }
  }, [renamingId]);

  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [menu]);

  const statusByWs = useMemo(() => {
    const map: Record<string, KanbanColumn | null> = {};
    workspaces.forEach((w) => {
      let best: KanbanColumn | null = null;
      tabs.forEach((t) => {
        if (t.workspaceId !== w.id || t.type !== 'chat') return;
        const flags = tabUi[t.id];
        if (!flags) return;
        const col = deriveKanbanColumn(flags);
        if (best === null || COLUMN_PRIORITY[col] > COLUMN_PRIORITY[best]) best = col;
      });
      map[w.id] = best;
    });
    return map;
  }, [workspaces, tabs, tabUi]);

  const now = Date.now();
  const isRecentlyActive = (w: Workspace): boolean => {
    if (w.id === activeWorkspaceId) return true;
    if (tabs.some((t) => t.workspaceId === w.id)) return true;
    const last = activityByRoot[w.rootPath] ?? 0;
    return last > 0 && now - last < DAY_MS;
  };

  const commitRename = () => {
    if (renamingId) {
      const trimmed = renameValue.trim();
      if (trimmed) onRenameWorkspace(renamingId, trimmed);
    }
    setRenamingId(null);
  };

  return (
    <div className="flex h-full w-14 shrink-0 flex-col items-center gap-2 border-r border-border bg-background-secondary py-2">
      {workspaces.map((w) => {
        const active = w.id === activeWorkspaceId;
        const recent = isRecentlyActive(w);
        const status = statusByWs[w.id];
        const hue = hueFromPath(w.rootPath);

        if (renamingId === w.id) {
          return (
            <input
              key={w.id}
              ref={renameInputRef}
              value={renameValue}
              onChange={(e) => setRenameValue(e.target.value)}
              onBlur={commitRename}
              onKeyDown={(e) => {
                if (e.key === 'Enter') commitRename();
                if (e.key === 'Escape') setRenamingId(null);
              }}
              className="w-12 rounded-ide border border-border bg-background px-1 py-1 text-center text-xs text-foreground focus:outline-none"
            />
          );
        }

        return (
          <div key={w.id} className="relative">
            <button
              type="button"
              title={`${w.name}\n${w.rootPath}`}
              onClick={() => onSelectWorkspace(w.id)}
              onContextMenu={(e) => {
                e.preventDefault();
                setMenu({ id: w.id, x: e.clientX, y: e.clientY });
              }}
              className={`relative flex h-10 w-10 items-center justify-center rounded-ide text-sm font-semibold transition-all
                ${active ? 'ring-2 ring-offset-1 ring-offset-background-secondary' : 'hover:opacity-90'}
                ${recent ? 'text-white' : 'text-foreground-secondary grayscale opacity-50'}`}
              style={{ backgroundColor: recent ? `hsl(${hue} 55% 45%)` : 'var(--ide-background)' }}
            >
              {(w.name.trim()[0] || '?').toUpperCase()}
              {status && (
                <span
                  className={`absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full border border-background-secondary ${DOT_CLASS[status]}`}
                />
              )}
            </button>
            {active && (
              <span className="absolute -left-2 top-1/2 h-6 w-1 -translate-y-1/2 rounded-r bg-foreground" />
            )}
          </div>
        );
      })}

      <button
        type="button"
        title="Add workspace"
        onClick={() => onAddWorkspace()}
        className="flex h-10 w-10 items-center justify-center rounded-ide border border-dashed border-border text-lg text-foreground-secondary transition-colors hover:border-foreground hover:text-foreground"
      >
        +
      </button>

      {addError && (
        <div className="mx-1 rounded-ide bg-error/15 p-1 text-center text-[10px] leading-tight text-error" onClick={onClearAddError}>
          {addError}
        </div>
      )}

      {menu && (
        <div
          className="fixed z-50 min-w-[120px] rounded-ide border border-border bg-background py-1 text-sm text-foreground shadow-lg"
          style={{ left: menu.x, top: menu.y }}
          onClick={(e) => e.stopPropagation()}
        >
          <button
            type="button"
            className="block w-full px-3 py-1 text-left hover:bg-hover"
            onClick={() => {
              const w = workspaces.find((x) => x.id === menu.id);
              setRenameValue(w?.name || '');
              setRenamingId(menu.id);
              setMenu(null);
            }}
          >
            Rename
          </button>
          <button
            type="button"
            className="block w-full px-3 py-1 text-left text-error hover:bg-hover"
            onClick={() => {
              onRemoveWorkspace(menu.id);
              setMenu(null);
            }}
          >
            Remove
          </button>
        </div>
      )}
    </div>
  );
}
