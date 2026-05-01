package com.codestrafe

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlin.math.max

object CodeStrafeVoiceCommandRouter {

    private val log = Logger.getInstance(CodeStrafeVoiceCommandRouter::class.java)

    fun route(project: Project, canonicalCommand: String, originalTranscript: String) {
        ApplicationManager.getApplication().invokeLater {
            log.warn("CODESTRAFE_VOICE: routing '$canonicalCommand' from transcript '$originalTranscript'")

            when (canonicalCommand) {
                "TOGGLE_MODE" -> {
                    try {
                        CodeStrafeState.toggleNavigationMode()
                        val editor = CodeStrafeState.getCurrentEditor()
                        if (editor != null) {
                            CodeStrafeHighlightManager.refreshAll(listOf(editor))
                        }
                    } catch (t: Throwable) {
                        log.warn("CODESTRAFE_VOICE: toggle mode failed", t)
                    }
                }

                "MOVE_UP" -> withEditor(project) { moveLines(it, -1) }

                "MOVE_DOWN" -> withEditor(project) { moveLines(it, +1) }

                "MOVE_LEFT" -> withEditor(project) { moveChars(it, -1) }

                "MOVE_RIGHT" -> withEditor(project) { moveChars(it, +1) }

                "TAB_FORWARD" -> withEditor(project) {
                    CodeStrafePsiTargeting.cyclePsiTarget(it, forward = true)
                    CodeStrafeState.snapshotCaretPosition(it)
                    CodeStrafeHighlightManager.updateForEditor(it)
                }

                "TAB_BACK" -> withEditor(project) {
                    CodeStrafePsiTargeting.cyclePsiTarget(it, forward = false)
                    CodeStrafeState.snapshotCaretPosition(it)
                    CodeStrafeHighlightManager.updateForEditor(it)
                }

                "NEXT_ERROR" -> {
                    notify(
                        project,
                        "CodeStrafe Voice",
                        "Next error is not wired in this first pass yet.",
                        NotificationType.WARNING
                    )
                }

                "PREVIOUS_ERROR" -> {
                    notify(
                        project,
                        "CodeStrafe Voice",
                        "Previous error is not wired in this first pass yet.",
                        NotificationType.WARNING
                    )
                }

                "STOP_LISTENING" -> {
                    CodeStrafeVoiceService.stopListening(project)
                }

                else -> {
                    notify(
                        project,
                        "CodeStrafe Voice",
                        "Could not confidently map transcript: \"$originalTranscript\"",
                        NotificationType.WARNING
                    )
                }
            }
        }
    }

    private inline fun withEditor(project: Project, block: (Editor) -> Unit) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        block(editor)
    }

    private fun moveLines(editor: Editor, deltaLines: Int) {
        val caret = editor.caretModel.currentCaret
        val doc = editor.document
        val current = caret.logicalPosition
        val newLine = (current.line + deltaLines).coerceIn(0, max(0, doc.lineCount - 1))

        caret.moveToLogicalPosition(LogicalPosition(newLine, current.column))
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)

        CodeStrafeState.snapshotCaretPosition(editor)
        CodeStrafeHighlightManager.updateForEditor(editor)
    }

    private fun moveChars(editor: Editor, delta: Int) {
        val caret = editor.caretModel.currentCaret
        val len = editor.document.textLength
        val next = (caret.offset + delta).coerceIn(0, len)

        caret.moveToOffset(next)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)

        CodeStrafeState.snapshotCaretPosition(editor)
        CodeStrafeHighlightManager.updateForEditor(editor)
    }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeStrafe Notifications")
                .createNotification(title, content, type)
                .notify(project)
        } catch (_: Throwable) {
            log.warn("CODESTRAFE_VOICE: notification fallback -> $title: $content")
        }
    }
}