package com.netvio.data.tv

import kotlinx.serialization.Serializable
import java.net.URI

object TvCompanionProtocol {
    const val PROTOCOL_VERSION: Int = 1
    const val MDNS_SERVICE_TYPE: String = "_euthopiar._tcp."
    const val DEFAULT_PORT: Int = 47831
}

enum class TvConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

@Serializable
data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = TvCompanionProtocol.DEFAULT_PORT,
    val appVersion: String? = null,
    val isPaired: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis()
)

@Serializable
data class TvPairRequest(
    val pin: String,
    val deviceName: String,
    val protocolVersion: Int = TvCompanionProtocol.PROTOCOL_VERSION
)

@Serializable
data class TvPairResponse(
    val ok: Boolean = false,
    val token: String = "",
    val tvId: String = "",
    val tvName: String = "",
    val error: String? = null
)

@Serializable
data class TvHandoffPayload(
    val action: String = "play",
    val mediaId: String,
    val title: String,
    val mediaType: String,
    val provider: String? = null,
    val episodeData: String? = null,
    val episodeTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val streamUrl: String? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
    val isM3u8: Boolean = false,
    val subtitleUrl: String? = null,
    val subtitleLabel: String? = null,
    val profileId: String = "default_user"
)

@Serializable
data class TvRemoteCommand(
    val command: String,
    val valueMs: Long? = null,
    val text: String? = null,
    val key: String? = null
)

@Serializable
data class TvNowPlayingState(
    val isPlaying: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val episodeTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val posterUrl: String? = null,
    val activeInputFocused: Boolean = false,
    val currentInputHint: String? = null
)

@Serializable
data class TvConfigPushPayload(
    val repoUrls: List<String> = emptyList(),
    val stremioAddonUrls: List<String> = emptyList(),
    val activeDebridProvider: String? = null,
    val realDebridApiKey: String? = null,
    val allDebridApiKey: String? = null,
    val torBoxApiKey: String? = null,
    val debridEnabled: Boolean? = null
)

@Serializable
data class TvWsEnvelope(
    val type: String,
    val status: TvNowPlayingState? = null,
    val command: TvRemoteCommand? = null,
    val error: String? = null
)

object TvHandoffSanitizer {

    fun isLocalOnlyUrl(url: String?): Boolean {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val lower = raw.lowercase()
        if (lower.startsWith("file://") || lower.startsWith("content://") || raw.startsWith("/")) {
            return true
        }
        val host = runCatching { URI(raw).host }.getOrNull()?.lowercase()?.trim('[', ']')
        if (host != null) {
            return host == "127.0.0.1" ||
                host == "localhost" ||
                host == "0.0.0.0" ||
                host == "::1" ||
                host.startsWith("127.")
        }
        return false
    }

    fun sanitizeForRemoteTv(payload: TvHandoffPayload): TvHandoffPayload {
        val cleanStreamUrl = if (isLocalOnlyUrl(payload.streamUrl)) null else payload.streamUrl?.trim()?.ifBlank { null }
        val cleanSubUrl = if (isLocalOnlyUrl(payload.subtitleUrl)) null else payload.subtitleUrl?.trim()?.ifBlank { null }
        return payload.copy(
            streamUrl = cleanStreamUrl,
            streamHeaders = if (cleanStreamUrl != null) payload.streamHeaders else emptyMap(),
            isM3u8 = if (cleanStreamUrl != null) payload.isM3u8 else false,
            subtitleUrl = cleanSubUrl,
            subtitleLabel = if (cleanSubUrl != null) payload.subtitleLabel else null
        )
    }
}

object TvEndpointParser {

    fun parseHostPort(
        rawInput: String,
        defaultPort: Int = TvCompanionProtocol.DEFAULT_PORT
    ): Pair<String, Int>? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val parsed = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = parsed.host?.trim()?.trim('[', ']')?.takeIf { it.isNotBlank() } ?: return null
        if (host.any { it.isWhitespace() }) return null

        val port = if (parsed.port in 1..65535) {
            parsed.port
        } else if (parsed.port == -1) {
            defaultPort
        } else {
            return null
        }

        // Reject malformed explicit port suffixes that URI.port left as -1 (e.g., "192.168.1.5:99999")
        val afterHost = trimmed
            .substringAfter("://", trimmed)
            .substringBefore("/")
        val colonIdx = afterHost.lastIndexOf(':')
        if (colonIdx >= 0 && !afterHost.endsWith("]")) {
            val portStr = afterHost.substring(colonIdx + 1)
            val explicitPort = portStr.toIntOrNull() ?: return null
            if (explicitPort !in 1..65535) return null
        }

        return host to port
    }
}
