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
            override fun selectionChanged(e: SelectionEvent) {
                schedule(e.editor)
            }
        }, disposable)

        editorFactory.eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                schedule(event.editor)
            }
        }, disposable)

        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                schedule(event.editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                CodeStrafeHighlightManager.removeForEditor(event.editor)
                pending.remove(event.editor)
            }
        }, disposable)

        log.info("CodeStrafeHighlightService installed (persistent global listeners)")
    }
}