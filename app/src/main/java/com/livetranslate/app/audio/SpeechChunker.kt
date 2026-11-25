package com.livetranslate.app.audio

import java.io.ByteArrayOutputStream

/**
 * Groups 16 kHz mono 16-bit PCM into chunks for transcription, cutting at pauses in the
 * speech instead of every fixed N seconds so words and sentences are not split in half.
 *
 * Inaudible frames are not stored; they only measure how long the current pause lasts.
 * - A pause of [pauseBytes] after at least [minChunkBytes] of speech ends the chunk.
 * - A longer pause ([longPauseBytes]) flushes even a short utterance of [minSpeechBytes] or more.
 * - Speech that never pauses is cut at [maxChunkBytes] so latency stays bounded.
 */
class SpeechChunker(
    private val minSpeechBytes: Int = 16_000,    // 0.5 s: shortest chunk worth transcribing
    private val minChunkBytes: Int = 24_000,     // 0.75 s
    private val pauseBytes: Int = 8_000,         // 0.25 s of silence ends a phrase
    private val longPauseBytes: Int = 16_000,    // 0.5 s of silence flushes short utterances
    private val maxChunkBytes: Int = 64_000,     // 2 s hard cap
) {
    private val buffer = ByteArrayOutputStream()
    private var silentBytes = 0
    private val lock = Any()

    /** Feed one captured frame; returns the chunks (possibly none) that are now complete. */
    fun offer(pcm: ByteArray, audible: Boolean): List<ByteArray> = synchronized(lock) {
        val out = mutableListOf<ByteArray>()
        if (audible) {
            silentBytes = 0
            buffer.write(pcm)
            while (buffer.size() >= maxChunkBytes) {
                val all = buffer.toByteArray()
                out += all.copyOfRange(0, maxChunkBytes)
                buffer.reset()
                buffer.write(all, maxChunkBytes, all.size - maxChunkBytes)
            }
        } else {
            silentBytes += pcm.size
            val size = buffer.size()
            val endOfPhrase = size >= minChunkBytes && silentBytes >= pauseBytes
            val endOfShortUtterance = size >= minSpeechBytes && silentBytes >= longPauseBytes
            if (endOfPhrase || endOfShortUtterance) {
                out += buffer.toByteArray()
                buffer.reset()
            }
        }
        out
    }

    fun reset() = synchronized(lock) {
        buffer.reset()
        silentBytes = 0
    }
}
