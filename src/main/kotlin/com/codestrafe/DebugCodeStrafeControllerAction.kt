package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class DebugCodeStrafeControllerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        Messages.showInfoMessage(
            "Controller debug is currently being written to idea.log.\n\n" +
                    "Search the log for:\n" +
                    "CODESTRAFE_CONTROLLER",
            "CodeStrafe Controller Debug"
        )
    }
}