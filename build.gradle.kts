import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
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
    providers.gradleProperty("openProject").orNull?.let { args(it) }
}
