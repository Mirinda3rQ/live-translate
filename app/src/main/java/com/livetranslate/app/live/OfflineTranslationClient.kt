package com.livetranslate.app.live

import android.content.Context
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text translation foundation.
 *
 * The language identifier is bundled with the app. Translation packs are
 * downloaded on demand and then remain available locally for offline use.
 * This class intentionally accepts recognized text; audio transcription is
 * provided by a separate offline ASR engine.
 */
class OfflineTranslationClient(context: Context) {
    private val appContext = context.applicationContext
    private val languageIdentifier = LanguageIdentification.getClient()

    suspend fun detectLanguage(text: String): String? {
        if (text.isBlank()) return null
        return suspendCancellableCoroutine { continuation ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (continuation.isActive) {
                        continuation.resume(code.takeUnless { it == "und" })
                    }
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    /**
     * Downloads the source-to-Thai model the first time it is needed, then
     * translates entirely on device. The model is managed by Google ML Kit.
     */
    suspend fun downloadAndTranslate(text: String, sourceLanguageTag: String? = null): String {
        require(text.isNotBlank()) { "ไม่มีข้อความสำหรับแปล" }
        val detected = sourceLanguageTag ?: detectLanguage(text)
            ?: throw IllegalArgumentException("ตรวจจับภาษาต้นฉบับไม่สำเร็จ")
        val source = TranslateLanguage.fromLanguageTag(detected)
            ?: throw IllegalArgumentException("ยังไม่รองรับภาษาต้นฉบับ: $detected")
        require(source != TranslateLanguage.THAI) { "ข้อความเป็นภาษาไทยอยู่แล้ว" }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
        val translator = Translation.getClient(options)
        try {
            awaitTask(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            return awaitTask(translator.translate(text))
        } finally {
            translator.close()
        }
    }

    fun close() {
        languageIdentifier.close()
    }

    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }.addOnCanceledListener {
                if (continuation.isActive) continuation.cancel()
            }
        }
}
