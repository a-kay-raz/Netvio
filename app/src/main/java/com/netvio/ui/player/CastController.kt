package com.netvio.ui.player

import android.content.Context
import android.net.wifi.WifiManager
import com.netvio.data.tv.TvHandoffSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CastDevice(
    val id: String,
    val name: String,
    val location: String,
    val ipAddress: String,
    val deviceType: String = "DLNA / Smart TV",
    val controlUrl: String? = null,
    val isCompanionTv: Boolean = false
)

object CastController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanGuard = AtomicBoolean(false)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val soapMediaType = "text/xml; charset=\"utf-8\"".toMediaType()

    private val _discoveredDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<CastDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val _activeCastDevice = MutableStateFlow<CastDevice?>(null)
    val activeCastDevice: StateFlow<CastDevice?> = _activeCastDevice.asStateFlow()

    /**
     * Scans local Wi-Fi network using SSDP (UPnP / DLNA Discovery).
     * Safely guards multicast lock acquisition against missing permissions or OEM restrictions.
     */
    suspend fun scanDevices(context: Context) = withContext(Dispatchers.IO) {
        if (!scanGuard.compareAndSet(false, true)) return@withContext
        _isScanning.value = true
        _scanError.value = null

        var multicastLock: WifiManager.MulticastLock? = null
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("NetvioCastLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: SecurityException) {
            multicastLock = null
        } catch (_: Exception) {
            multicastLock = null
        }


        val found = mutableListOf<CastDevice>()

        try {
            val group = InetAddress.getByName("239.255.255.250")
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 2200

                val searchTargets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1"
                )
                for (st in searchTargets) {
                    val query = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n"
                    val bytes = query.toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
                }

                val buffer = ByteArray(4096)
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < 2800L) {
                    try {
                        val receivePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                        val ip = receivePacket.address?.hostAddress.orEmpty()
                        if (ip.isBlank()) continue

                        val location = response.lines()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                            ?: "http://$ip"

                        val serverName = response.lines()
                            .firstOrNull { it.startsWith("SERVER:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                            ?: "Smart TV ($ip)"

                        if (found.any { it.ipAddress == ip && it.location == location }) continue

                        val (friendlyName, controlUrl) = fetchUpnpDescription(location)
                        val resolvedName = friendlyName?.ifBlank { null } ?: serverName

                        val dev = CastDevice(
                            id = "${ip}::$location",
                            name = resolvedName,
                            location = location,
                            ipAddress = ip,
                            deviceType = if (controlUrl != null) "DLNA Renderer" else "UPnP / Smart TV",
                            controlUrl = controlUrl,
                            isCompanionTv = false
                        )

                        if (found.none { it.ipAddress == dev.ipAddress }) {
                            found.add(dev)
                            _discoveredDevices.value = found.toList()
                        } else if (controlUrl != null) {
                            val idx = found.indexOfFirst { it.ipAddress == dev.ipAddress && it.controlUrl == null }
                            if (idx >= 0) {
                                found[idx] = dev
                                _discoveredDevices.value = found.toList()
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            _scanError.value = e.message ?: "Unable to scan local Wi-Fi network"
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
            _isScanning.value = false
            scanGuard.set(false)
        }
    }

    private fun fetchUpnpDescription(locationUrl: String): Pair<String?, String?> {
        if (!locationUrl.startsWith("http://", ignoreCase = true) &&
            !locationUrl.startsWith("https://", ignoreCase = true)
        ) {
            return null to null
        }
        return try {
            val req = Request.Builder().url(locationUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null to null
                val xml = resp.body?.string().orEmpty()
                parseUpnpDeviceDescription(xml, locationUrl)
            }
        } catch (_: Exception) {
            null to null
        }
    }

    internal fun parseUpnpDeviceDescription(
        xml: String,
        locationUrl: String
    ): Pair<String?, String?> {
        if (xml.isBlank()) return null to null
        val friendlyName = Regex("(?is)<friendlyName>\\s*([^<]+?)\\s*</friendlyName>")
            .find(xml)?.groupValues?.get(1)?.let(::xmlUnescape)

        val serviceBlocks = Regex("(?is)<service>(.*?)</service>").findAll(xml)
        var rawControlUrl: String? = null
        for (match in serviceBlocks) {
            val block = match.groupValues[1]
            if (block.contains("AVTransport", ignoreCase = true)) {
                rawControlUrl = Regex("(?is)<controlURL>\\s*([^<]+?)\\s*</controlURL>")
                    .find(block)?.groupValues?.get(1)?.trim()
                if (!rawControlUrl.isNullOrBlank()) break
            }
        }

        val resolvedControlUrl = rawControlUrl?.let { raw ->
            runCatching { URI(locationUrl).resolve(xmlUnescape(raw)).toString() }.getOrNull()
        }
        return friendlyName to resolvedControlUrl
    }

    /**
     * Sends a stream URL to a discovered DLNA UPnP AVTransport renderer and starts playback.
     */
    suspend fun castToDlnaDevice(
        device: CastDevice,
        streamUrl: String,
        title: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (streamUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Video source is not loaded yet"))
        }
        if (TvHandoffSanitizer.isLocalOnlyUrl(streamUrl)) {
            return@withContext Result.failure(
                IllegalStateException("Local proxy stream cannot be cast over DLNA — use the TV companion app instead")
            )
        }

        val controlUrl = device.controlUrl?.takeIf { it.isNotBlank() }
            ?: fetchUpnpDescription(device.location).second
            ?: return@withContext Result.failure(
                IOException("${device.name} did not expose a DLNA playback control endpoint")
            )

        val protocolInfo = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> "http-get:*:application/x-mpegURL:*"
            streamUrl.contains(".mpd", ignoreCase = true) -> "http-get:*:application/dash+xml:*"
            else -> "http-get:*:video/mp4:*"
        }

        val didlMetadata = buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>${xmlEscape(title.ifBlank { "Netvio Stream" })}</dc:title>")
            append("<upnp:class>object.item.videoItem</upnp:class>")
            append("<res protocolInfo=\"$protocolInfo\">${xmlEscape(streamUrl)}</res>")
            append("</item></DIDL-Lite>")
        }

        val setUriBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>${xmlEscape(streamUrl)}</CurrentURI>
                  <CurrentURIMetaData>${xmlEscape(didlMetadata)}</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val setRes = postSoapAction(
            controlUrl = controlUrl,
            action = "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            bodyXml = setUriBody
        )
        if (setRes.isFailure) return@withContext setRes

        val playBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <Speed>1</Speed>
                </u:Play>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val playRes = postSoapAction(
            controlUrl = controlUrl,
            action = "urn:schemas-upnp-org:service:AVTransport:1#Play",
            bodyXml = playBody
        )
        if (playRes.isSuccess) {
            _activeCastDevice.value = if (device.controlUrl == null) device.copy(controlUrl = controlUrl) else device
        }
        playRes
    }

    suspend fun stopDlnaPlayback(device: CastDevice) = withContext(Dispatchers.IO) {
        val controlUrl = device.controlUrl?.takeIf { it.isNotBlank() } ?: return@withContext
        val stopBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                </u:Stop>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        runCatching {
            postSoapAction(
                controlUrl = controlUrl,
                action = "urn:schemas-upnp-org:service:AVTransport:1#Stop",
                bodyXml = stopBody
            )
        }
    }

    private fun postSoapAction(controlUrl: String, action: String, bodyXml: String): Result<Unit> {
        return try {
            val req = Request.Builder()
                .url(controlUrl)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPACTION", "\"$action\"")
                .post(bodyXml.toRequestBody(soapMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("TV renderer returned HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(IOException(e.message ?: "Failed to reach DLNA renderer", e))
        }
    }

    internal fun xmlEscape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun xmlUnescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    fun selectDevice(device: CastDevice?) {
        _activeCastDevice.value = device
    }

    fun disconnect() {
        val prev = _activeCastDevice.value
        _activeCastDevice.value = null
        if (prev != null && !prev.controlUrl.isNullOrBlank()) {
            scope.launch {
                stopDlnaPlayback(prev)
            }
        }
    }
}
