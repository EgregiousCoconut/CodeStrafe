package com.codestrafe

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.util.ui.UIUtil
import kotlin.math.max
import kotlin.math.min

class CodeStrafeMoveAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor =
            e.getData(CommonDataKeys.EDITOR)
                ?: getBestActiveEditor()
                ?: return

        val project = e.project ?: editor.project
        if (project != null) {
            CodeStrafeState.setCurrentProject(project)
        }
        CodeStrafeState.setCurrentEditor(editor)
        CodeStrafeState.snapshotCaretPosition(editor)

        when (e.actionManager.getId(this)) {
            "CodeStrafe.CmdUp" -> moveLines(editor, -1)
            "CodeStrafe.CmdDown" -> moveLines(editor, 1)
            "CodeStrafe.CmdLeft" -> moveWord(editor, -1)
            "CodeStrafe.CmdRight" -> moveWord(editor, 1)
            "CodeStrafe.CmdJump" -> jumpDownInternal(editor)
            "CodeStrafe.CmdPanUp" -> panLines(editor, -10)
            "CodeStrafe.CmdPanDown" -> panLines(editor, 10)
        }

        CodeStrafeState.snapshotCaretPosition(editor)
        CodeStrafeHighlightManager.updateForEditor(editor)
    }

    companion object {

        fun moveLeft() {
            withActiveEditor { editor ->
                moveWord(editor, -1)
            }
        }

        fun moveRight() {
            withActiveEditor { editor ->
                moveWord(editor, 1)
            }
        }

        fun moveUp() {
            withActiveEditor { editor ->
                moveLines(editor, -1)
            }
        }

        fun moveDown() {
            withActiveEditor { editor ->
                moveLines(editor, 1)
            }
        }

        fun jump() {
            withActiveEditor { editor ->
                jumpDownInternal(editor)
            }
        }

        fun jumpBack() {
            withActiveEditor { editor ->
                jumpUpInternal(editor)
            }
        }

        fun lineStart() {
            withActiveEditor { editor ->
                lineStartInternal(editor)
            }
        }

        fun lineEnd() {
            withActiveEditor { editor ->
                lineEndInternal(editor)
            }
        }

        fun psiPrevious() {
            withActiveEditor { editor ->
                psiPreviousInternal(editor)
            }
        }

        fun psiNext() {
            withActiveEditor { editor ->
                psiNextInternal(editor)
            }
        }

        fun panUp() {
            withActiveEditor { editor ->
                panLines(editor, -10)
            }
        }

        fun panDown() {
            withActiveEditor { editor ->
                panLines(editor, 10)
            }
        }

        private fun withActiveEditor(block: (Editor) -> Unit) {
            UIUtil.invokeLaterIfNeeded {
                val editor = getBestActiveEditor()
                if (editor == null) {
                    System.err.println("CODESTRAFE_MOVE: no active editor found")
                    return@invokeLaterIfNeeded
                }

                val project = editor.project
                if (project != null) {
                    CodeStrafeState.setCurrentProject(project)
                }
                CodeStrafeState.setCurrentEditor(editor)
                CodeStrafeState.snapshotCaretPosition(editor)

                block(editor)

                CodeStrafeState.snapshotCaretPosition(editor)
                CodeStrafeHighlightManager.updateForEditor(editor)
            }
        }

        private fun getBestActiveEditor(): Editor? {
            val stateProject = CodeStrafeState.getCurrentProject()
            if (stateProject != null) {
                val selected = FileEditorManager.getInstance(stateProject).selectedTextEditor
                if (selected != null) return selected
            }

            for (project in ProjectManager.getInstance().openProjects) {
                val selected = FileEditorManager.getInstance(project).selectedTextEditor
                if (selected != null) return selected
            }

            return CodeStrafeState.getCurrentEditor()
        }

        private fun moveLines(editor: Editor, delta: Int) {
            val caret = editor.caretModel.currentCaret
            val current = caret.logicalPosition
            val lineCount = editor.document.lineCount
            val newLine = min(max(current.line + delta, 0), max(lineCount - 1, 0))

            caret.moveToLogicalPosition(LogicalPosition(newLine, current.column))
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }

        private fun moveWord(editor: Editor, direction: Int) {
            val caret = editor.caretModel.currentCaret
            val text = editor.document.charsSequence
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

            caret.moveToOffset(min(max(i, 0), editor.document.textLength))
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }

        private fun jumpDownInternal(editor: Editor) {
            val caret = editor.caretModel.currentCaret
            val current = caret.logicalPosition
            val lineCount = editor.document.lineCount
            val newLine = min(current.line + 15, max(lineCount - 1, 0))

            caret.moveToLogicalPosition(LogicalPosition(newLine, current.column))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        private fun jumpUpInternal(editor: Editor) {
            val caret = editor.caretModel.currentCaret
            val current = caret.logicalPosition
            val newLine = max(current.line - 15, 0)

            caret.moveToLogicalPosition(LogicalPosition(newLine, current.column))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        private fun lineStartInternal(editor: Editor) {
            val caret = editor.caretModel.currentCaret
            val current = caret.logicalPosition

            caret.moveToLogicalPosition(LogicalPosition(current.line, 0))
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }

        private fun lineEndInternal(editor: Editor) {
            val caret = editor.caretModel.currentCaret
            val currentLine = caret.logicalPosition.line
            val document = editor.document
            val lineEndOffset = document.getLineEndOffset(currentLine)

            caret.moveToOffset(lineEndOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }

        private fun psiPreviousInternal(editor: Editor) {
            val psiFile = PsiDocumentManager.getInstance(editor.project ?: return).getPsiFile(editor.document) ?: return
            val offset = normalizedOffset(editor)
            val current = psiFile.findElementAt(offset) ?: return
            val target = previousMeaningfulLeaf(current) ?: return

            editor.caretModel.moveToOffset(target.textRange.startOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        private fun psiNextInternal(editor: Editor) {
            val psiFile = PsiDocumentManager.getInstance(editor.project ?: return).getPsiFile(editor.document) ?: return
            val offset = normalizedOffset(editor)
            val current = psiFile.findElementAt(offset) ?: return
            val target = nextMeaningfulLeaf(current) ?: return

            editor.caretModel.moveToOffset(target.textRange.startOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        private fun normalizedOffset(editor: Editor): Int {
            val textLength = editor.document.textLength
            if (textLength <= 0) return 0
            return min(max(editor.caretModel.offset, 0), textLength - 1)
        }

        private fun nextMeaningfulLeaf(start: PsiElement): PsiElement? {
            var current: PsiElement? = start
            while (current != null) {
                var sibling = current.nextSibling
                while (sibling != null) {
                    val leaf = firstMeaningfulLeaf(sibling)
                    if (leaf != null) return leaf
                    sibling = sibling.nextSibling
                }
                current = current.parent
            }
            return null
        }

        private fun previousMeaningfulLeaf(start: PsiElement): PsiElement? {
            var current: PsiElement? = start
            while (current != null) {
                var sibling = current.prevSibling
                while (sibling != null) {
                    val leaf = lastMeaningfulLeaf(sibling)
                    if (leaf != null) return leaf
                    sibling = sibling.prevSibling
                }
                current = current.parent
            }
            return null
        }

        private fun firstMeaningfulLeaf(element: PsiElement): PsiElement? {
            if (isMeaningfulLeaf(element)) return element

            var child = element.firstChild
            while (child != null) {
                val result = firstMeaningfulLeaf(child)
                if (result != null) return result
                child = child.nextSibling
            }

            return null
        }

        private fun lastMeaningfulLeaf(element: PsiElement): PsiElement? {
            if (isMeaningfulLeaf(element)) return element

            var child = element.lastChild
            while (child != null) {
                val result = lastMeaningfulLeaf(child)
                if (result != null) return result
                child = child.prevSibling
            }

            return null
        }

        private fun isMeaningfulLeaf(element: PsiElement): Boolean {
            if (element is PsiWhiteSpace) return false
            if (element.firstChild != null) return false
            val range = element.textRange ?: return false
            if (range.length <= 0) return false
            return element.text.isNotBlank()
        }

        private fun panLines(editor: Editor, deltaLines: Int) {
            val currentOffset = editor.scrollingModel.verticalScrollOffset
            editor.scrollingModel.scrollVertically(currentOffset + deltaLines * editor.lineHeight)
        }
    }
}