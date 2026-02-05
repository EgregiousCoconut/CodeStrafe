package com.codestrafe

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

class CodeStrafeAppLifecycleListener : AppLifecycleListener {

    private val log = Logger.getInstance(CodeStrafeAppLifecycleListener::class.java)

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        log.warn("CODESTRAFE_BOOT: appFrameCreated -> installing Caps + Input hooks")
        System.err.println("CODESTRAFE_BOOT: appFrameCreated -> installIfNeeded() + ensureInstalled()")

        // 1) Caps Lock toggles CodeStrafeState.navigationModeEnabled
        CodeStrafeCapsLockService.installIfNeeded()

        // 2) Input hook reads CodeStrafeState and intercepts WASD
        CodeStrafeInputHook.ensureInstalled()
    }

    override fun appWillBeClosed(isRestart: Boolean) {
        log.warn("CODESTRAFE_BOOT: appWillBeClosed -> uninstalling Caps hook")
        System.err.println("CODESTRAFE_BOOT: appWillBeClosed -> uninstallIfNeeded()")

        CodeStrafeCapsLockService.uninstallIfNeeded()
        // Note: CodeStrafeInputHook doesn't need uninstall for now (it installs once per IDE run).
    }
}