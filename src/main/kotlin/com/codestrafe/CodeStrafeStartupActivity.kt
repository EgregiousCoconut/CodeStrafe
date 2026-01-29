package com.codestrafe

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import kotlin.math.max
import kotlin.math.min

/**
 * CodeStrafeStartupActivity (V1 - TypedAction based, compatibility)
 *
 * Intercepts typed characters and converts them to navigation when Navigation Mode is enabled.
 *
 * NOTE: This version does NOT use CommonDataKeys.KEY_EVENT (not available in some platform versions),
 * so Shift/Alt modifiers are not detected here.
 *
 * When Navigation Mode is ON:
 * - WASD/QE/Space will NOT type into the document
 * - Instead they move the caret / scroll
 */
class CodeStrafeStartupActivity : StartupActivity {

    override fun runActivity(project: Project) {
        val typedAction = TypedAction.getInstance()
        val original = typedAction.rawHandler

        val handler: TypedActionHandler = CodeStrafeTypedHandler(original)
        typedAction.setupHandler(handler)

        // Restore original handler when project closes
        Disposer.register(project, Disposable { typedAction.setupHandler(original) })
    }
}

private class CodeStrafeTypedHandler(
    private val delegate: TypedActionHandler
) : TypedActionHandler {

    override fun execute(
        editor: Editor,
        charTyped: Char,
        dataContext: com.intellij.openapi.actionSystem.DataContext
    ) {
        // If Navigation Mode is off, behave normally.
        if (!CodeStrafeState.isNavigationModeEnabled()) {
            delegate.execute(editor, charTyped, dataContext)
            return
        }

        CodeStrafeState.updateEditorContext(editor)

        val handled = when (charTyped.lowercaseChar()) {
            'w' -> { moveLines(editor, -1); true }
            's' -> { moveLines(editor, +1); true }
            'a' -> { moveWord(editor, -1); true }
            'd' -> { moveWord(editor, +1); true }
            'q' -> { panLines(editor, -5); true }
            'e' -> { panLines(editor, +5); true }
            ' ' -> { moveLines(editor, 15); centerCaret(editor); true }
            else -> false
        }

        if (handled) {
            CodeStrafeState.snapshotCaretPosition(editor)
            return // do not type into document
        }

        // For any other character, type normally even in Navigation Mode.
        delegate.execute(editor, charTyped, dataContext)
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