package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

/**
 * DebugCodeStrafeStateAction
 *
 * Shows current CodeStrafe state and whether an editor is currently available from the event.
 * This avoids relying on any CodeStrafeState.getCurrentEditor() method.
 */
class DebugCodeStrafeStateAction : AnAction("CodeStrafe: Debug State") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)

        val message =
            "Navigation Mode: " + (if (CodeStrafeState.isNavigationModeEnabled()) "ON" else "OFF") + "\n" +
                    "Input Hook Installed: " + (if (CodeStrafeInputHook.isInstalled()) "YES" else "NO") + "\n" +
                    "Editor detected: " + (if (editor != null) "YES" else "NO")

        Messages.showInfoMessage(project, message, "CodeStrafe Debug")
    }
}