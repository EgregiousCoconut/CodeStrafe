package com.codestrafe

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

object CodeStrafeOllamaService {

    private val log = Logger.getInstance(CodeStrafeOllamaService::class.java)

    private const val BASE_URL = "http://localhost:11434"
    private const val TAGS_URL = "$BASE_URL/api/tags"
    private const val CHAT_URL = "$BASE_URL/api/chat"

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    private val starting = AtomicBoolean(false)

    @Volatile
    private var managedProcess: Process? = null

    fun ensureRunning(project: Project): Boolean {
        if (isRunning()) {
            log.warn("CODESTRAFE_OLLAMA: Ollama is already running")
            return true
        }

        if (!starting.compareAndSet(false, true)) {
            log.warn("CODESTRAFE_OLLAMA: Ollama start already in progress")
            return waitUntilReady(project)
        }

        try {
            val executable = findOllamaExecutable()
            if (executable == null) {
                notify(
                    project,
                    "CodeStrafe Ollama",
                    "Could not find the Ollama executable. Install Ollama or add it to PATH.",
                    NotificationType.ERROR
                )
                return false
            }

            log.warn("CODESTRAFE_OLLAMA: starting Ollama using $executable")

            val processBuilder = ProcessBuilder(executable, "serve")
            processBuilder.redirectErrorStream(true)

            val env = processBuilder.environment()
            env["OLLAMA_HOST"] = "127.0.0.1:11434"

            managedProcess = processBuilder.start()

            notify(
                project,
                "CodeStrafe Ollama",
                "Starting Ollama in the background..."
            )

            return waitUntilReady(project)
        } catch (t: Throwable) {
            log.warn("CODESTRAFE_OLLAMA: failed to start Ollama", t)
            notify(
                project,
                "CodeStrafe Ollama",
                "Failed to start Ollama: ${t.message ?: "unknown error"}",
                NotificationType.ERROR
            )
            return false
        } finally {
            starting.set(false)
        }
    }

    fun chat(project: Project, requestJson: String): String {
        if (!ensureRunning(project)) {
            throw IllegalStateException("Ollama is not running and could not be started.")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(CHAT_URL))
            .timeout(Duration.ofSeconds(45))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Ollama returned HTTP ${response.statusCode()}: ${response.body()}")
        }

        return response.body()
    }

    fun isRunning(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(TAGS_URL))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() in 200..299
        } catch (_: ConnectException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun stopManagedProcess() {
        val process = managedProcess ?: return
        try {
            process.destroy()
        } catch (_: Throwable) {
        } finally {
            managedProcess = null
        }
    }

    private fun waitUntilReady(project: Project): Boolean {
        val deadline = System.currentTimeMillis() + 20_000L

        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                log.warn("CODESTRAFE_OLLAMA: Ollama is ready")
                notify(
                    project,
                    "CodeStrafe Ollama",
                    "Ollama is ready."
                )
                return true
            }

            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        notify(
            project,
            "CodeStrafe Ollama",
            "Timed out waiting for Ollama to start.",
            NotificationType.ERROR
        )
        return false
    }

    private fun findOllamaExecutable(): String? {
        val envOverride = System.getenv("CODESTRAFE_OLLAMA_BIN")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (envOverride != null && File(envOverride).canExecute()) {
            return envOverride
        }

        val candidates = listOf(
            "/opt/homebrew/bin/ollama",
            "/usr/local/bin/ollama",
            "/Applications/Ollama.app/Contents/Resources/ollama",
            "ollama"
        )

        for (candidate in candidates) {
            if (candidate == "ollama") {
                if (canRunOllamaFromPath()) {
                    return candidate
                }
            } else {
                val file = File(candidate)
                if (file.exists() && file.canExecute()) {
                    return candidate
                }
            }
        }

        return null
    }

    private fun canRunOllamaFromPath(): Boolean {
        return try {
            val process = ProcessBuilder("ollama", "--version")
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeStrafe Notifications")
                .createNotification(title, content, type)
                .notify(project)
        } catch (_: Throwable) {
            log.warn("CODESTRAFE_OLLAMA: notification fallback -> $title: $content")
        }
    }
}