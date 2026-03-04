package com.codestrafe

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PSI targeting + cycling WITHOUT using editor selection.
 *
 * This version makes Tab-cycling much richer by combining:
 * - Zoom (ancestor chain)
 * - Traverse (siblings at multiple zoom levels)
 * - Chunk (statement-window ranges)
 *
 * Output is a list of (start,end) ranges (not necessarily a single PSI element).
 */
object CodeStrafePsiTargeting {

    private val LAST_INDEX_KEY: Key<Int> = Key.create("codestrafe.psiCycle.lastIndex")
    private val LAST_ANCHOR_KEY: Key<Int> = Key.create("codestrafe.psiCycle.anchorOffset")

    private val FORCED_RANGE_KEY: Key<Pair<Int, Int>> = Key.create("codestrafe.psiTarget.forcedRange")
    private val FORCED_ANCHOR_KEY: Key<Int> = Key.create("codestrafe.psiTarget.forcedAnchor")

    private const val SIBLING_WINDOW = 6
    private const val SIBLING_LEVELS = 6 // how many ancestor levels we add sibling traversal for

    fun clearForcedTarget(editor: Editor) {
        editor.putUserData(FORCED_RANGE_KEY, null)
        editor.putUserData(FORCED_ANCHOR_KEY, null)
        editor.putUserData(LAST_INDEX_KEY, null)
        editor.putUserData(LAST_ANCHOR_KEY, null)
    }

    /**
     * Old behavior: forced target only if caret stayed within +/-2 chars of the original anchor.
     * That can feel fragile; instead: keep forced target as long as caret stays INSIDE the range.
     */
    fun getForcedTargetRangeIfValid(editor: Editor, caretOffset: Int): Pair<Int, Int>? {
        val range = editor.getUserData(FORCED_RANGE_KEY) ?: return null
        val (a, b) = normalize(range)
        return if (caretOffset in a..b) range else null
    }

    fun cyclePsiTarget(editor: Editor, forward: Boolean) {
        val project = editor.project ?: return
        if (editor.isDisposed) return

        val doc = editor.document
        val pdm = PsiDocumentManager.getInstance(project)
        pdm.commitDocument(doc)

        val psiFile = pdm.getPsiFile(doc) ?: return
        val caretOffset = editor.caretModel.offset.coerceIn(0, doc.textLength)

        val cycle = buildCycleList(psiFile, caretOffset)
        if (cycle.isEmpty()) return

        val anchor = editor.getUserData(LAST_ANCHOR_KEY)
        val currentIndex = editor.getUserData(LAST_INDEX_KEY)

        // Reset index if caret moved far away from the last anchor
        val reset = anchor == null || abs(anchor - caretOffset) > 20

        val idx = if (reset || currentIndex == null) {
            if (forward) 0 else cycle.lastIndex
        } else {
            val next = if (forward) currentIndex + 1 else currentIndex - 1
            wrap(next, cycle.size)
        }

        editor.putUserData(LAST_ANCHOR_KEY, caretOffset)
        editor.putUserData(LAST_INDEX_KEY, idx)

        val (start0, end0) = normalize(cycle[idx])
        if (start0 >= end0) return

        editor.selectionModel.removeSelection()

        editor.putUserData(FORCED_RANGE_KEY, start0 to end0)
        editor.putUserData(FORCED_ANCHOR_KEY, caretOffset) // kept for backwards compat

        editor.caretModel.moveToOffset(start0)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
    }

    /**
     * Used by HighlightManager for auto highlight when no forced range exists.
     * Returns smallest stable PSI element range at caret.
     */
    fun psiRangeAtCaret(editor: Editor): Pair<Int, Int>? {
        val project = editor.project ?: return null
        if (editor.isDisposed) return null

        val doc = editor.document
        val pdm = PsiDocumentManager.getInstance(project)
        pdm.commitDocument(doc)

        val psiFile = pdm.getPsiFile(doc) ?: return null
        val offset = editor.caretModel.offset.coerceIn(0, doc.textLength)

        val leaf =
            psiFile.findElementAt(offset)
                ?: psiFile.findElementAt((offset - 1).coerceAtLeast(0))
                ?: return null

        val best = chooseStablePsiTarget(leaf) ?: leaf
        val tr = best.textRange ?: return null
        if (tr.length <= 0) return null
        return tr.startOffset to tr.endOffset
    }

    // ------------------------------------------------------------
    // Core: build a big list of target ranges to cycle through
    // ------------------------------------------------------------

