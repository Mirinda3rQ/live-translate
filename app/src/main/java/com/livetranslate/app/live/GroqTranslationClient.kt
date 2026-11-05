package com.livetranslate.app.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Chunked Groq path: Whisper auto-detects the spoken language, then GPT-OSS translates it into Thai.
 * This is near-live (short audio chunks), not the Gemini Live WebSocket protocol.
 */
class GroqTranslationClient {
    suspend fun translatePcmToThai(
        pcm16kMono: ByteArray,
        apiKey: String,
        speechModel: String = "whisper-large-v3-turbo",
        chatModel: String = "openai/gpt-oss-120b",
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Missing Groq API key" }
        require(pcm16kMono.size >= 16_000) { "Not enough audio for transcription" }

        val wav = pcm16kToWav(pcm16kMono, 16_000)
        val audioBody = wav.toRequestBody("audio/wav".toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", speechModel)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "audio.wav", audioBody)
            .build()
        val transcriptionRequest = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()

        val transcript = httpClient.newCall(transcriptionRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                throw IllegalStateException(detail.ifBlank { "Groq transcription failed (HTTP ${response.code})" })
            }
            JSONObject(body).optString("text").trim()
        }
        if (transcript.isBlank()) return@withContext ""

        val system = """
            You are a professional subtitle translator working from the detected source language into Thai.
            Translate the source speech into natural Thai, faithfully and completely.
            Preserve the speaker's intent, context, tone, register, slang, insults, swear words,
            profanity, sexual language, and explicit 18+ meaning whenever present in the source.
            Never censor, mask, omit, soften, sanitize, or replace words with asterisks.
            Do not summarize, explain, add warnings, or invent content.
            Keep names, numbers, and sentence meaning accurate. Return only the Thai translation.
        """.trimIndent()
        val chatBody = JSONObject()
            .put("model", chatModel)
            .put("reasoning_effort", "low")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", transcript)))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val chatRequest = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(chatBody)
            .build()
        httpClient.newCall(chatRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(detail.ifBlank { "Groq translation failed (HTTP ${response.code})" })
            }
            JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")?.trim()
                ?.ifBlank { throw IllegalStateException("Groq returned an empty translation") }
                ?: throw IllegalStateException("Groq returned no translation")
        }
    }

    suspend fun testConnection(apiKey: String, chatModel: String = "openai/gpt-oss-120b"): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Missing Groq API key" }
            val body = JSONObject()
                .put("model", chatModel)
                .put("reasoning_effort", "low")
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Reply with OK only.")
                ))
                .put("max_completion_tokens", 64)
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
                    throw IllegalStateException(detail.ifBlank { "Groq test failed (HTTP ${response.code})" })
                }
                "Groq API connected"
            }
        }
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
