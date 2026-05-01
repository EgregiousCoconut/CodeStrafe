package com.codestrafe

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

object CodeStrafeVoiceIntentService {

    private val log = Logger.getInstance(CodeStrafeVoiceIntentService::class.java)

    private fun resolveModel(): String {
        return System.getenv("CODESTRAFE_OLLAMA_MODEL")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "llama3.2"
    }

    fun resolveIntent(project: Project, transcript: String): String {
        val cleaned = transcript.trim()
        if (cleaned.isBlank()) return "UNKNOWN"

        val payload = buildRequestJson(cleaned)
        val responseJson = CodeStrafeOllamaService.chat(project, payload)

        val command = parseCommandFromResponse(responseJson)
        log.warn("CODESTRAFE_VOICE: Ollama intent result='$command'")
        return command.ifBlank { "UNKNOWN" }
    }

    private fun buildRequestJson(transcript: String): String {
        val systemPrompt = """
            You map voice transcripts to one canonical CodeStrafe command.

            Return JSON only in this format:
            {"command":"COMMAND_NAME"}

            Allowed command names:
            TOGGLE_MODE
            MOVE_UP
            MOVE_DOWN
            MOVE_LEFT
            MOVE_RIGHT
            TAB_FORWARD
            TAB_BACK
            NEXT_ERROR
            PREVIOUS_ERROR
            STOP_LISTENING
            UNKNOWN

            Rules:
            - Return exactly one command.
            - If the transcript is ambiguous, return UNKNOWN.
            - "tab", "next target", "next" => TAB_FORWARD
            - "tab back", "back target", "previous target" => TAB_BACK
            - "up" => MOVE_UP
            - "down" => MOVE_DOWN
            - "left" => MOVE_LEFT
            - "right" => MOVE_RIGHT
            - "next error" => NEXT_ERROR
            - "previous error" => PREVIOUS_ERROR
            - "toggle mode" => TOGGLE_MODE
            - "stop listening" => STOP_LISTENING
        """.trimIndent()

        return """
            {
              "model": "${escapeJson(resolveModel())}",
              "stream": false,
              "format": "json",
              "messages": [
                {
                  "role": "system",
                  "content": "${escapeJson(systemPrompt)}"
                },
                {
                  "role": "user",
                  "content": "${escapeJson(transcript)}"
                }
              ]
            }
        """.trimIndent()
    }

    private fun parseCommandFromResponse(json: String): String {
        val contentRegex = Regex(""""content"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val content = contentRegex.find(json)
            ?.groupValues
            ?.get(1)
            ?.let(::unescapeJson)
            .orEmpty()

        val commandRegex = Regex(""""command"\s*:\s*"([A-Z_]+)"""")
        val direct = commandRegex.find(content)?.groupValues?.get(1)
        if (!direct.isNullOrBlank()) return direct

        val fallback = commandRegex.find(json)?.groupValues?.get(1)
        return fallback ?: "UNKNOWN"
    }

    private fun escapeJson(value: String): String {
        return buildString(value.length + 16) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }

    private fun unescapeJson(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}