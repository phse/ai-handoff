# Handoff for Claude Code

<img src="marketplace/plugin-icon.png" width="360" alt="Handoff for Claude Code icon for light and dark themes">

Plugin ID `app.heckit.ide.aihandoff` · Vendor heck\it ([heckit.app](https://heckit.app)) · Source: https://github.com/phse/ai-handoff · Support: [GitHub Sponsors](https://github.com/sponsors/phse) or [Ko-fi](https://ko-fi.com/phse_de) · Feedback and feature requests: [plugin-ai-handoff@heckit.app](mailto:plugin-ai-handoff@heckit.app)

A plugin for JetBrains IDEs (PhpStorm, IntelliJ IDEA, … 2026.1 and later): the place where Claude Code hands work
over to you, and you hand the result back.

Some steps an AI agent should not run on its own: deploys, logins, releases, uploads. Instead of copying a command out
of the chat, running it somewhere and pasting the output back, Claude proposes it through the IDE's MCP server.

- **Proposed commands** appear as cards in the **Handoff** tool window: command, reason and working directory.
  Risky ones (production, publishing, deleting) get a highlighted border.
- **Run** starts the command in the Run tool window, in a login shell (`$SHELL -l -i -c`), so your `PATH`, `JAVA_HOME`
  and aliases are there. The command can be edited before running.
- **Dismiss…** drops it, optionally with a note for Claude (e.g. "I'll do that tomorrow").
- **Result back to Claude**: exit code and the last 60 lines of output. After the run the card turns green or red.
- **One changelist per task**: Claude can open the changelist "Claude: <task>" before it edits files. Everything
  changed until the task ends lands there (new files are added to Git), so you review and commit the task on its own.
- Nothing runs without your click.

## Tools

| Tool | Parameters | Returns |
|---|---|---|
| `handoff_propose_command` | `command` (string), `reason` (string, one sentence), `workingDirectory` (absolute or relative to the project root, empty = root), `risky` (boolean, default `false`) | an `id` at once; opens the tool window |
| `handoff_begin_task` | `title` (a few words) | makes the new changelist `Claude: <title>` the active one |
| `handoff_end_task` | – | the files changed in the task; makes the previous changelist active again, removes the task's changelist if empty |
| `handoff_wait_for_result` | `id`, `timeoutSeconds` (1–50, default 50; MCP clients abort longer calls) | exit code and last 60 output lines, the dismiss note, or `pending` after the timeout (call again to keep waiting) |

## Installation

Not on the Marketplace yet. Build the ZIP (below) and install it via *Settings | Plugins | ⚙ | Install Plugin from
Disk…*.

Then:

1. Enable the IDE's MCP server: *Settings | Tools | MCP Server*.
2. Connect Claude Code to it (same settings page, *Auto-Configure* for Claude Code), or reconnect with `/mcp`
   if it was connected before.
3. Tell Claude when to use it, e.g. in your `CLAUDE.md`:

   ```
   Commands I should run myself (deploys, logins, releases, uploads): propose them with
   handoff_propose_command and wait for the result with handoff_wait_for_result
   instead of writing them into the chat.
   ```

## Build and run

Gradle needs JDK 17 or later.

```
./gradlew runIde        # start a sandbox IDE with the plugin
./gradlew buildPlugin   # build/distributions/ai-handoff-<version>.zip
```

Verify against the local IDE, the oldest supported version (`verifyOldestVersion`) and the newest EAP
(`verifyNewestVersion`):

```
./gradlew verifyPlugin
```

By default Gradle downloads PhpStorm `platformVersion` (from `gradle.properties`) as the target platform. With an
installed IDE it's faster: create `local.properties` (not checked in) containing e.g.

```
platformLocalPath=/Applications/PhpStorm.app
```

The sandbox runs its MCP server on a fixed port (`-Didea.mcp.server.force.port`, default `64499`, override with
`-PsandboxMcpPort=…`), so Claude Code can test against it next to your main IDE:

```
claude mcp add --transport http -s local phpstorm-sandbox http://127.0.0.1:64499/stream
```

While the sandbox runs, `./gradlew prepareSandbox_runIde -PdevBuild=$(date +%s)` reloads the plugin there without a restart
(the sandbox only reloads a plugin whose version changed; `devBuild` appends `-dev.<n>`). The reload happens once the sandbox
window gets focus.

## Publishing (JetBrains Marketplace)

The Marketplace description lives in `src/main/resources/META-INF/plugin.xml`, the change notes in `build.gradle.kts`,
the icon in `META-INF/pluginIcon.svg` (light theme) and `pluginIcon_dark.svg` (dark theme). The IDE and the Marketplace
read the icon straight from the ZIP. After changing the icon, regenerate the preview `marketplace/plugin-icon.png` at
the top of this README:

```
T=$(mktemp -d)
rsvg-convert -w 120 src/main/resources/META-INF/pluginIcon.svg -o $T/l.png
rsvg-convert -w 120 src/main/resources/META-INF/pluginIcon_dark.svg -o $T/d.png
magick -size 180x160 xc:'#F7F8FA' $T/l.png -gravity center -composite $T/L.png
magick -size 180x160 xc:'#2B2D30' $T/d.png -gravity center -composite $T/D.png
magick $T/L.png $T/D.png +append marketplace/plugin-icon.png
```

1. Bump the version in `gradle.properties`, add change notes.
2. `./gradlew clean buildPlugin verifyPlugin`
3. `./gradlew publishPlugin` with a token from https://plugins.jetbrains.com/author/me/tokens, either as
   `PUBLISH_TOKEN=…` or globally in `~/.gradle/gradle.properties` as `jetbrainsMarketplaceToken=…`.
4. Tag the commit (`git tag v<version>`) and push.

## How it works

The plugin registers an `mcpServer.mcpToolset` with the IDE's bundled MCP server (plugin `com.intellij.mcpServer`), so
any connected MCP client sees the two tools. Proposals live in a project service; `handoff_propose_command` returns
right away, `handoff_wait_for_result` suspends on the proposal until it is run or dismissed (or the timeout passes), so
a long deploy never blocks a single MCP call. Commands run as a `KillableColoredProcessHandler` in the Run tool window.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). "Handoff", "heck\it" and the logo are not covered by
the license.

Not affiliated with or endorsed by Anthropic. "Claude" and "Claude Code" are trademarks of Anthropic, PBC.
