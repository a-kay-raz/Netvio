package com.netvio

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.netvio.app.plugin.PluginRepositoryService
import com.netvio.core.addons.CatalogPreferenceProvider
import com.netvio.core.network.DohDns
import com.netvio.core.registry.PluginRegistry
import com.netvio.core.repository.ProviderRepository
import com.netvio.core.util.AppSessionManager
import com.netvio.data.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

class NetvioApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("netvio_session", Context.MODE_PRIVATE)
        val lastIndex = prefs.getInt("launch_session_index", 0)
        val nextIndex = lastIndex + 1
        prefs.edit().putInt("launch_session_index", nextIndex).apply()
        AppSessionManager.initialize(nextIndex)

        // Initialize 50MB HTTP disk cache and connection pool immediately
        DohDns.initHttpCache(cacheDir)

        // Immediately warm DNS in parallel for all critical media, poster CDN, and metadata hosts
        // BEFORE any UI, catalog, or Coil image requests fire!
        DohDns.DEFAULT.prefetch(
            "euthopiar-vault.sehgalvansh716.workers.dev",
            "image.tmdb.org",
            "api.themoviedb.org",
            "graphql.anilist.co"
        )

        // Synchronously initialize JsPluginStorage, AddonRepository, and P2P engine instances
        com.netvio.core.js.JsPluginStorage.getInstance(this)
        com.netvio.core.addons.AddonRepository.getInstance(this)
        com.netvio.core.p2p.P2pStreamingEngine.initialize(this)
        com.netvio.core.p2p.P2pSettingsRepository.initialize(this)
        com.netvio.core.browser.HeadlessWebResolver.getInstance(this)

        // Feed the saved Catalog Manager preferences into the home catalog
        // pipeline so Home reflects the user's row order, visibility and names.
        ProviderRepository.Default.catalogPreferenceProvider =
            CatalogPreferenceProvider {
                PreferencesRepository.getInstance(this@NetvioApp).getCatalogPreferences()
            }

        val appScope = CoroutineScope(Dispatchers.IO)

        // Critical startup path: initialize plugins & restore active provider immediately
        appScope.launch {
            // Disk scan, so it runs off the main thread instead of in onCreate.
            com.netvio.ui.player.sequential.SequentialStreamManager.cleanOrphanedCache(this@NetvioApp)
            try {
                com.netvio.core.js.JsPluginStorage.getInstance(this@NetvioApp).initialize()
                com.netvio.core.addons.AddonRepository.getInstance(this@NetvioApp).initialize()
            } catch (_: Exception) {}

            try {
                val savedProvider = PreferencesRepository.getInstance(this@NetvioApp).activeProvider.first()
                if (savedProvider.isNotBlank()) {
                    PluginRegistry.Default.setActivePlugin(savedProvider)
                }
            } catch (_: Exception) {}

            try {
                PluginRepositoryService.getInstance(this@NetvioApp).initializePlugins()
            } catch (_: Exception) {}

            try {
                val prefsRepo = PreferencesRepository.getInstance(this@NetvioApp)
                val savedProvider = prefsRepo.activeProvider.first()
                if (savedProvider.isNotBlank() && PluginRegistry.Default.getPlugin(savedProvider) != null) {
                    PluginRegistry.Default.setActivePlugin(savedProvider)
                } else {
                    val firstPublic = PluginRepositoryService.getInstance(this@NetvioApp)
                        .getInstalledPublicPlugins()
                        .firstOrNull { it.isEnabled }
                    if (firstPublic != null) {
                        PluginRegistry.Default.setActivePlugin(firstPublic.name)
                        prefsRepo.setActiveProvider(firstPublic.name)
                    }
                }
            } catch (_: Exception) {}

            // Non-blocking plugin auto-update check after startup settles
            launch {
                delay(6_000L)
                try {
                    val updated = PluginRepositoryService.getInstance(this@NetvioApp)
                        .checkAndAutoUpdatePlugins(installNewFromRepos = false)
                    if (updated > 0) {
                        android.util.Log.i("NetvioApp", "Auto-updated $updated provider plugin(s) on launch")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("NetvioApp", "Startup plugin auto-update error: ${e.message}")
                }
            }
            launch(Dispatchers.IO) {
                // Non-critical: poster repair (database work) also waits for
                // the first frame.
                this@NetvioApp.awaitFirstFrame()
                try {
                    com.netvio.data.sync.MediaSyncManager.getInstance(this@NetvioApp)
                        .repairMissingLocalPosters()
                } catch (_: Exception) {}
            }
        }

        // Independent preference observers (P2P policy & Debrid) — never block plugin init
        appScope.launch {
            try {
                val prefsRepo = PreferencesRepository.getInstance(this@NetvioApp)
                launch {
                    prefsRepo.p2pStreamsEnabled.collect { enabled ->
                        com.netvio.core.p2p.P2pSettingsRepository.setP2pEnabled(enabled)
                    }
                }
                launch {
                    kotlinx.coroutines.flow.combine(
                        prefsRepo.debridEnabled,
                        prefsRepo.activeDebridProvider,
                        prefsRepo.realDebridApiKey,
                        prefsRepo.allDebridApiKey,
                        prefsRepo.torBoxApiKey
                    ) { enabled, provider, rdKey, adKey, tbKey ->
                        if (!enabled) {
                            com.netvio.core.debrid.DebridManager.getInstance().setActiveProviders(emptyList())
                            return@combine
                        }
                        val pType = com.netvio.core.debrid.DebridProviderType.fromId(provider)
                        val key = when (pType) {
                            com.netvio.core.debrid.DebridProviderType.REAL_DEBRID -> rdKey
                            com.netvio.core.debrid.DebridProviderType.ALL_DEBRID -> adKey
                            com.netvio.core.debrid.DebridProviderType.TORBOX -> tbKey
                            com.netvio.core.debrid.DebridProviderType.NONE -> ""
                        }
                        if (pType != com.netvio.core.debrid.DebridProviderType.NONE && key.isNotBlank()) {
                            com.netvio.core.debrid.DebridManager.getInstance().setActiveProviders(
                                listOf(com.netvio.core.debrid.DebridManager.ActiveDebridProvider(pType, true))
                            )
                        } else {
                            com.netvio.core.debrid.DebridManager.getInstance().setActiveProviders(emptyList())
                        }
                    }.collect { }
                }
            } catch (_: Exception) {}
        }

        // Deferred smart cache maintenance: runs well after Home catalog & posters finish hydrating
        // so it NEVER competes with startup disk I/O or network fetches.
        appScope.launch {
            delay(20_000L)
            try {
                com.netvio.data.cache.AppCacheManager.runAutomaticMaintenance(this@NetvioApp)
            } catch (_: Exception) {}
        }
    }

    /**
     * Suspends until the splash ignition has finished (first frame shown),
     * or 15s at most so this work still runs if ignition never reports
     * finished. On a warm start ignition is already finished and this
     * returns immediately.
     */
    private suspend fun awaitFirstFrame() {
        try {
            withTimeoutOrNull(15_000L) {
                com.netvio.ui.splash.AppLaunchCoordinator.isIgnitionActive.first { !it }
            }
        } catch (_: Exception) {}
    }

    private val imageHttpClient by lazy {
        DohDns.sharedBaseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 20
                }
            )
            .addInterceptor { chain ->
                val orig = chain.request()
                val req = if (orig.header("User-Agent") == null) {
                    orig.newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        )
                        .build()
                } else {
                    orig
                }
                try {
                    val response = chain.proceed(req)
                    if (!response.isSuccessful && (response.code == 429 || response.code in 500..599)) {
                        response.close()
                        chain.proceed(req)
                    } else {
                        response
                    }
                } catch (e: java.io.IOException) {
                    if (chain.call().isCanceled()) throw e
                    chain.proceed(req)
                }
            }
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(com.netvio.data.cache.AppCacheManager.getCoilDiskCacheCapBytes(this))
                    .build()
            }
            .bitmapConfig(Bitmap.Config.HARDWARE)
            .crossfade(220)
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Coil 3 trims memory proportionally on routine trim events.
        // Only clear the entire bitmap cache under extreme background eviction pressure
        // so browsing between Home, Details, Search, and Library never forces poster reloads.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            runCatching {
                coil3.SingletonImageLoader.get(this).memoryCache?.clear()
            }
        }
    }
}