    private fun buildCycleList(file: PsiFile, offset: Int): List<Pair<Int, Int>> {
        val el0 =
            file.findElementAt(offset)
                ?: file.findElementAt((offset - 1).coerceAtLeast(0))
                ?: return emptyList()

        val leaf0 = PsiTreeUtil.getDeepestFirst(el0) ?: el0
        val leaf = chooseStablePsiTarget(leaf0) ?: leaf0

        val chain = parentChain(leaf, file).filter { isUsable(it) }
        if (chain.isEmpty()) return emptyList()

        val out = ArrayList<Pair<Int, Int>>(256)

        // A) Zoom: full ancestor chain ranges
        for (e in chain) addRange(out, e)

        // B) Traverse: sibling rings at multiple zoom levels
        val levels = min(SIBLING_LEVELS, chain.size)
        for (i in 0 until levels) {
            val node = chain[i]
            addSiblingRing(out, node, SIBLING_WINDOW)
        }

        // C) Chunk: statement-window ranges around nearest statement-like node
        val stmt = chain.firstOrNull { isStatementLike(it) }
        if (stmt != null) {
            val stmts = collectSiblingCandidates(stmt, preferStatementLike = true)
            val idx = indexByRange(stmts, stmt)
            if (idx >= 0) {
                // individual nearby statements first
                addStatementNeighbors(out, stmts, idx, SIBLING_WINDOW)
                // then growing chunk windows
                addChunkLadder(out, stmts, idx)
            }
        }

        // D) Anchors: function/class/file-ish containers (ensure at end)
        chain.firstOrNull { isFunctionLike(it) }?.let { addRange(out, it) }
        chain.firstOrNull { isClassLike(it) }?.let { addRange(out, it) }
        chain.lastOrNull()?.let { addRange(out, it) }

        // Normalize + filter + dedup (keep first occurrence order)
        return out
            .map { normalize(it) }
            .filter { it.first < it.second }
            .distinct()
    }

    // -------------------------
    // Zoom helpers
    // -------------------------

    private fun parentChain(start: PsiElement, stopAtFile: PsiFile): List<PsiElement> {
        val out = ArrayList<PsiElement>(32)
        var cur: PsiElement? = start
        var guard = 0
        while (cur != null && cur != stopAtFile && guard < 128) {
            out.add(cur)
            cur = cur.parent
            guard++
        }
        return out
    }

    private fun addRange(out: MutableList<Pair<Int, Int>>, e: PsiElement) {
        val tr = e.textRange ?: return
        if (tr.length <= 0) return
        out.add(tr.startOffset to tr.endOffset)
    }

    private fun isUsable(e: PsiElement): Boolean {
        if (e is PsiWhiteSpace) return false
        val tr = e.textRange ?: return false
        if (tr.length <= 0) return false
        val txt = try { e.text } catch (_: Throwable) { null } ?: return false
        return txt.isNotBlank()
    }

    // -------------------------
    // Traverse helpers (siblings)
    // -------------------------

    private fun addSiblingRing(out: MutableList<Pair<Int, Int>>, node: PsiElement, window: Int) {
        val parent = node.parent ?: return
        val siblings = parent.children
            .asSequence()
            .filter { isUsable(it) }
            .toList()
        if (siblings.isEmpty()) return

        val idx = indexByRange(siblings, node)
        if (idx < 0) return

        // current, next..., prev...
        addRange(out, siblings[idx])
        for (k in 1..window) {
            val j = idx + k
            if (j in siblings.indices) addRange(out, siblings[j])
        }
        for (k in 1..window) {
            val j = idx - k
            if (j in siblings.indices) addRange(out, siblings[j])
        }
    }

    private fun collectSiblingCandidates(node: PsiElement, preferStatementLike: Boolean): List<PsiElement> {
        val parent = node.parent ?: return listOf(node)
        val base = parent.children
            .asSequence()
            .filter { isUsable(it) }
            .toList()

        if (!preferStatementLike) return if (base.isEmpty()) listOf(node) else base

        val filtered = base.filter { isStatementLike(it) || looksStatementSiblingCompatible(it) }
        return if (filtered.isEmpty()) listOf(node) else filtered
    }

    private fun addStatementNeighbors(out: MutableList<Pair<Int, Int>>, stmts: List<PsiElement>, idx: Int, window: Int) {
        addRange(out, stmts[idx])
        for (k in 1..window) {
            val j = idx + k
            if (j in stmts.indices) addRange(out, stmts[j])
        }
        for (k in 1..window) {
            val j = idx - k
            if (j in stmts.indices) addRange(out, stmts[j])
        }
    }

    // -------------------------
    // Chunk helpers (range windows)
    // -------------------------

