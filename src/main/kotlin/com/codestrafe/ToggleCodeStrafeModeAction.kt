package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.WindowManager

class ToggleCodeStrafeModeAction : AnAction("Toggle CodeStrafe Navigation Mode") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project

        try {
            // Install once and keep forever (prevents handler recursion issues).
            CodeStrafeInputHook.ensureInstalled()

            val enabled = CodeStrafeState.toggleNavigationMode()

            val statusBar = project?.let { WindowManager.getInstance().getStatusBar(it) }
            statusBar?.info =
                "CodeStrafe Nav Mode: " + (if (enabled) "ON" else "OFF") +
                        " | Hook: " + (if (CodeStrafeInputHook.isInstalled()) "INSTALLED" else "NOT installed")

        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "CodeStrafe toggle failed.\n\n" +
                        "Error: ${t::class.java.simpleName}\n" +
                        "Message: ${t.message ?: "(no message)"}",
                "CodeStrafe Error"
            )
        }
    }
}