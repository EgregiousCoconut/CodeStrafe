package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * CodeStrafeCapsLockService
 *
 * Optional Caps Lock listener that toggles CodeStrafe mode.
 * Note: Caps Lock is unreliable on macOS, so this is best-effort only.
 */
class CodeStrafeCapsLockService {

    init {
        installCapsLockListener()
    }

    private fun installCapsLockListener() {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()

        manager.addKeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_CAPS_LOCK) {
                ApplicationManager.getApplication().invokeLater {
                    // Ensure the input hook is installed once
                    CodeStrafeInputHook.ensureInstalled()

                    // Toggle navigation mode
                    CodeStrafeState.toggleNavigationMode()
                }
                true
            } else {
                false
            }
        }
    }
}