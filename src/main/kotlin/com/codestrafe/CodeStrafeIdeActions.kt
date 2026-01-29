package com.codestrafe

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger

/**
 * CodeStrafeIdeActions
 *
 * Safely invokes built-in IntelliJ actions by ID.
 * We try multiple known IDs because JetBrains sometimes renames actions across versions/editions.
 */
object CodeStrafeIdeActions {

    private val log = Logger.getInstance(CodeStrafeIdeActions::class.java)

    fun invokeFirstAvailable(actionIds: List<String>, dataContext: DataContext) {
        val am = ActionManager.getInstance()
        val action: AnAction? = actionIds.firstNotNullOfOrNull { id -> am.getAction(id) }

        if (action == null) {
            log.warn("No matching action found for IDs: $actionIds")
            return
        }

        val event = AnActionEvent.createFromAnAction(action, null, "CodeStrafe", dataContext)
        try {
            action.actionPerformed(event)
        } catch (t: Throwable) {
            log.warn("Failed invoking action ${actionIds.firstOrNull()} (picked ${action.javaClass.name})", t)
        }
    }

    fun gotoNextError(dataContext: DataContext) {
        invokeFirstAvailable(
            listOf(
                "GotoNextError",
                "GotoNextErrorInAnyFile",
                "GotoNextErrorInCurrentFile"
            ),
            dataContext
        )
    }

    fun gotoPreviousError(dataContext: DataContext) {
        invokeFirstAvailable(
            listOf(
                "GotoPreviousError",
                "GotoPreviousErrorInAnyFile",
                "GotoPreviousErrorInCurrentFile"
            ),
            dataContext
        )
    }

    fun gotoNextTodo(dataContext: DataContext) {
        invokeFirstAvailable(
            listOf(
                "NextTodo",
                "NextOccurence",
                "NextOccurrence"
            ),
            dataContext
        )
    }

    fun gotoPreviousTodo(dataContext: DataContext) {
        invokeFirstAvailable(
            listOf(
                "PreviousTodo",
                "PreviousOccurence",
                "PreviousOccurrence"
            ),
            dataContext
        )
    }
}