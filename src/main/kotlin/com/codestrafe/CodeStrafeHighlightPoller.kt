package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls open editors and refreshes highlighting when caret/selection changes.
 * This is intentionally brute-force and reliable across editor event weirdness.
 */
object CodeStrafeHighlightPoller {

    private val log = Logger.getInstance(CodeStrafeHighlightPoller::class.java)

    private const val INTERVAL_MS = 40 // ~25 fps, feels immediate

    private val running = AtomicBoolean(false)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)

    /**
     * Snapshot is a class used by the CodeStrafe plugin.
     */
    private data class Snapshot(val caret: Int, val selStart: Int, val selEnd: Int)

    // Keep last-known editor state so we only update when something changes
    private val last = ConcurrentHashMap<Editor, Snapshot>()

    /**
     * start runs part of CodeStrafe behavior.
     *
     * 
     */
    fun start() {
        if (running.getAndSet(true)) return
        log.info("CodeStrafeHighlightPoller started")
        tick()
    }

    /**
     * stop runs part of CodeStrafe behavior.
     *
     * 
     */
    fun stop() {
        running.set(false)
        alarm.cancelAllRequests()
        last.clear()
        log.info("CodeStrafeHighlightPoller stopped")
    }

    /**
     * tick runs part of CodeStrafe behavior.
     *
     * - looks at all open editor windows.
     * - schedules repeated updates using a timer.
     * - checks or uses the current text selection.
     * - reads or moves the caret position.
     *
     * 
     */
    private fun tick() {
        if (!running.get()) return

        if (!CodeStrafeState.isNavigationModeEnabled()) {
            // If Nav Mode is off, don’t waste cycles.
            alarm.addRequest({ tick() }, INTERVAL_MS)
            return
        }

        val editors = EditorFactory.getInstance().allEditors.toList()

        // Clean up disposed editors
        last.keys.removeIf { it.isDisposed }

        for (ed in editors) {
            if (ed.isDisposed) continue

            val caret = ed.caretModel.offset
            val sm = ed.selectionModel
            val selStart = sm.selectionStart
            val selEnd = sm.selectionEnd

            val snap = Snapshot(caret, selStart, selEnd)
            val prev = last.put(ed, snap)

            // Update highlight only if something changed
            if (prev == null || prev != snap) {
                CodeStrafeHighlightManager.updateForEditor(ed)
            }
        }

        alarm.addRequest({ tick() }, INTERVAL_MS)
    }
}