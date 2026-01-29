package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

object CodeStrafeHighlightHook {

    private val log = Logger.getInstance(CodeStrafeHighlightHook::class.java)
    private val installed = AtomicBoolean(false)

    fun ensureInstalled() {
        if (installed.getAndSet(true)) return

        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            if (!CodeStrafeState.isNavigationModeEnabled()) return@addAWTEventListener

            if (event !is MouseEvent) return@addAWTEventListener
            if (event.id != MouseEvent.MOUSE_DRAGGED && event.id != MouseEvent.MOUSE_RELEASED) return@addAWTEventListener

            val editor = findEditorForEvent(event) ?: findFocusedEditor() ?: return@addAWTEventListener
            CodeStrafeHighlightManager.updateForEditor(editor)

        }, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)

        log.info("CodeStrafeHighlightHook installed (AWT mouse drag)")
    }

    private fun findFocusedEditor(): Editor? {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
        return EditorFactory.getInstance().allEditors.firstOrNull { ed ->
            SwingUtilities.isDescendingFrom(focusOwner, ed.contentComponent)
        }
    }

    private fun findEditorForEvent(e: MouseEvent): Editor? {
        val c = e.component ?: return null
        return EditorFactory.getInstance().allEditors.firstOrNull { ed ->
            SwingUtilities.isDescendingFrom(c, ed.contentComponent)
        }
    }
}