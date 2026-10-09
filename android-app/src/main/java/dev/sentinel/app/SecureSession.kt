package dev.sentinel.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureSession {
    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "sentinel_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun token(context: Context) = prefs(context).getString("token", null)
    fun deviceId(context: Context) = prefs(context).getString("device_id", null)
    fun isTracking(context: Context) = prefs(context).getBoolean("tracking", false)
    fun save(context: Context, token: String) { prefs(context).edit().putString("token", token).apply() }
    fun saveDevice(context: Context, id: String) { prefs(context).edit().putString("device_id", id).apply() }
    fun saveTracking(context: Context, active: Boolean) { prefs(context).edit().putBoolean("tracking", active).apply() }
    fun clear(context: Context) { prefs(context).edit().clear().apply() }
}
