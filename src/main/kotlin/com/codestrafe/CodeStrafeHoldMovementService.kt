package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.util.Alarm
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class CodeStrafeHoldMovementService {

    private val log = Logger.getInstance(CodeStrafeHoldMovementService::class.java)

    private val initialDelayMs = 200
    private val repeatIntervalMs = 35

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
    private val activeKeys = ConcurrentHashMap<Int, Boolean>()

    init {
        installDispatcher()
        log.info("CodeStrafeHoldMovementService started")
    }

    private fun installDispatcher() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(
            KeyEventDispatcher { e ->
                if (!CodeStrafeState.isNavigationModeEnabled()) return@KeyEventDispatcher false

                val editor = getActiveEditor() ?: return@KeyEventDispatcher false
                val action = mapKeyCode(e.keyCode) ?: return@KeyEventDispatcher false

                // Shift = faster movement
                val speed = if (e.isShiftDown) 5 else 1

                when (e.id) {
                    KeyEvent.KEY_PRESSED -> {
                        if (activeKeys.putIfAbsent(e.keyCode, true) == null) {
                            perform(editor, action, speed)
                            scheduleRepeat(editor, e.keyCode, action, speed)
                        }
                        true // consume so it doesn’t type
                    }

                    KeyEvent.KEY_RELEASED -> {
                        activeKeys.remove(e.keyCode)
                        true
                    }

                    else -> false
                }
            }
        )
    }

    private fun scheduleRepeat(editor: Editor, keyCode: Int, action: Action, speed: Int) {
        alarm.addRequest({
            if (!activeKeys.containsKey(keyCode)) return@addRequest
            perform(editor, action, speed)
            scheduleFastRepeat(editor, keyCode, action, speed)
        }, initialDelayMs)
    }

    private fun scheduleFastRepeat(editor: Editor, keyCode: Int, action: Action, speed: Int) {
        alarm.addRequest({
            if (!activeKeys.containsKey(keyCode)) return@addRequest
            perform(editor, action, speed)
            scheduleFastRepeat(editor, keyCode, action, speed)
        }, repeatIntervalMs)
    }

    private fun perform(editor: Editor, action: Action, speed: Int) {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { perform(editor, action, speed) }
            return
        }

        when (action) {
            Action.UP -> moveLines(editor, -speed)
            Action.DOWN -> moveLines(editor, +speed)
            Action.LEFT -> moveWord(editor, -1, speed)
            Action.RIGHT -> moveWord(editor, +1, speed)
        }

        CodeStrafeState.snapshotCaretPosition(editor)
        CodeStrafeHighlightManager.updateForEditor(editor)
    }

    private fun mapKeyCode(keyCode: Int): Action? {
        return when (keyCode) {
            KeyEvent.VK_W -> Action.UP
            KeyEvent.VK_S -> Action.DOWN
            KeyEvent.VK_A -> Action.LEFT
            KeyEvent.VK_D -> Action.RIGHT
            else -> null
        }
    }

    private fun getActiveEditor(): Editor? {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
        return EditorFactory.getInstance().allEditors.firstOrNull { ed ->
            ed.contentComponent === focusOwner || ed.component === focusOwner
        }
    }

    private fun moveLines(editor: Editor, delta: Int) {
        val caret = editor.caretModel.currentCaret
        val doc = editor.document
        val current = caret.logicalPosition
        val newLine = min(max(current.line + delta, 0), doc.lineCount - 1)
        caret.moveToLogicalPosition(LogicalPosition(newLine, current.column))
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    }

    private fun moveWord(editor: Editor, direction: Int, steps: Int) {
        repeat(max(1, steps)) { moveOneWord(editor, direction) }
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

    private enum class Action { UP, DOWN, LEFT, RIGHT }
}