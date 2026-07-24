# Agent Dock

Agent Dock brings widely used AI coding agents into a unified GUI that follows the active JetBrains IDE theme.

The project's goal is to deliver a rich GUI experience for AI agents within JetBrains IDEs, including features absent from other JetBrains AI plugins, such as live token usage updates directly in the chat interface and switching between AI agents within the same chat while preserving session context.

> This is a fork of [edgarsr/agentdock](https://github.com/edgarsr/agentdock) maintained by **Xnuiem (Ryan C Meinzer)**, adding WSL execution, project-local custom agents, a Kanban board, tab rename, and live tab status. See [Fork enhancements](#fork-enhancements-xnuiem--ryan-c-meinzer) for details.

Supported AI agents (this fork focuses on three, deeply supported):

- Claude Code
- Kilo
- OpenCode

## Features

- Installation, update, and uninstall flows for supported AI agents inside the plugin.
- Communication with supported AI agents through ACP (Agent Client Protocol) in a GUI.
- Structured display of agent output, including tool use, thinking blocks, terminal commands, plans, file edits, and diffs.
- Review of files changed by an agent, with options to accept selected changes or revert them from the IDE.
- Audio notifications for important chat and agent events.
- Slash commands and `@` mentions backed by JetBrains project file search.
- Code selections and file references can be added to chat from the editor and project view.
- Images can be pasted into chat and previewed inline.
- Live token quota and context usage are shown directly in the chat input while prompting, for agents that support it.
  For Claude Code, quota data is fetched using the OAuth credentials.
- Voice input for prompts (Windows only).
- Chats can be continued in the IDE terminal when CLI mode is a better fit.
- Chat history supports opening, renaming, deleting, and bulk deletion.
- Chats can be forked from any point.
- AI agents can be switched within the same chat while preserving the session context.
- MCP server configuration for additional agent tools and external resources.
- Reusable prompts can be saved in the prompt library and inserted into chat when needed.
- System instructions can be managed and applied to agent sessions.
- Git commit messages can be generated from the current changes.

## Fork enhancements (Xnuiem — Ryan C Meinzer)

The following capabilities were added in this fork by **Xnuiem (Ryan C Meinzer)**:

- **WSL execution.** Agents can run inside a WSL distribution instead of on the Windows host,
  using JetBrains' EelApi (the sanctioned environment-aware process/path abstraction) rather than
  manual path rewriting. It is an explicit opt-in in settings — choose **Local** or **WSL** under
  *Agent Execution*, with an optional distribution name that otherwise falls back to whichever
  distro the project is opened from. Adapter installation (`npm install`), process launch, and ACP
  chat sessions all run natively inside the chosen WSL environment.
- **Project-local custom agents.** OpenCode's project custom agents (e.g. `.opencode/agents/*.md`)
  are now discovered and listed, because agent/session working directories resolve to the actual
  project root under both Local and WSL execution.
- **Agent → model coupling.** Selecting an OpenCode custom agent moves the model selection to that
  agent's declared model (read from its frontmatter `model:`), since the protocol never reports it
  as the session model. A manual model change afterward still wins.
- **Per-agent approval memory.** The chat Ask/Auto approval choice is remembered per agent, so
  switching agents restores that agent's approval mode.
- **Kanban board.** A board view of chat sessions with columns **Ready, Running, Question, Error,
  Review, Archive**, covering both currently-open tabs and historical sessions from the current
  project. Columns are color-coded, cards show scope/name/agent/model, clicking a card opens the
  session, and finished items can be archived off the board.
- **Tab rename.** Rename a chat tab with the `/rename <new title>` command in the chat input
  (intercepted locally, never sent to the agent) or by double-clicking the tab, persisted through
  the existing history rename plumbing.
- **Live tab status.** A spinning loader icon appears on any tab whose agent is currently running.

## Requirements

- JetBrains IDE based on IntelliJ Platform 2025.1 or newer.
- **WSL execution requires IntelliJ Platform 2026.1 or newer** (for EelApi) and the full
  *IntelliJ IDEA* product; the Community edition is not published for these versions and does not
  provide EelApi. The project must be opened from a WSL path (`\\wsl.localhost\<distro>\...`).
- Some agents use JetBrains IDE terminal for authentication.
- On macOS and Linux, installing some agents requires `curl` and `tar`.

## Technology

- **Backend:** Kotlin
- **Frontend:** React, Tailwind
- **Agent communication:** ACP (Agent Client Protocol)

## Screenshot

![Agent Dock chat interface](docs/images/agent-dock-chat.png)
