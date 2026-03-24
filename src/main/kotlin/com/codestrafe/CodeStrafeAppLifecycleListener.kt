package com.codestrafe

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

class CodeStrafeAppLifecycleListener : AppLifecycleListener {

    private val log = Logger.getInstance(CodeStrafeAppLifecycleListener::class.java)

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        log.warn("CODESTRAFE_BOOT: appFrameCreated -> installing Caps + Input hooks")
        System.err.println("CODESTRAFE_BOOT: appFrameCreated -> installIfNeeded() + ensureInstalled()")

        CodeStrafeCapsLockService.installIfNeeded()
        CodeStrafeInputHook.ensureInstalled()

        // Start controller support here because this listener is definitely running.
        CodeStrafeControllerService.start()
        log.warn("CODESTRAFE_CONTROLLER: start() called from AppLifecycleListener")
    }

    override fun appWillBeClosed(isRestart: Boolean) {
        log.warn("CODESTRAFE_BOOT: appWillBeClosed -> uninstalling Caps hook")
        System.err.println("CODESTRAFE_BOOT: appWillBeClosed -> uninstallIfNeeded()")

        CodeStrafeCapsLockService.uninstallIfNeeded()
        CodeStrafeControllerService.stop()
    }
}