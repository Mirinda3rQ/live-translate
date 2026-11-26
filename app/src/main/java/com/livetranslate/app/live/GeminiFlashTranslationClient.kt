package com.livetranslate.app.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Text-only Gemini 3.8 Flash path used by the optional on-device Japanese ASR mode. */
class GeminiFlashTranslationClient {
    suspend fun translateJapaneseToThai(
        transcript: String,
        systemInstructions: String,
        apiKey: String,
    ): String =
        withContext(Dispatchers.IO) {
            require(transcript.isNotBlank()) { "Empty Japanese transcript" }
            require(apiKey.isNotBlank()) { "Missing API key" }

            val prompt = transcript.trim()
            val body = JSONObject()
                .put("contents", org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt))),
                ))
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        org.json.JSONArray().put(
                            JSONObject().put("text", thaiOnlySystemInstruction(systemInstructions)),
                        ),
                    ),
                )
                .put("safetySettings", safetySettings())
                .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    throw IllegalStateException(
                        detail.ifBlank { "Gemini text request failed (HTTP ${response.code})" },
                    )
                }
                val root = JSONObject(responseText)
                val candidates = root.optJSONArray("candidates")
                val parts = candidates?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IllegalStateException("Gemini returned no translation text")
                val translated = buildString {
                    for (i in 0 until parts.length()) {
                        append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }.trim()
                translated.takeIf { it.isNotBlank() }?.let(::rejectModelRefusal)
                    ?: throw IllegalStateException("Gemini returned an empty translation")
            }
        }

    /**
     * Hybrid path: translate an already-transcribed line (from Groq Whisper) into Thai.
     * [previousLines] are earlier source lines, sent only as context for meaning and for sentences
     * that were cut at the audio chunk boundary.
     */
    suspend fun translateTranscriptToThai(
        transcript: String,
        previousLines: List<String>,
        apiKey: String,
        systemInstructions: String = com.livetranslate.app.data.UserSettings.Defaults.DEFAULT_SYSTEM_PROMPT,
    ): String = withContext(Dispatchers.IO) {
        require(transcript.isNotBlank()) { "Empty transcript" }
        require(apiKey.isNotBlank()) { "Missing API key" }
        val userText = buildString {
            if (previousLines.isNotEmpty()) {
                append("Previous lines of the same video, shown as: source => Thai translation already shown to the viewer. ")
                append("Context only: never translate or repeat them. Keep names, pronouns, speaker gender, tone and terminology consistent with the Thai already used, ")
                append("and if the new line continues an unfinished sentence, translate it as a continuation.\n")
                previousLines.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("New transcript line (follow the system prompt):\n")
            append(transcript.trim())
        }
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", userText))),
            ))
            .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(
                JSONObject().put(
                    "text",
                    thaiOnlySystemInstruction(systemInstructions) + "\n\n" + TargetLanguageGuard.TRANSCRIPT_RULES,
                ),
            )))
            .put("safetySettings", safetySettings())
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(detail.ifBlank {
                    "Gemini text request failed (HTTP " + response.code + ")"
                })
            }
            val root = JSONObject(responseText)
            val outputParts = root.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no translation" + emptyReason(root))
            val text = buildString {
                for (i in 0 until outputParts.length()) append(outputParts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            if (text.isBlank()) throw IllegalStateException("Gemini returned an empty translation" + emptyReason(root))
            rejectModelRefusal(text)
        }
    }

    /** Translate captured playback audio directly into Thai subtitle text. */
    suspend fun translateAudioToThai(
        pcm16kMono: ByteArray,
        systemInstructions: String,
        apiKey: String,
    ): String = withContext(Dispatchers.IO) {
        require(pcm16kMono.size >= 16_000) { "Not enough media audio" }
        require(apiKey.isNotBlank()) { "Missing API key" }
        val wav = pcm16kToWav(pcm16kMono, 16_000)
        val parts = org.json.JSONArray()
            .put(JSONObject().put("text", "The audio may contain speech in ANY language, including English, Japanese, Chinese, Korean, or mixed languages. Identify the language from the audio yourself, understand the spoken words, and translate them into natural Thai. Never limit translation to Japanese or Chinese. Return only the Thai subtitle translation; if there is no intelligible speech, return an empty string."))
            .put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "audio/wav")
                .put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))))
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("parts", parts)))
            .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(
                JSONObject().put("text", thaiOnlySystemInstruction(systemInstructions)),
            )))
            .put("safetySettings", safetySettings())
            .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body).build()
        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(detail.ifBlank {
                    "Gemini audio request failed (HTTP " + response.code + ")"
                })
            }
            val root = JSONObject(responseText)
            val outputParts = root.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no subtitle text" + emptyReason(root))
            buildString {
                for (i in 0 until outputParts.length()) append(outputParts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim().ifBlank { throw IllegalStateException("Gemini returned empty subtitles") }.let(::rejectModelRefusal)
        }
    }

    /**
     * Subtitles must not be filtered: Gemini's default thresholds can silently drop
     * profanity or sexual language. BLOCK_NONE is the loosest setting the API allows
     * (some content, e.g. child-safety, is always blocked regardless).
     */
    /**
     * Keep custom instructions, but put a non-overridable Thai output contract above them.
     * This also protects quota failover from stale saved Japanese/Chinese-only prompts.
     */
    private fun thaiOnlySystemInstruction(userInstructions: String): String {
        val userPrompt = userInstructions.trim().ifBlank {
            com.livetranslate.app.data.UserSettings.Defaults.DEFAULT_SYSTEM_PROMPT
        }
        return """
            MANDATORY TRANSLATION CONTRACT — HIGHEST PRIORITY:
            Detect the source language automatically. Accept ANY source language, including English,
            Japanese, Chinese, Korean, and mixed-language speech. Translate all intelligible content
            into natural, accurate Thai (ภาษาไทย). Output Thai subtitles only; never answer in English
            or another language. Never claim that you can only translate Japanese or Chinese.
            Never apologize, refuse, explain limitations, or add commentary. If a segment is genuinely
            unintelligible, return an empty string rather than inventing speech.
            Preserve meaning, names, numbers, tone, slang, profanity, and explicit adult dialogue.

            ADDITIONAL USER TRANSLATION INSTRUCTIONS:
            $userPrompt
        """.trimIndent()
    }

    /** Never display a model refusal as if it were a translated subtitle. */
    private fun rejectModelRefusal(text: String): String {
        val refusal = Regex("(?i)\\b(i['’]?m sorry|i am sorry|i can only translate|i can['’]?t fulfill|i cannot fulfill|i cannot help with that request|as an ai language model)\\b")
        if (refusal.containsMatchIn(text)) {
            throw IllegalStateException("Gemini returned a refusal instead of Thai subtitles; this segment was not displayed.")
        }
        return text
    }
    private fun safetySettings(): org.json.JSONArray = org.json.JSONArray().apply {
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        ).forEach { put(JSONObject().put("category", it).put("threshold", "BLOCK_NONE")) }
    }

    /** " (reason)" when Gemini reports why a response is empty (blockReason / finishReason), else "". */
    private fun emptyReason(root: JSONObject): String {
        val block = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        val finish = root.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
        val reason = listOf(block, finish).filter { it.isNotBlank() }.joinToString("/")
        return if (reason.isBlank()) "" else " ($reason)"
    }

    private fun pcm16kToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        fun ascii(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        fun le(value: Int) {
            out.write(value and 255); out.write((value shr 8) and 255)
            out.write((value shr 16) and 255); out.write((value shr 24) and 255)
        }
        ascii("RIFF"); le(36 + pcm.size); ascii("WAVE"); ascii("fmt "); le(16)
        out.write(1); out.write(0); out.write(1); out.write(0); le(sampleRate); le(sampleRate * 2)
        out.write(2); out.write(0); out.write(16); out.write(0); ascii("data"); le(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}
