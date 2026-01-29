package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * CodeStrafeInputHook
 *
 * IMPORTANT DESIGN CHANGE:
 * - We install the TypedAction handler ONCE and never uninstall it.
 * - We avoid delegating to other TypedActionHandlers (prevents recursion/StackOverflow).
 * - When we want "normal typing", we insert text directly via EditorModificationUtil.
 *
 * This is extremely stable on macOS and avoids handler-chain recursion.
 */
object CodeStrafeInputHook {

    private val log = Logger.getInstance(CodeStrafeInputHook::class.java)
    private val installed = AtomicBoolean(false)

    /**
     * Install once. Safe to call repeatedly.
     */
    fun ensureInstalled() {
        runOnEdt {
            if (installed.get()) return@runOnEdt

            val typedAction = TypedAction.getInstance()
            typedAction.setupHandler(CodeStrafeTypedHandler())
            installed.set(true)

            log.info("CodeStrafeInputHook installed (permanent)")
        }
    }

    fun isInstalled(): Boolean = installed.get()

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
            // If Navigation Mode is OFF, behave like normal typing (WITHOUT delegating).
            if (!CodeStrafeState.isNavigationModeEnabled()) {
                typeNormally(editor, charTyped)
                return
            }

            // Navigation Mode ON: intercept only our movement keys.
            CodeStrafeState.updateEditorContext(editor)

            val handled = when (charTyped) {

                // ===== BASE LAYER =====
                'w' -> { moveLines(editor, -1); true }
                's' -> { moveLines(editor, +1); true }
                'a' -> { moveWord(editor, -1); true }
                'd' -> { moveWord(editor, +1); true }

                'q' -> { panLines(editor, -5); true }
                'e' -> { panLines(editor, +5); true }

                ' ' -> { moveLines(editor, 15); centerCaret(editor); true }

                // ===== SHIFT LAYER (macOS-friendly) =====
                'W' -> { moveLines(editor, -10); true }
                'S' -> { moveLines(editor, +10); true }
                'A' -> { moveChars(editor, -1); true }
                'D' -> { moveChars(editor, +1); true }

                'Q' -> { panLines(editor, -20); true }
                'E' -> { panLines(editor, +20); true }

                else -> false
            }

            if (handled) {
                CodeStrafeState.snapshotCaretPosition(editor)
                return
            }

            // In nav mode, for any other character, type normally (without delegating).
            typeNormally(editor, charTyped)
        }

        private fun typeNormally(editor: Editor, charTyped: Char) {
            // Safest “normal typing” without invoking TypedAction handler chain.
            EditorModificationUtil.insertStringAtCaret(
                editor,
                charTyped.toString(),
                /* toProcessOverwriteMode = */ true,
                /* toMoveCaret = */ true
            )
        }

        private fun moveLines(editor: Editor, delta: Int) {
            val caret = editor.caretModel.currentCaret
            val doc = editor.document
            val current = caret.logicalPosition
            val newLine = min(max(current.line + delta, 0), doc.lineCount - 1)
            caret.moveToLogicalPosition(
                com.intellij.openapi.editor.LogicalPosition(newLine, current.column)
            )
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.RELATIVE)
        }

        private fun moveChars(editor: Editor, delta: Int) {
            val caret = editor.caretModel.currentCaret
            val doc = editor.document
            val newOffset = min(max(caret.offset + delta, 0), doc.textLength)
            caret.moveToOffset(newOffset)
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
}