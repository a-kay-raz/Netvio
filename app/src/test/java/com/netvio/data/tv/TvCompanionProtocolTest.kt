package com.netvio.data.tv

import com.netvio.ui.player.CastController
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCompanionProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun sanitizeForRemoteTv_stripsLocalhostLoopbackAndFileUrls_whileKeepingMetadata() {
        val localUrls = listOf(
            "http://127.0.0.1:8080/stream.mp4",
            "http://localhost:9000/video.mkv",
            "http://[::1]:8080/a.m3u8",
            "file:///storage/emulated/0/movie.mp4",
            "content://media/external/video/media/42",
            "/data/local/tmp/a.mp4"
        )

        for (localUrl in localUrls) {
            assertTrue("Expected local-only URL: $localUrl", TvHandoffSanitizer.isLocalOnlyUrl(localUrl))

            val sanitized = TvHandoffSanitizer.sanitizeForRemoteTv(
                TvHandoffPayload(
                    action = "play",
                    mediaId = "tt0944947",
                    title = "Game of Thrones",
                    mediaType = "SERIES",
                    provider = "TorrentProvider",
                    episodeData = "ep-101-token",
                    episodeTitle = "Winter Is Coming",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    positionMs = 425_000L,
                    durationMs = 3_600_000L,
                    posterUrl = "https://image.tmdb.org/poster.jpg",
                    backdropUrl = "https://image.tmdb.org/backdrop.jpg",
                    streamUrl = localUrl,
                    streamHeaders = mapOf("Referer" to "https://example.com"),
                    isM3u8 = true,
                    subtitleUrl = "http://127.0.0.1:8080/sub.vtt",
                    subtitleLabel = "English",
                    profileId = "default_user"
                )
            )

            assertNull(sanitized.streamUrl)
            assertTrue(sanitized.streamHeaders.isEmpty())
            assertFalse(sanitized.isM3u8)
            assertNull(sanitized.subtitleUrl)
            assertNull(sanitized.subtitleLabel)

            // Canonical metadata survives for native TV extraction
            assertEquals("tt0944947", sanitized.mediaId)
            assertEquals("TorrentProvider", sanitized.provider)
            assertEquals("ep-101-token", sanitized.episodeData)
            assertEquals(1, sanitized.seasonNumber)
            assertEquals(1, sanitized.episodeNumber)
            assertEquals(425_000L, sanitized.positionMs)
        }
    }

    @Test
    fun sanitizeForRemoteTv_preservesRemoteHttpsUrlsHeadersAndSubtitles() {
        val remoteStream = "https://cdn.example.com/master.m3u8"
        val remoteSub = "https://subs.example.com/en.vtt"
        assertFalse(TvHandoffSanitizer.isLocalOnlyUrl(remoteStream))
        assertFalse(TvHandoffSanitizer.isLocalOnlyUrl(remoteSub))

        val sanitized = TvHandoffSanitizer.sanitizeForRemoteTv(
            TvHandoffPayload(
                mediaId = "tt1375666",
                title = "Inception",
                mediaType = "MOVIE",
                provider = "CineStream",
                positionMs = 90_000L,
                streamUrl = "  $remoteStream  ",
                streamHeaders = mapOf("User-Agent" to "Netvio", "Referer" to "https://cdn.example.com"),
                isM3u8 = true,
                subtitleUrl = "  $remoteSub ",
                subtitleLabel = "English"
            )
        )

        assertEquals(remoteStream, sanitized.streamUrl)
        assertEquals(
            mapOf("User-Agent" to "Netvio", "Referer" to "https://cdn.example.com"),
            sanitized.streamHeaders
        )
        assertTrue(sanitized.isM3u8)
        assertEquals(remoteSub, sanitized.subtitleUrl)
        assertEquals("English", sanitized.subtitleLabel)
    }

    @Test
    fun parseHostPort_parsesIpv4ExplicitPortAndMdnsHost() {
        assertEquals("192.168.1.42" to 47831, TvEndpointParser.parseHostPort("192.168.1.42"))
        assertEquals("192.168.1.42" to 8090, TvEndpointParser.parseHostPort("192.168.1.42:8090"))
        assertEquals(
            "livingroom.local" to 47831,
            TvEndpointParser.parseHostPort("http://livingroom.local:47831")
        )
    }

    @Test
    fun parseHostPort_rejectsInvalidOrOutOfRangeEndpoints() {
        assertNull(TvEndpointParser.parseHostPort(""))
        assertNull(TvEndpointParser.parseHostPort("   "))
        assertNull(TvEndpointParser.parseHostPort("192.168.1.42:99999"))
        assertNull(TvEndpointParser.parseHostPort("192.168.1.42:abc"))
        assertNull(TvEndpointParser.parseHostPort("192.168.1.42:0"))
    }

    @Test
    fun jsonSerialization_roundTripsAllCompanionContracts() {
        val handoff = TvHandoffPayload(
            action = "play",
            mediaId = "12345",
            title = "Severance",
            mediaType = "SERIES",
            provider = "StreamBox",
            episodeData = "s02e01",
            episodeTitle = "Hello, Ms. Cobel",
            seasonNumber = 2,
            episodeNumber = 1,
            positionMs = 123_456L,
            durationMs = 2_700_000L,
            posterUrl = "https://img/p.jpg",
            backdropUrl = "https://img/b.jpg",
            streamUrl = "https://cdn/s02e01.m3u8",
            streamHeaders = mapOf("Origin" to "https://cdn"),
            isM3u8 = true,
            subtitleUrl = "https://cdn/sub.vtt",
            subtitleLabel = "English",
            profileId = "profile_kids"
        )
        assertEquals(handoff, json.decodeFromString<TvHandoffPayload>(json.encodeToString(handoff)))

        val cmd = TvRemoteCommand(command = "seek_relative", valueMs = -10_000L, text = null, key = null)
        assertEquals(cmd, json.decodeFromString<TvRemoteCommand>(json.encodeToString(cmd)))

        val status = TvNowPlayingState(
            isPlaying = true,
            mediaId = "12345",
            title = "Severance",
            episodeTitle = "Hello, Ms. Cobel",
            seasonNumber = 2,
            episodeNumber = 1,
            positionMs = 130_000L,
            durationMs = 2_700_000L,
            posterUrl = "https://img/p.jpg",
            activeInputFocused = true,
            currentInputHint = "Search titles"
        )
        assertEquals(status, json.decodeFromString<TvNowPlayingState>(json.encodeToString(status)))

        val config = TvConfigPushPayload(
            repoUrls = listOf("https://repo.example/index.json"),
            stremioAddonUrls = listOf("https://addon.example/manifest.json"),
            activeDebridProvider = "REAL_DEBRID",
            realDebridApiKey = "rd-secret-key",
            allDebridApiKey = null,
            torBoxApiKey = "tb-key",
            debridEnabled = true
        )
        assertEquals(config, json.decodeFromString<TvConfigPushPayload>(json.encodeToString(config)))

        val envelope = TvWsEnvelope(type = "status_update", status = status, command = cmd, error = null)
        assertEquals(envelope, json.decodeFromString<TvWsEnvelope>(json.encodeToString(envelope)))
    }

    @Test
    fun parseUpnpDeviceDescription_extractsFriendlyNameAndResolvesRelativeControlUrl() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Living Room OLED &amp; Bar</friendlyName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/RenderingControl/control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/AVTransport/control</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        val (name, controlUrl) = CastController.parseUpnpDeviceDescription(
            xml = xml,
            locationUrl = "http://192.168.1.25:49152/description.xml"
        )

        assertEquals("Living Room OLED & Bar", name)
        assertEquals("http://192.168.1.25:49152/AVTransport/control", controlUrl)
    }

    @Test
    fun parseUpnpDeviceDescription_handlesAbsoluteUrlBlankXmlAndMissingService() {
        val absoluteXml = """
            <root><device><friendlyName>Projector</friendlyName>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>http://192.168.1.25:5555/ctl/AVT</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent()
        val (absName, absUrl) = CastController.parseUpnpDeviceDescription(
            absoluteXml,
            "http://192.168.1.25:49152/desc.xml"
        )
        assertEquals("Projector", absName)
        assertEquals("http://192.168.1.25:5555/ctl/AVT", absUrl)

        assertEquals(null to null, CastController.parseUpnpDeviceDescription("", "http://192.168.1.25/desc.xml"))

        val noAvtXml = """
            <root><device><friendlyName>Speaker Only</friendlyName>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/rc</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent()
        val (spkName, spkUrl) = CastController.parseUpnpDeviceDescription(
            noAvtXml,
            "http://192.168.1.30:49152/desc.xml"
        )
        assertEquals("Speaker Only", spkName)
        assertNull(spkUrl)
    }

    @Test
    fun xmlEscape_escapesAllXmlSpecialCharacters() {
        val raw = """A & B <tag attr="1" single='2'>"""
        assertEquals(
            "A &amp; B &lt;tag attr=&quot;1&quot; single=&apos;2&apos;&gt;",
            CastController.xmlEscape(raw)
        )
    }
}
