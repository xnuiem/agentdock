import { useEffect, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { LspServer } from '../types/chat';
import { ACPBridge } from '../utils/bridge';

interface LspServersViewProps {
  rootPath?: string;
}

/**
 * Lists the LSP servers opencode is CONFIGURED to use for this workspace, read from its own
 * opencode.json / opencode.jsonc. This is configured state, not a live health check — opencode/ACP
 * does not expose running LSP status.
 */
export function LspServersView({ rootPath }: LspServersViewProps) {
  const [servers, setServers] = useState<LspServer[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    const off = ACPBridge.onLspServers((e) => {
      setServers(e.detail.servers);
      setLoaded(true);
    });
    if (rootPath) ACPBridge.requestLspServers(rootPath);
    return off;
  }, [rootPath]);

  const refresh = () => {
    if (rootPath) ACPBridge.requestLspServers(rootPath);
  };

  return (
    <div className="flex h-full flex-col bg-background text-foreground overflow-hidden">
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <div className="flex flex-col">
          <span className="text-ide-regular font-semibold">Language Servers</span>
          <span className="text-ide-small text-foreground-secondary">
            Configured in opencode for this workspace (not a live status)
          </span>
        </div>
        <button
          type="button"
          onClick={refresh}
          title="Refresh from opencode config"
          className="flex items-center gap-1.5 rounded-ide border border-border px-2 py-1 text-ide-small text-foreground-secondary transition-colors hover:bg-hover hover:text-foreground"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-auto">
        {loaded && servers.length === 0 && (
          <div className="p-4 text-ide-small text-foreground-secondary">
            No LSP servers configured in this workspace's opencode.json / opencode.jsonc.
          </div>
        )}
        {servers.map((s) => (
          <div key={s.name} className="flex items-start gap-3 border-b border-border px-3 py-2">
            <span
              className={`mt-1 inline-block h-2 w-2 shrink-0 rounded-full ${s.disabled ? 'bg-foreground-secondary/40' : 'bg-success'}`}
              title={s.disabled ? 'Disabled' : 'Enabled'}
            />
            <div className="min-w-0 flex-1 text-ide-small text-foreground-secondary">
              <div className="flex items-baseline gap-2">
                <span className="text-ide-regular font-semibold text-foreground">{s.name}</span>
                {s.disabled && <span className="text-foreground-secondary">(disabled)</span>}
                <span className="ml-auto text-foreground-secondary/70">{s.source}</span>
              </div>
              {s.command && <div className="mt-0.5 truncate font-mono" title={s.command}>{s.command}</div>}
              {s.extensions && <div className="mt-0.5 text-foreground-secondary/80">Extensions: {s.extensions}</div>}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
