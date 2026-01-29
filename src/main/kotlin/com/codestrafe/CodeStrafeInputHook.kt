package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

object CodeStrafeInputHook {

    private val log = Logger.getInstance(CodeStrafeInputHook::class.java)
    private val installed = AtomicBoolean(false)

    fun ensureInstalled() {
        runOnEdt {
            if (installed.get()) return@runOnEdt
            TypedAction.getInstance().setupHandler(CodeStrafeTypedHandler())
            installed.set(true)
            log.info("CodeStrafeInputHook installed")
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeAndWait { block() }
    }

    private class CodeStrafeTypedHandler : TypedActionHandler {

        override fun execute(
            editor: Editor,
            charTyped: Char,
            dataContext: com.intellij.openapi.actionSystem.DataContext
        ) {
            // Normal typing when Nav Mode is off
            if (!CodeStrafeState.isNavigationModeEnabled()) {
                typeNormally(editor, charTyped)
                return
            }

            // In Nav Mode: intercept movement keys, including SHIFT variants (uppercase)
            val handled = when (charTyped) {
                'w', 'W' -> { moveLines(editor, if (charTyped == 'W') -5 else -1); true }
                's', 'S' -> { moveLines(editor, if (charTyped == 'S') +5 else +1); true }

                // A/D are horizontal navigation
                // lowercase = word, uppercase (shift) = faster word movement
                'a', 'A' -> { moveWord(editor, direction = -1, steps = if (charTyped == 'A') 5 else 1); true }
                'd', 'D' -> { moveWord(editor, direction = +1, steps = if (charTyped == 'D') 5 else 1); true }

                // Error targeting
                'n', 'N' -> { CodeStrafeIdeActions.gotoNextError(dataContext); true }
                'p', 'P' -> { CodeStrafeIdeActions.gotoPreviousError(dataContext); true }

                else -> false
            }

            if (handled) {
                CodeStrafeState.snapshotCaretPosition(editor)
                CodeStrafeHighlightManager.updateForEditor(editor)
                return
            }

            // In Nav Mode, allow other keys to type (your design choice).
            // If you want Nav Mode to block ALL typing, tell me and I’ll flip this behavior.
            typeNormally(editor, charTyped)
        }

        private fun typeNormally(editor: Editor, charTyped: Char) {
            EditorModificationUtil.insertStringAtCaret(editor, charTyped.toString(), true, true)
        }

        private fun moveLines(editor: Editor, deltaLines: Int) {
            val caret = editor.caretModel.currentCaret
            val doc = editor.document
            val current = caret.logicalPosition
            val newLine = (current.line + deltaLines).coerceIn(0, doc.lineCount - 1)
            caret.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(newLine, current.column))
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }

        private fun moveWord(editor: Editor, direction: Int, steps: Int) {
            repeat(max(1, steps)) {
                moveOneWord(editor, direction)
            }
        }

        private fun moveOneWord(editor: Editor, direction: Int) {
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
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
    }
}