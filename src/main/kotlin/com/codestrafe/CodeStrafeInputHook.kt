package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.EditorFactory
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * CodeStrafeInputHook
 *
 * Installs:
 * 1) AWT KeyEventDispatcher (PRIMARY) so W/A/S/D movement works even when Shift is held.
 *    This prevents uppercase letters from being typed in Navigation Mode.
 *
 * 2) TypedActionHandler (FALLBACK) to keep normal typing stable and to catch cases where
 *    KeyEventDispatcher doesn't fire for some reason.
 *
 * IMPORTANT:
 * - The dispatcher only handles W/A/S/D. Actions that require DataContext (like next error)
 *   are kept in the TypedActionHandler where DataContext is available.
 */
object CodeStrafeInputHook {

    private val log = Logger.getInstance(CodeStrafeInputHook::class.java)
    private val installed = AtomicBoolean(false)

    @Volatile private var lastMoveMs: Long = 0
    private const val MOVE_DEBOUNCE_MS = 10L

    private val dispatcher: KeyEventDispatcher = KeyEventDispatcher { e: KeyEvent ->
        try {
            // Only intercept in navigation mode
            if (!CodeStrafeState.isNavigationModeEnabled()) return@KeyEventDispatcher false

            // Only handle key presses
            if (e.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false

            // Only handle when an editor has focus (avoid hijacking menus, dialogs, etc.)
            val editor = findFocusedEditor() ?: return@KeyEventDispatcher false

            // Debounce for a smoother feel on key repeat
            val now = System.currentTimeMillis()
            if (now - lastMoveMs < MOVE_DEBOUNCE_MS) {
                if (isMovementKey(e.keyCode)) return@KeyEventDispatcher true
                return@KeyEventDispatcher false
            }
            lastMoveMs = now

            val shift = e.isShiftDown

            val handled = when (e.keyCode) {
                KeyEvent.VK_W -> { moveLines(editor, if (shift) -5 else -1); true }
                KeyEvent.VK_S -> { moveLines(editor, if (shift) +5 else +1); true }
                KeyEvent.VK_A -> { moveWord(editor, direction = -1, steps = if (shift) 5 else 1); true }
                KeyEvent.VK_D -> { moveWord(editor, direction = +1, steps = if (shift) 5 else 1); true }
                else -> false
            }

            if (handled) {
                CodeStrafeState.updateEditorContext(editor)
                CodeStrafeState.snapshotCaretPosition(editor)
                CodeStrafeHighlightManager.updateForEditor(editor)

                // Consume event so it never types the letter
                return@KeyEventDispatcher true
            }

            false
        } catch (t: Throwable) {
            log.warn("CODESTRAFE_INPUT: dispatcher error", t)
            false
        }
    }

    /**
     * Installs both dispatcher + typed handler once.
     */
    fun ensureInstalled() {
        runOnEdt {
            if (installed.get()) return@runOnEdt

            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
            TypedAction.getInstance().setupHandler(CodeStrafeTypedHandler())

            installed.set(true)
            log.warn("CODESTRAFE_INPUT: installed (AWT KeyEventDispatcher + TypedActionHandler)")
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeAndWait { block() }
    }

    private fun isMovementKey(code: Int): Boolean {
        return code == KeyEvent.VK_W || code == KeyEvent.VK_A || code == KeyEvent.VK_S || code == KeyEvent.VK_D
    }

    /**
     * Attempts to find the editor that currently has focus.
     *
     * We try:
     * 1) CodeStrafeState.getCurrentEditor() if it's focused
     * 2) Any open editor whose component has focus
     */
    private fun findFocusedEditor(): Editor? {
        val current = CodeStrafeState.getCurrentEditor()
        if (current is EditorEx && current.contentComponent.isFocusOwner) {
            return current
        }

        val all = EditorFactory.getInstance().allEditors
        for (ed in all) {
            val ex = ed as? EditorEx ?: continue
            if (ex.contentComponent.isFocusOwner) return ed
        }
        return null
    }

    /**
     * Fallback typed handler.
     *
     * In navigation mode:
     * - Blocks W/A/S/D typing (fallback).
     * - Keeps N/P for next/prev error here because DataContext is available.
     * - Allows other keys to type (your current design).
     */
    private class CodeStrafeTypedHandler : TypedActionHandler {

        override fun execute(
            editor: Editor,
            charTyped: Char,
            dataContext: com.intellij.openapi.actionSystem.DataContext
        ) {
            if (!CodeStrafeState.isNavigationModeEnabled()) {
                typeNormally(editor, charTyped)
                return
            }

            val handled = when (charTyped) {
                'w', 'W' -> { moveLines(editor, if (charTyped == 'W') -5 else -1); true }
                's', 'S' -> { moveLines(editor, if (charTyped == 'S') +5 else +1); true }
                'a', 'A' -> { moveWord(editor, direction = -1, steps = if (charTyped == 'A') 5 else 1); true }
                'd', 'D' -> { moveWord(editor, direction = +1, steps = if (charTyped == 'D') 5 else 1); true }

                // DataContext-required actions stay here
                'n', 'N' -> { CodeStrafeIdeActions.gotoNextError(dataContext); true }
                'p', 'P' -> { CodeStrafeIdeActions.gotoPreviousError(dataContext); true }

                else -> false
            }

            if (handled) {
                CodeStrafeState.updateEditorContext(editor)
                CodeStrafeState.snapshotCaretPosition(editor)
                CodeStrafeHighlightManager.updateForEditor(editor)
                return
            }

            typeNormally(editor, charTyped)
        }

        private fun typeNormally(editor: Editor, charTyped: Char) {
            EditorModificationUtil.insertStringAtCaret(editor, charTyped.toString(), true, true)
        }
    }

    /**
     * Movement helpers
     */
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