package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CodeStrafeStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(CodeStrafeStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.warn("CODESTRAFE_STARTUP: startup called for project ${project.name}")

        invokeOptionalNoArg("CodeStrafeInputHook.ensureInstalled") {
            CodeStrafeInputHook::class.java
                .getMethod("ensureInstalled")
                .invoke(CodeStrafeInputHook)
        }

        invokeOptionalNoArg("CodeStrafeCapsLockService.ensureInstalled") {
            CodeStrafeCapsLockService::class.java
                .getMethod("ensureInstalled")
                .invoke(CodeStrafeCapsLockService)
        }

        invokeOptionalNoArg("CodeStrafeHighlightHook.ensureInstalled") {
            CodeStrafeHighlightHook::class.java
                .getMethod("ensureInstalled")
                .invoke(CodeStrafeHighlightHook)
        }

        invokeOptionalWithProject("CodeStrafeHighlightHook.ensureInstalled(project)", project) {
            CodeStrafeHighlightHook::class.java
                .getMethod("ensureInstalled", Project::class.java)
                .invoke(CodeStrafeHighlightHook, project)
        }

        invokeOptionalNoArg("CodeStrafeHighlightPoller.ensureInstalled") {
            CodeStrafeHighlightPoller::class.java
                .getMethod("ensureInstalled")
                .invoke(CodeStrafeHighlightPoller)
        }

        invokeOptionalWithProject("CodeStrafeHighlightPoller.ensureStarted(project)", project) {
            CodeStrafeHighlightPoller::class.java
                .getMethod("ensureStarted", Project::class.java)
                .invoke(CodeStrafeHighlightPoller, project)
        }
    }

    private fun invokeOptionalNoArg(name: String, block: () -> Unit) {
        try {
            block()
            log.warn("CODESTRAFE_STARTUP: initialized $name")
        } catch (t: NoSuchMethodException) {
            log.warn("CODESTRAFE_STARTUP: skipped $name because method does not exist")
        } catch (t: Throwable) {
            log.warn("CODESTRAFE_STARTUP: failed while initializing $name", t)
        }
    }

    private fun invokeOptionalWithProject(name: String, project: Project, block: (Project) -> Unit) {
        try {
            block(project)
            log.warn("CODESTRAFE_STARTUP: initialized $name")
        } catch (t: NoSuchMethodException) {
            log.warn("CODESTRAFE_STARTUP: skipped $name because method does not exist")
        } catch (t: Throwable) {
            log.warn("CODESTRAFE_STARTUP: failed while initializing $name", t)
        }
    }
}