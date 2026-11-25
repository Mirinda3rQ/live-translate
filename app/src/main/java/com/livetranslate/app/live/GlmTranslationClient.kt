package com.livetranslate.app.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OpenAI-compatible OneAI GLM text translator. Audio transcription remains on Groq Whisper. */
class GlmTranslationClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val modelId: String = DEFAULT_MODEL_ID,
) {
    suspend fun translateTranscriptToThai(
        transcript: String,
        apiKey: String,
        previousLines: List<String> = emptyList(),
        systemPrompt: String = "",
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Missing GLM API key" }
        require(transcript.isNotBlank()) { "Empty transcript" }

        val userMessage = buildString {
            if (previousLines.isNotEmpty()) {
                append("Previous source lines (context only; do not repeat):\n")
                previousLines.takeLast(4).forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("Translate this source transcript into Thai. Return only the Thai translation:\n\n")
            append(transcript)
        }
        val body = JSONObject()
            .put("model", modelId)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system")
                    .put("content", TargetLanguageGuard.thaiOnlySystemPrompt(systemPrompt)))
                .put(JSONObject().put("role", "user").put("content", userMessage)))
            .put("temperature", 0.1)
            .put("max_tokens", 512)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message")
                        ?: JSONObject(responseBody).optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    detail.ifBlank { "GLM request failed (HTTP ${response.code})" },
                )
            }
            val message = JSONObject(responseBody).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("GLM response has no message")
            val raw = when (val value = message.opt("content")) {
                is String -> value
                is JSONArray -> buildString {
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i)
                        append(item?.optString("text").orEmpty())
                    }
                }
                else -> ""
            }.trim()
            if (looksLikeRefusal(raw)) {
                throw IllegalStateException("GLM returned a refusal instead of a translation")
            }
            val translated = TargetLanguageGuard.stripPlaceholders(raw)
            if (translated.isBlank()) return@withContext ""
            if (!TargetLanguageGuard.accepts(translated, "th")) {
                throw IllegalStateException("GLM output was not Thai; rejected to protect subtitle language")
            }
            translated
        }
    }

    suspend fun testConnection(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Missing GLM API key" }
            val body = JSONObject()
                .put("model", modelId)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Reply with OK only."),
                ))
                .put("max_tokens", 16)
                .toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    throw IllegalStateException(detail.ifBlank { "GLM test failed (HTTP ${response.code})" })
                }
                "GLM API connected"
            }
        }
    }

    private fun looksLikeRefusal(text: String): Boolean =
        REFUSAL_PATTERNS.any { it.containsMatchIn(text) }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.oneaiapikey.com/v1"
        const val DEFAULT_MODEL_ID = "dealignai-glm-5.3-flash-uncensored-fp8:free"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val REFUSAL_PATTERNS = listOf(
            Regex("(?i)^\\s*(i'?m sorry|i am sorry|sorry,|i apologize|i cannot|i can't)"),
            Regex("(?i)\\b(as an ai|unable to assist|cannot help with)"),
            Regex("ขออภัย.{0,30}(ไม่สามารถ|ช่วย)"),
        )
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
