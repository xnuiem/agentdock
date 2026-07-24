import { MutableRefObject, useCallback, useEffect, useRef, useState } from 'react';
import { TabUiFlags } from '../../types/chat';

const DEFAULT_TAB_UI: TabUiFlags = {
  unread: false,
  atBottom: true,
  canMarkRead: true,
  warning: false,
  processing: false,
  status: 'not started',
  hasPendingReview: false,
};

export function useAppTabUiState(activeTabId: string, activeTabIdRef: MutableRefObject<string>) {
  const [tabUi, setTabUi] = useState<Record<string, TabUiFlags>>({});
  const tabUiRef = useRef(tabUi);
  const pendingPermissionRef = useRef<Record<string, boolean>>({});
  tabUiRef.current = tabUi;

  const canUserSeeResponse = useCallback((tabId: string) => {
    const isActive = tabId === activeTabIdRef.current;
    const canMarkRead = tabUiRef.current[tabId]?.canMarkRead ?? true;
    return isActive && canMarkRead;
  }, [activeTabIdRef]);

  const initTabUi = useCallback((id: string) => {
    setTabUi(prev => ({ ...prev, [id]: { ...DEFAULT_TAB_UI } }));
    pendingPermissionRef.current[id] = false;
  }, []);

  const cleanupTabUiState = useCallback((id: string) => {
    setTabUi(prev => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    delete pendingPermissionRef.current[id];
  }, []);

  const cleanupTabUiStateForIds = useCallback((ids: string[]) => {
    setTabUi(prev => {
      const next = { ...prev };
      ids.forEach(id => delete next[id]);
      return next;
    });
    ids.forEach(id => {
      delete pendingPermissionRef.current[id];
    });
  }, []);

  const resetTabUiState = useCallback(() => {
    setTabUi({});
    pendingPermissionRef.current = {};
  }, []);

  const markTabReadIfAllowed = useCallback((id: string) => {
    if ((tabUi[id]?.canMarkRead ?? true)) {
      setTabUi(prev => prev[id]?.unread ? { ...prev, [id]: { ...prev[id], unread: false } } : prev);
    }
  }, [tabUi]);

  const clearTabUnread = useCallback((tabId: string) => {
    setTabUi(prev => {
      const current = prev[tabId] ?? DEFAULT_TAB_UI;
      if (prev[tabId] && !current.unread) {
        return prev;
      }
      return {
        ...prev,
        [tabId]: {
          ...current,
          unread: false,
        },
      };
    });
  }, []);

  const handleAssistantActivity = useCallback((tabId: string) => {
    setTabUi(prev => {
      const current = prev[tabId] ?? DEFAULT_TAB_UI;
      if (pendingPermissionRef.current[tabId] || current.warning) {
        return current.unread ? { ...prev, [tabId]: { ...current, unread: false } } : prev;
      }

      const isActive = tabId === activeTabIdRef.current;
      const canMarkRead = current.canMarkRead;
      const canSeeResponse = isActive && canMarkRead;

      if (canSeeResponse) {
        return current.unread ? { ...prev, [tabId]: { ...current, unread: false } } : prev;
      }
      
      return current.unread ? prev : { ...prev, [tabId]: { ...current, unread: true } };
    });
  }, [activeTabIdRef]);

  const handleAtBottomChange = useCallback((tabId: string, isAtBottom: boolean) => {
    setTabUi(prev => {
      const current = prev[tabId] ?? DEFAULT_TAB_UI;
      const next = {
        ...current,
        atBottom: isAtBottom,
      };

      if (
        current.atBottom === next.atBottom &&
        current.canMarkRead === next.canMarkRead &&
        current.unread === next.unread &&
        current.warning === next.warning &&
        current.processing === next.processing
      ) {
        return prev;
      }

      return { ...prev, [tabId]: next };
    });
  }, []);

  const handleCanMarkReadChange = useCallback((tabId: string, canMarkRead: boolean) => {
    setTabUi(prev => {
      const current = prev[tabId] ?? DEFAULT_TAB_UI;
      const shouldClearUnread = canMarkRead && tabId === activeTabIdRef.current && current.unread;
      const next = {
        ...current,
        canMarkRead,
        unread: shouldClearUnread ? false : current.unread,
      };

      if (
        current.atBottom === next.atBottom &&
        current.canMarkRead === next.canMarkRead &&
        current.unread === next.unread &&
        current.warning === next.warning &&
        current.processing === next.processing
      ) {
        return prev;
      }

      return { ...prev, [tabId]: next };
    });
  }, [activeTabIdRef]);

  const handleProcessingChange = useCallback((tabId: string, isProcessing: boolean) => {
    setTabUi(prev => {
      const current = prev[tabId];
      if (!current || current.processing === isProcessing) return prev;
      return { ...prev, [tabId]: { ...current, processing: isProcessing } };
    });
  }, []);

  const handleStatusChange = useCallback((tabId: string, status: string) => {
    setTabUi(prev => {
      const current = prev[tabId];
      if (!current || current.status === status) return prev;
      return { ...prev, [tabId]: { ...current, status } };
    });
  }, []);

  const handleReviewChange = useCallback((tabId: string, hasPendingReview: boolean) => {
    setTabUi(prev => {
      const current = prev[tabId];
      if (!current || current.hasPendingReview === hasPendingReview) return prev;
      return { ...prev, [tabId]: { ...current, hasPendingReview } };
    });
  }, []);

  const handleSessionMetaChange = useCallback((tabId: string, meta: { modelId?: string; adapterDisplayName?: string }) => {
    setTabUi(prev => {
      const current = prev[tabId];
      if (!current || (current.modelId === meta.modelId && current.adapterDisplayName === meta.adapterDisplayName)) return prev;
      return { ...prev, [tabId]: { ...current, modelId: meta.modelId, adapterDisplayName: meta.adapterDisplayName } };
    });
  }, []);

  const handlePermissionRequestChange = useCallback((tabId: string, hasPendingPermission: boolean) => {
    pendingPermissionRef.current[tabId] = hasPendingPermission;
    setTabUi(prev => {
      const current = prev[tabId];
      if (!current) return prev;
      const needsUpdate = current.warning !== hasPendingPermission;
      if (!needsUpdate) return prev;
      return {
        ...prev,
        [tabId]: {
          ...current,
          unread: hasPendingPermission ? false : current.unread,
          warning: hasPendingPermission
        }
      };
    });
  }, []);

  useEffect(() => {
    if (!activeTabId) return;
    if (canUserSeeResponse(activeTabId)) {
      setTabUi(prev => prev[activeTabId]?.unread ? { ...prev, [activeTabId]: { ...prev[activeTabId], unread: false } } : prev);
    }
  }, [activeTabId, canUserSeeResponse]);

  return {
    tabUi,
    pendingPermissionRef,
    initTabUi,
    cleanupTabUiState,
    cleanupTabUiStateForIds,
    resetTabUiState,
    markTabReadIfAllowed,
    clearTabUnread,
    handleAssistantActivity,
    handleAtBottomChange,
    handleCanMarkReadChange,
    handlePermissionRequestChange,
    handleProcessingChange,
    handleStatusChange,
    handleReviewChange,
    handleSessionMetaChange,
  };
}
