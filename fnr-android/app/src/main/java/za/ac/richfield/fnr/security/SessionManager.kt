package za.ac.richfield.fnr.security

import android.content.Context
import za.ac.richfield.fnr.BuildConfig
import za.ac.richfield.fnr.data.db.Role
import java.util.UUID

/** Who is logged in, and the 15-minute inactivity timeout (FR-USR-08). */
class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("fnr_session", Context.MODE_PRIVATE)

    val userId: Long get() = prefs.getLong(KEY_USER, -1L)
    val role: Role get() = runCatching { Role.valueOf(prefs.getString(KEY_ROLE, "STUDENT")!!) }.getOrDefault(Role.STUDENT)
    val isLoggedIn: Boolean get() = userId > 0
    val isAdmin: Boolean get() = isLoggedIn && role == Role.ADMIN

    val isExpired: Boolean
        get() = isLoggedIn && System.currentTimeMillis() - prefs.getLong(KEY_ACTIVE, 0L) > TIMEOUT_MS

    fun start(userId: Long, role: Role) {
        prefs.edit()
            .putLong(KEY_USER, userId)
            .putString(KEY_ROLE, role.name)
            .putLong(KEY_ACTIVE, System.currentTimeMillis())
            .apply()
    }

    fun touch() {
        if (isLoggedIn) prefs.edit().putLong(KEY_ACTIVE, System.currentTimeMillis()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_USER).remove(KEY_ROLE).remove(KEY_ACTIVE).apply()
    }

    fun requireUserId(): Long {
        check(isLoggedIn) { "Not logged in" }
        return userId
    }

    fun requireAdmin() {
        if (!isAdmin) throw SecurityException("Only an administrator can do this")
    }

    companion object {
        const val TIMEOUT_MS = 15 * 60 * 1000L
        private const val KEY_USER = "user_id"
        private const val KEY_ROLE = "role"
        private const val KEY_ACTIVE = "last_active"
    }
}

/** App settings that are not secrets: Gateway address, device id, auto-backup keys per user. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("fnr_settings", Context.MODE_PRIVATE)

    var gatewayUrl: String
        get() = prefs.getString("gateway_url", null) ?: BuildConfig.GATEWAY_URL
        set(value) {
            val clean = value.trim().let { if (it.endsWith("/")) it else "$it/" }
            prefs.edit().putString("gateway_url", clean).apply()
        }

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun autoBackupEnabled(userId: Long): Boolean = prefs.contains("auto_key_$userId")

    /** Stores the backup key (already derived from the password) wrapped by the Keystore. */
    fun saveAutoBackupKey(userId: Long, salt: ByteArray, key: ByteArray) {
        prefs.edit()
            .putString("auto_salt_$userId", PasswordHasher.b64(salt))
            .putString("auto_key_$userId", KeystoreCrypto.encrypt(key))
            .apply()
    }

    fun autoBackupKey(userId: Long): Pair<ByteArray, ByteArray>? {
        val salt = prefs.getString("auto_salt_$userId", null) ?: return null
        val key = prefs.getString("auto_key_$userId", null) ?: return null
        return PasswordHasher.unb64(salt) to KeystoreCrypto.decrypt(key)
    }

    fun clearAutoBackup(userId: Long) {
        prefs.edit().remove("auto_salt_$userId").remove("auto_key_$userId").apply()
    }
}
