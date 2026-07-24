import { ClaudeChatUsage } from './ClaudeChatUsage';

interface ChatUsageIndicatorProps {
  agentId: string;
  modelId?: string;
}

export function ChatUsageIndicator({ agentId }: ChatUsageIndicatorProps) {
  switch (agentId) {
    case 'claude-code':
      return <ClaudeChatUsage />;
    default:
      return null;
  }
}
