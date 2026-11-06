package com.infoagent.enrich

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first

private val Context.enrichDataStore by preferencesDataStore(name = "info_agent_enrich")

/**
 * 外部接口配置读写：key 进 Keystore 加密区（不随导出/备份外流），host/端点/常住城市进 DataStore。
 */
class EnrichSettings(private val context: Context) : EnrichConfigSource {

    private val store = context.enrichDataStore

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "info_agent_enrich_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("enrich_enabled")
        val HOST = stringPreferencesKey("weather_host")
        val SEARCH_ENDPOINT = stringPreferencesKey("search_endpoint")
        val HOME_CITY = stringPreferencesKey("home_city")
    }

    private companion object {
        const val SECURE_WEATHER = "weather_key"
        const val SECURE_SEARCH = "search_key"
        const val DEFAULT_HOST = "devapi.qweather.com"
        const val DEFAULT_SEARCH = "https://api.tavily.com/search"
    }

    private fun weatherKey(): String = runCatching { secure.getString(SECURE_WEATHER, "") }.getOrNull().orEmpty()

    private fun searchKey(): String = runCatching { secure.getString(SECURE_SEARCH, "") }.getOrNull().orEmpty()

    override suspend fun load(): EnrichConfig {
        val p = store.data.first()
        return EnrichConfig(
            weatherKey = weatherKey(),
            weatherHost = p[Keys.HOST]?.trim().orEmpty().ifBlank { DEFAULT_HOST },
            searchKey = searchKey(),
            searchEndpoint = p[Keys.SEARCH_ENDPOINT]?.trim().orEmpty().ifBlank { DEFAULT_SEARCH },
            homeCity = p[Keys.HOME_CITY].orEmpty(),
        )
    }

    override suspend fun enabled(): Boolean = store.data.first()[Keys.ENABLED] ?: false

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun save(
        weatherKey: String?,
        weatherHost: String,
        searchKey: String?,
        searchEndpoint: String,
        homeCity: String,
    ) {
        if (!weatherKey.isNullOrBlank()) secure.edit().putString(SECURE_WEATHER, weatherKey.trim()).apply()
        if (!searchKey.isNullOrBlank()) secure.edit().putString(SECURE_SEARCH, searchKey.trim()).apply()
        store.edit { p ->
            p[Keys.HOST] = weatherHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            p[Keys.SEARCH_ENDPOINT] = searchEndpoint.trim()
            p[Keys.HOME_CITY] = homeCity.trim()
        }
    }

    suspend fun clearWeatherKey() {
        secure.edit().remove(SECURE_WEATHER).apply()
    }

    suspend fun clearSearchKey() {
        secure.edit().remove(SECURE_SEARCH).apply()
    }
}