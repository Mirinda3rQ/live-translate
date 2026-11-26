package com.livetranslate.app.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * HTTP 429 from Groq (per-minute or daily free-tier quota). [retryAfterMs] comes from the
 * `retry-after` header (clamped), so callers can fall back to another provider meanwhile.
 */
class GroqRateLimitException(message: String, val retryAfterMs: Long) : IllegalStateException(message)

/** Invalid/revoked credentials should be skipped in favor of the next configured key. */
class GroqCredentialException(message: String) : IllegalStateException(message)

/** Temporary provider/network failures should fall back for this chunk, then retry Groq later. */
class GroqTransientException(message: String) : java.io.IOException(message)
/**
 * The Groq chat model refused to translate a chunk (content-policy style reply such as
 * "I'm sorry, but I can't help with that"). Never show that text as a subtitle; callers may
 * retry the chunk with another provider.
 */
class GroqRefusalException(message: String) : IllegalStateException(message)

/**
 * Chunked Groq path: Whisper auto-detects the spoken language, then GPT-OSS translates it into Thai.
 * This is near-live (short audio chunks), not the Gemini Live WebSocket protocol.
 */
class GroqTranslationClient {
    private val languageLock = WhisperLanguageLock()

    /** Whisper only: returns the transcript ("" for silence / non-speech). */
    suspend fun transcribePcm(
        pcm16kMono: ByteArray,
        apiKey: String,
        speechModel: String = "whisper-large-v3-turbo",
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Missing Groq API key" }
        require(pcm16kMono.size >= 16_000) { "Not enough audio for transcription" }
        // Ignore near-silent media chunks. Whisper can hallucinate common phrases
        // (for example, polite sign-offs) when given silence or very faint noise.
        if (isNearSilence(pcm16kMono)) return@withContext ""

        val wav = pcm16kToWav(pcm16kMono, 16_000)
        val audioBody = wav.toRequestBody("audio/wav".toMediaType())
        // Pin the language once several chunks agree (see WhisperLanguageLock); null = auto-detect.
        val forcedLanguage = languageLock.nextLanguage()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", speechModel)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .also { b -> forcedLanguage?.let { b.addFormDataPart("language", it) } }
            .addFormDataPart("file", "audio.wav", audioBody)
            .build()
        val transcriptionRequest = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()

        httpClient.newCall(transcriptionRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val error = JSONObject(body).opt("error")
                    if (error is JSONObject) error.optString("message") else error?.toString().orEmpty()
                }.getOrNull().orEmpty()
                if (response.code == 429) throw rateLimited(response, detail)
                if (response.code == 401 || response.code == 403) {
                    throw GroqCredentialException(detail.ifBlank { "Groq API key rejected (HTTP ${response.code})" })
                }
                if (response.code >= 500) throw GroqTransientException(detail.ifBlank { "Groq transcription server error (HTTP ${response.code})" })
                throw IllegalStateException(detail.ifBlank { "Groq transcription failed (HTTP ${response.code})" })
            }
            val result = JSONObject(body)
            val segments = result.optJSONArray("segments")
            // If Whisper marks every returned segment as non-speech, discard it.
            // If the provider omits segment confidence fields, keep the transcript.
            if (segments != null && segments.length() > 0) {
                var confidenceFields = 0
                var likelyNonSpeech = 0
                for (i in 0 until segments.length()) {
                    val segment = segments.optJSONObject(i) ?: continue
                    if (segment.has("no_speech_prob")) {
                        confidenceFields++
                        if (segment.optDouble("no_speech_prob", 0.0) >= 0.60) likelyNonSpeech++
                    }
                }
                if (confidenceFields > 0 && likelyNonSpeech == confidenceFields) return@use ""
            }
            languageLock.record(
                WhisperLanguageNames.toIso(result.optString("language")),
                forced = forcedLanguage != null,
            )
            val text = result.optString("text").trim()
            // Whisper invents sign-offs ("thanks for watching") on silence/breathing: drop them.
            if (WhisperHallucinations.isKnown(text)) "" else text
        }
    }

    /** Forget the pinned Whisper language (call when a new session starts). */
    fun resetLanguageLock() = languageLock.reset()

    suspend fun translatePcmToThai(
        pcm16kMono: ByteArray,
        apiKey: String,
        speechModel: String = "whisper-large-v3-turbo",
        chatModel: String = DEFAULT_CHAT_MODEL,
        systemPrompt: String = "",
    ): String {
        val transcript = transcribePcm(pcm16kMono, apiKey, speechModel)
        if (transcript.isBlank()) return ""
        return translateTranscriptToThai(transcript, apiKey, chatModel, systemPrompt = systemPrompt)
    }

    /**
     * Text translation on Groq. [previousLines] are earlier transcript lines used only as context.
     * GPT-OSS is safety-tuned and may refuse explicit lines; in that case the chunk is retried once on
     * [REFUSAL_FALLBACK_MODEL]. If that also fails for any reason the original refusal is rethrown so
     * the caller's existing Gemini fallback still runs.
     */
    suspend fun translateTranscriptToThai(
        transcript: String,
        apiKey: String,
        chatModel: String = DEFAULT_CHAT_MODEL,
        previousLines: List<String> = emptyList(),
        systemPrompt: String = "",
    ): String {
        val model = effectiveChatModel(chatModel)
        return try {
            translateWithModel(transcript, apiKey, model, previousLines, systemPrompt)
        } catch (refusal: GroqRefusalException) {
            if (model.equals(REFUSAL_FALLBACK_MODEL, ignoreCase = true)) throw refusal
            try {
                translateWithModel(transcript, apiKey, REFUSAL_FALLBACK_MODEL, previousLines, systemPrompt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (ignored: Exception) {
                throw refusal
            }
        }
    }

    private suspend fun translateWithModel(
        transcript: String,
        apiKey: String,
        model: String,
        previousLines: List<String>,
        systemPrompt: String,
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Missing Groq API key" }
        val system = TargetLanguageGuard.thaiOnlySystemPrompt(systemPrompt) +
            "\n\n" + TargetLanguageGuard.TRANSCRIPT_RULES
        val baseUser = userMessage(transcript, previousLines)
        val first = requestChat(apiKey, model, system, baseUser)
        if (first.isNotBlank() && TargetLanguageGuard.accepts(first, "th")) return@withContext first
        // The model answered in another language. Retry once with an explicit reminder;
        // if it still is not Thai, return nothing rather than show a wrong-language subtitle.
        val retry = requestChat(
            apiKey, model, system,
            baseUser + "\n\nIMPORTANT: your previous answer was not Thai. Output ONLY the Thai (ภาษาไทย) translation.",
        )
        // An empty answer for real speech is usually the model's content filter silently
        // declining. Surface it as a refusal so the caller can retry the chunk on Gemini
        // (safety filters off) instead of leaving a hole in the subtitles.
        if (retry.isBlank() && transcript.trim().length >= 8) {
            throw GroqRefusalException("Groq returned an empty translation for speech")
        }
        if (TargetLanguageGuard.accepts(retry, "th")) retry else ""
    }

    private fun requestChat(apiKey: String, model: String, system: String, userText: String): String {
        val chatBody = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", userText)))
            // Reasoning models (GPT-OSS) may spend part of the budget on reasoning, so they
            // get more room; other models only need the Thai subtitle itself. A smaller
            // reservation also keeps us further from the free tier's tokens-per-minute cap.
            .put("max_completion_tokens", maxCompletionTokensFor(model))
            .also { body -> reasoningEffortFor(model)?.let { body.put("reasoning_effort", it) } }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val chatRequest = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(chatBody)
            .build()
        return httpClient.newCall(chatRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                if (response.code == 429) throw rateLimited(response, detail)
                if (response.code == 401 || response.code == 403) {
                    throw GroqCredentialException(detail.ifBlank { "Groq API key rejected (HTTP ${response.code})" })
                }
                if (response.code >= 500) throw GroqTransientException(detail.ifBlank { "Groq translation server error (HTTP ${response.code})" })
                throw IllegalStateException(detail.ifBlank { "Groq translation failed (HTTP ${response.code})" })
            }
            // A blank completion means this audio chunk produced no caption.
            // Do not fail the entire live-caption session for one empty model response.
            val translated = JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")
                ?.let { THINK_BLOCK.replace(it, "") }
                ?.trim()
                .orEmpty()
            if (looksLikeRefusal(translated)) {
                throw GroqRefusalException("Groq model refused this chunk")
            }
            translated
        }
    }

    private fun userMessage(transcript: String, previousLines: List<String>): String = buildString {
        if (previousLines.isNotEmpty()) {
            append("Previous lines (source => Thai already shown; context only, do not translate or repeat them; keep names, pronouns and terminology consistent):\n")
            previousLines.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("Source transcript (follow the system prompt and return only the translation):\n\n")
        append(transcript)
    }

    suspend fun testConnection(apiKey: String, chatModel: String = DEFAULT_CHAT_MODEL): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Missing Groq API key" }
            val model = effectiveChatModel(chatModel)
            val body = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Reply with OK only.")
                ))
                .put("max_completion_tokens", 64)
                .also { body -> reasoningEffortFor(model)?.let { body.put("reasoning_effort", it) } }
                .toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                    if (response.code == 429) throw rateLimited(response, detail)
                    throw IllegalStateException(detail.ifBlank { "Groq test failed (HTTP ${response.code})" })
                }
                "Groq API connected"
            }
        }
    }

    /** Parse `retry-after` (seconds) and clamp so a daily-quota hit is re-probed every 10 min at most. */
    private fun rateLimited(response: Response, detail: String): GroqRateLimitException {
        val seconds = response.header("retry-after")?.trim()?.toDoubleOrNull() ?: 30.0
        val ms = (seconds * 1000).toLong().coerceIn(1_000L, 600_000L)
        return GroqRateLimitException(detail.ifBlank { "Groq rate limit reached (HTTP 429)" }, ms)
    }

    private fun isNearSilence(pcm: ByteArray): Boolean {
        var sumSquares = 0.0
        var peak = 0
        val samples = pcm.size / 2
        if (samples == 0) return true
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            sumSquares += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val rms = kotlin.math.sqrt(sumSquares / samples)
        return rms < 180.0 && peak < 600
    }

    private fun pcm16kToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
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

    internal companion object {
        /** Pseudo chat-model id: Groq transcribes, Gemini translates the text with context. */
        const val HYBRID_GEMINI_MODEL = "gemini-hybrid"
        const val DEFAULT_CHAT_MODEL = "openai/gpt-oss-120b"

        /** Second Groq model tried when GPT-OSS refuses a line (strong Japanese/Chinese; reasoning off). */
        const val REFUSAL_FALLBACK_MODEL = "qwen/qwen3.8-27b"

        /** The hybrid pseudo-model is not a Groq model; when Groq must translate, use the default. */
        fun effectiveChatModel(model: String): String =
            if (model.isBlank() || model == HYBRID_GEMINI_MODEL) DEFAULT_CHAT_MODEL else model

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")

        /**
         * `reasoning_effort` is not a universal parameter: GPT-OSS takes low/medium/high,
         * Qwen3 takes none/default, and models without reasoning (Llama etc.) reject or
         * ignore it. Send only what the selected model understands.
         */
        fun reasoningEffortFor(model: String): String? {
            val id = model.lowercase()
            return when {
                "gpt-oss" in id -> "low"
                "qwen3" in id -> "none"
                else -> null
            }
        }

        private val REFUSAL_PATTERNS = listOf(
            Regex("(?i)^\\s*(i'?m sorry|i am sorry|sorry,|i apologi[sz]e|apologies)"),
            Regex("(?i)\\bi (can'?t|cannot|can not|won'?t|am unable to|'?m unable to) (help|assist|comply|translate|provide|continue|do that)"),
            Regex("(?i)\\b(as an ai|against (my|the) (policy|policies|guidelines))"),
            Regex("ขออภัย.{0,20}(ไม่สามารถ|ช่วย)"),
            Regex("ฉันไม่สามารถ.{0,20}(ช่วย|แปล)"),
        )

        /** Short model replies that are a refusal rather than a translation. */
        fun looksLikeRefusal(text: String): Boolean {
            if (text.isBlank() || text.length > 300) return false
            return REFUSAL_PATTERNS.any { it.containsMatchIn(text) }
        }

        fun maxCompletionTokensFor(model: String): Int =
            if ("gpt-oss" in model.lowercase()) 256 else 192

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
