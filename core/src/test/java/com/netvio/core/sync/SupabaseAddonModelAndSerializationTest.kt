package com.netvio.core.sync

import com.netvio.core.sync.supabase.CloudPlaylist
import com.netvio.core.sync.supabase.CloudSyncBundle
import com.netvio.core.sync.supabase.CloudUserAddon
import com.netvio.core.sync.supabase.SupabaseAuthClient
import com.netvio.core.sync.supabase.SupabaseSession
import com.netvio.core.sync.supabase.SupabaseTokenAuthenticator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SupabaseAddonModelAndSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun testCloudUserAddonSerializationRoundTrip() {
        val addon = CloudUserAddon(
            userId = "user_abc_123",
            manifestUrl = "https://torrentio.strem.fun/manifest.json",
            addonType = "stremio",
            name = "Torrentio",
            isDeleted = false,
            clientUpdatedAtMs = 1700000000000L,
            updatedAt = "2026-11-09T12:00:00Z"
        )

        val serialized = json.encodeToString(addon)
        assertTrue(serialized.contains("\"user_id\":\"user_abc_123\""))
        assertTrue(serialized.contains("\"manifest_url\":\"https://torrentio.strem.fun/manifest.json\""))
        assertTrue(serialized.contains("\"addon_type\":\"stremio\""))
        assertTrue(serialized.contains("\"name\":\"Torrentio\""))
        assertTrue(serialized.contains("\"is_deleted\":false"))

        val deserialized = json.decodeFromString<CloudUserAddon>(serialized)
        assertEquals(addon.userId, deserialized.userId)
        assertEquals(addon.manifestUrl, deserialized.manifestUrl)
        assertEquals(addon.addonType, deserialized.addonType)
        assertEquals(addon.name, deserialized.name)
        assertFalse(deserialized.isDeleted)
        assertEquals(1700000000000L, deserialized.clientUpdatedAtMs)
        assertEquals("2026-11-09T12:00:00Z", deserialized.updatedAt)
    }

    @Test
    fun testCloudUserAddonDeduplicationKeepsLatest() {
        val oldItem = CloudUserAddon(
            userId = "user_1",
            manifestUrl = "https://example.com/manifest.json",
            addonType = "stremio",
            name = "Old Name",
            isDeleted = false,
            clientUpdatedAtMs = 1000L
        )
        val newItem = CloudUserAddon(
            userId = "user_1",
            manifestUrl = "https://example.com/manifest.json",
            addonType = "stremio",
            name = "New Name",
            isDeleted = false,
            clientUpdatedAtMs = 5000L
        )
        val deletedItem = CloudUserAddon(
            userId = "user_1",
            manifestUrl = "https://example.com/manifest.json",
            addonType = "stremio",
            name = "New Name",
            isDeleted = true,
            clientUpdatedAtMs = 10000L
        )

        val list = listOf(oldItem, newItem, deletedItem)
        val deduped = list
            .groupBy { "${it.userId}::${it.manifestUrl}" }
            .mapValues { (_, group) ->
                group.maxByOrNull { it.clientUpdatedAtMs }!!
            }
            .values
            .toList()

        assertEquals(1, deduped.size)
        assertEquals(10000L, deduped[0].clientUpdatedAtMs)
        assertTrue(deduped[0].isDeleted)
    }

    @Test
    fun testJsRepoTypeSerialization() {
        val repo = CloudUserAddon(
            userId = "user_999",
            manifestUrl = "https://raw.githubusercontent.com/org/repo/main/manifest.json",
            addonType = "js_repo",
            name = null,
            isDeleted = false,
            clientUpdatedAtMs = 2000L
        )

        val serialized = json.encodeToString(repo)
        assertTrue(serialized.contains("\"addon_type\":\"js_repo\""))

        val deserialized = json.decodeFromString<CloudUserAddon>(serialized)
        assertEquals("js_repo", deserialized.addonType)
        assertNull(deserialized.name)
    }

    @Test
    fun testCloudPlaylistDecodesBothStringAndRawJsonArray() {
        val directTableJson = """
            {
              "user_id": "u1",
              "profile_id": "default_user",
              "playlist_id": "pl_1",
              "title": "Favorites",
              "is_system": true,
              "items_json": "[{\"mediaId\":\"tt0133093\"}]",
              "created_at_ms": 1000,
              "last_played_at_ms": 2000,
              "is_deleted": false,
              "client_updated_at_ms": 3000
            }
        """.trimIndent()
        val fromTable = json.decodeFromString<CloudPlaylist>(directTableJson)
        assertTrue(fromTable.itemsJson.contains("tt0133093"))

        val rpcJsonbPayload = """
            {
              "user_id": "u1",
              "profile_id": "default_user",
              "playlist_id": "pl_1",
              "title": "Favorites",
              "is_system": true,
              "items_json": [{"mediaId":"tt0133093"}],
              "created_at_ms": 1000,
              "last_played_at_ms": 2000,
              "is_deleted": false,
              "client_updated_at_ms": 3000
            }
        """.trimIndent()
        val fromRpc = json.decodeFromString<CloudPlaylist>(rpcJsonbPayload)
        assertTrue(fromRpc.itemsJson.startsWith("["))
        assertTrue(fromRpc.itemsJson.contains("tt0133093"))
    }

    @Test
    fun testCloudSyncBundleDecodesFromRpcPayload() {
        val bundleJson = """
            {
              "profiles": [
                {
                  "profile_id": "default_user",
                  "user_id": "u1",
                  "name": "Primary",
                  "profile_type": "ADMIN",
                  "avatar_id": "avatar_1",
                  "is_deleted": false,
                  "client_updated_at_ms": 1234
                }
              ],
              "settings": {
                "user_id": "u1",
                "settings_json": {
                  "ui_blur_enabled": { "val": "true", "ts": 1234 }
                },
                "schema_version": 1
              },
              "addons": [],
              "playback": [],
              "watchlist": [],
              "playlists": [
                {
                  "user_id": "u1",
                  "profile_id": "default_user",
                  "playlist_id": "pl_mix",
                  "title": "Watch Later",
                  "is_system": false,
                  "items_json": [{"mediaId":"123"}],
                  "created_at_ms": 100,
                  "last_played_at_ms": 200,
                  "is_deleted": false,
                  "client_updated_at_ms": 300
                }
              ]
            }
        """.trimIndent()

        val bundle = json.decodeFromString<CloudSyncBundle>(bundleJson)
        assertEquals(1, bundle.profiles.size)
        assertEquals("Primary", bundle.profiles[0].name)
        assertEquals("true", bundle.settings?.settingsJson?.get("ui_blur_enabled")?.value)
        assertEquals(1, bundle.playlists.size)
        assertTrue(bundle.playlists[0].itemsJson.contains("123"))
    }

    @Test
    fun testTokenAuthenticatorSingleflightPersistsRotatedTokenBeforeWaitersReturn() = runBlocking {
        val refreshCount = AtomicInteger(0)
        val expiredCallbacks = AtomicInteger(0)
        val persistedSession = AtomicReference(
            SupabaseSession(
                accessToken = "old_access",
                refreshToken = "old_refresh",
                expiresIn = 3600,
                expiresAtMs = System.currentTimeMillis() - 60_000L,
                userId = "u1",
                email = "user@example.com",
                displayName = null,
                avatarUrl = null
            )
        )

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                refreshCount.incrementAndGet()
                Thread.sleep(60)
                val bodyJson = """
                    {
                      "access_token": "rotated_access_1",
                      "refresh_token": "rotated_refresh_1",
                      "expires_in": 3600,
                      "user": { "id": "u1", "email": "user@example.com" }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val authClient = SupabaseAuthClient("https://example.supabase.co", "anon", mockHttp)
        val authenticator = SupabaseTokenAuthenticator(
            authClient = authClient,
            sessionProvider = { persistedSession.get() },
            onSessionRefreshed = { updated -> persistedSession.set(updated) },
            onSessionExpired = { expiredCallbacks.incrementAndGet() }
        )

        val tokens = listOf(
            async { authenticator.getValidAccessToken() },
            async { authenticator.getValidAccessToken() }
        ).awaitAll()

        assertEquals(listOf("rotated_access_1", "rotated_access_1"), tokens)
        assertEquals(1, refreshCount.get())
        assertEquals("rotated_refresh_1", persistedSession.get().refreshToken)
        assertEquals(0, expiredCallbacks.get())
    }

    @Test
    fun testTokenAuthenticatorPreservesSessionOnTransient503Error() = runBlocking {
        val expiredCallbacks = AtomicInteger(0)
        val initialSession = SupabaseSession(
            accessToken = "old_access",
            refreshToken = "valid_refresh",
            expiresIn = 3600,
            expiresAtMs = System.currentTimeMillis() - 60_000L,
            userId = "u1",
            email = "user@example.com",
            displayName = null,
            avatarUrl = null
        )

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("{\"error\":\"overloaded\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val authClient = SupabaseAuthClient("https://example.supabase.co", "anon", mockHttp)
        val authenticator = SupabaseTokenAuthenticator(
            authClient = authClient,
            sessionProvider = { initialSession },
            onSessionRefreshed = {},
            onSessionExpired = { expiredCallbacks.incrementAndGet() }
        )

        val token = authenticator.getValidAccessToken()
        assertNull(token)
        assertEquals(0, expiredCallbacks.get())
    }
}
