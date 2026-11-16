import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    // Parameter von handoff_ask sind Objekte: Der MCP-Server der IDE leitet ihr Schema aus kotlinx.serialization ab.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// Rechnerspezifische Einstellungen (nicht eingecheckt), z. B. platformLocalPath=/Applications/PhpStorm.app
val localProperties = Properties().apply {
    file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val platformLocalPath: String? = localProperties.getProperty("platformLocalPath")?.takeIf { file(it).exists() }

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions { jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY) }
}

// ./gradlew runIde -Pscreenshots=<ordner> baut den Screenshot-Helfer mit ein (nie für Releases verwenden).
val screenshotDir = providers.gradleProperty("screenshots").orNull

sourceSets {
    main {
        if (screenshotDir != null) kotlin.srcDir("src/screenshot/kotlin")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (platformLocalPath != null) {
            local(platformLocalPath)
        } else {
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("platformVersion").get())
        }
        // MCP-Server der IDE: Über ihn bekommt Claude Code die Handoff-Werkzeuge.
        bundledPlugin("com.intellij.mcpServer")
        // ShowDiffAction (Diff der Aufgaben-Changelist) liegt im Modul der VCS-Implementierung.
        bundledModule("intellij.platform.vcs.impl")
        // Claude-Sessions im Terminal der IDE starten und ihnen Eingaben schicken (neues Terminal, Modul frontend).
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.terminal.frontend")
        // Ab 2026.3 liegt die VCS-API im eigenen Plugin - nur beim Bauen gegen eine lokale 2026.3 nötig (2026.2 hat es nicht).
        if (platformLocalPath != null && file("$platformLocalPath/Contents/plugins/platform-vcs-plugin").isDirectory) bundledPlugin("com.intellij.platform.vcs")
    }
}

