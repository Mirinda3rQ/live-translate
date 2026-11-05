package com.livetranslate.app.live

import android.content.Context
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
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
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translatorLock = Any()
    private val translators = mutableMapOf<String, Translator>()

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

    suspend fun prepareLanguageModel(sourceLanguageTag: String) {
        val source = TranslateLanguage.fromLanguageTag(sourceLanguageTag) ?: return
        if (source == TranslateLanguage.THAI) return
        val translator = translatorFor(sourceLanguageTag, source)
        awaitTask(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
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

        val translator = translatorFor(detected, source)
        awaitTask(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        return awaitTask(translator.translate(text))
    }

    fun close() {
        languageIdentifier.close()
        synchronized(translatorLock) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
    }

    /**
     * Reuse one ML Kit translator per detected source language for the whole
     * subtitle session. Recreating it for every four-second audio chunk adds
     * avoidable setup/model checks to the critical path and increases subtitle
     * lag. Translation options and source-to-Thai behavior remain unchanged.
     */
    private fun translatorFor(languageTag: String, source: String): Translator =
        synchronized(translatorLock) {
            translators.getOrPut(languageTag) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(TranslateLanguage.THAI)
                        .build(),
                )
            }
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
