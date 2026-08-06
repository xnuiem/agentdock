import { useEffect, useState } from 'react';
import { ACPBridge } from '../utils/bridge';

/**
 * Tracks the file currently open/active in the IntelliJ editor, pushed by EditorContextBridge.
 * Global to the IDE (not per-conversation). Consumers auto-attach it to prompts as context.
 */
export function useActiveFile(): { path: string; name: string } {
  const [file, setFile] = useState<{ path: string; name: string }>({ path: '', name: '' });

  useEffect(() => {
    const off = ACPBridge.onActiveFile((e) => setFile(e.detail.payload));
    // Ask for the current file in case we mounted after the last push.
    ACPBridge.requestActiveFile();
    return off;
  }, []);

  return file;
}
