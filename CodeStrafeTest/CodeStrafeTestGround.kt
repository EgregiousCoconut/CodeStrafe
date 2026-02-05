package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.*
import com.intellij.ui.JBColor
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

object CodeStrafeHighlightManager {

    private val log = Logger.getInstance(CodeStrafeHighlightManager::class.java)

    /**
     * Current RangeHighlighter per Editor.
     */
    private val highlighters = ConcurrentHashMap<Editor, RangeHighlighter>()

    /**
     * Soft fallback highlight (subtle background).
     */
    private val fallbackBg = JBColor(
        Color(120, 200, 255, 70),
        Color(120, 200, 255, 40)
    )

    /**
     * Normal target highlight (outline + light background).
     */
    private val targetBg = JBColor(
        Color(120, 200, 255, 45),
        Color(120, 200, 255, 25)
    )
    private val targetOutline = JBColor(
        Color(120, 200, 255, 230),
        Color(120, 200, 255, 200)
    )

    /**
     * Hard limits to avoid highlighting “the whole world”.
     * If PSI returns a massive range, we reject it and fall back.
     */
    private const val MAX_HIGHLIGHT_CHARS = 600
    private const val MAX_HIGHLIGHT_LINES = 20

    /**
     * Updates the highlight for a single editor.
     *
     * This function must be safe:
     * - Never throw
     * - Never leave you with “no highlight” while Nav Mode is on
     */
    fun updateForEditor(editor: Editor) {
        try {
            if (editor.isDisposed) {
                removeForEditor(editor)
                return
            }

            if (!CodeStrafeState.isNavigationModeEnabled()) {
                removeForEditor(editor)
                return
            }

            val len = editor.document.textLength
            if (len <= 0) {
                removeForEditor(editor)
                return
            }

            val (rawStart, rawEnd, style) = computeSmartRange(editor, len)
            val (start, end) = ensureValidRange(editor, rawStart, rawEnd)

            // If we already have the same range, keep it.
            val existing = highlighters[editor]
            if (existing != null) {
                if (existing.startOffset == start && existing.endOffset == end) return
                safeRemove(existing)
                highlighters.remove(editor)
            }

            val (layer, attrs) = when (style) {
                HighlightStyle.TARGET -> {
                    (HighlighterLayer.SELECTION + 10) to TextAttributes().apply {
                        backgroundColor = targetBg
                        effectColor = targetOutline
                        effectType = EffectType.BOXED
                    }
                }
                HighlightStyle.FALLBACK -> {
                    (HighlighterLayer.SELECTION - 1) to TextAttributes().apply {
                        backgroundColor = fallbackBg
                    }
                }
            }

            val h = editor.markupModel.addRangeHighlighter(
                start,
                end,
                layer,
                attrs,
                HighlighterTargetArea.EXACT_RANGE
            )
            h.isGreedyToLeft = false
            h.isGreedyToRight = false

            highlighters[editor] = h
        } catch (t: Throwable) {
            log.warn("CodeStrafe highlight update failed", t)
        }
    }

    fun removeForEditor(editor: Editor) {
        val h = highlighters.remove(editor) ?: return
        safeRemove(h)
    }

    fun refreshAll(editors: List<Editor>) {
        if (!CodeStrafeState.isNavigationModeEnabled()) {
            editors.forEach { removeForEditor(it) }
            return
        }
        editors.forEach { updateForEditor(it) }
    }

    /**
     * Priority:
     * 1) User selection
     * 2) PSI-based target (if available AND not huge)
     * 3) Nearest token on the line
     * 4) Bracket/quote block around caret
     * 5) Nearest non-whitespace run on the line
     * 6) 1-char fallback at caret
     */
    private fun computeSmartRange(editor: Editor, len: Int): Triple<Int, Int, HighlightStyle> {
        val sm = editor.selectionModel
        val sStart = sm.selectionStart
        val sEnd = sm.selectionEnd

        // 1) Manual selection always wins.
        if (sStart != sEnd) {
            val a = min(sStart, sEnd).coerceIn(0, len)
            val b = max(sStart, sEnd).coerceIn(0, len)
            return Triple(a, b, HighlightStyle.TARGET)
        }

        val doc = editor.document
        val text = doc.charsSequence
        val caret = editor.caretModel.offset.coerceIn(0, len)

        val line = doc.getLineNumber(caret)
        val lineStart = doc.getLineStartOffset(line)
        val lineEnd = doc.getLineEndOffset(line)

        // 2) PSI targeting (optional; must be rejected if it returns a massive range)
        try {
            val psi = CodeStrafePsiTargeting.findPsiHighlightRange(editor)
            if (psi != null) {
                val a = psi.first.coerceIn(0, len)
                val b = psi.second.coerceIn(0, len)

                if (a < b && isReasonableRange(doc, a, b)) {
                    // Keep PSI the same visual style as normal targeting.
                    return Triple(a, b, HighlightStyle.TARGET)
                } else {
                    log.warn("CodeStrafe: PSI range rejected (too large or invalid): $a..$b")
                }
            } else {
                log.warn("CodeStrafe: PSI returned null, falling back to text targeting")
            }
        } catch (t: Throwable) {
            log.warn("CodeStrafe PSI targeting failed; falling back to text targeting", t)
        }

        // 3) Token lock-on
        val token = findNearestTokenRangeOnLine(text, caret, lineStart, lineEnd)
        if (token != null) return Triple(token.first, token.second, HighlightStyle.TARGET)

        // 4) Bracket / quote block
        val block = findBracketRange(text, caret)
        if (block != null) return Triple(block.first, block.second, HighlightStyle.TARGET)

        // 5) Non-whitespace run
        val run = findNearestNonWhitespaceRunOnLine(text, caret, lineStart, lineEnd)
        if (run != null) return Triple(run.first, run.second, HighlightStyle.FALLBACK)

        // 6) Final fallback: 1 char at caret
        val start = if (caret == len) max(len - 1, 0) else caret
        val end = min(start + 1, len)
        return Triple(start, end, HighlightStyle.FALLBACK)
    }

