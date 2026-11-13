# Handoff for Claude Code: Guide

Handoff is where Claude Code hands work over to you: commands to run yourself, images to review, tasks in their own
changelist. This guide covers the setup and one prompt per feature to put into your `CLAUDE.md`.

## What's new

<!-- Newest version first. The first line under a version heading is shown as a one-line notice after an update. -->

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
- Before the first file change of a new task, call handoff_begin_task with a short
  title; after the last change call handoff_end_task. Each task then gets its own
  changelist "Claude: <title>" that I review and commit separately.
- Show images I should approve or choose from (icons, layouts, screenshots) with
  handoff_show_images instead of opening them: one path to approve, several to choose
  one. Fetch my choice, note and marked areas with handoff_wait_for_result.
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

### One changelist per task

Files Claude changes during a task land in their own changelist **Claude: &lt;title&gt;**. When the task ends, the
previous changelist becomes active again; an empty task changelist is removed.

Tools: `handoff_begin_task`, `handoff_end_task`.

```
Before the first file change of a new task, call handoff_begin_task with a short
title; after the last change call handoff_end_task.
```

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
