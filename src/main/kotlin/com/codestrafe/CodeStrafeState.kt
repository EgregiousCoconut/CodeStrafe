package com.codestrafe

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

object CodeStrafeState {

    private val navigationModeEnabled = AtomicBoolean(false)

    @Volatile
    private var currentEditor: Editor? = null

    @Volatile
    private var currentProject: Project? = null

    @Volatile
    private var lastCaretOffset: Int = 0

    @Volatile
    private var lastCaretLine: Int = 0

    @Volatile
    private var lastCaretColumn: Int = 0

    fun isNavigationModeEnabled(): Boolean {
        return navigationModeEnabled.get()
    }

    fun setNavigationModeEnabled(enabled: Boolean) {
        navigationModeEnabled.set(enabled)
        System.err.println("CODESTRAFE_STATE: navigationModeEnabled=$enabled")
    }

    fun toggleNavigationMode(): Boolean {
        val newValue = !navigationModeEnabled.get()
        setNavigationModeEnabled(newValue)
        return newValue
    }

    fun enableNavigationMode() {
        setNavigationModeEnabled(true)
    }

    fun disableNavigationMode() {
        setNavigationModeEnabled(false)
    }

    fun getCurrentEditor(): Editor? {
        return currentEditor
    }

    fun setCurrentEditor(editor: Editor?) {
        currentEditor = editor
    }

    fun getCurrentProject(): Project? {
        return currentProject
    }

    fun setCurrentProject(project: Project?) {
        currentProject = project
    }

    fun clearEditorIfMatches(editor: Editor?) {
        if (currentEditor == editor) {
            currentEditor = null
        }
    }

    fun snapshotCaretPosition(editor: Editor?) {
        if (editor == null) return

        val caret = editor.caretModel.currentCaret
        lastCaretOffset = caret.offset
        lastCaretLine = caret.logicalPosition.line
        lastCaretColumn = caret.logicalPosition.column
    }

    fun getLastCaretOffset(): Int {
        return lastCaretOffset
    }

    fun getLastCaretLine(): Int {
        return lastCaretLine
    }

    fun getLastCaretColumn(): Int {
        return lastCaretColumn
    }

    fun reset() {
        navigationModeEnabled.set(false)
        currentEditor = null
        currentProject = null
        lastCaretOffset = 0
        lastCaretLine = 0
        lastCaretColumn = 0

        System.err.println("CODESTRAFE_STATE: reset()")
    }
}