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
    private val highlighters = ConcurrentHashMap<Editor, RangeHighlighter>()

    private val caretBg = JBColor(
        Color(120, 200, 255, 70),
        Color(120, 200, 255, 40)
    )

    private val selectionOutline = JBColor(
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
        val (start, end, selectionStyle) = computeSmartRange(editor, len)

        val existing = highlighters[editor]
        if (existing != null) {
            if (existing.startOffset == start && existing.endOffset == end) return
            safeRemove(existing)
            highlighters.remove(editor)
        }

        val (layer, attrs) = if (selectionStyle) {
            (HighlighterLayer.SELECTION + 10) to TextAttributes().apply {
                effectColor = selectionOutline
                effectType = EffectType.BOXED
            }
        } else {
            (HighlighterLayer.SELECTION - 1) to TextAttributes().apply {
                backgroundColor = caretBg
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
     * "Nearest thing" targeting:
     * 1) User selection (if start != end)
     * 2) Nearest token on the current line (lock-on scan left/right from caret)
     * 3) Nearest bracket/quote block around caret
     * 4) Nearest non-whitespace run on the line
     * 5) Single-char caret fallback
     */
    private fun computeSmartRange(editor: Editor, len: Int): Triple<Int, Int, Boolean> {
        val sm = editor.selectionModel
        val sStart = sm.selectionStart
        val sEnd = sm.selectionEnd
        if (sStart != sEnd) {
            val a = min(sStart, sEnd).coerceIn(0, len)
            val b = max(sStart, sEnd).coerceIn(0, len)
            return Triple(a, b, true)
        }

        val doc = editor.document
        val text = doc.charsSequence
        val offset = editor.caretModel.offset.coerceIn(0, len)

        val line = doc.getLineNumber(offset)
        val lineStart = doc.getLineStartOffset(line)
        val lineEnd = doc.getLineEndOffset(line)

        // 2) Nearest token (this is the main “lock-on” behavior)
        val token = findNearestTokenRangeOnLine(text, offset, lineStart, lineEnd)
        if (token != null) return Triple(token.first, token.second, true)

        // 3) Bracket / quote block (useful when caret is near delimiters)
        val block = findBracketRange(text, offset)
        if (block != null) return Triple(block.first, block.second, true)

        // 4) Nearest non-whitespace run
        val run = findNearestNonWhitespaceRunOnLine(text, offset, lineStart, lineEnd)
        if (run != null) return Triple(run.first, run.second, false)

        // 5) Fallback: single character
        val start = if (offset == len) max(len - 1, 0) else offset
        val end = min(start + 1, len)
        return Triple(start, end, false)
    }

    /**
     * Finds the nearest "word token" on the current line.
     * Works even if caret is on whitespace or between characters.
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

        // Clamp caret into [lineStart, lineEnd]
        val c = caret.coerceIn(lineStart, lineEnd)

        // Search outward from the caret for the nearest token char
        var radius = 0
        while (true) {
            val left = c - radius
            val right = c + radius

            var hitPos: Int? = null

            if (left in lineStart until lineEnd) {
                val ch = text[left]
                if (isTokenChar(ch)) hitPos = left
            }
            if (hitPos == null && right in lineStart until lineEnd) {
                val ch = text[right]
                if (isTokenChar(ch)) hitPos = right
            }

            if (hitPos != null) {
                // Expand to full token
                var a = hitPos
                var b = hitPos + 1

                while (a > lineStart && isTokenChar(text[a - 1])) a--
                while (b < lineEnd && isTokenChar(text[b])) b++

                if (a != b) return a to b
                return null
            }

            radius++

            // Stop once we’ve covered the full line distance
            if (c - radius < lineStart && c + radius >= lineEnd) break
        }

        return null
    }

    private fun findNearestNonWhitespaceRunOnLine(
        text: CharSequence,
        caret: Int,
        lineStart: Int,
        lineEnd: Int
    ): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        if (lineStart >= lineEnd) return null

        fun isWs(i: Int): Boolean = text[i].isWhitespace()

        val c = caret.coerceIn(lineStart, lineEnd)

        // Find nearest non-whitespace position by scanning outward
        var radius = 0
        var pos: Int? = null
        while (true) {
            val left = c - radius
            val right = c + radius

            if (left in lineStart until lineEnd && !isWs(left)) { pos = left; break }
            if (right in lineStart until lineEnd && !isWs(right)) { pos = right; break }

            radius++
            if (c - radius < lineStart && c + radius >= lineEnd) break
        }

        val p = pos ?: return null

        var a = p
        var b = p + 1

        while (a > lineStart && !text[a - 1].isWhitespace()) a--
        while (b < lineEnd && !text[b].isWhitespace()) b++

        return if (a != b) a to b else null
    }

    private fun findBracketRange(text: CharSequence, offset: Int): Pair<Int, Int>? {
        val pairs = mapOf(
            '(' to ')',
            '{' to '}',
            '[' to ']',
            '"' to '"',
            '\'' to '\''
        )

        fun inBounds(i: Int) = i in 0 until text.length
        if (text.isEmpty() || !inBounds(offset.coerceAtMost(max(0, text.length - 1)))) return null

        for ((open, close) in pairs) {
            var left = offset
            var right = offset

            var depth = 0
            while (left >= 0 && left < text.length) {
                val ch = text[left]
                if (ch == open) {
                    if (depth == 0) break
                    depth--
                } else if (ch == close) depth++
                left--
            }

            depth = 0
            while (right >= 0 && right < text.length) {
                val ch = text[right]
                if (ch == close) {
                    if (depth == 0) break
                    depth--
                } else if (ch == open) depth++
                right++
            }

            if (left >= 0 && right < text.length && left < right) {
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
}