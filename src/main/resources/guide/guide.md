# Handoff for Claude Code: User Guide

Handoff is where Claude Code hands work over to you, and where you hand the result back: commands to run yourself,
questions to answer, images to review, changes to check. Nothing runs without your click.

**Contents**

1. [Getting started](#getting-started)
2. [The Commands tab](#the-commands-tab)
3. [What Claude can hand over](#what-claude-can-hand-over)
4. [When Claude is waiting](#when-claude-is-waiting)
5. [Memory tab](#memory-tab)
6. [Usage tab and Handoff tools](#usage-tab-and-handoff-tools)
7. [Menu and settings](#menu-and-settings)
8. [Keeping your CLAUDE.md up to date](#keeping-your-claudemd-up-to-date)
9. [Troubleshooting](#troubleshooting)
10. [Version history](#version-history)

## Getting started

1. Enable the IDE's MCP server: **Settings | Tools | MCP Server**. [Open MCP Server settings](handoff:mcp-settings)
2. Connect Claude Code to it: on the same settings page click **Auto-Configure** for Claude Code. If Claude Code was
   already running, reconnect with `/mcp`.
3. Tell Claude when to use Handoff: choose [Update CLAUDE.md Instructions…](handoff:update-claude-md) in the tool window's **⋮** menu (or
   **Add Instructions to CLAUDE.md…** in the empty Commands tab), pick the features you want and let Claude add them -
   it puts them into your existing `CLAUDE.md` or creates a Handoff section in `~/.claude/CLAUDE.md`. See
   *Keeping your CLAUDE.md up to date*. Prefer doing it by hand? Copy the snippet below into your `CLAUDE.md`.
4. Check it: ask Claude *"Propose `echo hello` via Handoff."* A card shows up in the Commands tab.

### The CLAUDE.md snippet

For doing it by hand ([copy it](handoff:copy-snippet)): all features in one block. Leave out what you don't want; each feature below also has its own
short prompt.

<!-- snippet:start -->
```
## Handoff (IDE plugin)

If the MCP tools handoff_* are available, use them:

- Commands I should run myself (deploys, logins, releases, uploads, anything that
  needs a password): propose them with handoff_propose_command (one-sentence reason,
  risky = true for live systems, publishing or deleting) and wait for the result with
  handoff_wait_for_result instead of writing them into the chat. Call it again on
  "pending".
- Several such commands in a row (e.g. verify, tag, push, upload): propose them as
  one card with handoff_propose_sequence instead of one by one. It stops at the
  first command that fails.
- Before the first file change of a new task, call handoff_begin_task with a short
  title; after the last change call handoff_end_task. Each task then gets its own
  changelist "Claude: <title>" that I review and commit separately.
- Show images I should approve or choose from (icons, layouts, screenshots) with
  handoff_show_images instead of opening them: one path to approve, several to choose
  one. Fetch my choice, note and marked areas with handoff_wait_for_result.
- When a task is done, ask me to review the changes with handoff_request_review
  (short summary) before calling handoff_end_task, and wait with
  handoff_wait_for_result: I approve or send notes on single lines (path:line and
  text). Apply the notes, then ask again. If I say I left notes in the editor,
  fetch them with handoff_get_notes.
- Questions with options for me (which variant, which findings to save): ask them
  with handoff_ask (options with a short description, multiSelect when several fit)
  instead of in the terminal, and wait with handoff_wait_for_result.
```
<!-- snippet:end -->

## The Commands tab

Everything Claude hands over appears here as a card. A card waits until you act on it; Claude fetches the outcome
(`handoff_wait_for_result`) and goes on. **Clear Finished** (broom icon in the header) removes done cards.

- **Order:** oldest at the top, newest at the bottom, and the list scrolls to a new card. **Newest Cards on Top** in
  the **⋮** menu turns it around.
- **Claude's messages** ("Claude is done", "Claude is waiting for you") stay pinned to the bottom of the tab, so they
  don't scroll away. Switch it off with **Pin Claude Messages to the Bottom**.
- **Nothing waiting:** once Handoff is set up, the empty tab shows a single line - plus a link when your `CLAUDE.md`
  misses instructions for new features.

### Notifications

When something is waiting and the IDE is not the active application, Handoff shows a system notification and marks
the IDE's dock or taskbar icon. The tool window icon carries a dot and the Commands tab a count as long as something
is waiting. Notifications can be switched off in the operating system's settings for the IDE.

## What Claude can hand over

### Commands you run yourself

Claude proposes a command; it appears as a card with the reason and the working directory. **Run** executes it in the
Run tool window (type into it if the command asks for input), **Dismiss…** sends an optional note back. Risky commands
are highlighted. Exit code and the last 60 output lines go back to Claude.

**Tools:** `handoff_propose_command`, `handoff_wait_for_result`.

**Prompt for your CLAUDE.md:**

```
Commands I should run myself (deploys, logins, releases, uploads): propose them with
handoff_propose_command and wait for the result with handoff_wait_for_result
instead of writing them into the chat.
```

### Several commands as one sequence

For steps that belong together (verify, tag, push, upload) Claude proposes one card with all commands. **Run All** runs
them one after another, each in its own Run tab, and stops at the first one that fails; **Run Step n** runs only the
next one. Each command can be edited until it has run. Claude gets exit code and output per step and sees which steps
were not run. Each command runs in its own shell, so `cd` and variables do not carry over.

**Tools:** `handoff_propose_sequence`, `handoff_wait_for_result`.

**Prompt for your CLAUDE.md:**

```
Several commands in a row that I should run myself: propose them as one card with
handoff_propose_sequence instead of one by one.
```

### Questions with options

Claude asks one or more questions with options as a card: per question one answer (radio buttons) or several
(checkboxes, `multiSelect`), each option with an optional short description, plus a field for an own answer. Several
questions are shown one after another with **Back** and **Next**; **Submit** on the last one sends all answers back,
**Dismiss** an optional note. Markdown in the questions is rendered; a long question also opens in an editor tab.

**Tools:** `handoff_ask`, `handoff_wait_for_result`.

**Prompt for your CLAUDE.md:**

```
Questions with options for me: ask them with handoff_ask instead of in the terminal
and wait with handoff_wait_for_result.
```

### Images to review or choose from

One image to approve, or several variants to pick one. The eye icon opens a viewer with zoom and paging (← →), where you
mark areas with a frame and a note for Claude.

**Tools:** `handoff_show_images`, `handoff_wait_for_result`.

**Prompt for your CLAUDE.md:**

```
Show images I should approve or choose from (icons, layouts, screenshots) with
handoff_show_images instead of opening them, and fetch my choice with
handoff_wait_for_result.
```

### Review with line notes

Claude asks you to review its changes. The card lists the changed files of the task (click one to open its diff, or
**Show Diff** for all). To request a change, hover a line on the right side of the diff and click the **+** next to
its line number. The **+** also appears in normal editors while a review is waiting. For a range of selected lines
(or at any other time) use **Add Note for Claude…** in the context menu. Notes show up as an icon in the
gutter and as grey text at the end of the line; click the icon to edit or delete one. Then **Approve**, or
**Send Notes**: Claude gets each note with path, line and the line of code it belongs to. Sent notes are removed.

You can also leave notes without a review. They wait in a card in the Commands tab until you tell Claude to read them
(or until the next review). Notes are kept in memory only and are gone after an IDE restart.

**Tools:** `handoff_request_review`, `handoff_get_notes`, `handoff_wait_for_result`.

**Prompt for your CLAUDE.md:**

```
When a task is done, ask me to review the changes with handoff_request_review before
calling handoff_end_task, and wait with handoff_wait_for_result. Apply my line notes,
then ask again. If I say I left notes in the editor, fetch them with handoff_get_notes.
```

### One changelist per task

Files Claude changes during a task land in their own changelist **Claude: &lt;title&gt;**. When the task ends, the
previous changelist becomes active again; an empty task changelist is removed.

**Tools:** `handoff_begin_task`, `handoff_end_task`.

**Prompt for your CLAUDE.md:**

```
Before the first file change of a new task, call handoff_begin_task with a short
title; after the last change call handoff_end_task.
```

## When Claude is waiting

Optional: **Show When Claude Is Waiting** in the tool window's **⋮** menu. A card in the Commands tab then shows when a
Claude Code session in this project is done (with the start of its last message) or needs your input (permission
prompt, question), together with the dot, the count and the system notification described above. The card disappears
with your next prompt in that session, or with **Dismiss**. No prompt needed.

The "done" card shows the start of Claude's last message, formatted. **Open Full Message** shows all of it in an
editor tab. Type your next prompt into the card's field and press **Enter** (**Shift+Enter** for a new line): it goes
to that Claude Code session as if you had typed it there, marked as coming from the IDE. Typing in the terminal
instead works as before. The field appears for sessions started after the hooks were set up (or updated) by this
version.

Claude Code reports this only through hooks, so Handoff creates `~/.claude/handoff/hook.sh` and adds it to
`~/.claude/settings.json` for the events Stop, Notification, UserPromptSubmit and SessionEnd (backup:
`settings.json.bak-handoff`); existing hooks stay. The script keeps one small file per waiting session in
`~/.claude/handoff/waiting/`. The Stop hook runs in the background (`asyncRewake`) and waits up to a day for a prompt
from the card; it ends as soon as you type in the terminal. It applies to sessions started afterwards and only to sessions whose working directory
is the project folder or below it. Switching the menu item off removes the hooks and the script.

## Memory tab

Everything Claude Code loads into each session for this project: CLAUDE.md files, imports and project memory, as a tree
with token estimates per file and section. Warnings for memories missing from the index, on-demand files and duplicated
paragraphs. No prompt needed.

## Usage tab and Handoff tools

Tokens per day and per session for this project (today, 7 or 30 days), read from Claude Code's session logs. No prompt
needed.

**Show Plan Limits…** adds your 5-hour and weekly plan limits (Pro and Max plans). Claude Code passes them only to a
status line script, so Handoff creates `~/.claude/handoff/statusline.sh` and sets it as the status line in
`~/.claude/settings.json` (backup: `settings.json.bak-handoff`). An existing status line keeps working; the script calls
it.

### Handoff tools

The group **Handoff tools** lists every tool with the number of calls in the period and whether your `CLAUDE.md`
mentions it. Double-click it, or choose **Handoff Tools…** in the **⋮** menu, to switch each feature:

- **In CLAUDE.md**: whether your instructions tell Claude to use it. Handoff doesn't edit your files itself; **Apply**
  sends a prompt with the additions and removals to a waiting Claude Code session of this project, or copies it.
- **Allowed**: unchecked, Handoff adds `mcp__<server>__<tool>` to `permissions.deny` in `~/.claude/settings.json` (for
  each local MCP server entry in `~/.claude.json`; backup `settings.json.bak-handoff`), so Claude Code cannot call it.

## Menu and settings

The **⋮** menu of the tool window:

| Entry | What it does |
|---|---|
| **Guide** | Opens this guide. |
| **Handoff Tools…** | Calls per feature; switch its CLAUDE.md instruction and its permission. |
| **Update CLAUDE.md Instructions…** | Lets Claude add the instructions your CLAUDE.md is missing. |
| **Show When Claude Is Waiting** | Cards for Claude Code sessions that are done or need your input (sets up hooks). |
| **Pin Claude Messages to the Bottom** | Keeps those cards at the bottom of the Commands tab (on by default). |
| **Newest Cards on Top** | Newest card at the top instead of the bottom. |

## Keeping your CLAUDE.md up to date

Claude only uses a tool when your `CLAUDE.md` says so - on first setup and whenever a new version brings new tools. **Update CLAUDE.md
Instructions…** ([open it](handoff:update-claude-md); also in the **⋮** menu, the notice after an update and the empty Commands tab) lists the snippet lines whose
tools your `CLAUDE.md` files (global, project, parent folders and their `@` imports) don't mention yet. Choose the ones
you want: **Send to Claude** hands the prompt to a Claude Code session of this project that is done and waiting (see
[When Claude is waiting](#when-claude-is-waiting)), **Copy Prompt** puts it on the clipboard.

## Troubleshooting

- **No cards show up:** check that the MCP server is enabled and Claude Code lists the IDE under `/mcp`. Several open
  IDEs each run their own server; Claude talks to the one it is connected to.
- **Claude doesn't use the tools:** add the snippet to `CLAUDE.md`, or ask explicitly ("via Handoff").
- **`pending` from handoff_wait_for_result:** normal after 50 seconds; Claude calls it again.

## Version history

<!-- Newest version first. The first line under a version heading is shown as a one-line notice after an update. -->

### 1.2.0

Questions with options as a card (`handoff_ask`), and a reply field on "Claude is done".

Claude can ask one or more questions with options to pick (one or several) plus an own answer, instead of asking in the terminal. The "Claude is done" card shows the start of the message formatted, opens the full message in an editor tab and has a field for your next prompt: it goes straight to that Claude Code session. **Update CLAUDE.md…** (in this notice, the empty Commands tab or the ⋮ menu) lists the instructions your `CLAUDE.md` does not have yet; pick some and send them to a waiting Claude Code session, or copy the prompt. **Handoff Tools…** (⋮ menu, or double-click *Handoff tools* in the Usage tab) shows per tool how often Claude called it and switches its `CLAUDE.md` instruction and its permission. Cards are now in chronological order, the newest at the bottom (or on top: **Newest Cards on Top** in the ⋮ menu); Claude's messages stay pinned to the bottom of the Commands tab (**Pin Claude Messages to the Bottom**). This guide moved into the ⋮ menu. Once Handoff is set up, the empty Commands tab shows a single line instead of the setup.

### 1.1.0

Review Claude's changes in the diff and attach notes to single lines.

Hover a line in the diff or editor and click the **+** next to its line number, then send the notes back from the review card. A system notification tells you when something is waiting while the IDE is in the background.

Also new: several commands as one card (`handoff_propose_sequence`: run all or step by step, stops at the first failure), and **Show When Claude Is Waiting** in the tool window's ⋮ menu, which shows a card when a Claude Code session is done or needs your input.

Add the new `handoff_request_review` and `handoff_propose_sequence` lines from the snippet below to your `CLAUDE.md`.

### 1.0.1

Setup help in the empty Commands tab, this guide and a ready-made `CLAUDE.md` snippet.

### 1.0.0

First release: proposed commands, task changelists, images to review or choose from, Memory and Usage tabs.
