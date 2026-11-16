package app.heckit.ide.aihandoff

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Diktieren (macOS): die Diktierfunktion des Systems starten bzw. beenden - dieselbe Aktion wie "Bearbeiten > Diktat starten"
 * (`startDictation:` an die Responder-Kette; während des Diktats beendet sie es). Der Text kommt über die Eingabemethode
 * ins fokussierte Feld.
 */
object Dictation {
    val available get() = com.intellij.openapi.util.SystemInfo.isMac

    /** [done] bekommt auf dem EDT, ob macOS die Aktion angenommen hat (false: Diktat in den Systemeinstellungen aus). */
    fun toggle(done: (Boolean) -> Unit) {
        fun finish(handled: Boolean) = SwingUtilities.invokeLater { done(handled) }
        SwingUtilities.invokeLater {
            runCatching {
                // Nicht auf den Hauptthread warten (könnte mit dem EDT verklemmen) - Ergebnis kommt per invokeLater zurück.
                com.intellij.ui.mac.foundation.Foundation.executeOnMainThread(true, false) {
                    val ok = runCatching {
                        val app = com.intellij.ui.mac.foundation.Foundation.invoke("NSApplication", "sharedApplication")
                        // createSelector liefert je nach IDE-Version Pointer oder Selector - per Reflexion, sonst meldet der
                        // Verifier die Methode unter 2026.1 als fehlend.
                        val sel = com.intellij.ui.mac.foundation.Foundation::class.java.getMethod("createSelector", String::class.java)
                            .invoke(null, "startDictation:")
                        com.intellij.ui.mac.foundation.Foundation.invoke(app, "sendAction:to:from:", sel,
                            com.intellij.ui.mac.foundation.ID.NIL, com.intellij.ui.mac.foundation.ID.NIL).toInt() != 0
                    }.onFailure { logger<Dictation>().warn("Handoff: dictation failed", it) }.getOrDefault(false)
                    finish(ok)
                }
            }.onFailure { logger<Dictation>().warn("Handoff: dictation failed", it); finish(false) }
        }
    }

    /**
     * [field] mit Mikrofon-Knopf rechts daneben (Notizfelder der Karten). Klick fokussiert das Feld und startet das Diktat,
     * ein zweiter Klick oder Esc beendet es; verliert das Feld den Fokus, gilt es als beendet. Ohne macOS nur das Feld.
     */
    fun withMic(field: JComponent, target: JComponent = field): JComponent {
        if (!available) return field
        var on = false
        val mic = object : JButton(HandoffIcons.mic()) {
            override fun getPreferredSize() = super.getPreferredSize().apply { width = JBUI.scale(34) }
        }
        fun set(value: Boolean) {
            on = value
            mic.icon = HandoffIcons.mic(on = value)
            mic.toolTipText = if (value) "Stop dictation (Esc)" else "Dictate (macOS dictation)"
        }
        set(false)
        mic.addActionListener {
            val stop = on
            target.requestFocusInWindow()
            toggle { ok -> set(ok && !stop); if (!ok) mic.toolTipText = "Dictation did not start - switch it on in System Settings > Keyboard > Dictation." }
        }
        target.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { if (on && e.oppositeComponent !== mic) set(false) }
        })
        target.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) { if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE && on) set(false) }
        })
        return JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(field, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(mic, BorderLayout.NORTH) }, BorderLayout.EAST)
        }
    }
}
