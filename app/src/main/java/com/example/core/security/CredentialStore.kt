package com.example.core.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores provider API keys encrypted with the Android Keystore ([KeystoreHelper]), one entry per
 * provider id, in a dedicated preferences file that is excluded from cloud backup and device
 * transfer (see res/xml/backup_rules.xml and data_extraction_rules.xml). Keystore keys are never
 * backed up, so a restored ciphertext would be unusable; excluding the file avoids that.
 *
 * Keys are never logged, never placed in exceptions, and are only decrypted right before a
 * request is made.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun hasKey(providerId: String): Boolean = !prefs.getString(entry(providerId), null).isNullOrEmpty()

    /** Encrypts and stores [apiKey]. Throws [CredentialSecurityException]; nothing is stored then. */
    fun saveKey(providerId: String, apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "API key is empty" }
        val encrypted = KeystoreHelper.encrypt(trimmed)
        if (!prefs.edit().putString(entry(providerId), encrypted).commit()) {
            throw CredentialSecurityException("Could not persist the credential")
        }
    }

    /** Returns the decrypted key, or null when none is stored. Throws when it cannot be decrypted. */
    fun readKey(providerId: String): String? {
        val stored = prefs.getString(entry(providerId), null) ?: return null
        if (stored.isEmpty()) return null
        return KeystoreHelper.decrypt(stored)
    }

    /** Removes the stored key (overwrites, then removes the entry). */
    fun deleteKey(providerId: String) {
        prefs.edit().putString(entry(providerId), "").commit()
        prefs.edit().remove(entry(providerId)).commit()
    }

    private fun entry(providerId: String) = "provider_key_$providerId"

    companion object {
        /** Must match the exclusions in backup_rules.xml / data_extraction_rules.xml. */
        const val FILE_NAME = "smartbubble_credentials"
    }
}
