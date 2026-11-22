package com.netvio.ui.details.downloads

import com.netvio.core.debrid.DebridManager
import com.netvio.core.debrid.DebridProviderType
import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.core.streams.StreamItem
import com.netvio.download.DownloadRepository
import com.netvio.download.StoragePreflightResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DebridCredentials(
    val enabled: Boolean,
    val providerType: DebridProviderType,
    val apiKey: String
)

internal fun resolveEffectiveDebridCredentials(
    debridEnabled: Boolean,
    activeProviderId: String,
    realDebridKey: String,
    allDebridKey: String,
    torBoxKey: String
): DebridCredentials {
    if (!debridEnabled) return DebridCredentials(false, DebridProviderType.NONE, "")
    val preferred = DebridProviderType.fromId(activeProviderId)
    var key = when (preferred) {
        DebridProviderType.REAL_DEBRID -> realDebridKey
        DebridProviderType.ALL_DEBRID -> allDebridKey
        DebridProviderType.TORBOX -> torBoxKey
        DebridProviderType.NONE -> ""
    }
    var provider = preferred
    if (key.isBlank()) {
        when {
            realDebridKey.isNotBlank() -> {
                key = realDebridKey
                provider = DebridProviderType.REAL_DEBRID
            }
            allDebridKey.isNotBlank() -> {
                key = allDebridKey
                provider = DebridProviderType.ALL_DEBRID
            }
            torBoxKey.isNotBlank() -> {
                key = torBoxKey
                provider = DebridProviderType.TORBOX
            }
        }
    }
    val active = key.isNotBlank() && provider != DebridProviderType.NONE
    return DebridCredentials(
        enabled = active,
        providerType = if (active) provider else DebridProviderType.NONE,
        apiKey = key
    )
}

internal data class StreamResolutionOutcome(
    val preflight: StoragePreflightResult,
    val statusMessage: String,
    val resolvedStream: StreamItem
)

/**
 * Resolves Debrid magnets (with safe P2P fallback) and enqueues direct or torrent streams
 * into [DownloadRepository] through a single shared code path.
 */
internal suspend fun resolveAndEnqueueStream(
    downloadRepository: DownloadRepository,
    detail: MediaDetail,
    episode: EpisodeItem?,
    stream: StreamItem,
    debrid: DebridCredentials,
    allowP2pFallback: Boolean = com.netvio.core.p2p.P2pSettingsRepository.isP2pEnabled(),
    onProgressNotice: ((String) -> Unit)? = null
): StreamResolutionOutcome {
    val cleanQuality = stream.quality ?: if (stream.isTorrentStream) "Torrent" else "Direct Stream"
    if (!stream.isTorrentStream) {
        val preflight = downloadRepository.enqueueFromStream(detail, episode, stream)
        return StreamResolutionOutcome(preflight, "Downloading $cleanQuality...", stream)
    }

    val magnet = stream.effectiveMagnetOrHash
        ?: return StreamResolutionOutcome(
            StoragePreflightResult.UnsupportedStream("Invalid torrent magnet"),
            "Invalid torrent magnet",
            stream
        )

    if (debrid.enabled) {
        onProgressNotice?.invoke("Unrestricting with ${debrid.providerType.displayName}...")
        val resolveResult = withContext(Dispatchers.IO) {
            DebridManager.getInstance().resolveStream(
                providerType = debrid.providerType,
                apiKey = debrid.apiKey,
                linkOrMagnet = magnet,
                fileIdx = stream.fileIdx,
                season = episode?.seasonNumber,
                episode = episode?.episodeNumber,
                originalResolutionLabel = stream.quality
            )
        }
        if (resolveResult.isSuccess) {
            val resolved = resolveResult.getOrThrow()
            if (resolved.url.isNotBlank() && resolved.url.startsWith("http", ignoreCase = true)) {
                val directStream = stream.copy(url = resolved.url, headers = resolved.headers)
                val preflight = downloadRepository.enqueueFromStream(detail, episode, directStream)
                return StreamResolutionOutcome(preflight, "Downloading $cleanQuality...", directStream)
            }
            if (allowP2pFallback) {
                onProgressNotice?.invoke("Debrid returned invalid URL, falling back to P2P...")
            }
        } else {
            val err = resolveResult.exceptionOrNull()?.message ?: "Debrid resolution failed"
            if (!allowP2pFallback) {
                return StreamResolutionOutcome(
                    StoragePreflightResult.UnsupportedStream("Debrid failed and P2P is disabled: $err"),
                    "Debrid failed (P2P disabled)",
                    stream
                )
            }
            onProgressNotice?.invoke("Debrid fallback to P2P: $err")
        }
    }

    if (!allowP2pFallback) {
        return StreamResolutionOutcome(
            StoragePreflightResult.UnsupportedStream("P2P streams & torrent downloads are disabled in settings"),
            "P2P downloads disabled in settings",
            stream
        )
    }

    val torrentStream = stream.copy(url = magnet)
    val preflight = downloadRepository.enqueueFromStream(detail, episode, torrentStream)
    return StreamResolutionOutcome(preflight, "Starting Native P2P Download...", torrentStream)
}
