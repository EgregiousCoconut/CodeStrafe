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
 * PRIMARY: AWT KeyEventDispatcher handles WASD movement (and blocks typing).
 * SECONDARY: TypedActionHandler only handles "normal typing" and any actions that require DataContext (like N/P).
 *
 * Key rule:
 * - WASD movement must NOT be implemented in both places, or you'll get double movement.
 *
 * macOS note:
 * - When Caps Lock is ON, AWT may report isShiftDown=true for letter keys.
 * - We track physical Shift state ourselves via VK_SHIFT press/release.
 */
object CodeStrafeInputHook {

    private val log = Logger.getInstance(CodeStrafeInputHook::class.java)
    private val installed = AtomicBoolean(false)

    // Physical Shift state (not affected by Caps Lock)
    private val shiftPhysicallyDown = AtomicBoolean(false)

    @Volatile private var lastMoveMs: Long = 0
    private const val MOVE_DEBOUNCE_MS = 10L

    private val dispatcher: KeyEventDispatcher = KeyEventDispatcher { e: KeyEvent ->
        try {
            // Track physical Shift state always
            if (e.keyCode == KeyEvent.VK_SHIFT) {
                when (e.id) {
                    KeyEvent.KEY_PRESSED -> shiftPhysicallyDown.set(true)
                    KeyEvent.KEY_RELEASED -> shiftPhysicallyDown.set(false)
                }
                return@KeyEventDispatcher false
            }

            // Only intercept movement in navigation mode
            if (!CodeStrafeState.isNavigationModeEnabled()) return@KeyEventDispatcher false
            if (e.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false

            val editor = findFocusedEditor() ?: return@KeyEventDispatcher false

            // Debounce for smoother repeat
            val now = System.currentTimeMillis()
            if (now - lastMoveMs < MOVE_DEBOUNCE_MS) {
                if (isMovementKey(e.keyCode)) return@KeyEventDispatcher true
                return@KeyEventDispatcher false
            }
            lastMoveMs = now

            val fast = shiftPhysicallyDown.get()

            val handled = when (e.keyCode) {
                KeyEvent.VK_W -> { moveLines(editor, if (fast) -5 else -1); true }
                KeyEvent.VK_S -> { moveLines(editor, if (fast) +5 else +1); true }
                KeyEvent.VK_A -> { moveWord(editor, direction = -1, steps = if (fast) 5 else 1); true }
                KeyEvent.VK_D -> { moveWord(editor, direction = +1, steps = if (fast) 5 else 1); true }
                else -> false
            }

            if (handled) {
                CodeStrafeState.updateEditorContext(editor)
                CodeStrafeState.snapshotCaretPosition(editor)
                CodeStrafeHighlightManager.updateForEditor(editor)

                // Consume so it does not type and does not trigger typed-handler movement
                return@KeyEventDispatcher true
            }

            false
        } catch (t: Throwable) {
            log.warn("CODESTRAFE_INPUT: dispatcher error", t)
            false
        }
    }

    /**
     * Installs the dispatcher + typed handler once.
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

    private fun findFocusedEditor(): Editor? {
        val current = CodeStrafeState.getCurrentEditor()
        if (current is EditorEx && current.contentComponent.isFocusOwner) return current

        for (ed in EditorFactory.getInstance().allEditors) {
            val ex = ed as? EditorEx ?: continue
            if (ex.contentComponent.isFocusOwner) return ed
        }
        return null
    }

    /**
     * Typed fallback:
     * - Does NOT handle WASD movement (to prevent double movement).
     * - Keeps DataContext-required commands (N/P) here if you want them.
     * - Optionally allows typing in nav mode for all other keys.
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

            // Prevent WASD from doing anything here (movement is handled in dispatcher).
            if (charTyped == 'w' || charTyped == 'W' ||
                charTyped == 'a' || charTyped == 'A' ||
                charTyped == 's' || charTyped == 'S' ||
                charTyped == 'd' || charTyped == 'D'
            ) {
                // Do nothing: dispatcher already moved and consumed the keypress.
                return
            }

            val handled = when (charTyped) {
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

            // Your current design: allow typing other keys in nav mode
            typeNormally(editor, charTyped)
        }

        private fun typeNormally(editor: Editor, charTyped: Char) {
            EditorModificationUtil.insertStringAtCaret(editor, charTyped.toString(), true, true)
        }
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