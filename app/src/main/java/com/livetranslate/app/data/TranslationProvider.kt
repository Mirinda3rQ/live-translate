package com.livetranslate.app.data

enum class TranslationProvider {
    GEMINI,
    GROQ;

    companion object {
        fun fromStorage(value: String?): TranslationProvider =
            entries.firstOrNull { it.name == value } ?: GEMINI
    }
}
