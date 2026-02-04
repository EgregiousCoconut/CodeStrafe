package com.codestrafe

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.openapi.util.TextRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CodeStrafePsiTargeting
 *
 * Finds a “good” highlight range using JetBrains PSI.
 *
 * PSI is the IDE’s structured model of code. We use it to highlight meaningful
 * elements near the caret (like an expression, a call, a parameter, etc.)
 * instead of guessing based on plain text.
 *
 * This version is intentionally less strict than the earlier one:
 * - it will still pick a PSI element even if it is larger than the “ideal” size
 * - it avoids returning null unless PSI truly cannot be used
 * - it logs details when PSI fails so we can diagnose what happened
 */
object CodeStrafePsiTargeting {

    private val log = Logger.getInstance(CodeStrafePsiTargeting::class.java)

    // “Ideal” size limits. We still allow larger targets, but we score them lower.
    private const val IDEAL_MAX_CHARS = 400
    private const val IDEAL_MAX_LINES = 10

    // Hard safety limits to avoid selecting the entire file most of the time.
    private const val HARD_MAX_CHARS = 5000
    private const val HARD_MAX_LINES = 200

    // How far we search left/right when caret is on whitespace.
    private const val NEARBY_RADIUS = 40

    /**
     * Returns a PSI-based (startOffset, endOffset) range to highlight.
     *
     * Returns null only if:
     * - there is no project
     * - there is no PsiFile for this document
     * - PSI cannot find any element near the caret
     */
    fun findPsiHighlightRange(editor: Editor): Pair<Int, Int>? {
        val project = editor.project
        if (project == null) {
            log.debug("PSI targeting skipped: editor.project is null")
            return null
        }

        val doc = editor.document
        val docLen = doc.textLength
        if (docLen <= 0) {
            log.debug("PSI targeting skipped: document is empty")
            return null
        }

        if (DumbService.isDumb(project)) {
            log.debug("PSI targeting note: project is indexing (dumb mode). PSI may be limited right now.")
        }

        return ReadAction.compute<Pair<Int, Int>?, Throwable> {
            val psiDocManager = PsiDocumentManager.getInstance(project)
            val psiFile: PsiFile? = psiDocManager.getPsiFile(doc)

            if (psiFile == null) {
                log.warn("PSI targeting failed: PsiDocumentManager.getPsiFile(document) returned null")
                return@compute null
            }

            val caretOffset = editor.caretModel.offset.coerceIn(0, docLen)
            val leaf = findElementNearCaret(psiFile, caretOffset)

            if (leaf == null) {
                log.warn("PSI targeting failed: could not find any PsiElement near caret offset=$caretOffset")
                return@compute null
            }

            val chosen = chooseBestParent(editor, leaf, caretOffset)

            if (chosen == null) {
                log.warn(
                    "PSI targeting failed: could not choose a target. " +
                            "leaf=${leaf.javaClass.simpleName} caret=$caretOffset"
                )
                return@compute null
            }

            val range: TextRange? = chosen.textRange
            if (range == null) {
                log.warn("PSI targeting failed: chosen element has null textRange, element=${chosen.javaClass.simpleName}")
                return@compute null
            }

            val start = range.startOffset.coerceIn(0, docLen)
            val end = range.endOffset.coerceIn(0, docLen)

            if (start >= end) {
                log.warn("PSI targeting failed: invalid range start=$start end=$end element=${chosen.javaClass.simpleName}")
                return@compute null
            }

            val charLen = end - start
            val startLine = doc.getLineNumber(start)
            val endLine = doc.getLineNumber(max(start, end - 1))
            val lineSpan = endLine - startLine + 1

            val preview = safePreview(doc.charsSequence, start, end)

            log.warn(
                "PSI targeting SUCCESS: element=${chosen.javaClass.simpleName} " +
                        "range=$start..$end chars=$charLen lines=$lineSpan preview=$preview"
            )

            start to end
        }
    }

    /**
     * Tries to find a PSI element at the caret.
     * If caret is on whitespace, it searches nearby offsets.
     */
    private fun findElementNearCaret(psiFile: PsiFile, caret: Int): PsiElement? {
        val textLength = psiFile.textLength
        if (textLength <= 0) return null

        fun elementAt(off: Int): PsiElement? {
            val o = off.coerceIn(0, max(textLength - 1, 0))
            return psiFile.findElementAt(o)
        }

        val direct = elementAt(caret)
        if (direct != null && direct !is PsiWhiteSpace) return direct

        for (d in 1..NEARBY_RADIUS) {
            val left = caret - d
            val right = caret + d

            val e1 = if (left >= 0) elementAt(left) else null
            if (e1 != null && e1 !is PsiWhiteSpace) return e1

            val e2 = if (right < textLength) elementAt(right) else null
            if (e2 != null && e2 !is PsiWhiteSpace) return e2
        }

        return direct
    }

