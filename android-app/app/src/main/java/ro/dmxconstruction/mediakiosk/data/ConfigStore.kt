package ro.dmxconstruction.mediakiosk.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = prefs.getBoolean(KEY_CONFIGURED, false)

    fun load(): AppConfig? {
        if (!isConfigured()) return null
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val key = prefs.getString(KEY_DEVICE_KEY, null) ?: return null
        return AppConfig(
            server,
            key,
            prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE),
            runCatching { ScreenOrientation.valueOf(prefs.getString(KEY_ORIENTATION, ScreenOrientation.LANDSCAPE.name)!!) }
                .getOrDefault(ScreenOrientation.LANDSCAPE)
        )
    }

    fun save(config: AppConfig, pin: String?) {
        val editor = prefs.edit()
            .putString(KEY_SERVER, ServerUrl.normalize(config.serverUrl))
            .putString(KEY_DEVICE_KEY, config.deviceKey.trim())
            .putLong(KEY_CACHE_LIMIT, config.cacheLimitBytes)
            .putString(KEY_ORIENTATION, config.orientation.name)
            .putBoolean(KEY_CONFIGURED, true)
        if (!pin.isNullOrBlank()) {
            require(pin.length >= 4 && pin.all(Char::isDigit)) { "PIN-ul trebuie să conțină minimum 4 cifre." }
            val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }
            editor.putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            editor.putString(KEY_PIN_HASH, Base64.encodeToString(PinSecurity.hash(pin, salt), Base64.NO_WRAP))
            editor.putInt(KEY_PIN_FAILURES, 0).putLong(KEY_PIN_LOCKED_UNTIL, 0)
        } else require(prefs.contains(KEY_PIN_HASH)) { "Este necesar un PIN administrativ." }
        editor.apply()
    }

    fun verifyPin(pin: String, now: Long = System.currentTimeMillis()): PinResult {
        val lockedUntil = prefs.getLong(KEY_PIN_LOCKED_UNTIL, 0)
        if (now < lockedUntil) return PinResult.Locked(lockedUntil - now)
        val salt = prefs.getString(KEY_PIN_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return PinResult.Invalid
        val expected = prefs.getString(KEY_PIN_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return PinResult.Invalid
        if (MessageDigest.isEqual(PinSecurity.hash(pin, salt), expected)) {
            prefs.edit().putInt(KEY_PIN_FAILURES, 0).putLong(KEY_PIN_LOCKED_UNTIL, 0).apply()
            return PinResult.Valid
        }
        val failures = prefs.getInt(KEY_PIN_FAILURES, 0) + 1
        if (failures >= 3) {
            val until = now + 30_000L
            prefs.edit().putInt(KEY_PIN_FAILURES, 0).putLong(KEY_PIN_LOCKED_UNTIL, until).apply()
            return PinResult.Locked(30_000L)
        }
        prefs.edit().putInt(KEY_PIN_FAILURES, failures).apply()
        return PinResult.Invalid
    }

    companion object {
        const val DEFAULT_SERVER = "https://kiosk.dmxconstruction.ro"
        const val DEFAULT_CACHE = 1024L * 1024 * 1024
        private const val KEY_CONFIGURED = "configured"
        private const val KEY_SERVER = "server"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_CACHE_LIMIT = "cache_limit"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_FAILURES = "pin_failures"
        private const val KEY_PIN_LOCKED_UNTIL = "pin_locked_until"
    }
}

object PinSecurity {
    fun hash(pin: String, salt: ByteArray): ByteArray {
        var value = salt + pin.toByteArray(Charsets.UTF_8)
        repeat(50_000) { value = MessageDigest.getInstance("SHA-256").digest(value + salt) }
        return value
    }
}

sealed class PinResult {
    object Valid : PinResult()
    object Invalid : PinResult()
    data class Locked(val remainingMs: Long) : PinResult()
}

class RuntimeStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk_runtime_state", Context.MODE_PRIVATE)
    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(value) { prefs.edit().putLong("last_sync", value).apply() }
    var lastHeartbeat: Long
        get() = prefs.getLong("last_heartbeat", 0)
        set(value) { prefs.edit().putLong("last_heartbeat", value).apply() }
    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(value) { prefs.edit().putString("last_error", value?.take(300)).apply() }
}
