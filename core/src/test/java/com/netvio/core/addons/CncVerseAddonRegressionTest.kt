package com.netvio.core.addons

import com.netvio.core.matcher.MatchScorer
import com.netvio.core.model.ContentType
import com.netvio.core.model.MatchHints
import com.netvio.core.model.MediaType
import com.netvio.core.network.PlayerPlaybackNetworking
import com.netvio.core.streams.StreamItem
import com.netvio.core.streams.StreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CncVerseAddonRegressionTest {

    @Test
    fun `other type meta with episode videos is classified as TV_SERIES with preserved stremio type`() {
        val payload = """
            {
              "meta": {
                "id": "cnc:SG90c3Rhcjo6MQ==",
                "type": "other",
                "name": "Ironheart",
                "poster": "https://img.example.com/poster.jpg",
                "videos": [
                  { "id": "cnc:ep1", "title": "Episode 1", "season": 1, "episode": 1 },
                  { "id": "cnc:ep2", "title": "Episode 2", "season": 1, "episode": 2 }
                ]
              }
            }
        """.trimIndent()

        val detail = AddonMetaParser.parseMeta(payload, addonName = "CNCVerse Bridge")
        assertNotNull(detail)
        assertEquals(MediaType.TV_SERIES, detail!!.type)
        assertEquals("stremio_type:other", detail.url)
        assertEquals(2, detail.episodes.size)
        assertEquals("cnc:ep2", detail.episodes[1].id)
    }

    @Test
    fun `other type meta without videos remains MOVIE`() {
        val payload = """
            {
              "meta": {
                "id": "cnc:TmV0ZmxpeDo6MQ==",
                "type": "other",
                "name": "Ranabaali",
                "poster": "https://img.example.com/poster.jpg",
                "videos": []
              }
            }
        """.trimIndent()

        val detail = AddonMetaParser.parseMeta(payload, addonName = "CNCVerse Bridge")
        assertNotNull(detail)
        assertEquals(MediaType.MOVIE, detail!!.type)
        assertEquals("stremio_type:other", detail.url)
        assertEquals(1, detail.episodes.size)
    }

    @Test
    fun `home catalog retains poster card when name is braille blank`() {
        val payload = """
            {
              "metas": [
                {
                  "id": "cnc:Hotstar::123",
                  "type": "other",
                  "name": "\u2800",
                  "poster": "https://img.example.com/ironheart.jpg"
                }
              ]
            }
        """.trimIndent()

        val items = HomeCatalogParser.parseCatalog(payload, addonName = "CNCVerse Bridge")
        assertEquals(1, items.size)
        assertEquals("", items[0].title)
        assertEquals("stremio_type:other", items[0].url)
    }

    @Test
    fun `match scorer accepts multiline emoji stream title when filename hint is absent`() {
        val stream = StreamItem(
            name = "🎬CNCVerse 🧊 2160p",
            title = "🍿 Inception\n🏷️ WEB-DL SDR HEVC DDP5 DTS-HD X265 [30.03 GB]\n🎞️ 2160p • HEVC",
            url = "https://cdn.example.com/Inception (2010) [Hindi].mkv",
            addonId = "cncverse",
            addonName = "CNCVerse Bridge"
        )
        val hints = MatchHints(
            tmdbId = 27205,
            type = ContentType.MOVIE,
            titles = setOf("Inception"),
            year = 2010
        )

        val accepted = MatchScorer.scoreStream(hints, stream)
        assertTrue("Expected multiline CNCVerse stream to be accepted, got: ${accepted.reason}", accepted.isAccepted)

        val mismatched = MatchScorer.scoreStream(
            MatchHints(
                tmdbId = 603,
                type = ContentType.MOVIE,
                titles = setOf("The Matrix"),
                year = 1999
            ),
            stream
        )
        assertFalse(
            "Expected unrelated title to be rejected, got score=${mismatched.score}, reason=${mismatched.reason}",
            mismatched.isAccepted
        )
    }

    @Test
    fun `stream parser encodes raw spaces and path brackets while keeping ipv6 loopback intact`() {
        val payload = """
            {
              "streams": [
                {
                  "name": "🎬CNCVerse 1080p",
                  "title": "🍿 Inception\n🏷️ WEB-DL [2.4 GB]",
                  "url": "https://cdn.example.com/files/Inception (2010) [Hindi Audio].mkv?token=abc"
                }
              ]
            }
        """.trimIndent()

        val streams = StreamParser.parse(payload, addonName = "CNCVerse Bridge", addonId = "cncverse")
        assertEquals(1, streams.size)
        assertEquals(
            "https://cdn.example.com/files/Inception%20(2010)%20%5BHindi%20Audio%5D.mkv?token=abc",
            streams[0].url
        )
        assertEquals(
            "http://[::1]:8080/live%20stream.mkv",
            PlayerPlaybackNetworking.sanitizeHttpStreamUrl("http://[::1]:8080/live stream.mkv")
        )
    }
}
