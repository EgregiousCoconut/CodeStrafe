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
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * macOS-first strict gating + ACTION-level Tab override.
 *
 * IMPORTANT FIX:
 * - Do NOT use uppercase charTyped to infer Shift (Caps Lock makes letters uppercase).
 * - Track actual Shift state via VK_SHIFT press/release in the AWT dispatcher.
 *
 * A/D move by PSI tokens (leafs), boundary-aware, skipping whitespace/comments.
 */
object CodeStrafeInputHook {

    private val log = Logger.getInstance(CodeStrafeInputHook::class.java)

    private val installed = AtomicBoolean(false)
    private val dispatcherInstalled = AtomicBoolean(false)
    private val tabOverrideInstalled = AtomicBoolean(false)

    private var originalTypedHandler: TypedActionHandler? = null

    // Real modifier state (not affected by Caps Lock)
    private val shiftDown = AtomicBoolean(false)

    fun ensureInstalled() {
        runOnEdt {
            if (installed.get()) return@runOnEdt

            installTypedHandler()
            installTabAndIndentOverrides()
            installStrictAllowlistGate()

            installed.set(true)
            log.info("CodeStrafeInputHook installed")
        }
    }

    private fun runOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) block() else app.invokeAndWait { block() }
    }

    private fun installTypedHandler() {
        originalTypedHandler = TypedAction.getInstance().rawHandler
        @Suppress("DEPRECATION")
        TypedAction.getInstance().setupHandler(CodeStrafeTypedHandler())
    }

    /**
     * Override actions that commonly result in Tab insertion/indentation.
     * Guarantees: no tab character is inserted while Navigation Mode is on.
     */
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
                log.info("Installed CodeStrafe override for action: $id")
            } catch (_: Throwable) {
                // ignore missing IDs
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

    /**
     * Strict allowlist while in Navigation Mode.
     * We allow Tab key to reach action system (we intercept at action level).
     *
     * ALSO: track Shift key state here.
     */
    private fun installStrictAllowlistGate() {
        if (!dispatcherInstalled.compareAndSet(false, true)) return

        val dispatcher = KeyEventDispatcher { e ->
            try {
                // Track Shift state regardless of mode (helps avoid “stuck shift”)
                if (e.keyCode == KeyEvent.VK_SHIFT) {
                    when (e.id) {
                        KeyEvent.KEY_PRESSED -> shiftDown.set(true)
                        KeyEvent.KEY_RELEASED -> shiftDown.set(false)
                    }
                    return@KeyEventDispatcher false
                } else {
                    // Keep shiftDown honest even if we miss an event
                    shiftDown.set(e.isShiftDown)
                }

                if (!CodeStrafeState.isNavigationModeEnabled()) return@KeyEventDispatcher false

                if (e.keyCode == KeyEvent.VK_CAPS_LOCK) return@KeyEventDispatcher false
                if (e.keyCode == KeyEvent.VK_TAB || e.keyChar == '\t') return@KeyEventDispatcher false

                if (isAllowedInNavigationMode(e)) return@KeyEventDispatcher false
                true
            } catch (t: Throwable) {
                log.warn("CodeStrafeInputHook dispatcher error", t)
                false
            }
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        log.info("Installed CodeStrafe strict allowlist AWT gate (Tab handled via action overrides)")
    }

    private fun isAllowedInNavigationMode(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.VK_SHIFT) return true

        return when (e.keyCode) {
            KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D,
            KeyEvent.VK_N, KeyEvent.VK_P -> true
            else -> when (e.keyChar) {
                'w', 'W', 'a', 'A', 's', 'S', 'd', 'D', 'n', 'N', 'p', 'P' -> true
                else -> false
            }
        }
    }

    private class CodeStrafeTypedHandler : TypedActionHandler {

        override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
            if (!CodeStrafeState.isNavigationModeEnabled()) {
                originalTypedHandler?.execute(editor, charTyped, dataContext)
                return
            }

            val ch = charTyped.lowercaseChar()
            val speed = if (shiftDown.get()) 5 else 1

            val handled = when (ch) {
                'w' -> { moveLines(editor, -speed); true }
                's' -> { moveLines(editor, +speed); true }
                'a' -> { moveToken(editor, direction = -1, steps = speed); true }
                'd' -> { moveToken(editor, direction = +1, steps = speed); true }
                'n' -> { CodeStrafeIdeActions.gotoNextError(dataContext); true }
                'p' -> { CodeStrafeIdeActions.gotoPreviousError(dataContext); true }
                else -> false
            }

            if (handled) {
                CodeStrafeState.snapshotCaretPosition(editor)
                CodeStrafeHighlightManager.updateForEditor(editor)
            }
        }

        private fun moveLines(editor: Editor, deltaLines: Int) {
            val caret = editor.caretModel.currentCaret
            val doc = editor.document
            val current = caret.logicalPosition
            val newLine = (current.line + deltaLines).coerceIn(0, doc.lineCount - 1)
            caret.moveToLogicalPosition(LogicalPosition(newLine, current.column))
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }

        private fun moveToken(editor: Editor, direction: Int, steps: Int) {
            repeat(max(1, steps)) { moveOneToken(editor, direction) }
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

            val probe = if (direction < 0) (offset0 - 1).coerceAtLeast(0) else offset0.coerceIn(0, max(0, len - 1))
            val foundAtProbe = psiFile.findElementAt(probe) ?: run {
                moveCharFallback(editor, direction)
                return
            }

            val deep = PsiTreeUtil.getDeepestFirst(foundAtProbe) ?: foundAtProbe
            val leaf = nearestNonSkippableLeaf(deep, direction) ?: run {
                moveCharFallback(editor, direction)
                return
            }

            val tr = leaf.textRange ?: run { moveCharFallback(editor, direction); return }
            if (tr.length <= 0) { moveCharFallback(editor, direction); return }

            val start = tr.startOffset.coerceIn(0, len)
            val end = tr.endOffset.coerceIn(0, len)

            if (direction > 0) {
                if (offset0 < end) { moveToOffset(editor, end); return }
                val next = nextNonSkippableLeaf(leaf) ?: run { moveCharFallback(editor, direction); return }
                val ntr = next.textRange ?: run { moveCharFallback(editor, direction); return }
                moveToOffset(editor, ntr.endOffset.coerceIn(0, len))
            } else {
                if (offset0 > start) { moveToOffset(editor, start); return }
                val prev = prevNonSkippableLeaf(leaf) ?: run { moveCharFallback(editor, direction); return }
                val ptr = prev.textRange ?: run { moveCharFallback(editor, direction); return }
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
            val txt = try { e.text } catch (_: Throwable) { null } ?: return true
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
                cur = if (direction > 0) PsiTreeUtil.nextLeaf(cur, true) else PsiTreeUtil.prevLeaf(cur, true)
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
}