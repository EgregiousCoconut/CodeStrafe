package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap

/**
 * CodeStrafeHighlightService
 *
 * Keeps global listeners alive for the lifetime of the service.
 * Updates highlights continuously (caret + selection) while Nav Mode is ON.
 */
class CodeStrafeHighlightService {

    private val log = Logger.getInstance(CodeStrafeHighlightService::class.java)

    // IMPORTANT: keep these as fields so they live as long as the service lives
    private val disposable = Disposer.newDisposable("CodeStrafeHighlightService")
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    // Small coalescing per-editor (prevents spam during drag/hold)
    private val pending = ConcurrentHashMap<Editor, Boolean>()

    init {
        val editorFactory = EditorFactory.getInstance()

        /**
         * schedule runs part of CodeStrafe behavior.
         *
         * - schedules repeated updates using a timer.
         * - requests a highlight refresh so the target box stays correct.
         *
         * Parameters: Editor.
         */
        fun schedule(editor: Editor) {
            if (editor.isDisposed) return
            pending[editor] = true

            // Coalesce very quickly; do NOT cancel all requests globally in a way that drops updates.
            alarm.addRequest({
                val editors = pending.keys.toList()
                pending.clear()
                editors.forEach { ed ->
                    if (!ed.isDisposed) {
                        // Ensure UI state (selection/caret) has settled
                        ApplicationManager.getApplication().invokeLater {
                            CodeStrafeHighlightManager.updateForEditor(ed)
                        }
                    }
                }
            }, 10)
        }

        editorFactory.eventMulticaster.addSelectionListener(object : SelectionListener {
            /**
             * selectionChanged runs part of CodeStrafe behavior.
             *
             * Parameters: SelectionEvent.
             */
            override fun selectionChanged(e: SelectionEvent) {
                schedule(e.editor)
            }
        }, disposable)

        editorFactory.eventMulticaster.addCaretListener(object : CaretListener {
            /**
             * caretPositionChanged runs part of CodeStrafe behavior.
             *
             * Parameters: CaretEvent.
             */
            override fun caretPositionChanged(event: CaretEvent) {
                schedule(event.editor)
            }
        }, disposable)

        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            /**
             * editorCreated runs part of CodeStrafe behavior.
             *
             * Parameters: EditorFactoryEvent.
             */
            override fun editorCreated(event: EditorFactoryEvent) {
                schedule(event.editor)
            }

            /**
             * editorReleased runs part of CodeStrafe behavior.
             *
             * - requests a highlight refresh so the target box stays correct.
             *
             * Parameters: EditorFactoryEvent.
             */
            override fun editorReleased(event: EditorFactoryEvent) {
                CodeStrafeHighlightManager.removeForEditor(event.editor)
                pending.remove(event.editor)
            }
        }, disposable)

        log.info("CodeStrafeHighlightService installed (persistent global listeners)")
    }
}