package com.euthopiar.core.addons

import com.euthopiar.core.util.TmdbBridge
import kotlinx.serialization.Serializable

/**
 * Saved user preference for one Stremio addon catalog row, keyed by
 * [HomeCatalogDefinition.key] ("${manifest.id}:${catalog.type}:${catalog.id}").
 *
 * Hiding a catalog only removes its row from Home. It never disables or
 * uninstalls the addon that provides it, and the catalog stays searchable.
 */
@Serializable
data class CatalogPreference(
    val catalogKey: String,
    val onHome: Boolean = true,
    val orderIndex: Int = Int.MAX_VALUE,
    val customTitle: String? = null
)

/**
 * Default Home visibility for a catalog key with no saved preference.
 * Progressive TMDB discovery rails are off Home by default so a fresh
 * install shows only the Pan-India core feeds plus enabled Stremio
 * catalogs; every other catalog (core Indian feeds, Stremio catalogs)
 * defaults to on Home. A saved preference always wins over this default.
 */
fun defaultCatalogOnHome(key: String): Boolean =
    key !in TmdbBridge.PROGRESSIVE_RAIL_KEYS

/**
 * Source of saved catalog preferences for [ProviderRepository].
 *
 * The preferences live in the app module (DataStore), which the core module
 * cannot depend on, so the app supplies them through this interface. When no
 * provider is set the repository behaves exactly as before: every catalog is
 * on Home, in manifest order, under its default title.
 */
fun interface CatalogPreferenceProvider {
    suspend fun snapshot(): Map<String, CatalogPreference>
}

/**
 * Orders catalog definitions by the user's saved order. Definitions with a
 * stored [CatalogPreference.orderIndex] come first, ascending; definitions
 * without one keep their original manifest order after them. The sort is
 * stable, so equal order values never shuffle the manifest order.
 */
fun orderCatalogDefinitions(
    definitions: List<HomeCatalogDefinition>,
    preferences: Map<String, CatalogPreference>
): List<HomeCatalogDefinition> =
    definitions.sortedWith(
        compareBy { preferences[it.key]?.orderIndex ?: Int.MAX_VALUE }
    )

/** Returns the user's custom title for a catalog, or its default title. */
fun resolveCatalogTitle(
    definition: HomeCatalogDefinition,
    preferences: Map<String, CatalogPreference>
): String =
    preferences[definition.key]?.customTitle?.takeIf { it.isNotBlank() }
        ?: definition.defaultTitle
