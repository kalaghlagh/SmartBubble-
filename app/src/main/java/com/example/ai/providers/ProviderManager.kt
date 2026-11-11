package com.example.ai.providers

import android.util.Base64
import androidx.room.withTransaction
import com.example.core.security.CredentialSecurityException
import com.example.core.security.CredentialStore
import com.example.core.security.KeystoreHelper
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ProviderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Machine-readable provider status stored in ProviderEntity.status and localized by the UI. */
object ProviderStatus {
    const val NOT_TESTED = "not_tested"
    const val NEEDS_KEY = "needs_key"
    private const val CONNECTED_PREFIX = "connected:"
    private const val ERROR_PREFIX = "error:"

    fun connected(latencyMs: Long) = "$CONNECTED_PREFIX$latencyMs"
    fun error(kind: AiErrorKind) = "$ERROR_PREFIX${kind.name}"

    fun latencyOf(status: String): Long? =
        if (status.startsWith(CONNECTED_PREFIX)) status.removePrefix(CONNECTED_PREFIX).toLongOrNull() else null

    fun errorOf(status: String): AiErrorKind? =
        if (status.startsWith(ERROR_PREFIX)) {
            runCatching { AiErrorKind.valueOf(status.removePrefix(ERROR_PREFIX)) }.getOrNull()
        } else null

    fun isKnown(status: String) = status == NOT_TESTED || status == NEEDS_KEY ||
        latencyOf(status) != null || errorOf(status) != null
}

/** User input for adding/editing a provider. A blank [apiKey] on edit keeps the stored key. */
data class ProviderInput(
    val name: String,
    val type: AiProviderType,
    val baseUrl: String,
    val model: String,
    val apiKey: String
)

enum class ProviderField { NAME, BASE_URL, MODEL, API_KEY }

class ProviderValidationException(val invalidFields: Set<ProviderField>) :
    Exception("Invalid provider fields: $invalidFields")

/** Pure validation, shared by the Settings dialog and [ProviderManager]. */
object ProviderValidator {
    fun validate(input: ProviderInput, requireKey: Boolean): Set<ProviderField> {
        val invalid = mutableSetOf<ProviderField>()
        if (input.name.isBlank()) invalid += ProviderField.NAME
        if (input.model.isBlank()) invalid += ProviderField.MODEL
        val url = input.baseUrl.trim().ifBlank { input.type.defaultBaseUrl }
        if (url.isBlank() || !url.startsWith("https://") || url.length < 12 || url.contains(' ')) {
            invalid += ProviderField.BASE_URL
        }
        if (requireKey && input.apiKey.isBlank()) invalid += ProviderField.API_KEY
        return invalid
    }
}

/**
 * Provider management and the single source of truth for the default provider:
 * the `isDefault` flag of the ai_providers table. Nothing else stores or infers the default.
 * API keys live only in [CredentialStore], never in the database.
 */
