package com.codestrafe

import com.google.cloud.speech.v1.RecognitionAudio
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.RecognizeResponse
import com.google.cloud.speech.v1.SpeechClient
import com.google.protobuf.ByteString
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

object CodeStrafeVoiceService {

    private val log = Logger.getInstance(CodeStrafeVoiceService::class.java)

    private const val SAMPLE_RATE = 16_000f
    private const val BUFFER_SIZE = 4096

    private val recording = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CodeStrafe-GoogleVoice").apply { isDaemon = true }
    }

    @Volatile
    private var worker: Future<*>? = null

    @Volatile
    private var currentLine: TargetDataLine? = null

    @Volatile
    private var currentBuffer: ByteArrayOutputStream? = null

    fun startListening(project: Project) {
        log.warn("CODESTRAFE_VOICE: startListening() called")

        if (!recording.compareAndSet(false, true)) {
            log.warn("CODESTRAFE_VOICE: already recording")
            notify(project, "CodeStrafe Voice", "Voice recording is already running.")
            return
        }

        val format = AudioFormat(
            SAMPLE_RATE,
            16,
            1,
            true,
            false
        )

        val info = DataLine.Info(TargetDataLine::class.java, format)

        if (!AudioSystem.isLineSupported(info)) {
            recording.set(false)
            log.warn("CODESTRAFE_VOICE: no compatible microphone input line was found")
            notify(
                project,
                "CodeStrafe Voice",
                "No compatible microphone input line was found.",
                NotificationType.ERROR
            )
            return
        }

        try {
            log.warn("CODESTRAFE_VOICE: opening microphone line")

            val line = AudioSystem.getLine(info) as TargetDataLine
            line.open(format)
            line.start()

            log.warn("CODESTRAFE_VOICE: microphone line opened and started")

            currentLine = line
            currentBuffer = ByteArrayOutputStream()

            notify(
                project,
                "CodeStrafe Voice",
                "Recording started. Trigger Toggle CodeStrafe Voice again to stop and process."
            )

            worker = executor.submit {
                val localBuffer = ByteArray(BUFFER_SIZE)
                val output = currentBuffer ?: return@submit

                try {
                    log.warn("CODESTRAFE_VOICE: microphone capture loop started")

                    while (recording.get()) {
                        val count = line.read(localBuffer, 0, localBuffer.size)

                        if (count > 0) {
                            output.write(localBuffer, 0, count)
                        }
                    }
                } catch (t: Throwable) {
                    log.warn("CODESTRAFE_VOICE: microphone capture failed", t)
                } finally {
                    try {
                        line.stop()
                        line.close()
                    } catch (_: Throwable) {
                    }

                    log.warn("CODESTRAFE_VOICE: microphone capture loop ended")
                }
            }
        } catch (t: Throwable) {
            recording.set(false)
            currentLine = null
            currentBuffer = null

            log.warn("CODESTRAFE_VOICE: failed to open microphone", t)

            notify(
                project,
                "CodeStrafe Voice",
                "Failed to open microphone: ${t.message ?: "unknown error"}",
                NotificationType.ERROR
            )
        }
    }

    fun stopListening(project: Project) {
        log.warn("CODESTRAFE_VOICE: stopListening() called")

        if (!recording.compareAndSet(true, false)) {
            log.warn("CODESTRAFE_VOICE: stop requested but recording was not active")
            notify(project, "CodeStrafe Voice", "Voice recording is not running.")
            return
        }

        try {
            currentLine?.stop()
            currentLine?.close()
        } catch (_: Throwable) {
        }

        try {
            worker?.get()
        } catch (_: Throwable) {
        }

        val pcmBytes = currentBuffer?.toByteArray() ?: ByteArray(0)

        worker = null
        currentLine = null
        currentBuffer = null

        if (pcmBytes.isEmpty()) {
            notify(
                project,
                "CodeStrafe Voice",
                "No audio was captured.",
                NotificationType.WARNING
            )
            return
        }

        notify(project, "CodeStrafe Voice", "Recording stopped. Transcribing...")

        executor.submit {
            try {
                val transcript = transcribeWithGoogle(pcmBytes)

                log.warn("CODESTRAFE_VOICE: transcript='$transcript'")

                if (transcript.isBlank()) {
                    notify(
                        project,
                        "CodeStrafe Voice",
                        "No speech was recognized.",
                        NotificationType.WARNING
                    )
                    return@submit
                }

                val canonicalCommand = CodeStrafeVoiceIntentService.resolveIntent(project, transcript)

                log.warn("CODESTRAFE_VOICE: canonicalCommand='$canonicalCommand'")

                CodeStrafeVoiceCommandRouter.route(project, canonicalCommand, transcript)
            } catch (t: Throwable) {
                log.warn("CODESTRAFE_VOICE: transcription/intent pipeline failed", t)

                notify(
                    project,
                    "CodeStrafe Voice",
                    "Voice pipeline failed: ${t.message ?: "unknown error"}",
                    NotificationType.ERROR
                )
            }
        }
    }

    fun isListening(): Boolean {
        return recording.get()
    }

    private fun transcribeWithGoogle(pcmBytes: ByteArray): String {
        SpeechClient.create().use { speechClient: SpeechClient ->
            val config = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                .setSampleRateHertz(SAMPLE_RATE.toInt())
                .setLanguageCode("en-US")
                .build()

            val audio = RecognitionAudio.newBuilder()
                .setContent(ByteString.copyFrom(pcmBytes))
                .build()

            val response: RecognizeResponse = speechClient.recognize(config, audio)

            return response.resultsList
                .mapNotNull { result ->
                    result.alternativesList.firstOrNull()?.transcript
                }
                .joinToString(" ")
                .trim()
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
            log.warn("CODESTRAFE_VOICE: notification fallback -> $title: $content")
        }
    }
}