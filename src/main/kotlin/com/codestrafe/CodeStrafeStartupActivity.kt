package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * CodeStrafeStartupActivity is a class used by the CodeStrafe plugin.
 */
class CodeStrafeStartupActivity : StartupActivity {

    private val log = Logger.getInstance(CodeStrafeStartupActivity::class.java)

    /**
     * runActivity runs part of CodeStrafe behavior.
     *
     * Parameters: Project.
     */
    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            try {
                CodeStrafeInputHook.ensureInstalled()
                CodeStrafeHighlightHook.ensureInstalled() // if you still have it
                CodeStrafeHighlightPoller.start()
                log.info("CodeStrafeStartupActivity initialized")
            } catch (t: Throwable) {
                log.warn("CodeStrafeStartupActivity init failed", t)
            }
        }
    }
}