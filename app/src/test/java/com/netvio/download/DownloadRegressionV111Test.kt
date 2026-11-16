package com.netvio.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.netvio.data.preferences.PreferencesRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRegressionV111Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var prefs: PreferencesRepository
    private lateinit var engine: DownloadEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNet = cm.activeNetwork
        if (activeNet != null) {
            val caps = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(cm).setNetworkCapabilities(activeNet, caps)
        }
        prefs = PreferencesRepository.getInstance(context)
        engine = DownloadEngine(context)
        runBlocking { prefs.setCustomDownloadDir(null) }
    }

    @After
    fun tearDown() {
        runBlocking { prefs.setCustomDownloadDir(null) }
    }

    @Test
    fun testUnwritableCustomDirFallsBackToWritableAppStorage() = runBlocking {
        // Regular file exists() == true, mimicking a Scoped Storage path that passes existence
        // checks yet cannot host writable downloads or mkdirs().
        val nonDirectoryFile = tempFolder.newFile("blocked_download_target")
        prefs.setCustomDownloadDir(nonDirectoryFile.absolutePath)

        val resolvedDir = engine.getDownloadDirectory()

        assertNotEquals(nonDirectoryFile.absolutePath, resolvedDir.absolutePath)
        assertTrue("Fallback directory must be a directory", resolvedDir.isDirectory)
        assertTrue("Fallback directory must pass write probe", engine.isDirectoryWritable(resolvedDir))
        assertTrue("Default writable volumes should include at least one volume", engine.getAvailableWritableVolumes().isNotEmpty())
    }

    @Test
    fun testSanitizeFileIdRemovesReservedCharacters() {
        val rawId = "tmdb:1399/season:1_ep?1*test"
        val sanitized = DownloadEngine.sanitizeFileId(rawId)
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("?"))
        assertFalse(sanitized.contains("*"))
        assertEquals("tmdb_1399_season_1_ep_1_test", sanitized)
    }

    @Test
    fun testChunkedTransferWithoutContentLengthCompletesEvenWithLargeEstimate() {
        val payloadSize = 180 * 1024 // > 100 KB minimum completion guard
        val payload = ByteArray(payloadSize) { (it % 251).toByte() }

        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    socket.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = sock.getOutputStream()
                        val headers = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: video/mp4\r\n")
                            append("Transfer-Encoding: chunked\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(headers.toByteArray(Charsets.US_ASCII))
                        val chunkHeader = "${Integer.toHexString(payload.size)}\r\n"
                        out.write(chunkHeader.toByteArray(Charsets.US_ASCII))
                        out.write(payload)
                        out.write("\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            val item = DownloadItem(
                id = "tmdb:1399_s1e1",
                mediaId = "tmdb:1399",
                title = "Episode One",
                streamUrl = "http://127.0.0.1:${server.localPort}/episode.mp4",
                // Simulates DownloadRepository's 2 GB fallback estimate when addon omits size
                totalBytes = 2L * 1024L * 1024L * 1024L,
                state = DownloadState.DOWNLOADING
            )

            val doneLatch = CountDownLatch(1)
            var completedPath: String? = null
            var completedBytes = -1L
            var capturedError: String? = null

            engine.startOrResumeDownload(
                item = item,
                onProgress = { _, _, _, _ -> },
                onCompleted = { path, total ->
                    completedPath = path
                    completedBytes = total
                    doneLatch.countDown()
                },
                onError = { err ->
                    capturedError = err
                    doneLatch.countDown()
                }
            )

            assertTrue("Download did not finish in time", doneLatch.await(15, TimeUnit.SECONDS))
            assertNull("Chunked download should not fail: $capturedError", capturedError)
            assertEquals(payloadSize.toLong(), completedBytes)

            val savedFile = File(completedPath!!)
            assertTrue("Final file must exist", savedFile.exists())
            assertFalse("Filename on disk must not contain colon", savedFile.name.contains(":"))
            assertArrayEquals(payload, savedFile.readBytes())
            savedFile.delete()
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun testPrematureEofWithAuthoritativeContentLengthResumesViaRange() {
        val totalSize = 256 * 1024
        val splitPoint = 128 * 1024
        val payload = ByteArray(totalSize) { (it % 199).toByte() }

        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    socket.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        var rangeStart = 0
                        var hasRange = false
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                hasRange = true
                                val match = Regex("""bytes=(\d+)-""").find(line)
                                rangeStart = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            }
                        }

                        val out = sock.getOutputStream()
                        if (!hasRange) {
                            // First request: declare 256 KB Content-Length, drop cleanly after 128 KB
                            val headers = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: video/mp4\r\n")
                                append("Content-Length: $totalSize\r\n")
                                append("Accept-Ranges: bytes\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(headers.toByteArray(Charsets.US_ASCII))
                            out.write(payload, 0, splitPoint)
                            out.flush()
                        } else {
                            // Resume request: return remaining bytes cleanly via 206 Partial Content
                            val remaining = (totalSize - rangeStart).coerceAtLeast(0)
                            val headers = buildString {
                                append("HTTP/1.1 206 Partial Content\r\n")
                                append("Content-Type: video/mp4\r\n")
                                append("Content-Length: $remaining\r\n")
                                append("Content-Range: bytes $rangeStart-${totalSize - 1}/$totalSize\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            out.write(headers.toByteArray(Charsets.US_ASCII))
                            if (remaining > 0) {
                                out.write(payload, rangeStart, remaining)
                            }
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            val item = DownloadItem(
                id = "test_range_resume_1",
                mediaId = "test_media",
                title = "Resume Movie",
                streamUrl = "http://127.0.0.1:${server.localPort}/resume.mp4",
                totalBytes = totalSize.toLong(),
                state = DownloadState.DOWNLOADING
            )

            val doneLatch = CountDownLatch(1)
            var completedPath: String? = null
            var completedBytes = -1L
            var capturedError: String? = null

            engine.startOrResumeDownload(
                item = item,
                onProgress = { _, _, _, _ -> },
                onCompleted = { path, total ->
                    completedPath = path
                    completedBytes = total
                    doneLatch.countDown()
                },
                onError = { err ->
                    capturedError = err
                    doneLatch.countDown()
                }
            )

            assertTrue("Range resume did not finish in time", doneLatch.await(15, TimeUnit.SECONDS))
            assertNull("Range resume should not fail: $capturedError", capturedError)
            assertEquals(totalSize.toLong(), completedBytes)

            val savedFile = File(completedPath!!)
            assertTrue("Final file must exist", savedFile.exists())
            assertArrayEquals(payload, savedFile.readBytes())
            savedFile.delete()
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }
}
