package com.example.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thrown when secure encryption/decryption is not possible. Never carries the secret. */
class CredentialSecurityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Android Keystore-backed AES-256/GCM encryption.
 *
 * There is deliberately NO fallback: if the Keystore is unavailable or an operation fails, a
 * [CredentialSecurityException] is thrown and the caller must refuse to store/use the
 * credential. Values are never stored as plaintext or as a reversible encoding.
 */
object KeystoreHelper {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "SmartBubbleSecureKeyAlias"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    /** Format marker so encrypted values can be told apart from anything else. */
    private const val PREFIX = "kv1:"

    @Synchronized
    private fun secretKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        } catch (e: Exception) {
            throw CredentialSecurityException("Android Keystore is unavailable", e)
        }
    }

    fun isEncryptedValue(value: String): Boolean = value.startsWith(PREFIX)

    /** Encrypts [plainText]. Throws [CredentialSecurityException] on any failure. */
    fun encrypt(plainText: String): String {
        require(plainText.isNotEmpty()) { "Nothing to encrypt" }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return PREFIX + Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
        } catch (e: CredentialSecurityException) {
            throw e
        } catch (e: Exception) {
            throw CredentialSecurityException("Encryption failed", e)
        }
    }

    /**
     * Decrypts a value produced by [encrypt]. Also accepts the legacy un-prefixed IV+ciphertext
     * format written by earlier versions. Throws [CredentialSecurityException] if the value
     * cannot be decrypted (e.g. the Keystore key was lost after a device restore).
     */
    fun decrypt(encrypted: String): String {
        if (encrypted.isEmpty()) throw CredentialSecurityException("No credential stored")
        try {
            val combined = Base64.decode(encrypted.removePrefix(PREFIX), Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) throw CredentialSecurityException("Invalid credential format")
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            return String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: CredentialSecurityException) {
            throw e
        } catch (e: Exception) {
            throw CredentialSecurityException("Stored credential cannot be decrypted", e)
        }
    }
}
