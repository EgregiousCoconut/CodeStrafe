package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class CodeStrafeStartupActivity : StartupActivity {

    private val log = Logger.getInstance(CodeStrafeStartupActivity::class.java)

    override fun runActivity(project: Project) {
        log.warn("CODESTRAFE_STARTUP: runActivity(project=${project.name})")

        CodeStrafeState.setCurrentProject(project)
        CodeStrafeControllerService.start()

        log.warn("CODESTRAFE_STARTUP: current project set and controller service started")
    }
}