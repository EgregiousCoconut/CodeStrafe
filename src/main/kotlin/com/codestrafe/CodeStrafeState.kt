package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor

object CodeStrafeState {

    private val log = Logger.getInstance(CodeStrafeState::class.java)

    @Volatile private var navigationModeEnabled: Boolean = false

    fun isNavigationModeEnabled(): Boolean = navigationModeEnabled

    fun setNavigationModeEnabled(enabled: Boolean) {
        navigationModeEnabled = enabled
        log.info("Navigation mode set to: " + (if (enabled) "ON" else "OFF"))
    }

    fun toggleNavigationMode(): Boolean {
        navigationModeEnabled = !navigationModeEnabled
        log.info("Navigation mode toggled to: " + (if (navigationModeEnabled) "ON" else "OFF"))
        return navigationModeEnabled
    }

    fun updateEditorContext(editor: Editor) {
        // optional
    }

    fun snapshotCaretPosition(editor: Editor) {
        // optional
    }
}