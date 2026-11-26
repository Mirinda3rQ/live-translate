package com.livetranslate.app.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetLanguageGuardTest {
    @Test
    fun acceptsPlainThai() {
        assertTrue(TargetLanguageGuard.accepts("ไม่เป็นไรหรอก ฉันอยู่ตรงนี้", "th"))
    }

    @Test
    fun acceptsThaiWithLatinNames() {
        assertTrue(TargetLanguageGuard.accepts("เดี๋ยวโทรหา Yuki นะ", "th"))
    }

    @Test
    fun acceptsThaiWithShortCjkName() {
        assertTrue(TargetLanguageGuard.accepts("คุณยูกิ (由紀) มาแล้วหรือยัง", "th"))
    }

    @Test
    fun rejectsJapaneseWithAStrayThaiLetter() {
        assertFalse(TargetLanguageGuard.accepts("今日はとても気持ちいいです ก", "th"))
    }

    @Test
    fun rejectsPureCjkAndLongEnglish() {
        assertFalse(TargetLanguageGuard.accepts("今日はいい天気ですね", "th"))
        assertFalse(TargetLanguageGuard.accepts("I will be there in five minutes", "th"))
    }

    @Test
    fun otherTargetsAreNotChecked() {
        assertTrue(TargetLanguageGuard.accepts("anything", "en"))
    }
}
