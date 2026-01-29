package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.ui.Messages

/**
 * Debug action to inspect CodeStrafe internal state.
 */
class DebugCodeStrafeStateAction : AnAction("CodeStrafe: Debug State") {

    override fun actionPerformed(e: AnActionEvent) {
        val editors = EditorFactory.getInstance().allEditors
        val activeEditors = editors.size

        val msg = buildString {
            appendLine("CodeStrafe Debug State")
            appendLine("----------------------")
            appendLine("Navigation Mode: ${CodeStrafeState.isNavigationModeEnabled()}")
            appendLine("Editors Open: $activeEditors")

            if (activeEditors > 0) {
                val ed = editors.first()
                appendLine("Caret Offset: ${ed.caretModel.offset}")
                appendLine("Selection: ${ed.selectionModel.selectionStart} → ${ed.selectionModel.selectionEnd}")
            }
        }

        Messages.showInfoMessage(e.project, msg, "CodeStrafe Debug")
    }
}