class ProviderManager(
    private val database: SmartBubbleDatabase,
    private val credentials: CredentialStore
) {
    private val dao get() = database.providerDao()

    val providers: Flow<List<ProviderEntity>> get() = dao.getAllProviders()

    suspend fun defaultProvider(): ProviderEntity? = dao.getDefaultProvider()

    fun hasKey(providerId: String): Boolean = credentials.hasKey(providerId)

    /** The default provider and a ready client, or an [AiException] explaining why not. */
    suspend fun defaultClient(): Pair<ProviderEntity, AiProviderClient> = withContext(Dispatchers.IO) {
        val provider = dao.getDefaultProvider() ?: throw AiException(AiErrorKind.NO_DEFAULT_PROVIDER)
        return@withContext provider to clientFor(provider)
    }

    fun clientFor(provider: ProviderEntity): AiProviderClient {
        val key = try {
            credentials.readKey(provider.id)
        } catch (e: CredentialSecurityException) {
            throw AiException(AiErrorKind.CREDENTIAL_UNAVAILABLE, cause = e)
        } ?: throw AiException(AiErrorKind.MISSING_API_KEY)
        return AiProviderFactory.create(provider, key)
    }

    /**
     * Adds a provider. The key is encrypted first; if that fails nothing is stored.
     * The new provider becomes default only when there is no default yet.
     * @return the stored provider
     */
    suspend fun addProvider(input: ProviderInput): ProviderEntity = withContext(Dispatchers.IO) {
        val invalid = ProviderValidator.validate(input, requireKey = true)
        if (invalid.isNotEmpty()) throw ProviderValidationException(invalid)
        val id = UUID.randomUUID().toString()
        credentials.saveKey(id, input.apiKey) // throws CredentialSecurityException
        try {
            val entity = database.withTransaction {
                val makeDefault = dao.getDefaultProvider() == null
                ProviderEntity(
                    id = id,
                    name = input.name.trim(),
                    providerType = input.type.storageKey,
                    baseUrl = input.baseUrl.trim().ifBlank { input.type.defaultBaseUrl },
                    encryptedApiKey = "", // keys are kept in CredentialStore, not in the database
                    modelName = input.model.trim(),
                    isDefault = makeDefault,
                    status = ProviderStatus.NOT_TESTED
                ).also { dao.insertProvider(it) }
            }
            return@withContext entity
        } catch (e: Exception) {
            credentials.deleteKey(id)
            throw e
        }
    }

    /** Updates a provider. A blank key keeps the stored key. The default flag is unchanged. */
    suspend fun updateProvider(id: String, input: ProviderInput): ProviderEntity = withContext(Dispatchers.IO) {
        val existing = dao.getProviderById(id) ?: throw IllegalArgumentException("Provider not found")
        val invalid = ProviderValidator.validate(input, requireKey = !credentials.hasKey(id))
        if (invalid.isNotEmpty()) throw ProviderValidationException(invalid)
        if (input.apiKey.isNotBlank()) credentials.saveKey(id, input.apiKey)
        val updated = existing.copy(
            name = input.name.trim(),
            providerType = input.type.storageKey,
            baseUrl = input.baseUrl.trim().ifBlank { input.type.defaultBaseUrl },
            modelName = input.model.trim(),
            encryptedApiKey = "",
            status = ProviderStatus.NOT_TESTED
        )
        dao.updateProvider(updated)
        return@withContext updated
    }

    /** Deletes the provider and its credential. Never picks a new default automatically. */
    suspend fun deleteProvider(provider: ProviderEntity) = withContext(Dispatchers.IO) {
        dao.deleteProvider(provider)
        credentials.deleteKey(provider.id)
    }

    /** Makes [providerId] the only default provider (atomic). */
    suspend fun setDefault(providerId: String) {
        database.withTransaction {
            dao.clearDefault()
            dao.setAsDefault(providerId)
        }
    }

    /**
     * Real connection test: an authenticated request that also checks the configured model.
     * The result is stored as the provider's status. Throws [AiException] on failure.
     */
    suspend fun testConnection(provider: ProviderEntity): Long = withContext(Dispatchers.IO) {
        try {
            val latency = clientFor(provider).testConnection()
            dao.getProviderById(provider.id)?.let { dao.updateProvider(it.copy(status = ProviderStatus.connected(latency))) }
            return@withContext latency
        } catch (e: AiException) {
            val status = if (e.kind == AiErrorKind.MISSING_API_KEY) ProviderStatus.NEEDS_KEY else ProviderStatus.error(e.kind)
            dao.getProviderById(provider.id)?.let { dao.updateProvider(it.copy(status = status)) }
            throw e
        }
    }

    /**
     * One-time clean-up of data written by earlier versions (idempotent, runs at start-up):
     *  - API keys stored in the database are moved into [CredentialStore] and the column cleared;
     *  - the fake keys earlier versions seeded and the "placeholder-key" filler are discarded;
     *  - keys earlier versions stored as plain Base64 (insecure fallback) are re-encrypted properly;
     *  - fake/free-text statuses ("Connected (28ms)", "Ready", ...) are reset.
     */
    suspend fun migrateLegacyProviderData() = withContext(Dispatchers.IO) {
        for (provider in dao.getAllProvidersOnce()) {
            var status = provider.status
            if (provider.encryptedApiKey.isNotEmpty()) {
                val recovered = recoverLegacyKey(provider.encryptedApiKey)
                if (recovered != null && !credentials.hasKey(provider.id)) {
                    try {
                        credentials.saveKey(provider.id, recovered)
                    } catch (e: CredentialSecurityException) {
                        // Cannot store securely: drop it; the user re-enters the key.
                    }
                }
                status = ProviderStatus.NOT_TESTED
            }
            if (!credentials.hasKey(provider.id)) status = ProviderStatus.NEEDS_KEY
            else if (!ProviderStatus.isKnown(status)) status = ProviderStatus.NOT_TESTED
            if (status != provider.status || provider.encryptedApiKey.isNotEmpty()) {
                dao.updateProvider(provider.copy(encryptedApiKey = "", status = status))
            }
        }
    }

    private fun recoverLegacyKey(stored: String): String? {
        val plain = try {
            KeystoreHelper.decrypt(stored)
        } catch (e: CredentialSecurityException) {
            // Earlier versions silently fell back to plain Base64 when encryption failed.
            runCatching { String(Base64.decode(stored, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
                ?.takeIf { decoded -> decoded.isNotBlank() && decoded.all { it.code in 0x21..0x7E } }
        } ?: return null
        return plain.trim().takeIf { it.isNotEmpty() && it !in LEGACY_FAKE_KEYS }
    }

    companion object {
        /** Placeholder "keys" that earlier versions seeded or substituted for an empty field. */
        val LEGACY_FAKE_KEYS = setOf(
            "sk-live-smartbubble-encrypted",
            "gemini-smartbubble-encrypted",
            "sk-ant-smartbubble-encrypted",
            "placeholder-key"
        )
    }
}
