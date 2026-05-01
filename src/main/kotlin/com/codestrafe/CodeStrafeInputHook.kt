package com.codestrafe

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object CodeStrafeInputHook {

    private val log = Logger.getInstance(CodeStrafeInputHook::class.java)

    private val installed = AtomicBoolean(false)
    private val dispatcherInstalled = AtomicBoolean(false)
    private val tabOverrideInstalled = AtomicBoolean(false)

    private val shiftDown = AtomicBoolean(false)

    @Volatile
    private var lastVoiceToggleNanos: Long = 0L

    @Volatile
    private var lastCapsToggleNanos: Long = 0L

    fun ensureInstalled() {
        runOnEdt {
            if (installed.get()) return@runOnEdt

            installTabAndIndentOverrides()
            installDispatcherOnlyNavigation()

            installed.set(true)
            log.warn("CODESTRAFE_INPUT: input hook installed")
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            block()
        } else {
            app.invokeAndWait { block() }
        }
    }

    private fun installTabAndIndentOverrides() {
        if (!tabOverrideInstalled.compareAndSet(false, true)) return

        val eam = EditorActionManager.getInstance()

        val overrideIds = listOf(
            IdeActions.ACTION_EDITOR_TAB,
            "EditorBackTab",
            "EditorIndentSelection",
            "EditorUnindentSelection",
            "EditorIndentLineOrSelection",
            "EditorUnindentLineOrSelection"
        )

        for (id in overrideIds) {
            try {
                val original = eam.getActionHandler(id)
                eam.setActionHandler(id, CodeStrafeTabLikeActionHandler(original, id))
                log.warn("CODESTRAFE_INPUT: installed Tab override for action: $id")
            } catch (_: Throwable) {
            }
        }
    }

    private class CodeStrafeTabLikeActionHandler(
        private val delegate: EditorActionHandler?,
        private val actionId: String
    ) : EditorActionHandler(true) {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            if (!CodeStrafeState.isNavigationModeEnabled()) {
                delegate?.execute(editor, caret, dataContext)
                return
            }

            val backward =
                actionId.contains("BackTab", ignoreCase = true) ||
                        actionId.contains("Unindent", ignoreCase = true)

            CodeStrafePsiTargeting.cyclePsiTarget(editor, forward = !backward)
            CodeStrafeState.snapshotCaretPosition(editor)
            CodeStrafeHighlightManager.updateForEditor(editor)
        }
    }

    private fun installDispatcherOnlyNavigation() {
        if (!dispatcherInstalled.compareAndSet(false, true)) return

        val dispatcher = KeyEventDispatcher { e ->
            try {
                updateShiftState(e)

                if (e.keyCode == KeyEvent.VK_CAPS_LOCK) {
                    if (e.id == KeyEvent.KEY_PRESSED) {
                        toggleNavigationModeFromCapsLock()
                    }

                    // Do not consume Caps Lock. Let macOS keep normal LED / OS state.
                    return@KeyEventDispatcher false
                }

                if (!CodeStrafeState.isNavigationModeEnabled()) {
                    return@KeyEventDispatcher false
                }

                if (e.keyCode == KeyEvent.VK_SHIFT) {
                    return@KeyEventDispatcher false
                }

                if (e.keyCode == KeyEvent.VK_TAB || e.keyChar == '\t') {
                    return@KeyEventDispatcher false
                }

                val editor = findCurrentEditor()

                when (e.keyCode) {
                    KeyEvent.VK_W -> {
                        if (e.id == KeyEvent.KEY_PRESSED && editor != null) {
                            moveLines(editor, if (shiftDown.get()) -5 else -1)
                        }
                        return@KeyEventDispatcher true
                    }

                    KeyEvent.VK_S -> {
                        if (e.id == KeyEvent.KEY_PRESSED && editor != null) {
                            moveLines(editor, if (shiftDown.get()) 5 else 1)
                        }
                        return@KeyEventDispatcher true
                    }

                    KeyEvent.VK_A -> {
                        if (e.id == KeyEvent.KEY_PRESSED && editor != null) {
                            moveToken(editor, direction = -1, steps = if (shiftDown.get()) 5 else 1)
                        }
                        return@KeyEventDispatcher true
                    }

                    KeyEvent.VK_D -> {
                        if (e.id == KeyEvent.KEY_PRESSED && editor != null) {
                            moveToken(editor, direction = 1, steps = if (shiftDown.get()) 5 else 1)
                        }
                        return@KeyEventDispatcher true
                    }

                    KeyEvent.VK_V -> {
                        if (e.id == KeyEvent.KEY_PRESSED) {
                            toggleVoiceFromDispatcher()
                        }
                        return@KeyEventDispatcher true
                    }

                    KeyEvent.VK_N -> {
                        if (e.id == KeyEvent.KEY_PRESSED) {
                            log.warn("CODESTRAFE_INPUT: N pressed in nav mode")
                        }
                        return@KeyEventDispatcher true
                    }

                    KeyEvent.VK_P -> {
                        if (e.id == KeyEvent.KEY_PRESSED) {
                            log.warn("CODESTRAFE_INPUT: P pressed in nav mode")
                        }
                        return@KeyEventDispatcher true
                    }
                }

                when (e.keyChar) {
                    'w', 'W',
                    's', 'S',
                    'a', 'A',
                    'd', 'D',
                    'v', 'V',
                    'n', 'N',
                    'p', 'P' -> {
                        return@KeyEventDispatcher true
                    }
                }

                true
            } catch (t: Throwable) {
                log.warn("CODESTRAFE_INPUT: dispatcher error", t)
                false
            }
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        log.warn("CODESTRAFE_INPUT: dispatcher-only AWT gate installed")
    }

    private fun updateShiftState(e: KeyEvent) {
        if (e.keyCode == KeyEvent.VK_SHIFT) {
            when (e.id) {
                KeyEvent.KEY_PRESSED -> shiftDown.set(true)
                KeyEvent.KEY_RELEASED -> shiftDown.set(false)
            }
        } else {
            shiftDown.set(e.isShiftDown)
        }
    }

    private fun toggleNavigationModeFromCapsLock() {
        val now = System.nanoTime()
        if (now - lastCapsToggleNanos < 350_000_000L) {
            return
        }

        lastCapsToggleNanos = now

        ApplicationManager.getApplication().invokeLater {
            try {
                CodeStrafeState.toggleNavigationMode()

                val enabled = CodeStrafeState.isNavigationModeEnabled()
                log.warn("CODESTRAFE_INPUT: Caps Lock toggled Navigation Mode. enabled=$enabled")

                val editor = findCurrentEditor()
                if (editor != null) {
                    if (enabled) {
                        CodeStrafeState.snapshotCaretPosition(editor)
                        CodeStrafeHighlightManager.updateForEditor(editor)
                    } else {
                        CodeStrafeHighlightManager.removeForEditor(editor)
                    }
                }
            } catch (t: Throwable) {
                log.warn("CODESTRAFE_INPUT: failed to toggle Navigation Mode from Caps Lock", t)
            }
        }
    }

    private fun toggleVoiceFromDispatcher() {
        val now = System.nanoTime()
        if (now - lastVoiceToggleNanos < 250_000_000L) {
            return
        }

        lastVoiceToggleNanos = now

        ApplicationManager.getApplication().invokeLater {
            val editor = findCurrentEditor()
            val project = editor?.project ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return@invokeLater

            if (CodeStrafeVoiceService.isListening()) {
                CodeStrafeVoiceService.stopListening(project)
            } else {
                CodeStrafeVoiceService.startListening(project)
            }
        }
    }

    private fun findCurrentEditor(): Editor? {
        for (project in ProjectManager.getInstance().openProjects) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null && !editor.isDisposed) {
                return editor
            }
        }

        return null
    }

    private fun afterMove(editor: Editor) {
        CodeStrafeState.snapshotCaretPosition(editor)
        CodeStrafeHighlightManager.updateForEditor(editor)
    }

    private fun moveLines(editor: Editor, deltaLines: Int) {
        val caret = editor.caretModel.currentCaret
        val doc = editor.document
        val current = caret.logicalPosition
        val newLine = (current.line + deltaLines).coerceIn(0, max(0, doc.lineCount - 1))

        caret.moveToLogicalPosition(LogicalPosition(newLine, current.column))
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)

        afterMove(editor)
    }

    private fun moveToken(editor: Editor, direction: Int, steps: Int) {
        repeat(max(1, steps)) {
            moveOneToken(editor, direction)
        }

        afterMove(editor)
    }

    private fun moveOneToken(editor: Editor, direction: Int) {
        val project = editor.project
        val doc = editor.document
        val len = doc.textLength
        val caret = editor.caretModel.currentCaret
        val offset0 = caret.offset.coerceIn(0, len)

        if (project == null || len == 0) {
            moveCharFallback(editor, direction)
            return
        }

        val pdm = PsiDocumentManager.getInstance(project)
        pdm.commitDocument(doc)

        val psiFile = pdm.getPsiFile(doc) ?: run {
            moveCharFallback(editor, direction)
            return
        }

        val probe =
            if (direction < 0) {
                (offset0 - 1).coerceAtLeast(0)
            } else {
                offset0.coerceIn(0, max(0, len - 1))
            }

        val foundAtProbe = psiFile.findElementAt(probe) ?: run {
            moveCharFallback(editor, direction)
            return
        }

        val deep = PsiTreeUtil.getDeepestFirst(foundAtProbe) ?: foundAtProbe

        val leaf = nearestNonSkippableLeaf(deep, direction) ?: run {
            moveCharFallback(editor, direction)
            return
        }

        val tr = leaf.textRange ?: run {
            moveCharFallback(editor, direction)
            return
        }

        if (tr.length <= 0) {
            moveCharFallback(editor, direction)
            return
        }

        val start = tr.startOffset.coerceIn(0, len)
        val end = tr.endOffset.coerceIn(0, len)

        if (direction > 0) {
            if (offset0 < end) {
                moveToOffset(editor, end)
                return
            }

            val next = nextNonSkippableLeaf(leaf) ?: run {
                moveCharFallback(editor, direction)
                return
            }

            val ntr = next.textRange ?: run {
                moveCharFallback(editor, direction)
                return
            }

            moveToOffset(editor, ntr.endOffset.coerceIn(0, len))
        } else {
            if (offset0 > start) {
                moveToOffset(editor, start)
                return
            }

            val prev = prevNonSkippableLeaf(leaf) ?: run {
                moveCharFallback(editor, direction)
                return
            }

            val ptr = prev.textRange ?: run {
                moveCharFallback(editor, direction)
                return
            }

            moveToOffset(editor, ptr.startOffset.coerceIn(0, len))
        }
    }

    private fun moveToOffset(editor: Editor, offset: Int) {
        editor.caretModel.currentCaret.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    }

    private fun moveCharFallback(editor: Editor, direction: Int) {
        val caret = editor.caretModel.currentCaret
        val doc = editor.document
        val len = doc.textLength
        val off = caret.offset.coerceIn(0, len)
        val next = (off + direction).coerceIn(0, len)

        caret.moveToOffset(next)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    }

    private fun isSkippableLeaf(e: PsiElement): Boolean {
        if (e is PsiWhiteSpace) return true

        val tr = e.textRange ?: return true
        if (tr.length <= 0) return true

        val txt = try {
            e.text
        } catch (_: Throwable) {
            null
        } ?: return true

        if (txt.isBlank()) return true

        val n = e.javaClass.name
        if (n.contains("Comment", ignoreCase = true)) return true

        return false
    }

    private fun nearestNonSkippableLeaf(from: PsiElement, direction: Int): PsiElement? {
        var cur: PsiElement? = from
        var guard = 0

        while (cur != null && guard < 256) {
            if (!isSkippableLeaf(cur)) return cur

            cur =
                if (direction > 0) {
                    PsiTreeUtil.nextLeaf(cur, true)
                } else {
                    PsiTreeUtil.prevLeaf(cur, true)
                }

            guard++
        }

        return null
    }

    private fun nextNonSkippableLeaf(from: PsiElement): PsiElement? {
        var cur: PsiElement? = PsiTreeUtil.nextLeaf(from, true)
        var guard = 0

        while (cur != null && guard < 256) {
            if (!isSkippableLeaf(cur)) return cur

            cur = PsiTreeUtil.nextLeaf(cur, true)
            guard++
        }

        return null
    }

    private fun prevNonSkippableLeaf(from: PsiElement): PsiElement? {
        var cur: PsiElement? = PsiTreeUtil.prevLeaf(from, true)
        var guard = 0

        while (cur != null && guard < 256) {
            if (!isSkippableLeaf(cur)) return cur

            cur = PsiTreeUtil.prevLeaf(cur, true)
            guard++
        }

        return null
    }
}