    /**
     * Walks up the PSI tree and picks the best parent element to highlight.
     *
     * This version avoids returning null just because the element is bigger than an “ideal”.
     * It scores candidates and chooses the best one that:
     * - is not blank
     * - is not insanely huge (hard cap)
     */
    private fun chooseBestParent(editor: Editor, leaf: PsiElement, caretOffset: Int): PsiElement? {
        val doc = editor.document
        val docLen = doc.textLength

        val candidates = ArrayList<PsiElement>()
        var cur: PsiElement? = leaf
        while (cur != null) {
            val r = cur.textRange
            if (r != null && r.length > 0) {
                candidates.add(cur)
            }
            cur = cur.parent
        }

        if (candidates.isEmpty()) {
            log.warn("PSI chooseBestParent: no candidates from leaf=${leaf.javaClass.simpleName}")
            return null
        }

        var best: PsiElement? = null
        var bestScore = Int.MIN_VALUE
        var bestReason = ""

        var kept = 0
        var rejectedBlank = 0
        var rejectedHard = 0

        for (el in candidates) {
            val r = el.textRange ?: continue
            val start = r.startOffset.coerceIn(0, docLen)
            val end = r.endOffset.coerceIn(0, docLen)
            if (start >= end) continue

            val charLen = end - start
            val startLine = doc.getLineNumber(start)
            val endLine = doc.getLineNumber(max(start, end - 1))
            val lineSpan = endLine - startLine + 1

            // Hard cap: stop truly huge highlights.
            if (charLen > HARD_MAX_CHARS || lineSpan > HARD_MAX_LINES) {
                rejectedHard++
                continue
            }

            val slice = doc.charsSequence.subSequence(start, end).toString()
            if (slice.isBlank()) {
                rejectedBlank++
                continue
            }

            kept++

            val containsCaret = caretOffset in start..end
            val caretBonus = if (containsCaret) 4000 else 0

            // Prefer elements that are near the caret (for edge cases).
            val distanceToCaret = when {
                caretOffset < start -> start - caretOffset
                caretOffset > end -> caretOffset - end
                else -> 0
            }
            val distancePenalty = min(distanceToCaret, 200) * 5

            // Size scoring:
            // - small leaf tokens are usually too tiny, prefer slightly larger
            // - very large blocks are allowed, but get penalized
            val baseSizeScore = when {
                charLen <= 1 -> -3000
                charLen <= 4 -> 50
                charLen <= 12 -> 200
                charLen <= 40 -> 450
                charLen <= 120 -> 550
                charLen <= IDEAL_MAX_CHARS -> 600
                else -> 500
            }

            // Penalize going over ideal size and ideal line count (but do not reject).
            val overChars = max(0, charLen - IDEAL_MAX_CHARS)
            val overLines = max(0, lineSpan - IDEAL_MAX_LINES)
            val oversizePenalty = (overChars / 2) + (overLines * 80)

            // Nudge toward larger within reasonable bounds, without letting it run away.
            val mildLenBonus = min(charLen, 600)

            val score = caretBonus + baseSizeScore + mildLenBonus - oversizePenalty - distancePenalty

            if (score > bestScore) {
                bestScore = score
                best = el
                bestReason =
                    "len=$charLen lines=$lineSpan containsCaret=$containsCaret " +
                            "overChars=$overChars overLines=$overLines distance=$distanceToCaret score=$score"
            }
        }

        if (best == null) {
            log.warn(
                "PSI chooseBestParent: all candidates rejected. " +
                        "leaf=${leaf.javaClass.simpleName} caret=$caretOffset " +
                        "rejectedBlank=$rejectedBlank rejectedHard=$rejectedHard"
            )
            return null
        }

        log.debug(
            "PSI chooseBestParent: leaf=${leaf.javaClass.simpleName} -> chosen=${best.javaClass.simpleName} ($bestReason)"
        )

        return best
    }

    private fun safePreview(text: CharSequence, start: Int, end: Int): String {
        val s = max(0, start)
        val e = min(text.length, end)
        val raw = text.subSequence(s, e).toString()
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return if (raw.length <= 60) "\"$raw\"" else "\"${raw.take(60)}...\""
    }
}