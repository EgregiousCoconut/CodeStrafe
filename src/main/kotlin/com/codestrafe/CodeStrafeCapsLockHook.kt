package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.KeyboardFocusManager

/**
 * CodeStrafeCapsLockHook
 *
 * Listens for Caps Lock presses and toggles CodeStrafe mode.
 * macOS note: Caps Lock events are unreliable, so this is optional.
 */
object CodeStrafeCapsLockHook {

    private var installed = false

    /**
     * install runs part of CodeStrafe behavior.
     *
     * - listens to raw key press and key release events.
     * - registers a global key event dispatcher.
     * - reads or updates CodeStrafe's global mode state.
     *
     * 
     */
    fun install() {
        if (installed) return
        installed = true

        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()

        manager.addKeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_CAPS_LOCK) {
                ApplicationManager.getApplication().invokeLater {
                    // Ensure CodeStrafe input hook is installed once
                    CodeStrafeInputHook.ensureInstalled()

                    // Toggle mode
                    CodeStrafeState.toggleNavigationMode()
                }
                true
            } else {
                false
            }
        }
    }
}