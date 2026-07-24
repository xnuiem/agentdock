import { TabUiFlags } from '../../types/chat';

export type KanbanColumn = 'ready' | 'running' | 'question' | 'error' | 'review' | 'done';

export function deriveKanbanColumn(ui: TabUiFlags): KanbanColumn {
  if (ui.warning) return 'question'; // pending permission request
  if (ui.status === 'error') return 'error';
  if (ui.hasPendingReview) return 'review'; // unresolved file edits
  if (ui.processing || ui.status === 'initializing' || ui.status === 'prompting') return 'running';
  return 'ready';
}
