package com.codestrafe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.studiohartman.jamepad.ControllerManager
import com.studiohartman.jamepad.ControllerState
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object CodeStrafeControllerService {

    private val log = Logger.getInstance(CodeStrafeControllerService::class.java)

    private const val POLL_INTERVAL_MS = 16L
    private const val DEADZONE = 0.20f
    private const val TRIGGER_DEADZONE = 0.35f
    private const val NAV_MODE_GRACE_MS = 400L

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "CodeStrafe-ControllerPoller").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var pollTask: ScheduledFuture<*>? = null

    @Volatile
    private var manager: ControllerManager? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var lastInputLogTime = 0L

    @Volatile
    private var lastNavEnabledTime = 0L

    @Volatile
    private var lastNavGateState: Boolean? = null

    @Volatile
    private var leftHeld = false

    @Volatile
    private var rightHeld = false

    @Volatile
    private var upHeld = false

    @Volatile
    private var downHeld = false

    @Volatile
    private var aHeld = false

    @Volatile
    private var bHeld = false

    @Volatile
    private var xHeld = false

    @Volatile
    private var yHeld = false

    @Volatile
    private var ltHeld = false

    @Volatile
    private var rtHeld = false

    @Volatile
    private var panUpHeld = false

    @Volatile
    private var panDownHeld = false

    fun start() {
        if (pollTask != null) return

        ensureInitialized()
        lastNavEnabledTime = System.currentTimeMillis()

        pollTask = executor.scheduleAtFixedRate(
            { safePoll() },
            0L,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )

        log.warn("CODESTRAFE_CONTROLLER: Jamepad controller service started")
    }

    fun stop() {
        pollTask?.cancel(true)
        pollTask = null

        leftHeld = false
        rightHeld = false
        upHeld = false
        downHeld = false
        aHeld = false
        bHeld = false
        xHeld = false
        yHeld = false
        ltHeld = false
        rtHeld = false
        panUpHeld = false
        panDownHeld = false
        lastInputLogTime = 0L
        lastNavEnabledTime = 0L
        lastNavGateState = null

        try {
            manager?.quitSDLGamepad()
        } catch (_: Throwable) {
        }

        manager = null
        initialized = false

        log.warn("CODESTRAFE_CONTROLLER: Jamepad controller service stopped")
    }

    fun debugControllerSummary(): String {
        return try {
            val mgr = manager ?: return "Controller manager is null"
            mgr.update()

            val lines = mutableListOf<String>()
            var connectedCount = 0

            for (i in 0 until 8) {
                val state = mgr.getState(i)
                if (state.isConnected) {
                    connectedCount++
                    lines += buildString {
                        append("[$i] connected=true")
                        append(", lx=${state.leftStickX}")
                        append(", ly=${state.leftStickY}")
                        append(", rx=${state.rightStickX}")
                        append(", ry=${state.rightStickY}")
                        append(", lt=${state.leftTrigger}")
                        append(", rt=${state.rightTrigger}")
                        append(", a=${state.a}")
                        append(", b=${state.b}")
                        append(", x=${state.x}")
                        append(", y=${state.y}")
                        append(", lb=${state.lb}")
                        append(", rb=${state.rb}")
                        append(", du=${state.dpadUp}")
                        append(", dd=${state.dpadDown}")
                        append(", dl=${state.dpadLeft}")
                        append(", dr=${state.dpadRight}")
                    }
                }
            }

            if (connectedCount == 0) {
                "Controllers detected: 0"
            } else {
                "Controllers detected: $connectedCount\n" + lines.joinToString("\n")
            }
        } catch (t: Throwable) {
            "Controller debug failed: ${t.message}"
        }
    }

    private fun ensureInitialized() {
        if (initialized) return

        synchronized(this) {
            if (initialized) return

            try {
                val mgr = ControllerManager()
                mgr.initSDLGamepad()
                manager = mgr
                initialized = true
                log.warn("CODESTRAFE_CONTROLLER: Jamepad initialized successfully")
            } catch (t: Throwable) {
                log.warn("CODESTRAFE_CONTROLLER: failed to initialize Jamepad", t)
            }
        }
    }

    private fun safePoll() {
        try {
            poll()
        } catch (t: Throwable) {
            log.warn("CODESTRAFE_CONTROLLER: poll failed", t)
        }
    }

    private fun poll() {
        val mgr = manager ?: return
        mgr.update()

        val state = firstConnectedState(mgr) ?: run {
            releaseHeldDirections()
            return
        }

        logControllerInput(state)

        if (!controllerInputAllowed()) {
            releaseHeldDirections()
            return
        }

        val moveLeftPressed = state.leftStickX < -DEADZONE || state.dpadLeft
        val moveRightPressed = state.leftStickX > DEADZONE || state.dpadRight
        val moveUpPressed = state.leftStickY < -DEADZONE || state.dpadUp
        val moveDownPressed = state.leftStickY > DEADZONE || state.dpadDown

        if (moveLeftPressed && !leftHeld) {
            leftHeld = true
            log.warn("CODESTRAFE_CONTROLLER: moveLeft()")
            invokeOnEdt { CodeStrafeMoveAction.moveLeft() }
        } else if (!moveLeftPressed) {
            leftHeld = false
        }

        if (moveRightPressed && !rightHeld) {
            rightHeld = true
            log.warn("CODESTRAFE_CONTROLLER: moveRight()")
            invokeOnEdt { CodeStrafeMoveAction.moveRight() }
        } else if (!moveRightPressed) {
            rightHeld = false
        }

        if (moveUpPressed && !upHeld) {
            upHeld = true
            log.warn("CODESTRAFE_CONTROLLER: moveUp()")
            invokeOnEdt { CodeStrafeMoveAction.moveUp() }
        } else if (!moveUpPressed) {
            upHeld = false
        }

        if (moveDownPressed && !downHeld) {
            downHeld = true
            log.warn("CODESTRAFE_CONTROLLER: moveDown()")
            invokeOnEdt { CodeStrafeMoveAction.moveDown() }
        } else if (!moveDownPressed) {
            downHeld = false
        }

        if (state.a && !aHeld) {
            aHeld = true
            log.warn("CODESTRAFE_CONTROLLER: A -> jump()")
            invokeOnEdt { CodeStrafeMoveAction.jump() }
        } else if (!state.a) {
            aHeld = false
        }

        if (state.b && !bHeld) {
            bHeld = true
            log.warn("CODESTRAFE_CONTROLLER: B -> jumpBack()")
            invokeOnEdt { CodeStrafeMoveAction.jumpBack() }
        } else if (!state.b) {
            bHeld = false
        }

        if (state.x && !xHeld) {
            xHeld = true
            log.warn("CODESTRAFE_CONTROLLER: X -> lineStart()")
            invokeOnEdt { CodeStrafeMoveAction.lineStart() }
        } else if (!state.x) {
            xHeld = false
        }

        if (state.y && !yHeld) {
            yHeld = true
            log.warn("CODESTRAFE_CONTROLLER: Y -> lineEnd()")
            invokeOnEdt { CodeStrafeMoveAction.lineEnd() }
        } else if (!state.y) {
            yHeld = false
        }

        if (state.leftTrigger > TRIGGER_DEADZONE && !ltHeld) {
            ltHeld = true
            log.warn("CODESTRAFE_CONTROLLER: LT -> psiPrevious()")
            invokeOnEdt { CodeStrafeMoveAction.psiPrevious() }
        } else if (state.leftTrigger <= TRIGGER_DEADZONE) {
            ltHeld = false
        }

        if (state.rightTrigger > TRIGGER_DEADZONE && !rtHeld) {
            rtHeld = true
            log.warn("CODESTRAFE_CONTROLLER: RT -> psiNext()")
            invokeOnEdt { CodeStrafeMoveAction.psiNext() }
        } else if (state.rightTrigger <= TRIGGER_DEADZONE) {
            rtHeld = false
        }

        if (state.lb && !panUpHeld) {
            panUpHeld = true
            log.warn("CODESTRAFE_CONTROLLER: panUp()")
            invokeOnEdt { CodeStrafeMoveAction.panUp() }
        } else if (!state.lb) {
            panUpHeld = false
        }

        if (state.rb && !panDownHeld) {
            panDownHeld = true
            log.warn("CODESTRAFE_CONTROLLER: panDown()")
            invokeOnEdt { CodeStrafeMoveAction.panDown() }
        } else if (!state.rb) {
            panDownHeld = false
        }
    }

    private fun controllerInputAllowed(): Boolean {
        val now = System.currentTimeMillis()
        val navEnabled = CodeStrafeState.isNavigationModeEnabled()

        if (navEnabled) {
            lastNavEnabledTime = now
        }

        val allowed = navEnabled || (now - lastNavEnabledTime) <= NAV_MODE_GRACE_MS

        if (lastNavGateState != allowed) {
            lastNavGateState = allowed
            log.warn(
                "CODESTRAFE_CONTROLLER: navEnabled=$navEnabled, allowed=$allowed, " +
                        "lastTrueAgo=${now - lastNavEnabledTime}ms"
            )
        }

        return allowed
    }

    private fun logControllerInput(state: ControllerState) {
        val now = System.currentTimeMillis()
        val active =
            abs(state.leftStickX) > 0.10f ||
                    abs(state.leftStickY) > 0.10f ||
                    abs(state.rightStickX) > 0.10f ||
                    abs(state.rightStickY) > 0.10f ||
                    state.leftTrigger > 0.05f ||
                    state.rightTrigger > 0.05f ||
                    state.a || state.b || state.x || state.y ||
                    state.lb || state.rb ||
                    state.dpadUp || state.dpadDown || state.dpadLeft || state.dpadRight

        if (!active) return
        if (now - lastInputLogTime < 250L) return
        lastInputLogTime = now

        log.warn(
            "CODESTRAFE_CONTROLLER: input " +
                    "lx=${state.leftStickX}, ly=${state.leftStickY}, " +
                    "rx=${state.rightStickX}, ry=${state.rightStickY}, " +
                    "lt=${state.leftTrigger}, rt=${state.rightTrigger}, " +
                    "a=${state.a}, b=${state.b}, x=${state.x}, y=${state.y}, " +
                    "lb=${state.lb}, rb=${state.rb}, " +
                    "du=${state.dpadUp}, dd=${state.dpadDown}, dl=${state.dpadLeft}, dr=${state.dpadRight}"
        )
    }

    private fun firstConnectedState(manager: ControllerManager): ControllerState? {
        for (i in 0 until 8) {
            val state = manager.getState(i)
            if (state.isConnected) return state
        }
        return null
    }

    private fun releaseHeldDirections() {
        leftHeld = false
        rightHeld = false
        upHeld = false
        downHeld = false
        aHeld = false
        bHeld = false
        xHeld = false
        yHeld = false
        ltHeld = false
        rtHeld = false
        panUpHeld = false
        panDownHeld = false
    }

    private fun invokeOnEdt(block: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            block()
        } else {
            app.invokeLater(block)
        }
    }
}