    /**
     * Reject “everything highlighted” cases.
     * PSI can sometimes hand you a big parent element (like a whole file or class).
     */
    private fun isReasonableRange(doc: com.intellij.openapi.editor.Document, start: Int, end: Int): Boolean {
        val charLen = end - start
        if (charLen <= 0) return false
        if (charLen > MAX_HIGHLIGHT_CHARS) return false

        val startLine = doc.getLineNumber(start)
        val endLine = doc.getLineNumber(max(start, end - 1))
        val lineSpan = endLine - startLine + 1
        if (lineSpan > MAX_HIGHLIGHT_LINES) return false

        return true
    }

    /**
     * Always return a valid range (start < end).
     * If invalid, we force a 1-character range at the caret.
     */
    private fun ensureValidRange(editor: Editor, start: Int, end: Int): Pair<Int, Int> {
        val len = editor.document.textLength
        val a = start.coerceIn(0, len)
        val b = end.coerceIn(0, len)
        if (a < b) return a to b

        val caret = editor.caretModel.offset.coerceIn(0, len)
        val s = if (caret == len) max(len - 1, 0) else caret
        val e = min(s + 1, len)
        return s to e
    }

    /**
     * Token = letters/digits/underscore, found by scanning left/right from caret.
     */
    private fun findNearestTokenRangeOnLine(
        text: CharSequence,
        caret: Int,
        lineStart: Int,
        lineEnd: Int
    ): Pair<Int, Int>? {

        fun isTokenChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'
        if (text.isEmpty()) return null
        if (lineStart >= lineEnd) return null

        val c = caret.coerceIn(lineStart, lineEnd)
        var radius = 0

        while (true) {
            val left = c - radius
            val right = c + radius

            var hit: Int? = null
            if (left in lineStart until lineEnd && isTokenChar(text[left])) hit = left
            if (hit == null && right in lineStart until lineEnd && isTokenChar(text[right])) hit = right

            if (hit != null) {
                var a = hit
                var b = hit + 1
                while (a > lineStart && isTokenChar(text[a - 1])) a--
                while (b < lineEnd && isTokenChar(text[b])) b++
                return if (a < b) a to b else null
            }

            radius++
            if (c - radius < lineStart && c + radius >= lineEnd) break
        }

        return null
    }

    /**
     * Fallback: nearest non-whitespace run on the line.
     */
    private fun findNearestNonWhitespaceRunOnLine(
        text: CharSequence,
        caret: Int,
        lineStart: Int,
        lineEnd: Int
    ): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        if (lineStart >= lineEnd) return null

        val c = caret.coerceIn(lineStart, lineEnd)
        var radius = 0
        var pos: Int? = null

        while (true) {
            val left = c - radius
            val right = c + radius

            if (left in lineStart until lineEnd && !text[left].isWhitespace()) { pos = left; break }
            if (right in lineStart until lineEnd && !text[right].isWhitespace()) { pos = right; break }

            radius++
            if (c - radius < lineStart && c + radius >= lineEnd) break
        }

        val p = pos ?: return null

        var a = p
        var b = p + 1
        while (a > lineStart && !text[a - 1].isWhitespace()) a--
        while (b < lineEnd && !text[b].isWhitespace()) b++

        return if (a < b) a to b else null
    }

    /**
     * Simple bracket/quote scan. Not PSI.
     */
    private fun findBracketRange(text: CharSequence, offset: Int): Pair<Int, Int>? {
        val pairs = mapOf(
            '(' to ')',
            '{' to '}',
            '[' to ']',
            '"' to '"',
            '\'' to '\''
        )

        if (text.isEmpty()) return null

        for ((open, close) in pairs) {
            var left = offset
            var right = offset

            var depth = 0
            while (left in text.indices) {
                val ch = text[left]
                if (ch == open) {
                    if (depth == 0) break
                    depth--
                } else if (ch == close) depth++
                left--
            }

            depth = 0
            while (right in text.indices) {
                val ch = text[right]
                if (ch == close) {
                    if (depth == 0) break
                    depth--
                } else if (ch == open) depth++
                right++
            }

            if (left in text.indices && right in text.indices && left < right) {
                return left to (right + 1)
            }
        }

        return null
    }

    private fun safeRemove(h: RangeHighlighter) {
        try {
            h.dispose()
        } catch (t: Throwable) {
            log.warn("Failed to dispose highlighter cleanly", t)
        }
    }

    private enum class HighlightStyle {
        TARGET,
        FALLBACK
    }
}