package com.example.agent.routing

import com.example.data.model.ProviderEntity

/** One row of the Agent's model priority list (Settings > Agent). */
data class PriorityEntry(val provider: ProviderEntity, val enabled: Boolean, val hasKey: Boolean) {
    /** Used for routing: enabled by the user and has a stored key. */
    val usable: Boolean get() = enabled && hasKey
}

/**
 * The user's model priority order. Pure Kotlin.
 *
 * The order is the list of provider ids the user saved. Providers added later (not in the saved
 * list yet) follow, in the order they were added; before the user arranges anything the default
 * provider comes first, so routing starts where Chat always started. Nothing here is hardcoded:
 * the providers and their order come only from the user's configuration.
 */
object PriorityList {
    fun arrange(
        providers: List<ProviderEntity>,
        savedOrder: List<String>,
        disabled: Set<String>,
        hasKey: (String) -> Boolean
    ): List<PriorityEntry> {
        val byId = providers.associateBy { it.id }
        val ordered = savedOrder.distinct().mapNotNull { byId[it] }
        val rest = providers.filter { it.id !in savedOrder }
            .sortedWith(compareByDescending<ProviderEntity> { it.isDefault }.thenBy { it.createdAt }.thenBy { it.id })
        return (ordered + rest).map { PriorityEntry(it, enabled = it.id !in disabled, hasKey = hasKey(it.id)) }
    }

    /** The order after moving the entry at [from] to [to] (Settings up/down buttons). */
    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        val list = ids.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        return list
    }

    /** Routing candidates: usable entries in order. [support] gives what is known about each model. */
    fun candidates(entries: List<PriorityEntry>, support: (ProviderEntity) -> ModelSupport): List<ProviderCandidate> =
        entries.filter { it.usable }.map {
            ProviderCandidate(it.provider.id, it.provider.name, it.provider.modelName, support(it.provider), it.provider.providerType)
        }

    fun encode(ids: List<String>): String = ids.joinToString(",")
    fun decode(text: String?): List<String> = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
