package com.livetranslate.app.live

import kotlinx.coroutines.Dispatchers
import android.util.Base64
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Gemini Flash translation from text or captured playback audio. */
data class GeminiAudioTranslation(val sourceText: String, val thaiText: String)

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

    suspend fun translatePcm16kAudioToThai(
        pcm16le: ByteArray,
        sourceLanguage: String,
        systemInstructions: String,
        apiKey: String,
    ): GeminiAudioTranslation = withContext(Dispatchers.IO) {
        require(pcm16le.isNotEmpty()) { "Empty captured audio segment" }
        require(apiKey.isNotBlank()) { "Missing API key" }
        val wav = pcm16leToWav(pcm16le)
        val prompt = "Transcribe speech in this audio segment. Source language: $sourceLanguage. " +
            "Translate it into natural Thai. Return only JSON with exactly two string keys: " +
            "Use JSON keys source for the transcript and thai for the Thai translation. No markdown or commentary."
        val baseInstruction = systemInstructions.trim().ifBlank {
            com.livetranslate.app.data.UserSettings.Defaults.GEMINI_FLASH_SYSTEM_INSTRUCTIONS
        }
        val instruction = baseInstruction + "\nReturn source transcript and Thai translation as JSON keys source and thai."
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))),
                ),
            ))
            .put("systemInstruction", JSONObject().put("parts",
                org.json.JSONArray().put(JSONObject().put("text", instruction))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString().toRequestBody(JSON_MEDIA_TYPE)
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
                    detail.ifBlank { "Gemini audio request failed (HTTP " + response.code + ")" },
                )
            }
            val parts = JSONObject(responseText).optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no audio translation")
            val text = buildString {
                for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            val result = runCatching { JSONObject(text) }
                .getOrElse { throw IllegalStateException("Gemini returned invalid audio translation") }
            val source = result.optString("source").trim()
            val thai = result.optString("thai").trim()
            if (source.isBlank() && thai.isBlank()) {
                throw IllegalStateException("Gemini returned empty audio translation")
            }
            GeminiAudioTranslation(source, thai)
        }
    }

    private fun pcm16leToWav(pcm: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(pcm.size + 44)
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun intLE(value: Int) {
            out.write(value and 255); out.write((value ushr 8) and 255)
            out.write((value ushr 16) and 255); out.write((value ushr 24) and 255)
        }
        fun shortLE(value: Int) {
            out.write(value and 255); out.write((value ushr 8) and 255)
        }
        ascii("RIFF"); intLE(pcm.size + 36); ascii("WAVE"); ascii("fmt ")
        intLE(16); shortLE(1); shortLE(1); intLE(16_000); intLE(32_000)
        shortLE(2); shortLE(16); ascii("data"); intLE(pcm.size); out.write(pcm)
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
