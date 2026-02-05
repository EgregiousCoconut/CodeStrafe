package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class CodeStrafeStartupActivity : StartupActivity {

    private val log = Logger.getInstance(CodeStrafeStartupActivity::class.java)

    override fun runActivity(project: Project) {
        log.warn("CODESTRAFE_BOOT: StartupActivity.runActivity(project=${project.name}) -> installing Caps dispatcher")
        System.err.println("CODESTRAFE_BOOT: StartupActivity for ${project.name} -> installIfNeeded()")
        CodeStrafeCapsLockService.installIfNeeded()
    }
}