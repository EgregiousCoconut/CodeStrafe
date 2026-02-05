package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Timer

/**
 * CodeStrafeCapsLockService
 *
 * macOS can produce inconsistent Caps Lock event streams:
 * - Sometimes multiple events per physical press
 * - Sometimes missing KEY_RELEASED
 * - Sometimes "off" looks different than "on"
 *
 * Reliable strategy:
 * - Treat any Caps Lock event (pressed or released) as part of a "burst".
 * - Toggle ONCE per burst.
 * - Ignore additional Caps Lock events until a short timer expires.
 *
 * We do NOT consume Caps Lock (return false) so the OS keeps LED/state behavior.
 */
object CodeStrafeCapsLockService {

    private val log = Logger.getInstance(CodeStrafeCapsLockService::class.java)
    private val installed = AtomicBoolean(false)

    // True while we're inside a Caps Lock event "burst"
    private val burstActive = AtomicBoolean(false)

    // How long to ignore extra Caps Lock events after the first one
    private const val BURST_WINDOW_MS = 220

    @Volatile private var burstResetTimer: Timer? = null

    private fun armBurstReset() {
        burstResetTimer?.stop()
        val t = Timer(BURST_WINDOW_MS) {
            burstActive.set(false)
            log.warn("CODESTRAFE_CAPS: burst reset")
        }
        t.isRepeats = false
        burstResetTimer = t
        t.start()
    }

    private val dispatcher: KeyEventDispatcher = KeyEventDispatcher { e: KeyEvent ->
        try {
            if (e.keyCode != KeyEvent.VK_CAPS_LOCK) return@KeyEventDispatcher false

            // Log what we see (helps if we need to tune BURST_WINDOW_MS)
            log.warn("CODESTRAFE_CAPS: event id=" + e.id + " when=" + e.`when` + " mods=" + e.modifiersEx)

            // Toggle only once per burst window
            if (!burstActive.compareAndSet(false, true)) {
                armBurstReset()
                return@KeyEventDispatcher false
            }

            // First event in the burst: toggle
            val enabled = CodeStrafeState.toggleNavigationMode()
            log.warn("CODESTRAFE_CAPS: TOGGLED -> navigationModeEnabled=" + enabled)

            armBurstReset()
            false
        } catch (t: Throwable) {
            log.warn("CODESTRAFE_CAPS: dispatcher error", t)
            false
        }
    }

    fun installIfNeeded() {
        if (!installed.compareAndSet(false, true)) return
        burstActive.set(false)
        burstResetTimer?.stop()
        burstResetTimer = null
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        log.warn("CODESTRAFE_CAPS: AWT KeyEventDispatcher installed")
    }

    fun uninstallIfNeeded() {
        if (!installed.compareAndSet(true, false)) return
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        burstResetTimer?.stop()
        burstResetTimer = null
        burstActive.set(false)
        log.warn("CODESTRAFE_CAPS: AWT KeyEventDispatcher removed")
    }
}