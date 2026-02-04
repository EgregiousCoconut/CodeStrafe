package com.codestrafe

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * CodeStrafeState
 *
 * Minimal global state holder for CodeStrafe V1.
 *
 * Responsibilities:
 * - Track whether Navigation Mode is enabled
 * - Track the "current" editor (best-effort, updated by controller/hooks)
 * - Track last caret position (for simple backtracking, future work)
 *
 * Notes:
 * - This is intentionally lightweight and thread-safe.
 * - Editor references should be updated by your controller when focus changes.
 */
object CodeStrafeState {

    private val navigationModeEnabled = AtomicBoolean(false)
    private val currentEditorRef = AtomicReference<Editor?>(null)
    private val lastCaretLogicalPosRef = AtomicReference<LogicalPosition?>(null)

    /** Returns true if CodeStrafe Navigation Mode is enabled. */
    fun isNavigationModeEnabled(): Boolean = navigationModeEnabled.get()

    /** Enables/disables Navigation Mode explicitly. */
    fun setNavigationModeEnabled(enabled: Boolean) {
        navigationModeEnabled.set(enabled)
    }

    /** Toggles Navigation Mode and returns the new state. */
    fun toggleNavigationMode(): Boolean {
        val newValue = !navigationModeEnabled.get()
        navigationModeEnabled.set(newValue)
        return newValue
    }

    /**
     * Returns the most recently known editor (may be null).
     * The controller should keep this updated as editor focus changes.
     */
    fun getCurrentEditor(): Editor? = currentEditorRef.get()

    /**
     * Updates editor context.
     * Call this whenever an editor gains focus or when handling an event tied to a specific editor.
     */
    fun updateEditorContext(editor: Editor?) {
        currentEditorRef.set(editor)
    }

    /** Returns the last stored caret logical position (may be null). */
    fun getLastCaretLogicalPosition(): LogicalPosition? = lastCaretLogicalPosRef.get()

    /**
     * Stores the last caret logical position.
     * Typically called before applying a navigation move so you can "return" later.
     */
    fun setLastCaretLogicalPosition(pos: LogicalPosition?) {
        lastCaretLogicalPosRef.set(pos)
    }

    /**
     * Convenience: snapshot current caret position from an Editor (best-effort).
     * Safe to call frequently; you may want to throttle updates elsewhere.
     */
    fun snapshotCaretPosition(editor: Editor?) {
        if (editor == null) return
        val caret = editor.caretModel.currentCaret
        lastCaretLogicalPosRef.set(caret.logicalPosition)
    }
}
