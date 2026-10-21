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
                            JSONObject().put("text", systemInstructions.trim().ifBlank {
                                com.livetranslate.app.data.UserSettings.Defaults.GEMINI_FLASH_SYSTEM_INSTRUCTIONS
                            }),
                        ),
                    ),
                )
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
                translated.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Gemini returned an empty translation")
            }
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
