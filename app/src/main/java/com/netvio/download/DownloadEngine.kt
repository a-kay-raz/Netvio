package com.netvio.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import com.netvio.core.network.DohDns
import com.netvio.data.preferences.PreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DownloadStorageVolume(
    val label: String,
    val directory: File,
    val availableBytes: Long,
    val isRemovable: Boolean,
    val isDefault: Boolean
)

/**
 * Resumable download engine supporting both direct progressive video files (MKV, MP4)
 * and HLS segmented video streams (M3U8).
 */
class DownloadEngine(private val context: Context) {

    private val client = com.netvio.core.network.PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder()
            if (req.header("User-Agent").isNullOrBlank()) {
                b.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            }
            if (req.header("Accept-Encoding").isNullOrBlank()) {
                b.header("Accept-Encoding", "identity")
            }
            chain.proceed(b.build())
        }
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val p2pDownloadSemaphore = kotlinx.coroutines.sync.Semaphore(1)
    private val directDownloadSemaphore = kotlinx.coroutines.sync.Semaphore(2)

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val activeNet = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun getDownloadDirectory(): File {
        val customPath = runBlocking {
            try {
                PreferencesRepository.getInstance(context).customDownloadDir.first()
            } catch (_: Exception) { null }
        }
        if (!customPath.isNullOrBlank()) {
            val resolvedPath = resolveCustomDownloadPath(customPath)
            val customDir = File(resolvedPath)
            if (isDirectoryWritable(customDir)) {
                return customDir
            }
            android.util.Log.w(
                "DownloadEngine",
                "Configured download directory is not writable ($resolvedPath); falling back to app storage"
            )
        }
        return resolveDefaultWritableDirectory()
    }

    /**
     * Verifies actual POSIX file creation access rather than trusting `exists()` or `canWrite()`,
     * which report false positives on Android 11+ Scoped Storage paths.
     */
    fun isDirectoryWritable(dir: File): Boolean {
        var probe: File? = null
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false
            probe = File(dir, ".netvio_write_probe_${Thread.currentThread().id}_${System.nanoTime()}")
            FileOutputStream(probe).use { it.write(0) }
            probe.exists()
        } catch (_: Exception) {
            false
        } finally {
            try {
                probe?.delete()
            } catch (_: Exception) {}
        }
    }

    fun isAppManagedDirectory(dir: File): Boolean {
        val targetPath = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
        val appRoots = mutableListOf<File>()
        try { appRoots.add(context.filesDir) } catch (_: Exception) {}
        try { context.getExternalFilesDir(null)?.let { appRoots.add(it) } } catch (_: Exception) {}
        try { context.getExternalFilesDirs(null)?.filterNotNull()?.forEach { appRoots.add(it) } } catch (_: Exception) {}
        return appRoots.any { root ->
            val rootPath = try { root.canonicalPath } catch (_: Exception) { root.absolutePath }
            targetPath == rootPath || targetPath.startsWith("$rootPath${File.separator}")
        }
    }

    fun resolveWritableCustomPath(rawPath: String): String = resolveCustomDownloadPath(rawPath)

    fun getAvailableWritableVolumes(): List<DownloadStorageVolume> {
        val seenPaths = HashSet<String>()
        val volumes = mutableListOf<DownloadStorageVolume>()
        val defaultExternal = try { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null }
        val defaultCanonical = defaultExternal?.let { try { it.canonicalPath } catch (_: Exception) { it.absolutePath } }

        val candidates = mutableListOf<File>()
        if (defaultExternal != null) candidates.add(defaultExternal)
        try {
            context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS)?.filterNotNull()?.forEach {
                candidates.add(it)
            }
        } catch (_: Exception) {}
        try {
            candidates.add(File(context.filesDir, "downloads"))
        } catch (_: Exception) {}

        var sdIndex = 1
        for (dir in candidates) {
            val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (!seenPaths.add(canonical)) continue
            if (!isDirectoryWritable(dir)) continue

            val isRemovable = try {
                Environment.isExternalStorageRemovable(dir)
            } catch (_: Exception) {
                false
            }
            val isPrivate = try {
                val filesCanonical = context.filesDir.canonicalPath
                canonical == filesCanonical || canonical.startsWith("$filesCanonical${File.separator}")
            } catch (_: Exception) {
                false
            }
            val label = when {
                isRemovable -> if (sdIndex == 1) "SD Card (App Storage)" else "SD Card $sdIndex (App Storage)"
                isPrivate -> "Private App Storage"
                else -> "Internal Storage (App Folder)"
            }
            if (isRemovable) sdIndex++

            val available = try { StatFs(dir.path).availableBytes } catch (_: Exception) { 0L }
            volumes.add(
                DownloadStorageVolume(
                    label = label,
                    directory = dir,
                    availableBytes = available,
                    isRemovable = isRemovable,
                    isDefault = (defaultCanonical != null && canonical == defaultCanonical) || (defaultCanonical == null && isPrivate)
                )
            )
        }
        return volumes
    }

    private fun resolveDefaultWritableDirectory(): File {
        try {
            val primary = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (primary != null && isDirectoryWritable(primary)) {
                return primary
            }
        } catch (_: Exception) {}

        try {
            val externalDirs = context.getExternalFilesDirs(Environment.DIRECTORY_DOWNLOADS)
            if (externalDirs != null) {
                for (candidate in externalDirs) {
                    if (candidate != null && isDirectoryWritable(candidate)) {
                        return candidate
                    }
                }
            }
        } catch (_: Exception) {}

        val internalFallback = File(context.filesDir, "downloads")
        if (!internalFallback.exists()) {
            internalFallback.mkdirs()
        }
        return internalFallback
    }

    companion object {
        fun sanitizeFileId(id: String): String {
            return id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        }

        fun resolveTreeUriToPath(uri: android.net.Uri): String {
            return try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val split = docId.split(":", limit = 2)
                val type = split.getOrNull(0).orEmpty()
                val relativePath = split.getOrNull(1).orEmpty().trim('/')
                if (type.equals("primary", ignoreCase = true)) {
                    val base = Environment.getExternalStorageDirectory().absolutePath
                    if (relativePath.isNotEmpty()) "$base/$relativePath" else base
                } else if (type.isNotBlank()) {
                    if (relativePath.isNotEmpty()) "/storage/$type/$relativePath" else "/storage/$type"
                } else {
                    uri.path ?: uri.toString()
                }
            } catch (_: Exception) {
                uri.path ?: uri.toString()
            }
        }
    }

    private fun resolveCustomDownloadPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.startsWith("content://")) {
            return try {
                val uri = android.net.Uri.parse(trimmed)
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                resolveTreeDocId(docId) ?: trimmed
            } catch (_: Exception) {
                trimmed
            }
        }
        if (trimmed.startsWith("/tree/")) {
            val docId = trimmed.removePrefix("/tree/").substringBefore("/document/")
            resolveTreeDocId(docId)?.let { return it }
        }
        return trimmed
    }

    private fun resolveTreeDocId(docId: String): String? {
        if (!docId.contains(":")) return null
        val split = docId.split(":", limit = 2)
        val type = split.getOrNull(0).orEmpty()
        val relativePath = split.getOrNull(1).orEmpty().trim('/')
        return if (type.equals("primary", ignoreCase = true)) {
            val base = Environment.getExternalStorageDirectory().absolutePath
            if (relativePath.isNotEmpty()) "$base/$relativePath" else base
        } else if (type.isNotBlank()) {
            if (relativePath.isNotEmpty()) "/storage/$type/$relativePath" else "/storage/$type"
        } else null
    }

    fun startOrResumeDownload(
        item: DownloadItem,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Float, speed: String) -> Unit,
        onCompleted: (finalFilePath: String, totalBytes: Long) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        val existing = activeJobs[item.id]
        if (existing != null && existing.isActive) {
            return
        }
        activeJobs[item.id]?.cancel()

        val job = scope.launch {
            try {
                val (extractedUrl, extractedHeaders) = com.netvio.core.network.PlayerPlaybackNetworking.extractUrlAndInlineHeaders(
                    item.streamUrl,
                    item.headers
                )
                val cleanUrl = sanitizeUrl(extractedUrl)
                val effectiveItem = if (cleanUrl != item.streamUrl || extractedHeaders != item.headers) {
                    item.copy(streamUrl = cleanUrl, headers = extractedHeaders)
                } else item

                val urlWithoutQuery = cleanUrl.substringBefore('?').trim()
                val isTorrent = cleanUrl.startsWith("magnet:", ignoreCase = true) ||
                        cleanUrl.startsWith("torrent://", ignoreCase = true) ||
                        cleanUrl.startsWith("torrent:", ignoreCase = true) ||
                        ((urlWithoutQuery.length == 40 || urlWithoutQuery.length == 64) && urlWithoutQuery.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) ||
                        (urlWithoutQuery.length == 32 && urlWithoutQuery.all { it.uppercaseChar() in 'A'..'Z' || it in '2'..'7' }) ||
                        com.netvio.core.p2p.extractP2pStreamRequest(cleanUrl) != null
                val isHls = !isTorrent && (
                    effectiveItem.container == com.netvio.core.model.StreamContainer.HLS ||
                    effectiveItem.isM3u8 ||
                    isHlsStream(cleanUrl, effectiveItem.headers)
                )

                when {
                    isTorrent -> downloadTorrentViaP2p(effectiveItem, cleanUrl, onProgress, onCompleted, onError)
                    isHls -> downloadHlsStream(effectiveItem, cleanUrl, onProgress, onCompleted, onError)
                    else -> downloadDirectFile(effectiveItem, cleanUrl, onProgress, onCompleted, onError)
                }
            } catch (e: CancellationException) {
                // User paused or canceled
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Download failed: connection interrupted")
            } finally {
                activeJobs.remove(item.id)
            }
        }

        activeJobs[item.id] = job
    }

    private suspend fun downloadDirectFile(
        item: DownloadItem,
        cleanUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Float, speed: String) -> Unit,
        onCompleted: (finalFilePath: String, totalBytes: Long) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val queuedTotal = item.totalBytes.takeIf { it > 0L } ?: 0L
        if (directDownloadSemaphore.availablePermits <= 0) {
            onProgress(0L, queuedTotal, 0f, "Queued...")
        } else {
            onProgress(0L, queuedTotal, 0f, "Connecting...")
        }
        directDownloadSemaphore.withPermit {
            onProgress(0L, queuedTotal, 0f, "Connecting...")
            val dir = getDownloadDirectory()
            if (!isDirectoryWritable(dir)) {
                onError("Download folder is not writable")
                return@withPermit
            }
            val safeId = sanitizeFileId(item.id)
            val ext = inferExtension(cleanUrl)
            val safeFilename = sanitizeFilename(item.title)
            val partFile = File(dir, "$safeId.part")
            if (!partFile.exists() && safeId != item.id) {
                val legacyPart = File(dir, "${item.id}.part")
                if (legacyPart.exists()) {
                    try { legacyPart.renameTo(partFile) } catch (_: Exception) {}
                }
            }
            val finalFile = File(dir, "$safeFilename-$safeId.$ext")

            var currentUrl = cleanUrl
            var retryCount = 0
            val maxRetries = 4
            var totalBytes = item.totalBytes.takeIf { it > 0 } ?: 0L
            // Only server-provided Content-Length / Content-Range is valid for premature-EOF detection;
            // UI fallback estimates (e.g. "~2.0 GB") must not falsely reject shorter completed streams.
            var authoritativeTotalBytes = 0L
            var skipRangeOnNextRequest = false
            var isDownloadComplete = false

            var lastProgressUpdateMs = System.currentTimeMillis()
            var bytesReadSinceLastUpdate = 0L

            while (!isDownloadComplete && coroutineContext.isActive) {
                var existingBytes = if (partFile.exists()) partFile.length() else 0L

                if (authoritativeTotalBytes > 0L && existingBytes >= authoritativeTotalBytes) {
                    isDownloadComplete = true
                    break
                }

                val requestBuilder = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Encoding", "identity")

                applyHeaders(requestBuilder, currentUrl, item.headers)

                if (existingBytes > 0L && !skipRangeOnNextRequest) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }
                skipRangeOnNextRequest = false

                var response: Response? = null
                try {
                    response = client.newCall(requestBuilder.build()).execute()
                } catch (e: Exception) {
                    if (!coroutineContext.isActive) {
                        return@withPermit
                    }
                    if (!isNetworkAvailable()) {
                        onError("Waiting for network")
                        return@withPermit
                    }
                    if (retryCount < maxRetries) {
                        retryCount++
                        val backoffMs = (1000L * (1L shl (retryCount - 1))).coerceAtMost(8000L)
                        delay(backoffMs)
                        continue
                    } else {
                        onError("Download interrupted: connection lost")
                        return@withPermit
                    }
                }

                val resp = response
                val code = resp.code
                val isPartial = code == 206
                val isOk = resp.isSuccessful

                if (code == 416 && existingBytes > 0L) {
                    val rangeTotal = parseContentRangeTotal(resp.header("Content-Range"))
                    resp.close()
                    val knownTotal = rangeTotal.takeIf { it > 0L } ?: authoritativeTotalBytes
                    if (knownTotal > 0L && existingBytes >= (knownTotal * 0.95)) {
                        isDownloadComplete = true
                        break
                    }
                    if (retryCount < maxRetries) {
                        retryCount++
                        if (rangeTotal > 0L && existingBytes > rangeTotal) {
                            partFile.delete()
                            existingBytes = 0L
                        } else {
                            skipRangeOnNextRequest = true
                        }
                        continue
                    } else if (knownTotal <= 0L && existingBytes >= 100 * 1024L) {
                        isDownloadComplete = true
                        break
                    }
                }

                if (!isOk && !isPartial) {
                    val errStatusMsg = resp.message
                    resp.close()
                    if ((code == 401 || code == 403 || code == 410) && retryCount < maxRetries) {
                        retryCount++
                        val refreshedUrl = tryRefreshToken(item, currentUrl)
                        if (refreshedUrl != null) {
                            currentUrl = refreshedUrl
                            delay(1000L * retryCount)
                            continue
                        }
                    }
                    if (code == 416 && partFile.exists() && partFile.length() > 0L) {
                        isDownloadComplete = true
                        break
                    }
                    val errorMsg = when (code) {
                        402 -> "Payment required or traffic limit reached (HTTP 402)"
                        403 -> "Stream access forbidden or signature expired (HTTP 403)"
                        401 -> "Stream unauthorized (HTTP 401)"
                        404 -> "Video stream not found (HTTP 404)"
                        410 -> "Video stream expired or gone (HTTP 410)"
                        else -> "HTTP Error $code: $errStatusMsg"
                    }
                    if (code == 404 || code == 410 || code == 402) {
                        onError(errorMsg)
                        return@withPermit
                    }
                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(1000L * retryCount)
                        continue
                    }
                    onError(errorMsg)
                    return@withPermit
                }

                val body = resp.body
                if (body == null) {
                    resp.close()
                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(1000L)
                        continue
                    }
                    onError("Empty response body from server")
                    return@withPermit
                }

                // Safety check: If server responded with an HLS playlist instead of direct media
                val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                val isPlaylistResponse = contentType.contains("mpegurl") ||
                        contentType.contains("application/x-mpegurl") ||
                        try {
                            resp.peekBody(512).string().contains("#EXTM3U")
                        } catch (_: Exception) { false }

                if (isPlaylistResponse) {
                    resp.close()
                    downloadHlsStream(item, currentUrl, onProgress, onCompleted, onError)
                    return@withPermit
                }

                // Safety check: Reject web pages (HTML) masquerading as direct video files
                if (contentType.contains("text/html") || contentType.contains("text/plain")) {
                    resp.close()
                    partFile.delete()
                    onError("Source returned a web page instead of a direct video stream.")
                    return@withPermit
                }

                val contentLength = body.contentLength()
                val contentRangeTotal = if (isPartial) parseContentRangeTotal(resp.header("Content-Range")) else 0L

                // Validate Range response:
                // If 206 Partial Content was returned, append to existing partial file
                // If 200 OK was returned, server ignored Range: reset partFile and stream from byte 0!
                val appendMode = isPartial && existingBytes > 0L
                if (!appendMode && existingBytes > 0L) {
                    // Server sent 200 OK instead of 206! Reset partFile to avoid silent data duplication
                    partFile.delete()
                    existingBytes = 0L
                }

                val serverReportedTotal = when {
                    contentRangeTotal > 0L -> contentRangeTotal
                    isPartial && contentLength > 0L -> existingBytes + contentLength
                    contentLength > 0L -> contentLength
                    else -> 0L
                }
                if (serverReportedTotal > 0L) {
                    authoritativeTotalBytes = serverReportedTotal
                    totalBytes = serverReportedTotal
                    val initialPct = (existingBytes.toFloat() / serverReportedTotal * 100f).coerceIn(0f, 100f)
                    onProgress(existingBytes, serverReportedTotal, initialPct, "Downloading...")
                }

                var bytesReadTotal = existingBytes
                var readError: Exception? = null
                var storageErrorMsg: String? = null

                try {
                    resp.use {
                        body.byteStream().use { input ->
                            java.io.BufferedOutputStream(FileOutputStream(partFile, appendMode), 256 * 1024).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    if (!coroutineContext.isActive) {
                                        output.flush()
                                        return@withPermit
                                    }

                                    output.write(buffer, 0, read)
                                    bytesReadTotal += read
                                    bytesReadSinceLastUpdate += read

                                    val now = System.currentTimeMillis()
                                    val elapsed = now - lastProgressUpdateMs

                                    if (elapsed >= 500L) {
                                        val speedBytesPerSec = if (elapsed > 0) {
                                            (bytesReadSinceLastUpdate * 1000L) / elapsed
                                        } else 0L

                                        val speedStr = formatSpeed(speedBytesPerSec)
                                        val effectiveTotal = authoritativeTotalBytes.takeIf { it > 0L } ?: totalBytes
                                        val percent = if (effectiveTotal > 0L) {
                                            (bytesReadTotal.toFloat() / effectiveTotal * 100f).coerceIn(0f, 100f)
                                        } else 0f

                                        onProgress(bytesReadTotal, effectiveTotal, percent, speedStr)

                                        lastProgressUpdateMs = now
                                        bytesReadSinceLastUpdate = 0L
                                    }
                                }
                                output.flush()
                            }
                        }
                    }
                } catch (ioe: Exception) {
                    if (!coroutineContext.isActive) {
                        return@withPermit
                    }
                    storageErrorMsg = classifyStorageException(ioe)
                    readError = ioe
                }

                if (storageErrorMsg != null) {
                    onError(storageErrorMsg)
                    return@withPermit
                }

                // Check stream result after draining socket:
                val currentFileLength = if (partFile.exists()) partFile.length() else 0L

                if (readError != null) {
                    // Socket read error (e.g. timeout, connection reset, cellular drop)
                    if (!isNetworkAvailable()) {
                        onError("Waiting for network")
                        return@withPermit
                    }
                    if (retryCount < maxRetries) {
                        retryCount++
                        val backoffMs = (1000L * (1L shl (retryCount - 1))).coerceAtMost(8000L)
                        delay(backoffMs)
                        continue
                    } else {
                        onError("Download interrupted: ${readError.localizedMessage ?: "connection lost"}")
                        return@withPermit
                    }
                }

                // Premature EOF detection (only against server-authoritative length, never fallback estimates):
                if (authoritativeTotalBytes > 0L && currentFileLength < (authoritativeTotalBytes * 0.98)) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        delay(1000L * retryCount)
                        continue
                    } else {
                        onError("Waiting for network")
                        return@withPermit
                    }
                }

                isDownloadComplete = true
            }

            if (!isDownloadComplete) {
                return@withPermit
            }

            if (finalFile.exists()) {
                finalFile.delete()
            }
            val targetFile = relocateOrRenamePartFile(partFile, finalFile)

            // Safety check: Ensure downloaded file is valid and non-empty
            if (targetFile.length() < 100 * 1024L) {
                targetFile.delete()
                onError("Download failed: incomplete data received (${targetFile.length()} bytes)")
                return@withPermit
            }

            onCompleted(targetFile.absolutePath, targetFile.length())
        }
    }

    private suspend fun isHlsStream(cleanUrl: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val lower = cleanUrl.lowercase()
        if (lower.contains(".m3u8") ||
            (lower.contains("workers.dev") && lower.contains("payload=")) ||
            lower.contains("playlist")
        ) {
            return@withContext true
        }

        // Fast probe via range GET to detect #EXTM3U
        try {
            val req = Request.Builder().url(cleanUrl)
            applyHeaders(req, cleanUrl, headers)
            req.header("Range", "bytes=0-511")
            client.newCall(req.build()).execute().use { resp ->
                val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                if (contentType.contains("mpegurl") || contentType.contains("application/x-mpegurl")) {
                    return@withContext true
                }
                val peek = resp.peekBody(512).string()
                if (peek.contains("#EXTM3U")) {
                    return@withContext true
                }
            }
        } catch (_: Exception) {}
        false
    }

    private suspend fun downloadHlsStream(
        item: DownloadItem,
        cleanUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Float, speed: String) -> Unit,
        onCompleted: (finalFilePath: String, totalBytes: Long) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dir = getDownloadDirectory()
        if (!isDirectoryWritable(dir)) {
            onError("Download folder is not writable")
            return@withContext
        }
        val safeId = sanitizeFileId(item.id)

        // Initial progress notification
        val initialTotal = if (item.totalBytes > 0L) item.totalBytes else 0L
        onProgress(0L, initialTotal, 0f, "Connecting...")

        // 1. Fetch playlist with transient retry & token refresh support
        val primaryPlaylistFetch = fetchTextWithRetry(item, cleanUrl)
        if (primaryPlaylistFetch == null) {
            onError(if (!isNetworkAvailable()) "Waiting for network" else "Download interrupted: connection lost")
            return@withContext
        }
        val (resolvedPlaylistUrl, playlistText) = primaryPlaylistFetch

        if (playlistText.startsWith("HTTP_ERR:")) {
            val parts = playlistText.removePrefix("HTTP_ERR:").split(":", limit = 2)
            val code = parts.getOrNull(0)?.toIntOrNull() ?: 0
            onError(
                when (code) {
                    401 -> "Stream unauthorized (HTTP 401)"
                    402 -> "Payment required or traffic limit reached (HTTP 402)"
                    403 -> "Stream access forbidden or signature expired (HTTP 403)"
                    404 -> "Video stream not found (HTTP 404)"
                    410 -> "Video stream expired or gone (HTTP 410)"
                    else -> "HTTP Error $code fetching HLS stream"
                }
            )
            return@withContext
        }

        if (playlistText.isBlank() || !playlistText.contains("#EXTM3U")) {
            onError("Invalid HLS playlist response")
            return@withContext
        }

        val baseUri = try { URI(resolvedPlaylistUrl) } catch (_: Exception) { null }

        // 2. If master playlist, resolve sub-playlist based on requested quality
        val (mediaPlaylistUrl, subPlaylistText) = if (playlistText.contains("#EXT-X-STREAM-INF")) {
            val lines = playlistText.lines()
            var selectedSubUrl: String? = null
            var bestBandwidth = -1L
            val target1080 = item.qualityLabel.contains("1080", ignoreCase = true)
            val target720 = item.qualityLabel.contains("720", ignoreCase = true)
            val target480 = item.qualityLabel.contains("480", ignoreCase = true)

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val res = Regex("""RESOLUTION=(\d+x\d+)""").find(line)?.groupValues?.get(1) ?: ""
                    val is1080 = res.contains("1080") || res.contains("800")
                    val is720 = res.contains("720") || res.contains("534")

                    var subLineUrl: String? = null
                    for (j in (i + 1) until lines.size) {
                        val subLine = lines[j].trim()
                        if (subLine.isNotBlank() && !subLine.startsWith("#")) {
                            subLineUrl = try { baseUri?.resolve(subLine)?.toString() ?: subLine } catch (_: Exception) { subLine }
                            break
                        }
                    }

                    if (subLineUrl != null) {
                        if ((target1080 && is1080) || (target720 && is720) || selectedSubUrl == null || (bw > bestBandwidth && !target480)) {
                            selectedSubUrl = subLineUrl
                            bestBandwidth = bw
                            if ((target1080 && is1080) || (target720 && is720)) break
                        }
                    }
                }
            }
            val targetSubUrl = selectedSubUrl ?: resolvedPlaylistUrl
            val subPlaylistFetch = fetchTextWithRetry(item, targetSubUrl)
            if (subPlaylistFetch == null) {
                onError(if (!isNetworkAvailable()) "Waiting for network" else "Download interrupted: connection lost")
                return@withContext
            }
            val (subResolved, text) = subPlaylistFetch
            if (text.startsWith("HTTP_ERR:")) {
                val code = text.removePrefix("HTTP_ERR:").substringBefore(':').toIntOrNull() ?: 0
                onError("HTTP Error $code fetching media segments")
                return@withContext
            }
            subResolved to text
        } else {
            resolvedPlaylistUrl to playlistText
        }

        val itemDir = File(dir, safeId)
        if (!itemDir.exists() && safeId != item.id) {
            val legacyDir = File(dir, item.id)
            if (legacyDir.exists()) {
                try { legacyDir.renameTo(itemDir) } catch (_: Exception) {}
            }
        }
        if (!itemDir.exists() && !itemDir.mkdirs()) {
            onError("Unable to create download folder")
            return@withContext
        }

        val isFmp4 = subPlaylistText.contains("#EXT-X-MAP:") || subPlaylistText.contains(".m4s") || subPlaylistText.contains(".mp4")
        val segExt = if (isFmp4) "m4s" else "ts"

        // 3. Build offline playlist and extract media assets
        val subUri = try { URI(mediaPlaylistUrl) } catch (_: Exception) { null }
        val outPlaylist = StringBuilder()
        val segmentEntries = mutableListOf<Pair<String, String>>()
        var initMapUrl: String? = null
        val keyEntries = mutableListOf<Pair<String, String>>()
        val uriAttr = Regex("""URI=[\"']([^\"']+)[\"']""")

        var segCounter = 0
        var keyCounter = 0
        var mapCounter = 0

        for (line in subPlaylistText.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY") && !trimmed.contains("METHOD=NONE") -> {
                    val match = uriAttr.find(trimmed)
                    if (match != null) {
                        val rawKeyUri = match.groupValues[1]
                        val resolvedKeyUrl = try { subUri?.resolve(rawKeyUri)?.toString() ?: rawKeyUri } catch (_: Exception) { rawKeyUri }
                        val localKeyName = "key_$keyCounter.bin"
                        keyCounter++
                        keyEntries.add(resolvedKeyUrl to localKeyName)
                        outPlaylist.appendLine(trimmed.replace(rawKeyUri, localKeyName))
                    } else {
                        outPlaylist.appendLine(trimmed)
                    }
                }
                trimmed.startsWith("#EXT-X-MAP:") -> {
                    val match = uriAttr.find(trimmed)
                    if (match != null) {
                        val rawMapUri = match.groupValues[1]
                        val resolvedMapUrl = try { subUri?.resolve(rawMapUri)?.toString() ?: rawMapUri } catch (_: Exception) { rawMapUri }
                        initMapUrl = resolvedMapUrl
                        val localMapName = "init_${mapCounter++}.mp4"
                        outPlaylist.appendLine(trimmed.replace(rawMapUri, localMapName))
                    } else {
                        outPlaylist.appendLine(trimmed)
                    }
                }
                trimmed.startsWith("#EXT-X-PLAYLIST-TYPE") || trimmed.startsWith("#EXT-X-ENDLIST") -> {
                    // Handled at finalize
                }
                trimmed.isBlank() -> {
                    // skip blank lines
                }
                trimmed.startsWith("#") -> {
                    outPlaylist.appendLine(trimmed)
                }
                else -> {
                    val resolvedSegUrl = try { subUri?.resolve(trimmed)?.toString() ?: trimmed } catch (_: Exception) { trimmed }
                    val localSegName = "seg_%05d.$segExt".format(segCounter++)
                    segmentEntries.add(resolvedSegUrl to localSegName)
                    outPlaylist.appendLine(localSegName)
                }
            }
        }

        if (segmentEntries.isEmpty()) {
            onError("No video segments found in HLS stream")
            return@withContext
        }

        // Download init map if present
        initMapUrl?.let { mapUrl ->
            val initFile = File(itemDir, "init_0.mp4")
            if (!initFile.exists() || initFile.length() == 0L) {
                try {
                    val initReq = Request.Builder().url(mapUrl)
                    applyHeaders(initReq, mapUrl, item.headers)
                    client.newCall(initReq.build()).execute().use { initResp ->
                        if (initResp.isSuccessful) {
                            initResp.body?.bytes()?.let { initFile.writeBytes(it) }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Download keys if present
        for ((keyUrl, keyName) in keyEntries) {
            val keyFile = File(itemDir, keyName)
            if (!keyFile.exists() || keyFile.length() == 0L) {
                try {
                    val keyReq = Request.Builder().url(keyUrl)
                    applyHeaders(keyReq, keyUrl, item.headers)
                    client.newCall(keyReq.build()).execute().use { keyResp ->
                        if (keyResp.isSuccessful) {
                            keyResp.body?.bytes()?.let { keyFile.writeBytes(it) }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val totalSegments = segmentEntries.size
        var totalBytesDownloaded = 0L

        // Pre-count already downloaded segments for resume
        for ((_, segName) in segmentEntries) {
            val segFile = File(itemDir, segName)
            if (segFile.exists()) {
                totalBytesDownloaded += segFile.length()
            }
        }

        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val downloadedBytesAtomic = java.util.concurrent.atomic.AtomicLong(totalBytesDownloaded)
        val bytesSinceUpdateAtomic = java.util.concurrent.atomic.AtomicLong(0L)
        val progressLock = Any()
        var lastProgressMs = System.currentTimeMillis()
        val errorSignal = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val semaphore = kotlinx.coroutines.sync.Semaphore(5)

        coroutineScope {
            val jobs = segmentEntries.mapIndexed { idx, (rawSegUrl, localSegName) ->
                launch(Dispatchers.IO) {
                    if (!isActive || errorSignal.get() != null) return@launch

                    val segFile = File(itemDir, localSegName)
                    if (segFile.exists() && segFile.length() > 0L) {
                        completedCount.incrementAndGet()
                        return@launch
                    }

                    semaphore.withPermit {
                        if (!isActive || errorSignal.get() != null) return@withPermit

                        val segUrl = sanitizeUrl(rawSegUrl)
                        var attempts = 0
                        var success = false

                        while (attempts < 3 && !success && isActive && errorSignal.get() == null) {
                            attempts++
                            try {
                                val segReq = Request.Builder().url(segUrl)
                                applyHeaders(segReq, segUrl, item.headers)

                                client.newCall(segReq.build()).execute().use { segResp ->
                                    if (segResp.isSuccessful) {
                                        val body = segResp.body ?: throw java.io.IOException("Empty segment body")
                                        val partSeg = File(itemDir, "$localSegName.part")
                                        partSeg.outputStream().use { fos ->
                                            body.byteStream().copyTo(fos)
                                        }
                                        if (partSeg.length() > 0L) {
                                            partSeg.renameTo(segFile)
                                            val downloadedSize = segFile.length()
                                            downloadedBytesAtomic.addAndGet(downloadedSize)
                                            bytesSinceUpdateAtomic.addAndGet(downloadedSize)
                                            success = true
                                        } else {
                                            partSeg.delete()
                                            throw java.io.IOException("Empty segment file")
                                        }
                                    } else {
                                        throw java.io.IOException("HTTP ${segResp.code} fetching segment $idx from $segUrl")
                                    }
                                }
                            } catch (e: Exception) {
                                val storageErr = classifyStorageException(e)
                                if (storageErr != null) {
                                    errorSignal.compareAndSet(null, storageErr)
                                    return@withPermit
                                }
                                if (attempts >= 3) {
                                    val msg = if (!isNetworkAvailable()) {
                                        "Waiting for network"
                                    } else {
                                        "Failed to download segment ${idx + 1}/$totalSegments: ${e.localizedMessage ?: "connection interrupted"}"
                                    }
                                    errorSignal.compareAndSet(null, msg)
                                    return@withPermit
                                }
                                delay(400L * attempts)
                            }
                        }

                        val done = completedCount.incrementAndGet()
                        val now = System.currentTimeMillis()
                        synchronized(progressLock) {
                            val elapsed = now - lastProgressMs
                            if (elapsed >= 300L || done == totalSegments) {
                                val bytesSince = bytesSinceUpdateAtomic.getAndSet(0L)
                                val speedBytesPerSec = if (elapsed > 0) (bytesSince * 1000L) / elapsed else 0L
                                val speedStr = formatSpeed(speedBytesPerSec)
                                val currentTotal = downloadedBytesAtomic.get()
                                val percent = ((done.toFloat() / totalSegments) * 100f).coerceIn(0f, 100f)
                                val estimatedTotal = if (done > 0) {
                                    (currentTotal / done) * totalSegments
                                } else if (item.totalBytes > 0L) {
                                    item.totalBytes
                                } else {
                                    currentTotal
                                }

                                onProgress(currentTotal, estimatedTotal, percent, speedStr)
                                lastProgressMs = now
                            }
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        val finalError = errorSignal.get()
        if (finalError != null) {
            onError(finalError)
            return@withContext
        }
        totalBytesDownloaded = downloadedBytesAtomic.get()

        // Finalize index.m3u8 playlist
        if (!outPlaylist.contains("#EXT-X-ENDLIST")) {
            outPlaylist.appendLine("#EXT-X-ENDLIST")
        }
        val rawContent = outPlaylist.toString()
        val finalPlaylistContent = if (!rawContent.contains("#EXT-X-PLAYLIST-TYPE")) {
            rawContent.replaceFirst(Regex("#EXTM3U\\r?\\n"), "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n")
        } else {
            rawContent
        }

        val playlistFile = File(itemDir, "index.m3u8")
        val tmpPlaylist = File(itemDir, "index.m3u8.tmp")
        tmpPlaylist.writeText(finalPlaylistContent)
        tmpPlaylist.renameTo(playlistFile)

        onCompleted(playlistFile.absolutePath, totalBytesDownloaded)
    }

    private fun applyHeaders(builder: Request.Builder, url: String, itemHeaders: Map<String, String>) {
        val lower = url.lowercase()
        val isPreSignedCloud = lower.contains("r2.cloudflarestorage.com") ||
                lower.contains(".r2.dev") ||
                lower.contains("googleusercontent.com") ||
                lower.contains("storage.googleapis.com") ||
                lower.contains(".amazonaws.com")
        val stripAuth = com.netvio.core.network.PlayerPlaybackNetworking.shouldStripAuthorization(url)

        // Ensure default User-Agent
        builder.header("User-Agent", itemHeaders["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

        if (isPreSignedCloud || stripAuth) {
            // Cloudflare R2, AWS S3, and GCS pre-signed URLs break with HTTP 403/400 when unsolicited Referer, Origin, or Authorization headers are attached
            itemHeaders.forEach { (k, v) ->
                val isExcluded = k.equals("Referer", ignoreCase = true) ||
                        k.equals("Origin", ignoreCase = true) ||
                        (stripAuth && k.equals("Authorization", ignoreCase = true))
                if (!isExcluded) {
                    builder.header(k, v)
                }
            }
            return
        }

        // Apply all headers passed from the plugin/stream source
        itemHeaders.forEach { (k, v) -> builder.header(k, v) }
    }

    private suspend fun tryRefreshToken(item: DownloadItem, staleUrl: String): String? {
        val scraperId = item.scraperId ?: item.provider ?: return null
        return try {
            val refreshable = com.netvio.core.streams.RefreshableSource(
                scraperId = scraperId,
                mediaType = item.mediaType ?: if (item.isTvSeries) "tv" else "movie",
                videoId = item.mediaId,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                quality = item.qualityLabel,
                initialUrl = staleUrl,
                initialHeaders = item.headers
            )
            val newUrl = refreshable.refreshOnExpiredToken(staleUrl)
            if (newUrl != null && newUrl != staleUrl) {
                DownloadRepository.getInstance(context).updateDownloadUrl(item.id, newUrl, refreshable.currentHeaders)
                newUrl
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.w("DownloadEngine", "Token refresh error: ${e.message}")
            null
        }
    }

    private suspend fun downloadTorrentViaP2p(
        item: DownloadItem,
        cleanUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Float, speed: String) -> Unit,
        onCompleted: (finalFilePath: String, totalBytes: Long) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val p2pRequest = com.netvio.core.p2p.extractP2pStreamRequest(
            rawUrl = cleanUrl,
            defaultFileIdx = null,
            defaultSeason = item.seasonNumber,
            defaultEpisode = item.episodeNumber,
            trackers = item.trackers
        ) ?: run {
            onError("Invalid torrent magnet or info hash")
            return@withContext
        }

        com.netvio.core.p2p.P2pStreamingEngine.initialize(context)

        val dir = getDownloadDirectory()
        if (!isDirectoryWritable(dir)) {
            onError("Download folder is not writable")
            return@withContext
        }
        val safeId = sanitizeFileId(item.id)
        val safeFilename = sanitizeFilename(item.title)
        val ext = inferExtension(p2pRequest.filename ?: item.title)
        val finalFile = File(dir, "$safeFilename-$safeId.$ext")

        onProgress(0L, 0L, 0f, "Queued...")

        p2pDownloadSemaphore.withPermit {
            try {
                onProgress(0L, 0L, 0f, "Connecting peers...")
                val downloadedFile = com.netvio.core.p2p.P2pStreamingEngine.downloadFileDirect(
                    request = p2pRequest,
                    destinationFile = finalFile,
                    onProgress = onProgress,
                    isCancelled = { !coroutineContext.isActive }
                )
                if (downloadedFile.exists() && downloadedFile.length() > 0L) {
                    onCompleted(downloadedFile.absolutePath, downloadedFile.length())
                } else {
                    onError("P2P download incomplete or empty")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val storageErr = classifyStorageException(e)
                when {
                    storageErr != null -> onError(storageErr)
                    !isNetworkAvailable() -> onError("Waiting for network")
                    else -> onError("P2P download interrupted: ${e.message}")
                }
            }
        }
    }

    fun pauseDownload(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
    }

    fun removeDownload(id: String, streamUrl: String? = null) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)

        try {
            val dir = getDownloadDirectory()
            val safeId = sanitizeFileId(id)
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.contains(id) || file.name.contains(safeId)) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }

            // Clean up corresponding P2P workdir if stream was a torrent
            val p2pRequest = streamUrl?.let { com.netvio.core.p2p.extractP2pStreamRequest(it) }
            val canonicalHash = p2pRequest?.infoHash?.let { com.netvio.core.p2p.canonicalP2pInfoHash(it) }
            if (canonicalHash != null) {
                val workDir = File(dir, ".p2p_work_$canonicalHash")
                if (workDir.exists()) {
                    workDir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            // Ignore file deletion errors
        }
    }

    fun cleanupOrphanedP2pWorkDirs(activeCanonicalHashes: Set<String>, targetDir: File? = null): Int {
        var deletedCount = 0
        try {
            val dir = targetDir ?: getDownloadDirectory()
            val files = dir.listFiles() ?: return 0
            for (file in files) {
                if (file.isDirectory && file.name.startsWith(".p2p_work_")) {
                    val hash = file.name.removePrefix(".p2p_work_")
                    if (hash !in activeCanonicalHashes) {
                        if (file.deleteRecursively()) {
                            deletedCount++
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return deletedCount
    }

    fun isDownloading(id: String): Boolean {
        return activeJobs[id]?.isActive == true
    }

    private fun sanitizeUrl(url: String): String {
        if (!url.contains(" ") && !url.contains("[") && !url.contains("]") && !url.contains("|") && !url.contains("^") && !url.contains("`") && !url.contains("{") && !url.contains("}")) {
            return url
        }
        return try {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd != -1) {
                val scheme = url.substring(0, schemeEnd + 3)
                val rest = url.substring(schemeEnd + 3)
                val slashIdx = rest.indexOf('/')
                if (slashIdx != -1) {
                    val host = rest.substring(0, slashIdx)
                    val pathAndQuery = rest.substring(slashIdx)
                    val cleanPathAndQuery = pathAndQuery
                        .replace(" ", "%20")
                        .replace("[", "%5B")
                        .replace("]", "%5D")
                        .replace("{", "%7B")
                        .replace("}", "%7D")
                        .replace("|", "%7C")
                        .replace("^", "%5E")
                        .replace("`", "%60")
                    scheme + host + cleanPathAndQuery
                } else {
                    url.replace(" ", "%20")
                }
            } else {
                url.replace(" ", "%20")
            }
        } catch (_: Exception) {
            url.replace(" ", "%20")
        }
    }

    private fun inferExtension(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".mkv") -> "mkv"
            lower.contains(".mp4") -> "mp4"
            lower.contains(".webm") -> "webm"
            lower.contains(".ts") -> "ts"
            else -> "mkv"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(40)
    }

    private suspend fun fetchTextWithRetry(
        item: DownloadItem,
        initialUrl: String,
        maxRetries: Int = 3
    ): Pair<String, String>? {
        var currentUrl = initialUrl
        var attempts = 0
        var lastHttpError: Pair<Int, String>? = null
        while (attempts < maxRetries && currentCoroutineContext().isActive) {
            attempts++
            var retryWithRefresh = false
            try {
                val req = Request.Builder().url(currentUrl)
                applyHeaders(req, currentUrl, item.headers)
                client.newCall(req.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        return currentUrl to body
                    }
                    val code = resp.code
                    lastHttpError = code to resp.message
                    if ((code == 401 || code == 403 || code == 410) && attempts < maxRetries) {
                        val refreshed = tryRefreshToken(item, currentUrl)
                        if (refreshed != null) {
                            currentUrl = refreshed
                            retryWithRefresh = true
                        }
                    }
                    if (!retryWithRefresh && code in listOf(401, 402, 403, 404, 410)) {
                        return currentUrl to "HTTP_ERR:$code:${resp.message}"
                    }
                }
            } catch (_: Exception) {
                if (!isNetworkAvailable()) {
                    return null
                }
            }
            if (attempts < maxRetries) {
                delay(if (retryWithRefresh) 400L * attempts else 600L * attempts)
            }
        }
        return lastHttpError?.let { (code, msg) -> currentUrl to "HTTP_ERR:$code:$msg" }
    }

    private fun parseContentRangeTotal(headerValue: String?): Long {
        if (headerValue.isNullOrBlank()) return 0L
        val totalPart = Regex("""/\s*(\d+)""").find(headerValue)?.groupValues?.get(1)
        return totalPart?.toLongOrNull() ?: 0L
    }

    private fun classifyStorageException(error: Throwable): String? {
        if (error is SecurityException) {
            return "Storage permission denied: ${error.localizedMessage ?: "cannot write file"}"
        }
        if (error is FileNotFoundException || error is IOException) {
            val msg = error.message?.lowercase().orEmpty()
            return when {
                msg.contains("enospc") || msg.contains("no space left") ->
                    "Insufficient storage space on device"
                msg.contains("eacces") || msg.contains("eperm") || msg.contains("permission denied") || msg.contains("operation not permitted") || msg.contains("erofs") || msg.contains("read-only") ->
                    "Storage write denied (${error.localizedMessage ?: "unwritable folder"})"
                else -> null
            }
        }
        return null
    }

    private fun relocateOrRenamePartFile(partFile: File, finalFile: File): File {
        if (partFile.renameTo(finalFile)) {
            return finalFile
        }
        return try {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
            finalFile
        } catch (_: Exception) {
            partFile
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format("%.1f MB/s", mb)
        } else {
            val kb = bytesPerSec / 1024.0
            String.format("%.0f KB/s", kb)
        }
    }
}
