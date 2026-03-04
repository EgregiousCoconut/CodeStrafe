package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.*
import com.intellij.ui.JBColor
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * PSI-only highlight rendering.
 *
 * Priority:
 * 1) Forced PSI target (from Tab cycling) if still valid for current caret
 * 2) PSI element at caret
 * Otherwise: no highlight (no non-PSI fallbacks)
 *
 * IMPORTANT:
 * - Does NOT use selection for highlighting (selection causes Tab indent behaviors).
 */
object CodeStrafeHighlightManager {

    private val log = Logger.getInstance(CodeStrafeHighlightManager::class.java)
    private val highlighters = ConcurrentHashMap<Editor, RangeHighlighter>()

    private val outlineColor = JBColor(
        Color(120, 200, 255, 230),
        Color(120, 200, 255, 200)
    )

    fun updateForEditor(editor: Editor) {
        if (editor.isDisposed) {
            removeForEditor(editor)
            return
        }

        if (!CodeStrafeState.isNavigationModeEnabled()) {
            removeForEditor(editor)
            return
        }

        val len = editor.document.textLength
        val caret = editor.caretModel.offset.coerceIn(0, len)

        // 1) Forced target from Tab cycling (if still valid)
        val forced = CodeStrafePsiTargeting.getForcedTargetRangeIfValid(editor, caret)
        val psiRange = forced ?: CodeStrafePsiTargeting.psiRangeAtCaret(editor)

        if (psiRange == null) {
            removeForEditor(editor)
            return
        }

        val start = psiRange.first.coerceIn(0, len)
        val end = psiRange.second.coerceIn(0, len)
        if (start >= end) {
            removeForEditor(editor)
            return
        }

        val existing = highlighters[editor]
        if (existing != null) {
            if (existing.startOffset == start && existing.endOffset == end) return
            safeRemove(existing)
            highlighters.remove(editor)
        }

        val attrs = TextAttributes().apply {
            effectColor = outlineColor
            effectType = EffectType.BOXED
        }

        val h = editor.markupModel.addRangeHighlighter(
            start,
            end,
            HighlighterLayer.SELECTION + 10,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        )
        h.isGreedyToLeft = false
        h.isGreedyToRight = false
        highlighters[editor] = h
    }

    fun removeForEditor(editor: Editor) {
        val h = highlighters.remove(editor) ?: return
        safeRemove(h)
    }

    /**
     * Keep the signature your project already uses.
     */
    fun refreshAll(editors: List<Editor>) {
        if (!CodeStrafeState.isNavigationModeEnabled()) {
            editors.forEach { removeForEditor(it) }
            return
        }
        editors.forEach { updateForEditor(it) }
    }

    private fun safeRemove(h: RangeHighlighter) {
        try {
            h.dispose()
        } catch (t: Throwable) {
            log.warn("Failed to dispose highlighter cleanly", t)
        }
    }
}