    private fun addChunkLadder(out: MutableList<Pair<Int, Int>>, stmts: List<PsiElement>, idx: Int) {
        if (stmts.isEmpty()) return

        val sizes = fibonacciSizesUpTo(stmts.size)
        for (size in sizes) {
            val (a, b) = centeredWindow(idx, size, stmts.size)
            val start = stmts[a].textRange?.startOffset ?: continue
            val end = stmts[b].textRange?.endOffset ?: continue
            out.add(start to end)
        }

        // directional ladders
        val topStart = stmts.first().textRange?.startOffset
        val bottomEnd = stmts.last().textRange?.endOffset
        val curStart = stmts[idx].textRange?.startOffset
        val curEnd = stmts[idx].textRange?.endOffset
        if (topStart != null && curEnd != null) out.add(topStart to curEnd)
        if (curStart != null && bottomEnd != null) out.add(curStart to bottomEnd)
        if (topStart != null && bottomEnd != null) out.add(topStart to bottomEnd)
    }

    private fun centeredWindow(center: Int, size: Int, n: Int): Pair<Int, Int> {
        val half = (size - 1) / 2
        var a = center - half
        var b = center + (size - 1 - half)

        if (a < 0) { b += -a; a = 0 }
        if (b > n - 1) {
            val over = b - (n - 1)
            a = max(0, a - over)
            b = n - 1
        }
        return a to b
    }

    private fun fibonacciSizesUpTo(maxSize: Int): List<Int> {
        val out = ArrayList<Int>(16)
        var a = 1
        var b = 2
        while (a <= maxSize) {
            out.add(a)
            val next = a + b
            a = b
            b = next
        }
        return out
    }

    // -------------------------
    // Heuristics
    // -------------------------

    private fun looksStatementSiblingCompatible(e: PsiElement): Boolean {
        val n = e.javaClass.name
        return n.contains("Property", ignoreCase = true) ||
                n.contains("Declaration", ignoreCase = true) ||
                n.contains("Expression", ignoreCase = true) ||
                n.contains("Call", ignoreCase = true)
    }

    private fun isStatementLike(e: PsiElement): Boolean {
        val n = e.javaClass.name
        return n.contains("Statement", ignoreCase = true) ||
                n.contains("If", ignoreCase = true) ||
                n.contains("When", ignoreCase = true) ||
                n.contains("For", ignoreCase = true) ||
                n.contains("While", ignoreCase = true) ||
                n.contains("Return", ignoreCase = true) ||
                n.contains("Throw", ignoreCase = true) ||
                n.contains("Property", ignoreCase = true) ||
                n.contains("Declaration", ignoreCase = true)
    }

    private fun isFunctionLike(e: PsiElement): Boolean {
        val n = e.javaClass.name
        return n.contains("Function", ignoreCase = true) ||
                n.contains("Method", ignoreCase = true) ||
                n.contains("Lambda", ignoreCase = true) ||
                n.contains("Callable", ignoreCase = true) ||
                (e is PsiNamedElement && n.contains("KtNamedFunction", ignoreCase = true))
    }

    private fun isClassLike(e: PsiElement): Boolean {
        val n = e.javaClass.name
        return n.contains("Class", ignoreCase = true) ||
                n.contains("Interface", ignoreCase = true) ||
                n.contains("ObjectDeclaration", ignoreCase = true) ||
                n.contains("Enum", ignoreCase = true)
    }

    private fun chooseStablePsiTarget(leaf: PsiElement): PsiElement? {
        var cur: PsiElement? = leaf
        var best: PsiElement? = null
        var guard = 0
        while (cur != null && guard < 32) {
            val tr = cur.textRange
            val txt = try { cur.text } catch (_: Throwable) { null }
            if (tr != null && tr.length > 0 && txt != null && txt.isNotBlank()) {
                best = cur
                if (tr.length >= 3) break
            }
            cur = cur.parent
            guard++
        }
        return best ?: leaf
    }

    // -------------------------
    // Utilities
    // -------------------------

    private fun indexByRange(list: List<PsiElement>, target: PsiElement): Int {
        val tr = target.textRange ?: return -1
        for (i in list.indices) {
            val r = list[i].textRange ?: continue
            if (r.startOffset == tr.startOffset && r.endOffset == tr.endOffset) return i
        }
        return -1
    }

    private fun normalize(r: Pair<Int, Int>): Pair<Int, Int> {
        val a = min(r.first, r.second)
        val b = max(r.first, r.second)
        return a to b
    }

    private fun wrap(i: Int, n: Int): Int {
        if (n <= 0) return 0
        var x = i % n
        if (x < 0) x += n
        return x
    }
}