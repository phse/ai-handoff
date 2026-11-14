# Handoff for Claude Code: Guide

Handoff is where Claude Code hands work over to you: commands to run yourself, images to review, tasks in their own
changelist. This guide covers the setup and one prompt per feature to put into your `CLAUDE.md`.

## What's new

<!-- Newest version first. The first line under a version heading is shown as a one-line notice after an update. -->

### 1.1.0

Review Claude's changes in the diff and attach notes to single lines.

Hover a line in the diff or editor and click the **+** next to its line number, then send the notes back from the review card. A system notification tells you when something is waiting while the IDE is in the background.

Also new: several commands as one card (`handoff_propose_sequence`: run all or step by step, stops at the first failure), and **Show When Claude Is Waiting** in the tool window's ⋮ menu, which shows a card when a Claude Code session is done or needs your input.

Add the new `handoff_request_review` and `handoff_propose_sequence` lines from the snippet below to your `CLAUDE.md`.

### 1.0.1

Setup help in the empty Commands tab, this guide (**?** in the tool window header) and a ready-made `CLAUDE.md` snippet.

### 1.0.0

First release: proposed commands, task changelists, images to review or choose from, Memory and Usage tabs.

## Setup

1. Enable the IDE's MCP server: **Settings | Tools | MCP Server**.
2. Connect Claude Code to it: on the same settings page click **Auto-Configure** for Claude Code. If Claude Code was
   already running, reconnect with `/mcp`.
3. Tell Claude when to use the tools: copy the snippet below into your `CLAUDE.md` (global `~/.claude/CLAUDE.md` for
   all projects, or the project's `CLAUDE.md`). The empty Commands tab has a **Copy CLAUDE.md Snippet** button for it.

Check: ask Claude *"Propose `echo hello` via Handoff."* A card shows up in the Commands tab.

## CLAUDE.md snippet

All features in one block. Leave out what you don't want.

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
```
<!-- snippet:end -->

## Features and their prompts

### Proposed commands

Claude proposes a command; it appears as a card with the reason and the working directory. **Run** executes it in the
Run tool window (type into it if the command asks for input), **Dismiss…** sends an optional note back. Risky commands
are highlighted. Exit code and the last 60 output lines go back to Claude.

Tools: `handoff_propose_command`, `handoff_wait_for_result`.

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

Tools: `handoff_propose_sequence`, `handoff_wait_for_result`.

```
Several commands in a row that I should run myself: propose them as one card with
handoff_propose_sequence instead of one by one.
```

### One changelist per task

Files Claude changes during a task land in their own changelist **Claude: &lt;title&gt;**. When the task ends, the
previous changelist becomes active again; an empty task changelist is removed.

Tools: `handoff_begin_task`, `handoff_end_task`.

```
Before the first file change of a new task, call handoff_begin_task with a short
title; after the last change call handoff_end_task.
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

Tools: `handoff_request_review`, `handoff_get_notes`, `handoff_wait_for_result`.

```
When a task is done, ask me to review the changes with handoff_request_review before
calling handoff_end_task, and wait with handoff_wait_for_result. Apply my line notes,
then ask again. If I say I left notes in the editor, fetch them with handoff_get_notes.
```

### Notifications

When a command, image or review is waiting and the IDE is not the active application, Handoff shows a system
notification and marks the IDE's dock or taskbar icon. The tool window icon carries a dot and the Commands tab a count
as long as something is waiting. Notifications can be switched off in the operating system's settings for the IDE.

### Claude is waiting

Optional: **Show When Claude Is Waiting** in the tool window's **⋮** menu. A card in the Commands tab then shows when a
Claude Code session in this project is done (with the start of its last message) or needs your input (permission
prompt, question), together with the dot, the count and the system notification described above. The card disappears
with your next prompt in that session, or with **Dismiss**. No prompt needed.

Claude Code reports this only through hooks, so Handoff creates `~/.claude/handoff/hook.sh` and adds it to
`~/.claude/settings.json` for the events Stop, Notification, UserPromptSubmit and SessionEnd (backup:
`settings.json.bak-handoff`); existing hooks stay. The script keeps one small file per waiting session in
`~/.claude/handoff/waiting/`. It applies to sessions started afterwards and only to sessions whose working directory
is the project folder or below it. Switching the menu item off removes the hooks and the script.

### Images to review or choose from

One image to approve, or several variants to pick one. The eye icon opens a viewer with zoom and paging (← →), where you
mark areas with a frame and a note for Claude.

Tools: `handoff_show_images`, `handoff_wait_for_result`.

```
Show images I should approve or choose from (icons, layouts, screenshots) with
handoff_show_images instead of opening them, and fetch my choice with
handoff_wait_for_result.
```

### Memory tab

Everything Claude Code loads into each session for this project: CLAUDE.md files, imports and project memory, as a tree
with token estimates per file and section. Warnings for memories missing from the index, on-demand files and duplicated
paragraphs. No prompt needed.

### Usage tab

Tokens per day and per session for this project (today, 7 or 30 days), read from Claude Code's session logs. No prompt
needed.

**Show Plan Limits…** adds your 5-hour and weekly plan limits (Pro and Max plans). Claude Code passes them only to a
status line script, so Handoff creates `~/.claude/handoff/statusline.sh` and sets it as the status line in
`~/.claude/settings.json` (backup: `settings.json.bak-handoff`). An existing status line keeps working; the script calls
it.

## Troubleshooting

- **No cards show up:** check that the MCP server is enabled and Claude Code lists the IDE under `/mcp`. Several open
  IDEs each run their own server; Claude talks to the one it is connected to.
- **Claude doesn't use the tools:** add the snippet to `CLAUDE.md`, or ask explicitly ("via Handoff").
- **`pending` from handoff_wait_for_result:** normal after 50 seconds; Claude calls it again.

Nothing runs without your click.
