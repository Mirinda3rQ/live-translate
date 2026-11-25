package com.livetranslate.app.data

enum class TranslationProvider {
    GEMINI,
    GROQ,
    GLM;

    companion object {
        fun fromStorage(value: String?): TranslationProvider =
            entries.firstOrNull { it.name == value } ?: GEMINI
    }
}
