import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { GlobalSettings, Workspace } from '../../types/chat';
import { ACPBridge } from '../../utils/bridge';

let wsCounter = 0;
function nextWorkspaceId(): string {
  return `ws-${++wsCounter}-${Date.now()}`;
}

export function workspaceBasename(p: string): string {
  const trimmed = (p || '').replace(/[/\\]+$/, '');
  const idx = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
  return idx >= 0 ? trimmed.slice(idx + 1) : trimmed;
}

/**
 * Owns the workspace list and active selection. Workspaces are persisted in global settings
 * (settings.json). On first run for an existing user, we seed a single default workspace from the
 * IntelliJ project root so their existing history/sessions render exactly as before (zero migration).
 */
export function useWorkspaces() {
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string>('');
  const [projectRoot, setProjectRoot] = useState<{ rootPath: string; name: string } | null>(null);
  const [hydrated, setHydrated] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);

  const settingsRef = useRef<GlobalSettings | null>(null);
  const workspacesRef = useRef<Workspace[]>(workspaces);
  workspacesRef.current = workspaces;

  const persist = useCallback((next: Workspace[], activeId: string) => {
    const base = settingsRef.current;
    if (!base) return;
    const updated: GlobalSettings = { ...base, workspaces: next, activeWorkspaceId: activeId };
    settingsRef.current = updated;
    ACPBridge.saveGlobalSettings(updated);
  }, []);

  const applyWorkspaces = useCallback((next: Workspace[], activeId: string) => {
    setWorkspaces(next);
    setActiveWorkspaceId(activeId);
    persist(next, activeId);
  }, [persist]);

  // Hydrate from settings + learn the project root.
  useEffect(() => {
    const offSettings = ACPBridge.onGlobalSettings((e) => {
      const settings = e.detail.payload.settings;
      settingsRef.current = settings;
      const list = settings.workspaces || [];
      setWorkspaces(list);
      setActiveWorkspaceId((prev) => {
        const desired = settings.activeWorkspaceId || prev;
        if (desired && list.some((w) => w.id === desired)) return desired;
        return list[0]?.id || '';
      });
      setHydrated(true);
    });

    const offRoot = ACPBridge.onWorkspaceProjectRoot((e) => {
      setProjectRoot(e.detail.payload);
    });

    const request = () => {
      ACPBridge.loadGlobalSettings();
      ACPBridge.requestWorkspaceProjectRoot();
    };
    if (window.__settingsBridgeReady) request();
    else window.addEventListener('settings-bridge-ready', request);

    return () => {
      offSettings();
      offRoot();
      window.removeEventListener('settings-bridge-ready', request);
    };
  }, []);

  // Seed the default workspace once both settings and project root are known and none exist yet.
  useEffect(() => {
    if (!hydrated || !projectRoot || workspaces.length > 0) return;
    if (!projectRoot.rootPath) return;
    const def: Workspace = {
      id: nextWorkspaceId(),
      name: projectRoot.name || 'Workspace',
      rootPath: projectRoot.rootPath,
      addedAt: Date.now(),
    };
    applyWorkspaces([def], def.id);
  }, [hydrated, projectRoot, workspaces.length, applyWorkspaces]);

  const activeWorkspace = useMemo(
    () => workspaces.find((w) => w.id === activeWorkspaceId) || null,
    [workspaces, activeWorkspaceId]
  );

  const selectWorkspace = useCallback((id: string) => {
    if (!workspacesRef.current.some((w) => w.id === id)) return;
    setActiveWorkspaceId(id);
    persist(workspacesRef.current, id);
  }, [persist]);

  const renameWorkspace = useCallback((id: string, name: string) => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const next = workspacesRef.current.map((w) => (w.id === id ? { ...w, name: trimmed } : w));
    applyWorkspaces(next, activeWorkspaceId);
  }, [activeWorkspaceId, applyWorkspaces]);

  const removeWorkspace = useCallback((id: string) => {
    const next = workspacesRef.current.filter((w) => w.id !== id);
    const nextActive = activeWorkspaceId === id ? (next[0]?.id || '') : activeWorkspaceId;
    applyWorkspaces(next, nextActive);
  }, [activeWorkspaceId, applyWorkspaces]);

  const addWorkspaceFromPath = useCallback((rootPath: string, name: string): Workspace | null => {
    const existing = workspacesRef.current.find((w) => w.rootPath === rootPath);
    if (existing) {
      selectWorkspace(existing.id);
      return existing;
    }
    const ws: Workspace = {
      id: nextWorkspaceId(),
      name: (name || workspaceBasename(rootPath)).trim() || 'Workspace',
      rootPath,
      addedAt: Date.now(),
    };
    applyWorkspaces([...workspacesRef.current, ws], ws.id);
    return ws;
  }, [applyWorkspaces, selectWorkspace]);

  // Native folder picker → validate → add. Errors surface via addError for inline UI feedback.
  const beginAddWorkspace = useCallback((onAdded?: (ws: Workspace) => void) => {
    setAddError(null);
    const off = ACPBridge.onWorkspacePicked((e) => {
      off();
      const p = e.detail.payload;
      if (p.cancelled) return;
      if (!p.isGitRepo || !p.sameTarget) {
        setAddError(p.error || 'That folder is not a valid workspace.');
        return;
      }
      const ws = addWorkspaceFromPath(p.rootPath, p.name);
      if (ws && onAdded) onAdded(ws);
    });
    ACPBridge.pickWorkspaceDirectory();
  }, [addWorkspaceFromPath]);

  return {
    workspaces,
    activeWorkspaceId,
    activeWorkspace,
    addError,
    clearAddError: useCallback(() => setAddError(null), []),
    selectWorkspace,
    renameWorkspace,
    removeWorkspace,
    beginAddWorkspace,
  };
}
