package com.livetranslate.app.data

enum class TranslationProvider {
    GEMINI,
    GROQ,
    GLM;

    companion object {
        fun fromStorage(value: String?): TranslationProvider =
            // GLM is retired (too slow): a stored GLM choice falls back to the Groq pipeline.
            if (value == "GLM") GROQ else entries.firstOrNull { it.name == value } ?: GEMINI
    }
}
