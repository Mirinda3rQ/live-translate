package com.livetranslate.app.live

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class OfflineWhisperClient(context: Context) {
    private val appContext = context.applicationContext
    private val modelFile = File(File(appContext.filesDir, "models"), MODEL_NAME)
    private var loadedModel: WhisperModel? = null

    suspend fun prepareModel() = withContext(Dispatchers.IO) {
        if (modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES) return@withContext
        modelFile.parentFile?.mkdirs()
        val partial = File(modelFile.parentFile, "\${MODEL_NAME}.download")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "ดาวน์โหลดโมเดลเสียงไม่สำเร็จ (\${connection.responseCode})"
            }
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(partial.length() > MIN_MODEL_BYTES) { "ไฟล์โมเดลเสียงไม่สมบูรณ์" }
            if (modelFile.exists()) modelFile.delete()
            check(partial.renameTo(modelFile)) { "บันทึกโมเดลเสียงไม่สำเร็จ" }
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    suspend fun loadModel() {
        if (loadedModel != null) return
        check(modelFile.exists() && modelFile.length() > MIN_MODEL_BYTES) {
            "ยังไม่มีโมเดลเสียงออฟไลน์ กรุณาเชื่อมต่ออินเทอร์เน็ตเพื่อดาวน์โหลดก่อน"
        }
        loadedModel = Whisper.loadModel(appContext, modelFile.absolutePath)
    }

    suspend fun transcribe(pcm16kMono: ByteArray): String = withContext(Dispatchers.IO) {
        require(pcm16kMono.size >= MIN_AUDIO_BYTES) { "เสียงสั้นเกินไป" }
        val model = loadedModel ?: error("Whisper model is not loaded")
        val wav = File(appContext.cacheDir, "offline-audio-\${System.nanoTime()}.wav")
        try {
            writePcmWav(wav, pcm16kMono)
            Whisper.transcribe(model, wav.absolutePath, WhisperConfig()).text.trim()
        } finally {
            wav.delete()
        }
    }

    fun close() {
        loadedModel?.let(Whisper::releaseModel)
        loadedModel = null
    }

    private fun writePcmWav(file: File, pcm: ByteArray) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeBytes("RIFF")
            writeLeInt(out, 36 + pcm.size)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLeInt(out, 16)
            writeLeShort(out, 1)
            writeLeShort(out, 1)
            writeLeInt(out, SAMPLE_RATE)
            writeLeInt(out, SAMPLE_RATE * 2)
            writeLeShort(out, 2)
            writeLeShort(out, 16)
            out.writeBytes("data")
            writeLeInt(out, pcm.size)
            out.write(pcm)
        }
    }

    private fun writeLeInt(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff)
        out.writeByte((value ushr 8) and 0xff)
        out.writeByte((value ushr 16) and 0xff)
        out.writeByte((value ushr 24) and 0xff)
    }

    private fun writeLeShort(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff)
        out.writeByte((value ushr 8) and 0xff)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_NAME = "ggml-base.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
        private const val MIN_MODEL_BYTES = 100_000_000L
        private const val MIN_AUDIO_BYTES = SAMPLE_RATE * 2
    }
}
