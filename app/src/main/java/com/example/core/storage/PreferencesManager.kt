package com.example.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.security.KeystoreHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_bubble_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_DEFAULT_PROVIDER = stringPreferencesKey("default_ai_provider")
        val KEY_USER_NAME = stringPreferencesKey("user_display_name")
        val KEY_DELIVERY_MODE = stringPreferencesKey("delivery_mode") // "BOTH", "FILE_ONLY", "CLIPBOARD_ONLY"
        val KEY_LANGUAGE = stringPreferencesKey("app_language") // "en", "ar", "fr"
        val KEY_THEME = stringPreferencesKey("app_theme") // "dark", "light", "system"
        val KEY_ACCENT = stringPreferencesKey("app_accent") // "cyan", "purple", "indigo"
        val KEY_PUSH_TO_TALK = booleanPreferencesKey("push_to_talk_enabled")
        val KEY_WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        val KEY_VOICE_LANG = stringPreferencesKey("voice_language")
        val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        val KEY_DEFAULT_WORD_TEMPLATE = stringPreferencesKey("default_word_template_id")
        val KEY_DEFAULT_PPT_TEMPLATE = stringPreferencesKey("default_ppt_template_id")
        val KEY_DEFAULT_EXCEL_TEMPLATE = stringPreferencesKey("default_excel_template_id")
        val KEY_DEFAULT_PDF_TEMPLATE = stringPreferencesKey("default_pdf_template_id")
        val KEY_TEMPLATE_MODE = stringPreferencesKey("template_application_mode") // "SMART_ADAPTATION", "STRICT_MATCH", "STYLE_ONLY"
        val KEY_DEFAULT_OFFICE_ACTION = stringPreferencesKey("default_office_action") // "PREVIEW", "NATIVE"
        val KEY_NOTEBOOKLM_EMAIL = stringPreferencesKey("notebooklm_email")
        val KEY_NOTEBOOKLM_CONNECTED = booleanPreferencesKey("notebooklm_connected")

        // Encrypted API keys
        val KEY_ENC_OPENAI = stringPreferencesKey("enc_key_openai")
        val KEY_ENC_CLAUDE = stringPreferencesKey("enc_key_claude")
        val KEY_ENC_GEMINI = stringPreferencesKey("enc_key_gemini")
        val KEY_ENC_DEEPSEEK = stringPreferencesKey("enc_key_deepseek")
        val KEY_ENC_OPENROUTER = stringPreferencesKey("enc_key_openrouter")
        val KEY_CUSTOM_ENDPOINT = stringPreferencesKey("custom_endpoint_url")
        val KEY_ENC_CUSTOM = stringPreferencesKey("enc_key_custom")
        val KEY_ACTIVE_CONVERSATION_ID = stringPreferencesKey("active_conversation_id")
        // One-time sample data seeding marker (never reset, so deleted samples are never recreated)
        val KEY_SAMPLE_DATA_SEEDED = booleanPreferencesKey("sample_data_seeded_v1")
    }

    val defaultProvider: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PROVIDER] ?: "Anthropic Claude 3.5 Sonnet"
    }

    val userName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_NAME] ?: "Alexander Wright"
    }

    val deliveryMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DELIVERY_MODE] ?: "BOTH"
    }

    val appLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: "en"
    }

    val appTheme: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME] ?: "dark"
    }

    val templateMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TEMPLATE_MODE] ?: "SMART_ADAPTATION"
    }

    val pushToTalkEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PUSH_TO_TALK] ?: true
    }

    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_WORD] ?: false // Disabled by default per requirements
    }

    val defaultOfficeAction: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_OFFICE_ACTION] ?: "PREVIEW"
    }

    val notebookLmConnected: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTEBOOKLM_CONNECTED] ?: true
    }

    val notebookLmEmail: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTEBOOKLM_EMAIL] ?: "alexander.w@enterprise.com"
    }

    val activeConversationId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_CONVERSATION_ID]
    }

    suspend fun setActiveConversationId(id: String) {
        context.dataStore.edit { it[KEY_ACTIVE_CONVERSATION_ID] = id }
    }

    suspend fun isSampleDataSeeded(): Boolean =
        context.dataStore.data.first()[KEY_SAMPLE_DATA_SEEDED] ?: false

    suspend fun markSampleDataSeeded() {
        context.dataStore.edit { it[KEY_SAMPLE_DATA_SEEDED] = true }
    }

    suspend fun setDefaultProvider(provider: String) {
        context.dataStore.edit { it[KEY_DEFAULT_PROVIDER] = provider }
    }

    suspend fun setAppLanguage(lang: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = lang }
    }

    suspend fun setAppTheme(theme: String) {
        context.dataStore.edit { it[KEY_THEME] = theme }
    }

    suspend fun setTemplateMode(mode: String) {
        context.dataStore.edit { it[KEY_TEMPLATE_MODE] = mode }
    }

    suspend fun setPushToTalk(enabled: Boolean) {
        context.dataStore.edit { it[KEY_PUSH_TO_TALK] = enabled }
    }

    suspend fun setWakeWord(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WAKE_WORD] = enabled }
    }

    suspend fun setDefaultOfficeAction(action: String) {
        context.dataStore.edit { it[KEY_DEFAULT_OFFICE_ACTION] = action }
    }

    suspend fun setNotebookLmStatus(connected: Boolean, email: String) {
        context.dataStore.edit {
            it[KEY_NOTEBOOKLM_CONNECTED] = connected
            it[KEY_NOTEBOOKLM_EMAIL] = email
        }
    }

    suspend fun saveEncryptedApiKey(provider: String, plainKey: String) {
        val encrypted = KeystoreHelper.encrypt(plainKey)
        context.dataStore.edit { prefs ->
            when (provider) {
                "OpenAI" -> prefs[KEY_ENC_OPENAI] = encrypted
                "Claude" -> prefs[KEY_ENC_CLAUDE] = encrypted
                "Gemini" -> prefs[KEY_ENC_GEMINI] = encrypted
                "DeepSeek" -> prefs[KEY_ENC_DEEPSEEK] = encrypted
                "OpenRouter" -> prefs[KEY_ENC_OPENROUTER] = encrypted
                "Custom" -> prefs[KEY_ENC_CUSTOM] = encrypted
            }
        }
    }

    fun getEncryptedApiKeyFlow(provider: String): Flow<String> {
        val keyPref = when (provider) {
            "OpenAI" -> KEY_ENC_OPENAI
            "Claude" -> KEY_ENC_CLAUDE
            "Gemini" -> KEY_ENC_GEMINI
            "DeepSeek" -> KEY_ENC_DEEPSEEK
            "OpenRouter" -> KEY_ENC_OPENROUTER
            else -> KEY_ENC_CUSTOM
        }
        return context.dataStore.data.map { prefs ->
            val enc = prefs[keyPref] ?: ""
            KeystoreHelper.decrypt(enc)
        }
    }
}