intellijPlatform {
    pluginConfiguration {
        // -PdevBuild=<zahl> hängt eine Build-Nummer an (0.2.0-dev.<zahl>): Die Sandbox lädt ein Plugin mit unveränderter
        // Version beim Hot-Reload nicht neu ("did not meet expectations"), mit neuer Version schon.
        version = providers.gradleProperty("pluginVersion").zip(providers.gradleProperty("devBuild").orElse("")) { v, dev ->
            if (dev.isBlank()) v else "$v-dev.$dev"
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>1.5.1</h3>
            <ul>
              <li>Esc in the session tab's prompt field interrupts Claude, like in the terminal.</li>
              <li>Names of settings pages and IDE actions written in bold become links in the session tab (exact matches only);
                  sessions started from Handoff are told to look up action ids with <code>search_ide_actions</code> and link them.</li>
              <li>The suggested prompt stays visible while the prompt field has the focus, with a hint that Tab takes it.</li>
              <li>The prompt field no longer stops taking keys after sending a dropped image or dictated text.</li>
              <li>After an interrupt the tab and the cards no longer keep showing "Claude is working…"; the interrupt shows as a grey note.</li>
            </ul>
            <h3>1.5.0</h3>
            <ul>
              <li><b>Claude Code's own questions in the session tab</b>: when Claude asks with options (AskUserQuestion), the question
                  shows as a card in the tab and the Handoff tool window; your answer goes straight back to Claude.
                  <i>Answer in Terminal</i> hands it to the terminal dialog instead.</li>
              <li><b>Suggested prompt</b>: the next prompt Claude Code suggests shows grey in the empty prompt field; Tab takes it.</li>
              <li><b>Dictation</b> (macOS): microphone button above Send starts the system dictation into the prompt field.</li>
              <li>The session tab shows a spinner while Claude works (the speech bubble of the Handoff icon being traced), the
                  Handoff icon when it is done.</li>
              <li>A session that no longer runs (e.g. after an IDE restart) takes a prompt again: <i>Send</i> resumes it with that prompt.</li>
              <li>Session tab header no longer overlaps on narrow tabs; the status and path shorten instead.</li>
              <li>Hooks are updated automatically (new PreToolUse hook for AskUserQuestion).</li>
            </ul>
            <h3>1.4.1</h3>
            <ul>
              <li>Session tabs and guide tabs follow a theme switch (prompt backgrounds stayed dark after Dark → Light).</li>
            </ul>
            <h3>1.4.0</h3>
            <ul>
              <li><b>New Claude Session in Folder…</b>: start a session in another folder, outside the project too; its cards and
                  tab show in this project.</li>
              <li><b>Resume Claude Session…</b>: pick an earlier session of the project (title or first prompt, date, prompts) and
                  continue it in an editor tab. Both in ⌘N / File | New and the tool window's ⋮ menu.</li>
              <li>PhpStorm 2026.3: <i>New Claude Session…</i> and <i>Resume Claude Session…</i> (latest sessions of all folders) as buttons in the new welcome tab.</li>
              <li>Session tabs show the session's folder in the header (click to reveal) and as tooltip; the tab title is the
                  session's name ("New Session" until it has one), with the folder if it is not the project.</li>
              <li><b>/</b> button above <i>Send</i>: all slash commands with a short description; run one right away or insert it
                  at the cursor in the prompt.</li>
              <li>Tool calls in a row collapse to one line ("▸ 12 steps · last step"); click to expand.</li>
              <li>A queued prompt no longer shows twice once Claude has picked it up.</li>
              <li>Multi-line prompts from the session tab arrive as typed text, without the <code>&lt;pasted_content&gt;</code> markers
                  Claude Code adds to pasted text (visible in Remote Control); prompts with those markers show in the tab again.</li>
              <li>Answers Claude no longer waits for (e.g. an image approved with marked areas after Claude finished) go to the
                  session as a prompt instead of getting lost.</li>
              <li>Image viewer: double-click a note to edit it.</li>
              <li>Image cards show the current file when Claude overwrites an image under the same name.</li>
              <li>Sessions started from Handoff are told to show images through <code>handoff_show_images</code> instead of opening them.</li>
            </ul>
            <h3>1.3.1</h3>
            <ul>
              <li>New cards no longer pop up the Handoff tool window while a session tab is in front; the card shows in the tab.</li>
            </ul>
            <h3>1.3.0</h3>
            <ul>
              <li><b>Claude sessions in editor tabs</b>: <i>New Claude Session</i> (also in ⌘N / File | New) starts Claude Code in a terminal tab in the
                  background and shows the session in an editor tab, the conversation formatted, a prompt field at the bottom.</li>
              <li>Prompts go to the terminal as if typed, so slash commands like <code>/clear</code> work; buttons for permission
                  questions, <i>Interrupt</i> and <i>Show Terminal</i>.</li>
              <li><code>/show-in-editor</code> in a Claude Code session opens its editor tab; cards have <i>Open in Editor</i>.</li>
              <li>Plan limits (5 hours, week), model and context use in the session tab's header.</li>
              <li>Suggestions for slash commands when you type <code>/</code>; a spinner below the last prompt while Claude works.</li>
              <li>Links in Claude's messages open files (with line), settings pages and IDE actions; plain URLs and paths become
                  links too, right-click for Reveal in Finder, Open in Terminal and Copy. New sessions are told to link a lot.</li>
              <li>Drop images onto the prompt field or paste a screenshot; Claude gets the file path. Mark areas with a note right
                  there, as in the image viewer.</li>
              <li>Image viewer: drag on a free spot to draw a frame right away (no pen button any more), click a frame to select it.</li>
              <li>Waiting Handoff cards (commands, questions, reviews) also show at the end of the conversation in the session tab. Session tabs open again after
                  a restart; <i>Resume in Terminal</i> continues a session that no longer runs.</li>
              <li>"Claude is working…" card after a prompt, until Claude is done.</li>
              <li>Editor tabs of Handoff show the Handoff icon. System notifications work again in 2026.3.</li>
              <li>Note: starting sessions and sending input uses the IDE's terminal API, which JetBrains marks as experimental.
                  It may change in future IDE versions; Handoff then turns off only this part.</li>
            </ul>
            <h3>1.2.0</h3>
            <ul>
              <li><b>Questions with options</b> (<code>handoff_ask</code>): Claude asks one or more questions as a card (Back/Next),
                  one answer or several per question, each option with a short description, plus a field for your own answer.</li>
              <li><b>Handoff Tools…</b>: calls per tool, and per tool switches for the CLAUDE.md instruction and the permission
                  (<code>permissions.deny</code>).</li>
              <li>Cards in chronological order, newest at the bottom or on top (⋮ menu); Claude's messages pinned to the bottom
                  of the Commands tab (can be switched off). The guide moved into the ⋮ menu.</li>
              <li><b>Claude is done</b>: the card shows the start of the message formatted, <i>Open Full Message</i> shows all of it in
                  an editor tab, and a field sends your next prompt straight to that Claude Code session.</li>
              <li><b>Update CLAUDE.md…</b>: pick the Handoff instructions your CLAUDE.md is missing and send them to a waiting Claude
                  Code session, or copy the prompt. The Usage tab shows which Handoff tools Claude calls.</li>
              <li>Once set up, the empty Commands tab shows one line instead of the setup steps.</li>
              <li>System notifications show Claude's messages without Markdown characters; clicking one brings the right project
                  window to the front (2026.2 and later).</li>
            </ul>
            <h3>1.1.0</h3>
            <ul>
              <li><b>Review with line notes</b>: Claude asks you to review its changes (<code>handoff_request_review</code>). Open the
                  diff from the card, attach notes to single lines (hover a line and click the <b>+</b>, or <i>Add Note for Claude…</i> in the
                  context menu of the diff or editor), then approve or send the notes back. Notes left without a review are fetched with
                  <code>handoff_get_notes</code>.</li>
              <li><b>Command sequences</b>: several commands as one card (<code>handoff_propose_sequence</code>). Run them all or
                  step by step; the sequence stops at the first command that fails, and Claude gets exit code and output per step.</li>
              <li><b>Claude is waiting</b> (optional, <i>Show When Claude Is Waiting</i> in the tool window's menu): a card and a
                  notification when a Claude Code session in the project is done or needs your input. Set up through Claude Code hooks.</li>
              <li><b>Notifications</b>: a system notification when something is waiting while the IDE is in the background, a dot
                  on the tool window icon and a count on the <i>Commands</i> tab.</li>
            </ul>
            <h3>1.0.1</h3>
            <ul>
              <li>Setup help in the empty <i>Commands</i> tab: link to the MCP server settings and a ready-made CLAUDE.md snippet.</li>
              <li>Built-in guide (<b>?</b> in the tool window header): setup, a CLAUDE.md prompt per feature and what's new.</li>
              <li>A short notice after updates that bring new features.</li>
              <li>The plugin unloads more cleanly on update.</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
              <li>First release. Claude Code proposes commands you should run yourself (deploys, logins, releases); run, edit or
                  dismiss them in the <i>Handoff</i> tool window, exit code and output go straight back to Claude.</li>
              <li>One changelist per Claude task.</li>
              <li>Images to review or choose from, with a viewer to mark areas and add notes for Claude.</li>
              <li><i>Memory</i> tab: CLAUDE.md files, imports and project memory with token estimates and warnings.</li>
              <li><i>Usage</i> tab: tokens per day and session, optionally the 5-hour and weekly plan limits.</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("jetbrainsMarketplaceToken"))
    }

    pluginVerification {
        ides {
            platformLocalPath?.let { local(it) }
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyOldestVersion").get())
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyNewestVersion").get())
        }
    }
}

// Apache 2.0 verlangt, dass LICENSE und NOTICE mit dem Plugin verteilt werden.
tasks.jar {
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
}

// Fester JAR-Name: Die Sandbox erkennt beim Hot-Reload nur eine geänderte, keine neu benannte Datei.
tasks.composedJar {
    archiveVersion.set("")
}

// Für Screenshots wird das Demo-Projekt an einen neutralen Ort kopiert, damit kein lokaler Benutzerpfad im Bild erscheint.
val screenshotProjectDir = "/Users/Shared/handoff-demo"
val prepareScreenshotProject = tasks.register<Sync>("prepareScreenshotProject") {
    from("marketplace/demo-project")
    into(screenshotProjectDir)
    // Als Git-Repo mit einem Commit, damit der Helfer eine Änderung samt Diff zeigen kann (Review-Screenshot).
    val dir = screenshotProjectDir
    doLast {
        fun git(vararg args: String) = ProcessBuilder(listOf("git", "-c", "user.name=Demo", "-c", "user.email=demo@example.com") + args)
            .directory(File(dir)).redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor()
        File(dir, ".git").deleteRecursively()
        git("init", "-q", "-b", "main"); git("add", "-A"); git("commit", "-q", "-m", "Initial commit")
    }
}

tasks.runIde {
    jvmArgs("-Didea.trust.all.projects=true")
    // Eigener fester MCP-Port für die Sandbox (den Server selbst einmal in den Sandbox-Settings einschalten), damit Claude Code
    // parallel zur normalen IDE gegen die Sandbox testen kann. Neue Builds per
    // `./gradlew prepareSandbox_runIde -PdevBuild=$(date +%s)` lädt die laufende Sandbox ohne Neustart nach (autoReload).
    jvmArgs("-Didea.mcp.server.force.port=" + (providers.gradleProperty("sandboxMcpPort").orNull ?: "64499"))
    autoReload = true
    // Hot-Reload: Ein "required" bzw. per plugin.path eingebundenes Plugin lässt sich nicht entladen ("expected not to be
    // loaded but was included in the module graph"). Ohne beide lädt die Sandbox es normal aus plugins_runIde.
    doFirst {
        val providers = jvmArgumentProviders.toList()
        jvmArgumentProviders.clear()
        providers.forEach { p ->
            jvmArgumentProviders.add(CommandLineArgumentProvider {
                p.asArguments().filterNot { it.startsWith("-Didea.required.plugins.id=") || it.startsWith("-Dplugin.path=") }
            })
        }
    }
    if (screenshotDir != null) {
        dependsOn(prepareScreenshotProject)
        args(screenshotProjectDir)
        jvmArgs("-Dhandoff.screenshots=${file(screenshotDir).absolutePath}")
    } else {
        providers.gradleProperty("openProject").orNull?.let { args(it) }
    }
}

// Ohne -Pscreenshots bleibt aus dem Build-Cache sonst ein leeres screenshot/-Verzeichnis in den Jars.
tasks.withType<Zip>().configureEach {
    includeEmptyDirs = false
}
