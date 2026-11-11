package com.euthopiar.ui.settings.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.euthopiar.core.addons.CatalogPreference
import com.euthopiar.core.addons.HomeCatalogDefinition
import com.euthopiar.core.repository.ProviderRepository
import com.euthopiar.data.preferences.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One catalog row as shown in the Catalog Manager, pairing the addon
 * definition with the user's saved preference for it.
 */
data class CatalogManagerItem(
    val definition: HomeCatalogDefinition,
    val onHome: Boolean,
    val orderIndex: Int,
    val customTitle: String?
) {
    /** Title shown on Home: the custom title, or the catalog's default title. */
    val displayTitle: String
        get() = customTitle?.takeIf { it.isNotBlank() } ?: definition.defaultTitle
}

/**
 * ViewModel for the Catalog Manager (Settings -> Reorder Catalogs).
 *
 * Loads the catalog definitions of the built-in TMDB rows and the enabled
 * Stremio addons and merges them with the saved preferences. Every change is
 * persisted immediately, so Home (which observes the same preferences)
 * reflects it on the next load.
 * Hiding a catalog only flips its [CatalogPreference.onHome] flag; the addon
 * that provides it is never disabled or uninstalled.
 */
class CatalogManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository = PreferencesRepository.getInstance(application)
    private val repository = ProviderRepository.Default

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _definitions = MutableStateFlow<List<HomeCatalogDefinition>>(emptyList())

    private val _items = MutableStateFlow<List<CatalogManagerItem>>(emptyList())
    val items: StateFlow<List<CatalogManagerItem>> = _items.asStateFlow()

    /** Catalogs shown on Home, in the user's saved order. */
    val onHomeItems: StateFlow<List<CatalogManagerItem>> = _items.map { items ->
        items.filter { it.onHome }.sortedWith(
            compareBy<CatalogManagerItem> { it.orderIndex }
                .thenBy { items.indexOf(it) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Catalogs hidden from Home, in manifest order. */
    val notOnHomeItems: StateFlow<List<CatalogManagerItem>> = _items.map { items ->
        items.filter { !it.onHome }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            preferencesRepository.catalogPreferences.collect { preferences ->
                rebuild(preferences)
            }
        }
        refreshCatalogs()
    }

    /** Reloads catalog definitions from the enabled addons and prunes stale preferences. */
    fun refreshCatalogs() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // TMDB rows first, matching Home's TMDB-first fetch order for
                // fresh users; Stremio addon catalogs follow. Both kinds share
                // the same keys, preferences and prune logic below, so hiding,
                // reordering or renaming a TMDB row works exactly like a
                // Stremio catalog. A TMDB preference whose key disappears
                // (feed renamed later) is pruned here by the known-keys check.
                val definitions = repository.getTmdbCatalogDefinitions() + repository.getCatalogDefinitions()
                _definitions.value = definitions
                val knownKeys = definitions.map { it.key }.toSet()
                val current = preferencesRepository.getCatalogPreferences()
                if (current.keys.any { it !in knownKeys }) {
                    preferencesRepository.saveCatalogPreferences(
                        current.filterKeys { it in knownKeys }
                    )
                }
                rebuild(preferencesRepository.getCatalogPreferences())
            } catch (_: Exception) {
                // Keep the previous list; the screen shows what it has.
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun rebuild(preferences: Map<String, CatalogPreference>) {
        _items.value = _definitions.value.map { definition ->
            val preference = preferences[definition.key]
            CatalogManagerItem(
                definition = definition,
                onHome = preference?.onHome ?: true,
                orderIndex = preference?.orderIndex ?: Int.MAX_VALUE,
                customTitle = preference?.customTitle
            )
        }
    }

    private fun prune(preferences: Map<String, CatalogPreference>): Map<String, CatalogPreference> {
        val knownKeys = _definitions.value.map { it.key }.toSet()
        return preferences.filterKeys { it in knownKeys }
    }

    /** Moves a Home catalog one step up (direction = -1) or down (+1). */
    fun moveCatalog(catalogKey: String, direction: Int) {
        val current = onHomeItems.value
        val index = current.indexOfFirst { it.definition.key == catalogKey }
        val target = index + direction
        if (index < 0 || target < 0 || target >= current.size) return
        val reordered = current.toMutableList().apply { add(target, removeAt(index)) }
        viewModelScope.launch {
            val saved = preferencesRepository.getCatalogPreferences().toMutableMap()
            reordered.forEachIndexed { position, item ->
                saved[item.definition.key] = CatalogPreference(
                    catalogKey = item.definition.key,
                    onHome = true,
                    orderIndex = position,
                    customTitle = item.customTitle
                )
            }
            preferencesRepository.saveCatalogPreferences(prune(saved))
        }
    }

    /** Shows or hides one catalog on Home. Hiding never touches its addon. */
    fun setCatalogOnHome(catalogKey: String, onHome: Boolean) {
        viewModelScope.launch {
            val saved = preferencesRepository.getCatalogPreferences().toMutableMap()
            val existing = saved[catalogKey]
            val orderIndex = if (onHome) {
                existing?.orderIndex?.takeIf { it != Int.MAX_VALUE }
                    ?: ((saved.values.filter { it.onHome }.maxOfOrNull { it.orderIndex } ?: -1) + 1)
            } else {
                existing?.orderIndex ?: Int.MAX_VALUE
            }
            saved[catalogKey] = CatalogPreference(
                catalogKey = catalogKey,
                onHome = onHome,
                orderIndex = orderIndex,
                customTitle = existing?.customTitle
            )
            preferencesRepository.saveCatalogPreferences(prune(saved))
        }
    }

    /** Renames one catalog. A blank title clears the custom title. */
    fun renameCatalog(catalogKey: String, newTitle: String) {
        viewModelScope.launch {
            val saved = preferencesRepository.getCatalogPreferences().toMutableMap()
            val existing = saved[catalogKey]
            saved[catalogKey] = CatalogPreference(
                catalogKey = catalogKey,
                onHome = existing?.onHome ?: true,
                orderIndex = existing?.orderIndex ?: Int.MAX_VALUE,
                customTitle = newTitle.trim().takeIf { it.isNotEmpty() }
            )
            preferencesRepository.saveCatalogPreferences(prune(saved))
        }
    }

    /** Hides every catalog from Home. Addons stay installed and searchable. */
    fun hideAllFromHome() {
        viewModelScope.launch {
            val saved = preferencesRepository.getCatalogPreferences().toMutableMap()
            _definitions.value.forEach { definition ->
                val existing = saved[definition.key]
                saved[definition.key] = CatalogPreference(
                    catalogKey = definition.key,
                    onHome = false,
                    orderIndex = existing?.orderIndex ?: Int.MAX_VALUE,
                    customTitle = existing?.customTitle
                )
            }
            preferencesRepository.saveCatalogPreferences(prune(saved))
        }
    }

    /** Shows every catalog on Home, keeping each catalog's saved position. */
    fun showAllOnHome() {
        viewModelScope.launch {
            val saved = preferencesRepository.getCatalogPreferences().toMutableMap()
            _definitions.value.forEach { definition ->
                val existing = saved[definition.key]
                val orderIndex = existing?.orderIndex?.takeIf { it != Int.MAX_VALUE }
                    ?: ((saved.values.filter { it.onHome }.maxOfOrNull { it.orderIndex } ?: -1) + 1)
                saved[definition.key] = CatalogPreference(
                    catalogKey = definition.key,
                    onHome = true,
                    orderIndex = orderIndex,
                    customTitle = existing?.customTitle
                )
            }
            preferencesRepository.saveCatalogPreferences(prune(saved))
        }
    }

    /** Clears every saved order, visibility and rename back to the defaults. */
    fun resetToDefaults() {
        viewModelScope.launch {
            preferencesRepository.saveCatalogPreferences(emptyMap())
        }
    }
}
