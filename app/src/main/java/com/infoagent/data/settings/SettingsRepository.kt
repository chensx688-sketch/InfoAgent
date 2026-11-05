package com.infoagent.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.infoagent.agent.ApiConfig
import com.infoagent.agent.EndpointSecurity
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore by preferencesDataStore(name = "info_agent_settings")

data class SettingsSnapshot(
    val baseUrl: String = "",
    val endpoint: String = "/chat/completions",
    val model: String = "",
    val temperature: Double = 0.3,
    val maxTokens: Int = 2048,
    val apiKeyPresent: Boolean = false,
)

/**
 * 设置存储（§6）：非敏感项走 DataStore；api_key 仅存 Keystore 加密的 EncryptedSharedPreferences，
 * 不随导出/备份外流（allowBackup=false + extraction rules）。
 */
class SettingsRepository(private val context: Context) {

    private val dataStore = context.settingsDataStore

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "info_agent_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val ONBOARDED = androidx.datastore.preferences.core.booleanPreferencesKey("onboarded")
        val ISLAND = androidx.datastore.preferences.core.booleanPreferencesKey("island_enabled")
    }

    private companion object {
        const val SECURE_KEY_API = "api_key"
        const val SECURE_KEY_URL_BACKUP = "base_url_display"
    }

    fun apiKey(): String? = runCatching { secure.getString(SECURE_KEY_API, null) }.getOrNull()

    suspend fun onboarded(): Boolean = dataStore.data.first()[Keys.ONBOARDED] ?: false

    suspend fun markOnboarded() {
        dataStore.edit { it[Keys.ONBOARDED] = true }
    }

    suspend fun islandEnabled(): Boolean = dataStore.data.first()[Keys.ISLAND] ?: false

    suspend fun setIslandEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ISLAND] = enabled }
        // 同步写轻量 SharedPreferences：悬浮窗/广播里需要同步读取
        context.getSharedPreferences("ui_state", Context.MODE_PRIVATE)
            .edit().putBoolean("island_enabled", enabled).apply()
    }

    fun saveApiKey(key: String) {
        secure.edit().putString(SECURE_KEY_API, key.trim()).apply()
    }

    suspend fun snapshot(): SettingsSnapshot {
        val prefs = dataStore.data.first()
        return SettingsSnapshot(
            baseUrl = prefs[Keys.BASE_URL].orEmpty(),
            endpoint = prefs[Keys.ENDPOINT] ?: "/chat/completions",
            model = prefs[Keys.MODEL].orEmpty(),
            temperature = prefs[Keys.TEMPERATURE] ?: 0.3,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 2048,
            apiKeyPresent = !apiKey().isNullOrBlank(),
        )
    }

    /** 保存接口设置；返回错误文案（null = 成功） */
    suspend fun saveApi(
        baseUrl: String,
        endpoint: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        apiKey: String?,
    ): String? {
        EndpointSecurity.validate(baseUrl)?.let { return it }
        if (model.isBlank()) return "模型名不能为空"
        if (apiKey != null && apiKey.isNotBlank()) saveApiKey(apiKey)
        dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = baseUrl.trim().trimEnd('/')
            prefs[Keys.ENDPOINT] = endpoint.trim().ifBlank { "/chat/completions" }
            prefs[Keys.MODEL] = model.trim()
            prefs[Keys.TEMPERATURE] = temperature
            prefs[Keys.MAX_TOKENS] = maxTokens
        }
        return null
    }

    /** 供流水线使用；缺 key/地址/模型时返回 null */
    suspend fun config(): ApiConfig? {
        val snap = snapshot()
        val key = apiKey()
        if (snap.baseUrl.isBlank() || snap.model.isBlank() || key.isNullOrBlank()) return null
        if (EndpointSecurity.validate(snap.baseUrl) != null) return null
        return ApiConfig(
            baseUrl = snap.baseUrl,
            endpoint = snap.endpoint,
            apiKey = key,
            model = snap.model,
            temperature = snap.temperature,
            maxTokens = snap.maxTokens,
        )
    }
}