package com.livetranslate.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetranslate.app.BuildConfig
import com.livetranslate.app.R
import com.livetranslate.app.data.ApiKeyStore
import com.livetranslate.app.data.TranslationProvider
import com.livetranslate.app.ui.components.PageTitle
import com.livetranslate.app.ui.components.SectionCard
import com.livetranslate.app.ui.components.SettingSwitchRow
import com.livetranslate.app.ui.components.StatusPill
import com.livetranslate.app.ui.components.StatusTone
import com.livetranslate.app.ui.theme.Booth
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
    onOpenOverlayPermission: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apiKeyFields by viewModel.apiKeyFields.collectAsStateWithLifecycle()
    val groqApiKey by viewModel.groqApiKey.collectAsStateWithLifecycle()
    val groqApiKeyFields by viewModel.groqApiKeyFields.collectAsStateWithLifecycle()
    val groqChatModel by viewModel.groqChatModel.collectAsStateWithLifecycle()
    val groqSpeechModel by viewModel.groqSpeechModel.collectAsStateWithLifecycle()
    val glmApiKey by viewModel.glmApiKey.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()

    var endpointField by remember {
        mutableStateOf(TextFieldValue(settings.endpoint, TextRange(settings.endpoint.length)))
    }
    var modelField by remember {
        mutableStateOf(TextFieldValue(settings.modelId, TextRange(settings.modelId.length)))
    }
    var revealKey by remember { mutableStateOf(false) }
    var revealGroqKey by remember { mutableStateOf(false) }
    var revealGlmKey by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var customPromptDraft by remember { mutableStateOf(settings.customPrompt) }
    LaunchedEffect(settings.customPrompt) { customPromptDraft = settings.customPrompt }

    var endpointHydrated by remember { mutableStateOf(false) }
    var modelHydrated by remember { mutableStateOf(false) }
    LaunchedEffect(settings.endpoint) {
        if (!endpointHydrated && settings.endpoint.isNotBlank()) {
            endpointField = TextFieldValue(settings.endpoint, TextRange(settings.endpoint.length))
            endpointHydrated = true
        }
    }
    LaunchedEffect(settings.modelId) {
        if (!modelHydrated && settings.modelId.isNotBlank()) {
            modelField = TextFieldValue(settings.modelId, TextRange(settings.modelId.length))
            modelHydrated = true
        }
    }

    val fieldColors = appFieldColors()
    val isGroq = settings.translationProvider != TranslationProvider.GEMINI
    val hasGeminiKey = apiKeyFields.any { it.text.isNotBlank() }
    val hasGroqKey = groqApiKeyFields.any { it.text.isNotBlank() }
    val hasGlmKey = glmApiKey.isNotBlank()
    val hybridGeminiSelected = settings.translationProvider == TranslationProvider.GROQ &&
        groqChatModel == "gemini-hybrid"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
        )

        // ---------------------------------------------------------------- Provider & models
        CategoryHeader("AI Provider & Models", "เลือกผู้ให้บริการและรูปแบบการประมวลผลเสียง")
        SectionTitle("ผู้ให้บริการแปลภาษา")
        SectionCard {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChoiceTile(
                            title = "Gemini Live",
                            description = "แปลสดต่อเนื่อง",
                            selected = settings.translationProvider == TranslationProvider.GEMINI,
                            modifier = Modifier.weight(1f),
                        ) {
                            viewModel.clearTestResult()
                            viewModel.update { it.copy(translationProvider = TranslationProvider.GEMINI) }
                        }
                        ChoiceTile(
                            title = "Groq",
                            description = "Whisper + Groq Chat",
                            selected = settings.translationProvider == TranslationProvider.GROQ,
                            modifier = Modifier.weight(1f),
                        ) {
                            viewModel.clearTestResult()
                            viewModel.update { it.copy(translationProvider = TranslationProvider.GROQ) }
                        }
                    }
                }
                val ready = when (settings.translationProvider) {
                    TranslationProvider.GEMINI -> hasGeminiKey
                    TranslationProvider.GROQ -> hasGroqKey && (!hybridGeminiSelected || hasGeminiKey)
                    TranslationProvider.GLM -> hasGroqKey && hasGlmKey
                }
                StatusPill(
                    label = when (settings.translationProvider) {
                        TranslationProvider.GEMINI -> if (ready) "พร้อมใช้งาน · มี Gemini key แล้ว" else "ยังไม่ได้ใส่ Gemini API key"
                        TranslationProvider.GROQ -> when {
                            !hasGroqKey -> "ยังไม่ได้ใส่ Groq API key สำหรับ Whisper"
                            hybridGeminiSelected && !hasGeminiKey -> "โหมด Gemini Hybrid ต้องเพิ่ม Gemini API Key"
                            hybridGeminiSelected -> "พร้อมใช้งาน · Groq Whisper + Gemini Flash"
                            else -> "พร้อมใช้งาน · Groq Whisper + Groq Chat"
                        }
                        TranslationProvider.GLM -> when {
                            ready -> "พร้อมใช้งาน · มี Groq และ GLM keys แล้ว"
                            !hasGroqKey -> "ยังไม่ได้ใส่ Groq API key สำหรับ Whisper"
                            else -> "ยังไม่ได้ใส่ GLM API key"
                        }
                    },
                    tone = if (ready) StatusTone.Ok else StatusTone.Warn,
                )
                if (settings.translationProvider == TranslationProvider.GROQ) {
                    Hint(
                        if (hybridGeminiSelected) {
                            "โหมดนี้ใช้ Groq Whisper ถอดเสียง แล้วส่งข้อความให้ Gemini Flash แปลเป็นไทยโดยตรง ต้องมีทั้ง Groq และ Gemini API Key"
                        } else if (hasGeminiKey) {
                            "มี Gemini เป็นตัวสำรอง จะสลับให้อัตโนมัติเมื่อ Groq เต็มโควตา หรือโมเดลปฏิเสธเนื้อหา"
                        } else {
                            "เพิ่ม Gemini API Key เพื่อเปิดใช้ Gemini เป็นตัวสำรองอัตโนมัติได้"
                        },
                    )
                }
            }
        }

        // ---------------------------------------------------------------- API key manager
        CategoryHeader("API Key Manager", "จัดการคีย์หลัก คีย์สำรอง และทดสอบการเชื่อมต่อ")
        SectionTitle(if (isGroq) "Groq API Keys · สูงสุด 5 รายการ" else stringResource(R.string.settings_api))
        SectionCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isGroq) {
                    Hint("รับเฉพาะเสียงจากวิดีโอหรือสื่อที่กำลังเล่น ไม่ใช้ไมโครโฟน • แปลคำหยาบและเนื้อหา 18+ ตามบริบทของต้นฉบับ ไม่เซ็นเซอร์")
                    Hint("เพิ่ม Groq API Key ได้สูงสุด 5 รายการ ระบบจะเก็บคีย์ไว้ในพื้นที่จัดเก็บแบบเข้ารหัส และลองคีย์สำรองเมื่อคีย์หลักถูกจำกัดหรือใช้ไม่ได้ ทั้งนี้คีย์ใน Project เดียวกันอาจใช้โควต้าร่วมกัน")
                    GroqKeyFields(
                        apiKeyFields = groqApiKeyFields,
                        reveal = revealGroqKey,
                        viewModel = viewModel,
                        fieldColors = fieldColors,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = if (revealGroqKey) "ซ่อน API Keys" else "แสดง API Keys",
                            onClick = { revealGroqKey = !revealGroqKey },
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.saveGroqApiKey()
                            viewModel.testGroqConnection()
                        },
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = if (testing) "กำลังทดสอบ..." else "บันทึกและทดสอบ Groq",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Hint(stringResource(R.string.settings_api_desc))
                    Hint(stringResource(R.string.settings_api_keys_hint))
                    GeminiKeyFields(
                        apiKeyFields = apiKeyFields,
                        reveal = revealKey,
                        viewModel = viewModel,
                        fieldColors = fieldColors,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(if (revealKey) R.string.settings_hide else R.string.settings_show),
                            onClick = { revealKey = !revealKey },
                        )
                    }
                    Button(
                        onClick = { viewModel.saveApiKeys(); viewModel.testConnection() },
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = stringResource(if (testing) R.string.settings_testing else R.string.settings_save_and_test),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.settings_save_key_only),
                        onClick = viewModel::saveApiKeys,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!testResult.isNullOrBlank()) {
                    val resultText = testResult.orEmpty()
                    val hasOk = resultText.contains("✅")
                    val hasFail = resultText.contains("❌")
                    Text(
                        text = resultText,
                        fontSize = 13.sp,
                        color = when {
                            hasOk && !hasFail -> Booth.Success
                            hasFail && !hasOk -> Booth.Danger
                            else -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                    )
                }
            }
        }

        if (settings.translationProvider == TranslationProvider.GLM) {
            SectionTitle("GLM API Key")
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Hint("ใช้ GLM แปลข้อความที่ถอดเสียงด้วย Groq Whisper")
                    Hint("Base URL: https://api.oneaiapikey.com/v1")
                    Hint("Model: dealignai-glm-5.3-flash-uncensored-fp8:free")
                    OutlinedTextField(
                        value = glmApiKey,
                        onValueChange = viewModel::updateGlmApiKey,
                        modifier = Modifier.fillMaxWidth(),
                        label = { androidx.compose.material3.Text("OneAI GLM API Key") },
                        singleLine = true,
                        visualTransformation = if (revealGlmKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        shape = RoundedCornerShape(16.dp),
                        colors = fieldColors,
                    )
                    TextButton(
                        text = if (revealGlmKey) "ซ่อน API Key" else "แสดง API Key",
                        onClick = { revealGlmKey = !revealGlmKey },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            revealGlmKey = false
                            viewModel.testGlmConnection()
                        },
                        enabled = !testing && glmApiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(if (testing) "กำลังทดสอบ..." else "บันทึกและทดสอบ GLM", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Provider fallback
        if (settings.translationProvider == TranslationProvider.GROQ) {
            SectionTitle(if (hybridGeminiSelected) "Gemini Flash API Key · จำเป็นสำหรับโหมดที่เลือก" else "Gemini สำรองอัตโนมัติ")
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Hint(
                        if (hybridGeminiSelected) {
                            "Gemini Flash เป็นโมเดลแปลหลัก: Groq Whisper → Gemini Flash ต้องบันทึก Gemini API Key ก่อนเริ่มแปล"
                        } else {
                            "เมื่อ Groq ใช้งานไม่ได้ ระบบจะใช้ Gemini รับช่วงแปลชั่วคราว ควรเพิ่ม Gemini API Key อย่างน้อยหนึ่งรายการ"
                        },
                    )
                    GeminiKeyFields(
                        apiKeyFields = apiKeyFields,
                        reveal = revealKey,
                        viewModel = viewModel,
                        fieldColors = fieldColors,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = stringResource(if (revealKey) R.string.settings_hide else R.string.settings_show),
                            onClick = { revealKey = !revealKey },
                        )
                    }
                    Button(
                        onClick = { viewModel.saveApiKeys(); viewModel.clearTestResult() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("บันทึก Gemini สำรอง", fontWeight = FontWeight.SemiBold)
                    }
                    StatusPill(
                        label = if (hasGeminiKey) "มี Gemini สำรองพร้อมใช้" else "ยังไม่ได้ตั้งค่า Gemini สำรอง",
                        tone = if (hasGeminiKey) StatusTone.Ok else StatusTone.Warn,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- Transcription & translation
        CategoryHeader("Transcription & Translation", "แยกโมเดลถอดเสียงและโมเดลแปล พร้อมตรวจจับภาษาต้นฉบับอัตโนมัติ")
        if (isGroq) {
            SectionTitle("โมเดล")
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GroupLabel("ถอดเสียง (Speech-to-Text)")
                    listOf(
                        Triple("whisper-large-v3-turbo", "Whisper Large V3 Turbo", "เร็ว เหมาะกับแปลสด"),
                        Triple("whisper-large-v3", "Whisper Large V3", "เน้นความแม่นยำ"),
                    ).forEach { (model, title, desc) ->
                        OptionRow(
                            title = title,
                            description = desc,
                            selected = groqSpeechModel == model,
                        ) { viewModel.selectGroqSpeechModel(model) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    GroupLabel("แปลภาษา (Text LLM)")
                    if (settings.translationProvider == TranslationProvider.GLM) {
                        Hint("GLM: dealignai-glm-5.3-flash-uncensored-fp8:free · แปลจากข้อความที่ Groq Whisper ถอดเสียง")
                    } else listOf(
                        Triple("gemini-hybrid", "Gemini แปลจากข้อความ (แนะนำ)", "ภาษาไทยลื่นสุด ใช้ประโยคก่อนหน้าเป็นบริบท ต้องมี Gemini key"),
                        Triple("openai/gpt-oss-120b", "GPT-OSS 120B", "คุณภาพและเข้าใจบริบทดี"),
                        Triple("openai/gpt-oss-20b", "GPT-OSS 20B", "เร็วกว่า"),
                        Triple("qwen/qwen3.8-27b", "Qwen 3.8 27B", "ตัวเลือกเพิ่มเติม"),
                    ).forEach { (model, title, desc) ->
                        OptionRow(
                            title = title,
                            description = desc,
                            selected = groqChatModel == model,
                        ) { viewModel.selectGroqChatModel(model) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Hint("ภาษาต้นฉบับ: ตรวจจับอัตโนมัติ (Whisper ฟังแล้วเลือกภาษาให้เอง)")
                }
            }
        }

        // ---------------------------------------------------------------- Subtitle display
        CategoryHeader("Subtitle Display", "ปรับรูปแบบคำบรรยายและดูตัวอย่างก่อนใช้งาน")
        SectionTitle(stringResource(R.string.settings_appearance))
        SectionCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Live preview of the floating subtitle box.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF4A5060))
                        .padding(horizontal = 14.dp, vertical = 16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = settings.backgroundAlpha.coerceIn(0f, 0.95f)))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "ตัวอย่างซับไตเติล · Sample subtitle",
                            fontSize = settings.fontSizeSp.sp,
                            color = Color.White,
                        )
                    }
                }

                // Quick presets.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetChip("กะทัดรัด", Modifier.weight(1f)) {
                        viewModel.update { it.copy(fontSizeSp = 14f, backgroundAlpha = 0.45f, bilingual = false) }
                    }
                    PresetChip("มาตรฐาน", Modifier.weight(1f)) {
                        viewModel.update { it.copy(fontSizeSp = 18f, backgroundAlpha = 0.65f) }
                    }
                    PresetChip("อ่านง่าย", Modifier.weight(1f)) {
                        viewModel.update { it.copy(fontSizeSp = 26f, backgroundAlpha = 0.8f, bilingual = true) }
                    }
                }

                SliderRow(
                    title = "ขนาดตัวอักษร",
                    valueText = "${settings.fontSizeSp.toInt()} sp",
                ) {
                    Slider(
                        value = settings.fontSizeSp,
                        onValueChange = { size -> viewModel.update { it.copy(fontSizeSp = size) } },
                        valueRange = 12f..32f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SliderRow(
                    title = "ความทึบพื้นหลัง",
                    valueText = "${(settings.backgroundAlpha * 100).toInt()}%",
                ) {
                    Slider(
                        value = settings.backgroundAlpha,
                        onValueChange = { a -> viewModel.update { it.copy(backgroundAlpha = a) } },
                        valueRange = 0f..0.95f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (settings.translationProvider == TranslationProvider.GEMINI) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_bilingual),
                    summary = stringResource(
                        if (settings.bilingual) {
                            R.string.settings_bilingual_on
                        } else {
                            R.string.settings_bilingual_off
                        },
                    ),
                    checked = settings.bilingual,
                    onCheckedChange = { c -> viewModel.update { it.copy(bilingual = c) } },
                )
            }
            TextButton(
                text = stringResource(R.string.settings_reset_appearance),
                onClick = viewModel::resetSubtitleAppearance,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ---------------------------------------------------------------- Voice (Gemini only)
        if (settings.translationProvider == TranslationProvider.GEMINI) {
            SectionTitle(stringResource(R.string.settings_voice))
            SectionCard {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_play_voice),
                    summary = stringResource(R.string.settings_play_voice_summary),
                    checked = settings.playTranslatedAudio,
                    onCheckedChange = { c -> viewModel.update { it.copy(playTranslatedAudio = c) } },
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SliderRow(
                        title = "ระดับเสียง",
                        valueText = buildString {
                            append("${(settings.translatedVolume * 100).toInt()}%")
                            if (settings.translatedVolume > 1f) append(" (บูสต์)")
                        },
                    ) {
                        Slider(
                            value = settings.translatedVolume.coerceIn(0f, 2f),
                            onValueChange = { v ->
                                viewModel.update { it.copy(translatedVolume = v.coerceIn(0f, 2f)) }
                            },
                            valueRange = 0f..2f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Hint(stringResource(R.string.settings_voice_volume_hint))
                }
            }
        }

        // ---------------------------------------------------------------- Advanced
        CategoryHeader("Advanced", "Custom Prompt และตัวเลือกที่ไม่จำเป็นต้องปรับบ่อย")
        SectionTitle("ขั้นสูง")
        SectionCard {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedOpen = !advancedOpen }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Custom Prompt และการตั้งค่าขั้นสูง",
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "คำสั่งแปลร่วมกันทุกโมเดล และตัวเลือกสำหรับผู้ใช้ขั้นสูง",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        text = if (advancedOpen) "ซ่อน ▴" else "แสดง ▾",
                        fontSize = 13.sp,
                        color = Booth.Accent,
                    )
                }
                if (advancedOpen) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!isGroq) {
                            OutlinedTextField(
                                value = endpointField,
                                onValueChange = { v ->
                                    endpointField = v
                                    viewModel.update { it.copy(endpoint = v.text.trim()) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { androidx.compose.material3.Text(stringResource(R.string.settings_endpoint)) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = fieldColors,
                            )
                            OutlinedTextField(
                                value = modelField,
                                onValueChange = { v ->
                                    modelField = v
                                    viewModel.update { it.copy(modelId = v.text.trim()) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { androidx.compose.material3.Text(stringResource(R.string.settings_model_id)) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                colors = fieldColors,
                            )
                        }
                        Text("Custom prompt", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = customPromptDraft,
                            onValueChange = { customPromptDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            maxLines = 10,
                            label = { androidx.compose.material3.Text("Custom prompt") },
                            placeholder = { androidx.compose.material3.Text("ใส่คำสั่งการแปลที่ต้องการใช้ร่วมกันทุกโมเดล...") },
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors,
                        )
                        Hint("หากเว้นว่าง แอปจะใช้ Prompt เริ่มต้นสำหรับแปลภาษาญี่ปุ่นเป็นไทย หากกรอกข้อความ จะส่งข้อความนี้เป็น System Prompt ให้ Gemini Flash, Groq และ Gemini Live")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { viewModel.update { it.copy(customPrompt = customPromptDraft.trim()) } },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) { Text("บันทึก Prompt") }
                            TextButton(
                                text = "รีเซ็ตเป็นค่าเริ่มต้น",
                                onClick = {
                                    customPromptDraft = ""
                                    viewModel.update { it.copy(customPrompt = "") }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Permissions
        SectionTitle(stringResource(R.string.settings_permissions))
        SectionCard {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.settings_open_overlay),
                    onClick = onOpenOverlayPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.settings_permissions_hint))
            }
        }

        // ---------------------------------------------------------------- About
        SectionTitle(stringResource(R.string.settings_about))
        SectionCard {
            val context = LocalContext.current
            val githubUrl = stringResource(R.string.github_url).trim()
            val diagnosticsCopied = stringResource(R.string.settings_diagnostics_copied)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME) +
                        " (" + BuildConfig.GIT_SHA + ")",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(
                    text = stringResource(R.string.settings_copy_diagnostics),
                    onClick = {
                        // Never includes API keys; only versions, chosen models and recent events.
                        val header = buildString {
                            appendLine("live-translate diagnostics")
                            appendLine("version=${BuildConfig.VERSION_NAME} commit=${BuildConfig.GIT_SHA}")
                            appendLine("android=${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                            appendLine("provider=${settings.translationProvider} geminiModel=${settings.modelId} target=${settings.targetLanguageCode} audio=${settings.audioSourceMode}")
                            appendLine("groqChatModel=$groqChatModel groqSpeechModel=$groqSpeechModel")
                            appendLine("customPromptChars=${settings.customPrompt.length}")
                        }
                        val report = com.livetranslate.app.util.DiagnosticsLog.buildReport(header)
                        runCatching {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", report))
                            android.widget.Toast.makeText(context, diagnosticsCopied, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (githubUrl.isNotBlank()) {
                    TextButton(
                        text = stringResource(R.string.settings_github_open),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

// ====================================================================== helpers

@Composable
private fun appFieldColors(): TextFieldColors {
    // Dark-mode safe field colors (explicit text / cursor colors).
    val scheme = MiuixTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        disabledTextColor = scheme.disabledOnSurface,
        focusedBorderColor = Booth.Accent,
        unfocusedBorderColor = scheme.outline.copy(alpha = 0.45f),
        focusedLabelColor = Booth.Accent,
        unfocusedLabelColor = scheme.onSurfaceVariantSummary,
        cursorColor = Booth.Accent,
        focusedContainerColor = scheme.surface,
        unfocusedContainerColor = scheme.surface,
        focusedPlaceholderColor = scheme.onSurfaceVariantSummary,
        unfocusedPlaceholderColor = scheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun CategoryHeader(
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, start = 4.dp, end = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Booth.Accent,
        )
        Text(
            text = description,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    SmallTitle(
        text = text,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** Large tappable tile used for the provider switch. */
@Composable
private fun ChoiceTile(
    title: String,
    description: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Booth.Accent.copy(alpha = 0.14f) else MiuixTheme.colorScheme.secondaryContainer)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) Booth.Accent else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Booth.Accent else MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** One selectable model row: title + short description, check mark on the right. */
@Composable
private fun OptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Booth.Accent.copy(alpha = 0.14f) else MiuixTheme.colorScheme.secondaryContainer)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) Booth.Accent else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Booth.Accent else MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Text(text = "✓", fontWeight = FontWeight.Bold, color = Booth.Accent)
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Title on the left, value chip on the right, slider below. */
@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    slider: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Booth.AccentSoft.copy(alpha = 0.22f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = valueText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Booth.Accent,
                )
            }
        }
        slider()
    }
}

/** Groq credentials: the first row is primary; additional rows are failover keys. */
@Composable
private fun GroqKeyFields(
    apiKeyFields: List<TextFieldValue>,
    reveal: Boolean,
    viewModel: SettingsViewModel,
    fieldColors: TextFieldColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apiKeyFields.forEachIndexed { index, field ->
            val canAdd = index == apiKeyFields.lastIndex && apiKeyFields.size < ApiKeyStore.MAX_GROQ_KEYS
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { viewModel.updateGroqApiKeyField(index, it) },
                    modifier = Modifier.weight(1f),
                    label = {
                        androidx.compose.material3.Text(
                            if (index == 0) "Groq Primary Key" else "Groq Backup Key $index",
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                )
                if (canAdd) {
                    IconButton(onClick = viewModel::addGroqApiKeyField, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = "เพิ่ม Groq API Key", tint = Booth.Accent)
                    }
                }
                if (index > 0) {
                    IconButton(
                        onClick = { viewModel.removeGroqApiKeyField(index) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = "ลบ Groq API Key", tint = Booth.Danger)
                    }
                }
            }
        }
    }
}

/** Gemini API key rows (up to [ApiKeyStore.MAX_KEYS]) with add / remove buttons. */
@Composable
private fun GeminiKeyFields(
    apiKeyFields: List<TextFieldValue>,
    reveal: Boolean,
    viewModel: SettingsViewModel,
    fieldColors: TextFieldColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apiKeyFields.forEachIndexed { index, field ->
            val canAdd = index == apiKeyFields.lastIndex && apiKeyFields.size < ApiKeyStore.MAX_KEYS
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { viewModel.updateApiKeyField(index, it) },
                    modifier = Modifier.weight(1f),
                    label = { androidx.compose.material3.Text(stringResource(R.string.settings_api_key_n, index + 1)) },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors,
                )
                if (canAdd) {
                    IconButton(onClick = viewModel::addApiKeyField, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.settings_add_key),
                            tint = Booth.Accent,
                        )
                    }
                }
                if (index > 0) {
                    IconButton(onClick = { viewModel.removeApiKeyField(index) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Outlined.Remove,
                            contentDescription = stringResource(R.string.settings_remove_key),
                            tint = Booth.Danger,
                        )
                    }
                }
            }
        }
    }
}
