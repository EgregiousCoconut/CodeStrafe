package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

class ToggleCodeStrafeVoiceAction : AnAction("Toggle CodeStrafe Voice") {

    private val log = Logger.getInstance(ToggleCodeStrafeVoiceAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        log.warn("CODESTRAFE_VOICE: ToggleCodeStrafeVoiceAction.actionPerformed() called")

        if (CodeStrafeVoiceService.isListening()) {
            CodeStrafeVoiceService.stopListening(project)
        } else {
            CodeStrafeVoiceService.startListening(project)
        }
    }
}