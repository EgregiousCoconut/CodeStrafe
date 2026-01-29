package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import kotlin.math.max
import kotlin.math.min

/**
 * CodeStrafeMoveAction
 *
 * A single action class that can be registered multiple times (different action IDs)
 * and bound to shortcuts like Cmd+W/A/S/D in plugin.xml.
 *
 * This is the correct way to make Command/Ctrl modifiers work on macOS/JetBrains,
 * because shortcuts are handled at the action layer (not the typed-character layer).
 */
class CodeStrafeMoveAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        // Only active while Navigation Mode is enabled
        if (!CodeStrafeState.isNavigationModeEnabled()) return

        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        CodeStrafeState.updateEditorContext(editor)

        when (e.actionManager.getId(this)) {
            "CodeStrafe.CmdUp" -> moveLines(editor, -1)
            "CodeStrafe.CmdDown" -> moveLines(editor, +1)
            "CodeStrafe.CmdLeft" -> moveWord(editor, -1)
            "CodeStrafe.CmdRight" -> moveWord(editor, +1)
            "CodeStrafe.CmdPanUp" -> panLines(editor, -10)
            "CodeStrafe.CmdPanDown" -> panLines(editor, +10)
            "CodeStrafe.CmdJump" -> { moveLines(editor, 15); centerCaret(editor) }
        }

        CodeStrafeState.snapshotCaretPosition(editor)
    }

    private fun moveLines(editor: Editor, delta: Int) {
        val caret = editor.caretModel.currentCaret
        val doc = editor.document
        val current = caret.logicalPosition
        val newLine = min(max(current.line + delta, 0), doc.lineCount - 1)
        caret.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(newLine, current.column))
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE)
    }

    private fun moveWord(editor: Editor, direction: Int) {
        val caret = editor.caretModel.currentCaret
        val doc = editor.document
        val text = doc.charsSequence
        var i = caret.offset

        fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

        if (direction > 0) {
            while (i < text.length && isWordChar(text[i])) i++
            while (i < text.length && !isWordChar(text[i])) i++
        } else {
            i = max(i - 1, 0)
            while (i > 0 && !isWordChar(text[i])) i--
            while (i > 0 && isWordChar(text[i - 1])) i--
        }

        caret.moveToOffset(min(max(i, 0), doc.textLength))
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE)
    }

    private fun panLines(editor: Editor, deltaLines: Int) {
        val lineHeight = editor.lineHeight
        val current = editor.scrollingModel.verticalScrollOffset
        editor.scrollingModel.scrollVertically(current + deltaLines * lineHeight)
    }

    private fun centerCaret(editor: Editor) {
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }
}