package com.livetranslate.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stores provider API keys (encrypted when possible) and supports bounded key rotation.
 * Legacy single-key preferences are migrated lazily and kept in sync for compatibility.
 */
class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val rotateIndex = AtomicInteger(0)
    private val groqRotateIndex = AtomicInteger(0)

    fun getApiKeys(): List<String> = readKeyList(KEY_API_LIST, KEY_API)

    fun setApiKeys(keys: List<String>) {
        val cleaned = cleanKeys(keys, MAX_KEYS)
        writeKeyList(KEY_API_LIST, KEY_API, cleaned)
        rotateIndex.updateAndGet { if (cleaned.isEmpty()) 0 else it.coerceIn(0, cleaned.lastIndex) }
    }

    /** Legacy helpers used by older call sites. */
    fun getApiKey(): String = getApiKeys().firstOrNull().orEmpty()

    fun setApiKey(value: String) {
        val rest = getApiKeys().drop(1)
        setApiKeys(listOf(value.trim()).filter { it.isNotEmpty() } + rest)
    }

    fun hasApiKey(): Boolean = getApiKeys().isNotEmpty()

    /** Groq supports multiple credentials; the old single-key field is migrated automatically. */
    fun getGroqApiKeys(): List<String> = readKeyList(KEY_GROQ_API_LIST, KEY_GROQ_API)

    fun setGroqApiKeys(keys: List<String>) {
        val cleaned = cleanKeys(keys, MAX_GROQ_KEYS)
        writeKeyList(KEY_GROQ_API_LIST, KEY_GROQ_API, cleaned)
        groqRotateIndex.updateAndGet { if (cleaned.isEmpty()) 0 else it.coerceIn(0, cleaned.lastIndex) }
    }

    fun getGroqApiKey(): String = getGroqApiKeys().firstOrNull().orEmpty()

    fun setGroqApiKey(value: String) = setGroqApiKeys(listOf(value))

    fun hasGroqApiKey(): Boolean = getGroqApiKeys().isNotEmpty()

    /** Returns the next configured Groq key in round-robin order. */
    fun nextGroqApiKey(): String {
        val keys = getGroqApiKeys()
        if (keys.isEmpty()) return ""
        val index = groqRotateIndex.getAndUpdate { (it + 1) % keys.size }
        return keys[index % keys.size]
    }

    /** All Groq keys, starting at the selected key, for bounded failover attempts. */
    fun groqKeysFrom(startIndex: Int = 0): List<String> {
        val keys = getGroqApiKeys()
        if (keys.isEmpty()) return emptyList()
        val start = startIndex.coerceIn(0, keys.lastIndex)
        return keys.drop(start) + keys.take(start)
    }

    fun getGroqChatModel(): String = prefs.getString(KEY_GROQ_CHAT_MODEL, DEFAULT_GROQ_CHAT_MODEL).orEmpty()
    fun setGroqChatModel(value: String) {
        prefs.edit().putString(KEY_GROQ_CHAT_MODEL, value).apply()
    }

    fun getGroqSpeechModel(): String = prefs.getString(KEY_GROQ_SPEECH_MODEL, DEFAULT_GROQ_SPEECH_MODEL).orEmpty()
    fun setGroqSpeechModel(value: String) {
        prefs.edit().putString(KEY_GROQ_SPEECH_MODEL, value).apply()
    }

    /** Round-robin pick for a new Gemini session. Empty string if none. */
    fun nextRotatedKey(): String {
        val keys = getApiKeys()
        if (keys.isEmpty()) return ""
        val i = rotateIndex.getAndUpdate { (it + 1) % keys.size }
        return keys[i % keys.size]
    }

    /** Try Gemini keys starting from [startIndex] (inclusive). */
    fun keysFrom(startIndex: Int = 0): List<String> {
        val keys = getApiKeys()
        if (keys.isEmpty()) return emptyList()
        val start = startIndex.coerceIn(0, keys.lastIndex)
        return keys.drop(start) + keys.take(start)
    }

    private fun readKeyList(listKey: String, legacyKey: String): List<String> {
        val raw = prefs.getString(listKey, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val key = array.optString(i).trim()
                        if (key.isNotEmpty()) add(key)
                    }
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        val legacy = prefs.getString(legacyKey, "").orEmpty().trim()
        if (legacy.isNotEmpty()) {
            val migrated = listOf(legacy)
            writeKeyList(listKey, legacyKey, migrated)
            return migrated
        }
        return emptyList()
    }

    private fun writeKeyList(listKey: String, legacyKey: String, keys: List<String>) {
        val array = JSONArray()
        keys.forEach(array::put)
        prefs.edit()
            .putString(listKey, array.toString())
            .putString(legacyKey, keys.firstOrNull().orEmpty())
            .apply()
    }

    private fun cleanKeys(keys: List<String>, max: Int): List<String> =
        keys.map(String::trim).filter(String::isNotEmpty).distinct().take(max)

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME_ENCRYPTED,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "EncryptedSharedPreferences unavailable, using private prefs", t)
            context.getSharedPreferences(FILE_NAME_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "ApiKeyStore"
        private const val FILE_NAME_ENCRYPTED = "secure_api_prefs"
        private const val FILE_NAME_FALLBACK = "api_prefs_fallback"
        private const val KEY_API = "api_key"
        private const val KEY_API_LIST = "api_key_list"
        private const val KEY_GROQ_API = "groq_api_key"
        private const val KEY_GROQ_API_LIST = "groq_api_key_list"
        private const val KEY_GROQ_CHAT_MODEL = "groq_chat_model"
        private const val KEY_GROQ_SPEECH_MODEL = "groq_speech_model"
        const val DEFAULT_GROQ_CHAT_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_GROQ_SPEECH_MODEL = "whisper-large-v3-turbo"
        const val MAX_KEYS = 10
        const val MAX_GROQ_KEYS = 5
    }
}
