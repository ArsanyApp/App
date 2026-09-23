package com.choice.autotap.license

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the installation id and the license in app-private SharedPreferences, encrypted and
 * authenticated with an AES-256-GCM key that lives in the Android Keystore (non-exportable, often
 * hardware-backed). Copying the preferences file to another phone therefore does not copy the
 * license: the other phone cannot decrypt it. The file is also excluded from backups.
 *
 * Installation id: 128 random bits from SecureRandom, created on first launch. No hardware ids,
 * phone numbers, advertising id or any personal data are used. The server only ever receives
 * SHA-256("choice-auto-tap/installation/v1:" + id).
 */
class AndroidLicenseStore(context: Context) : LicenseStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun installationId(): String {
        decrypt(prefs.getString(KEY_INSTALLATION, null))?.let { return it }
        // New installation (first launch, data cleared, or key lost): anything stored before is void.
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        prefs.edit().clear().putString(KEY_INSTALLATION, encrypt(id)).commit()
        return id
    }

    @Synchronized
    override fun load(): StoredLicense? {
        val raw = decrypt(prefs.getString(KEY_LICENSE, null)) ?: return null
        val parts = raw.split('\n')
        if (parts.size != 4) return null
        return StoredLicense(
            token = parts[0],
            lastVerifiedAt = parts[1].toLongOrNull() ?: return null,
            maxSeenAt = parts[2].toLongOrNull() ?: return null,
            revoked = parts[3] == "1",
        )
    }

    @Synchronized
    override fun save(license: StoredLicense) {
        val raw = listOf(license.token, license.lastVerifiedAt, license.maxSeenAt, if (license.revoked) "1" else "0").joinToString("\n")
        prefs.edit().putString(KEY_LICENSE, encrypt(raw)).commit()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY_LICENSE).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? {
        if (value == null) return null
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val PREFS = "license_state"
        const val KEY_INSTALLATION = "installation"
        const val KEY_LICENSE = "license"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "choice_auto_tap_license"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
