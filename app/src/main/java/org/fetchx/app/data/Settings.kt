package org.fetchx.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = true,
    val defaultThreads: Int = 16,
    val maxConcurrent: Int = 3,
    val userAgent: String = "",
    val referer: String = "",
    val destTreeUri: String = "",
    val clipboardWatch: Boolean = true,
    val ytNightly: Boolean = false,
)

class SettingsRepo(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val amoled = booleanPreferencesKey("amoled")
        val dynamic = booleanPreferencesKey("dynamic")
        val threads = intPreferencesKey("threads")
        val concurrent = intPreferencesKey("concurrent")
        val ua = stringPreferencesKey("ua")
        val referer = stringPreferencesKey("referer")
        val dest = stringPreferencesKey("dest")
        val clipboard = booleanPreferencesKey("clipboard")
        val ytNightly = booleanPreferencesKey("ytNightly")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            amoled = p[K.amoled] ?: false,
            dynamicColor = p[K.dynamic] ?: true,
            defaultThreads = p[K.threads] ?: 16,
            maxConcurrent = p[K.concurrent] ?: 3,
            userAgent = p[K.ua] ?: "",
            referer = p[K.referer] ?: "",
            destTreeUri = p[K.dest] ?: "",
            clipboardWatch = p[K.clipboard] ?: true,
            ytNightly = p[K.ytNightly] ?: false,
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.store.edit { it[K.theme] = v.name }
    suspend fun setAmoled(v: Boolean) = context.store.edit { it[K.amoled] = v }
    suspend fun setDynamic(v: Boolean) = context.store.edit { it[K.dynamic] = v }
    suspend fun setThreads(v: Int) = context.store.edit { it[K.threads] = v.coerceIn(1, 32) }
    suspend fun setConcurrent(v: Int) = context.store.edit { it[K.concurrent] = v.coerceIn(1, 8) }
    suspend fun setUserAgent(v: String) = context.store.edit { it[K.ua] = v }
    suspend fun setReferer(v: String) = context.store.edit { it[K.referer] = v }
    suspend fun setDest(v: String) = context.store.edit { it[K.dest] = v }
    suspend fun setClipboard(v: Boolean) = context.store.edit { it[K.clipboard] = v }
    suspend fun setYtNightly(v: Boolean) = context.store.edit { it[K.ytNightly] = v }
}
