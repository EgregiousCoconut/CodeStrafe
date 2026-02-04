package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager

/**
 * ToggleCodeStrafeModeAction is a class used by the CodeStrafe plugin.
 *
 * - looks at all open editor windows.
 * - updates the IDE status bar text.
 * - shows an error popup if something goes wrong.
 * - requests a highlight refresh so the target box stays correct.
 */
class ToggleCodeStrafeModeAction : AnAction("Toggle CodeStrafe Navigation Mode") {

    /**
     * actionPerformed runs part of CodeStrafe behavior.
     *
     * - looks at all open editor windows.
     * - updates the IDE status bar text.
     * - shows an error popup if something goes wrong.
     * - requests a highlight refresh so the target box stays correct.
     *
     * Parameters: AnActionEvent.
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        try {
            CodeStrafeInputHook.ensureInstalled()
            CodeStrafeHighlightHook.ensureInstalled() // if you still have it

            val enabled = CodeStrafeState.toggleNavigationMode()

            if (enabled) {
                CodeStrafeHighlightPoller.start()
            } else {
                CodeStrafeHighlightPoller.stop()
            }

            val editors = EditorFactory.getInstance().allEditors.toList()
            CodeStrafeHighlightManager.refreshAll(editors)

            val statusBar = project?.let { WindowManager.getInstance().getStatusBar(it) }
            statusBar?.info = "CodeStrafe Nav Mode: " + (if (enabled) "ON" else "OFF")

        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "CodeStrafe toggle failed.\n\nError: ${t::class.java.simpleName}\nMessage: ${t.message ?: "(no message)"}",
                "CodeStrafe Error"
            )
        }
    }
}