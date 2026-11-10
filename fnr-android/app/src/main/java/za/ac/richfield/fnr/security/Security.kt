package za.ac.richfield.fnr.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 password hashing (Phase 4, section 4.8.1). Plain passwords are never stored. */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun derive(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hash(password: String, salt: ByteArray): String = b64(derive(password, salt))

    /** Constant-time comparison so timing does not leak how much of the hash matched. */
    fun verify(password: String, saltB64: String, hashB64: String): Boolean {
        val expected = unb64(hashB64)
        val actual = derive(password, unb64(saltB64))
        return MessageDigest.isEqual(expected, actual)
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
}

/** Encrypts small secrets with an AES key that never leaves the Android Keystore. */
object KeystoreCrypto {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "fnr_master_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val out = cipher.doFinal(plain)
        return PasswordHasher.b64(iv + out)
    }

    fun decrypt(wrapped: String): ByteArray {
        val bytes = PasswordHasher.unb64(wrapped)
        val iv = bytes.copyOfRange(0, 12)
        val body = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}

/** Creates the random database passphrase once and keeps it only in Keystore-wrapped form. */
class DatabaseKeyProvider(context: Context) {
    private val prefs = context.getSharedPreferences("fnr_keys", Context.MODE_PRIVATE)

    fun passphrase(): ByteArray {
        prefs.getString(KEY, null)?.let { return KeystoreCrypto.decrypt(it) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // commit() (not apply()) so the key is on disk before the database is created with it
        prefs.edit().putString(KEY, KeystoreCrypto.encrypt(fresh)).commit()
        return fresh
    }

    private companion object {
        const val KEY = "db_passphrase"
    }
}
