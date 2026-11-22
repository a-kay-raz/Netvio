package com.netvio.data.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.netvio.core.addons.AddonRepository
import com.netvio.data.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TvCompanionManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val nsdLock = Any()
    private val nsdManager: NsdManager? =
        runCatching { context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryTimeoutJob: Job? = null

    private var activeWebSocket: WebSocket? = null
    private var activeToken: String? = null
    private var reconnectJob: Job? = null
    private val manualDisconnect = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(TvConnectionState.DISCONNECTED)
    val connectionState: StateFlow<TvConnectionState> = _connectionState.asStateFlow()

    private val _discoveredTvs = MutableStateFlow<List<TvDevice>>(emptyList())
    val discoveredTvs: StateFlow<List<TvDevice>> = _discoveredTvs.asStateFlow()

    private val _connectedTv = MutableStateFlow<TvDevice?>(null)
    val connectedTv: StateFlow<TvDevice?> = _connectedTv.asStateFlow()

    private val _savedTv = MutableStateFlow<TvDevice?>(null)
    val savedTv: StateFlow<TvDevice?> = _savedTv.asStateFlow()

    private val _nowPlaying = MutableStateFlow<TvNowPlayingState?>(null)
    val nowPlaying: StateFlow<TvNowPlayingState?> = _nowPlaying.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        loadSavedTv()
    }

    private fun loadSavedTv() {
        val host = prefs.getString(KEY_TV_HOST, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(KEY_TV_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return
        val tvId = prefs.getString(KEY_TV_ID, null)?.takeIf { it.isNotBlank() } ?: host
        val tvName = prefs.getString(KEY_TV_NAME, null)?.takeIf { it.isNotBlank() } ?: "TV ($host)"
        val port = prefs.getInt(KEY_TV_PORT, TvCompanionProtocol.DEFAULT_PORT)

        activeToken = token
        _savedTv.value = TvDevice(
            id = tvId,
            name = tvName,
            host = host,
            port = port,
            isPaired = true
        )
    }

    private fun savePairedTv(device: TvDevice, token: String) {
        prefs.edit()
            .putString(KEY_TV_ID, device.id)
            .putString(KEY_TV_NAME, device.name)
            .putString(KEY_TV_HOST, device.host)
            .putInt(KEY_TV_PORT, device.port)
            .putString(KEY_TV_TOKEN, token)
            .apply()
        activeToken = token
        _savedTv.value = device.copy(isPaired = true)
    }

    private fun clearSavedTv() {
        prefs.edit()
            .remove(KEY_TV_ID)
            .remove(KEY_TV_NAME)
            .remove(KEY_TV_HOST)
            .remove(KEY_TV_PORT)
            .remove(KEY_TV_TOKEN)
            .apply()
        activeToken = null
        _savedTv.value = null
    }

    fun refreshDiscovery() {
        val manager = nsdManager ?: return
        stopDiscovery()

        discoveryTimeoutJob?.cancel()
        _isDiscovering.value = true

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _isDiscovering.value = false
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                _isDiscovering.value = false
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                _isDiscovering.value = true
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                _isDiscovering.value = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val info = serviceInfo ?: return
                resolveFoundService(manager, info)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val lostName = serviceInfo?.serviceName ?: return
                _discoveredTvs.value = _discoveredTvs.value.filterNot {
                    it.id == lostName || it.name == lostName
                }
            }
        }

        synchronized(nsdLock) {
            activeDiscoveryListener = listener
            runCatching {
                manager.discoverServices(
                    TvCompanionProtocol.MDNS_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            }.onFailure {
                activeDiscoveryListener = null
                _isDiscovering.value = false
            }
        }

        discoveryTimeoutJob = scope.launch {
            delay(6000L)
            stopDiscovery()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveFoundService(manager: NsdManager, info: NsdServiceInfo) {
        runCatching {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    val service = resolved ?: return
                    val host = service.host?.hostAddress?.takeIf { it.isNotBlank() } ?: return
                    val port = service.port.takeIf { it in 1..65535 } ?: TvCompanionProtocol.DEFAULT_PORT

                    val attrs = runCatching { service.attributes }.getOrNull().orEmpty()
                    val attrName = attrs["tv_name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                    val attrId = attrs["tv_id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                    val attrVersion = attrs["v"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

                    val tvId = attrId ?: "${host}:$port"
                    val tvName = attrName ?: service.serviceName?.takeIf { it.isNotBlank() } ?: "TV"
                    val existingPaired = _savedTv.value?.let { it.host == host && it.port == port } == true

                    val device = TvDevice(
                        id = tvId,
                        name = tvName,
                        host = host,
                        port = port,
                        appVersion = attrVersion,
                        isPaired = existingPaired,
                        lastSeenMs = System.currentTimeMillis()
                    )

                    val current = _discoveredTvs.value.toMutableList()
                    val idx = current.indexOfFirst { it.id == device.id || (it.host == host && it.port == port) }
                    if (idx >= 0) {
                        current[idx] = device
                    } else {
                        current.add(device)
                    }
                    _discoveredTvs.value = current
                }
            })
        }
    }

    fun stopDiscovery() {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        val manager = nsdManager ?: run {
            _isDiscovering.value = false
            return
        }
        synchronized(nsdLock) {
            val listener = activeDiscoveryListener
            activeDiscoveryListener = null
            if (listener != null) {
                runCatching { manager.stopServiceDiscovery(listener) }
            }
        }
        _isDiscovering.value = false
    }

    suspend fun pairWithTv(hostInput: String, pin: String): Result<TvDevice> = withContext(Dispatchers.IO) {
        val parsedEndpoint = TvEndpointParser.parseHostPort(hostInput)
            ?: return@withContext Result.failure(IllegalArgumentException("Enter a valid TV IP or hostname"))
        val cleanPin = pin.trim()
        if (cleanPin.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Enter the pairing PIN shown on your TV"))
        }

        val (host, port) = parsedEndpoint
        _connectionState.value = TvConnectionState.CONNECTING
        _lastError.value = null

        try {
            val deviceModel = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
                .joinToString(" ")
                .ifBlank { "Android Phone" }
            val pairReq = TvPairRequest(
                pin = cleanPin,
                deviceName = deviceModel,
                protocolVersion = TvCompanionProtocol.PROTOCOL_VERSION
            )
            val request = Request.Builder()
                .url("http://$host:$port/api/v1/pair")
                .header("Content-Type", "application/json")
                .post(json.encodeToString(pairReq).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val parsedErr = runCatching { json.decodeFromString<TvPairResponse>(raw).error }.getOrNull()
                    val msg = parsedErr ?: "TV pairing failed (HTTP ${resp.code})"
                    _connectionState.value = TvConnectionState.ERROR
                    _lastError.value = msg
                    return@withContext Result.failure(IOException(msg))
                }

                val pairResp = json.decodeFromString<TvPairResponse>(raw)
                if (!pairResp.ok || pairResp.token.isBlank()) {
                    val msg = pairResp.error ?: "Invalid pairing PIN"
                    _connectionState.value = TvConnectionState.ERROR
                    _lastError.value = msg
                    return@withContext Result.failure(IOException(msg))
                }

                val tvDevice = TvDevice(
                    id = pairResp.tvId.ifBlank { "$host:$port" },
                    name = pairResp.tvName.ifBlank { "TV" },
                    host = host,
                    port = port,
                    isPaired = true,
                    lastSeenMs = System.currentTimeMillis()
                )
                manualDisconnect.set(false)
                savePairedTv(tvDevice, pairResp.token)
                _discoveredTvs.value = _discoveredTvs.value.map {
                    if (it.host == host && it.port == port) it.copy(isPaired = true, name = tvDevice.name) else it
                }
                openWebSocket(tvDevice, pairResp.token)
                Result.success(tvDevice)
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unable to connect to TV at $host:$port"
            _connectionState.value = TvConnectionState.ERROR
            _lastError.value = msg
            Result.failure(IOException(msg, e))
        }
    }

    suspend fun reconnectSavedTv(): Result<TvDevice> = withContext(Dispatchers.IO) {
        val tv = _savedTv.value
            ?: return@withContext Result.failure(IllegalStateException("No previously paired TV found"))
        val token = activeToken
            ?: return@withContext Result.failure(IllegalStateException("Saved pairing token missing — please re-pair"))

        manualDisconnect.set(false)
        _connectionState.value = TvConnectionState.CONNECTING
        _lastError.value = null

        try {
            val pingReq = Request.Builder()
                .url("http://${tv.host}:${tv.port}/api/v1/ping")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(pingReq).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    clearSavedTv()
                    val msg = "Pairing expired — enter the PIN from your TV again"
                    _connectionState.value = TvConnectionState.ERROR
                    _lastError.value = msg
                    return@withContext Result.failure(IOException(msg))
                }
                if (!resp.isSuccessful) {
                    val msg = "TV offline or unreachable at ${tv.host}:${tv.port}"
                    _connectionState.value = TvConnectionState.ERROR
                    _lastError.value = msg
                    return@withContext Result.failure(IOException(msg))
                }
            }
            openWebSocket(tv, token)
            Result.success(tv)
        } catch (e: Exception) {
            val msg = "Could not reach ${tv.name} on Wi-Fi"
            _connectionState.value = TvConnectionState.ERROR
            _lastError.value = msg
            Result.failure(IOException(msg, e))
        }
    }

    private fun openWebSocket(device: TvDevice, token: String, attempt: Int = 0) {
        reconnectJob?.cancel()
        runCatching { activeWebSocket?.close(1000, "Replacing connection") }

        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val wsReq = Request.Builder()
            .url("ws://${device.host}:${device.port}/ws?token=$encodedToken")
            .build()

        _connectionState.value = TvConnectionState.CONNECTING
        activeWebSocket = httpClient.newWebSocket(wsReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectedTv.value = device.copy(isPaired = true, lastSeenMs = System.currentTimeMillis())
                _connectionState.value = TvConnectionState.CONNECTED
                _lastError.value = null
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val env = runCatching { json.decodeFromString<TvWsEnvelope>(text) }.getOrNull() ?: return
                if (env.type == "status_update" && env.status != null) {
                    _nowPlaying.value = env.status
                } else if (!env.error.isNullOrBlank()) {
                    _lastError.value = env.error
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleSocketClosed(device, token, attempt, errorMsg = null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                if (code == 401 || code == 403) {
                    clearSavedTv()
                    _connectedTv.value = null
                    _nowPlaying.value = null
                    _connectionState.value = TvConnectionState.ERROR
                    _lastError.value = "TV pairing expired — re-enter PIN to pair again"
                    return
                }
                handleSocketClosed(device, token, attempt, errorMsg = t.message)
            }
        })
    }

    private fun handleSocketClosed(
        device: TvDevice,
        token: String,
        attempt: Int,
        errorMsg: String?
    ) {
        if (manualDisconnect.get() || activeToken != token) {
            _connectedTv.value = null
            _nowPlaying.value = null
            _connectionState.value = TvConnectionState.DISCONNECTED
            return
        }

        if (attempt < 3) {
            val backoffMs = listOf(2000L, 5000L, 10000L).getOrElse(attempt) { 10000L }
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(backoffMs)
                if (!manualDisconnect.get() && activeToken == token) {
                    openWebSocket(device, token, attempt + 1)
                }
            }
        } else {
            _connectedTv.value = null
            _nowPlaying.value = null
            _connectionState.value = if (errorMsg != null) TvConnectionState.ERROR else TvConnectionState.DISCONNECTED
            _lastError.value = errorMsg ?: "Lost connection to ${device.name}"
        }
    }

    fun disconnect(forgetSaved: Boolean = false) {
        manualDisconnect.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching { activeWebSocket?.close(1000, "User disconnected") }
        activeWebSocket = null
        _connectedTv.value = null
        _nowPlaying.value = null
        _connectionState.value = TvConnectionState.DISCONNECTED
        _lastError.value = null
        if (forgetSaved) {
            clearSavedTv()
        }
    }

    private suspend fun resolveTargetAndToken(): Pair<TvDevice, String>? {
        val token = activeToken ?: return null
        val target = _connectedTv.value ?: _savedTv.value ?: return null
        return target to token
    }

    suspend fun playOnTv(payload: TvHandoffPayload): Result<Unit> = withContext(Dispatchers.IO) {
        val (tv, token) = resolveTargetAndToken()
            ?: return@withContext Result.failure(IllegalStateException("Pair a TV first"))

        val sanitized = TvHandoffSanitizer.sanitizeForRemoteTv(payload)
        try {
            val req = Request.Builder()
                .url("http://${tv.host}:${tv.port}/api/v1/handoff")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .post(json.encodeToString(sanitized).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    clearSavedTv()
                    _connectedTv.value = null
                    _connectionState.value = TvConnectionState.ERROR
                    return@withContext Result.failure(IOException("TV pairing expired — please re-pair with PIN"))
                }
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("TV rejected playback handoff (HTTP ${resp.code})"))
                }
            }
            if (_connectionState.value != TvConnectionState.CONNECTED) {
                openWebSocket(tv, token)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IOException("Could not reach ${tv.name}: ${e.message ?: "network error"}", e))
        }
    }

    fun sendCommandAsync(command: TvRemoteCommand) {
        scope.launch {
            sendCommand(command)
        }
    }

    suspend fun sendCommand(command: TvRemoteCommand): Result<Unit> = withContext(Dispatchers.IO) {
        val (tv, token) = resolveTargetAndToken()
            ?: return@withContext Result.failure(IllegalStateException("No TV connected"))

        val envelope = json.encodeToString(TvWsEnvelope(type = "command", command = command))
        val ws = activeWebSocket
        if (ws != null && _connectionState.value == TvConnectionState.CONNECTED && ws.send(envelope)) {
            return@withContext Result.success(Unit)
        }

        return@withContext try {
            val req = Request.Builder()
                .url("http://${tv.host}:${tv.port}/api/v1/command")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .post(json.encodeToString(command).toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("TV command failed (HTTP ${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(IOException(e.message ?: "Command failed", e))
        }
    }

    suspend fun pushConfigToTv(
        includeAddons: Boolean,
        includeDebridKeys: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        val (tv, token) = resolveTargetAndToken()
            ?: return@withContext Result.failure(IllegalStateException("Pair a TV first"))

        val prefsRepo = PreferencesRepository.getInstance(context)
        val repoUrls = if (includeAddons) {
            prefsRepo.repositoryUrls.first().map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val stremioUrls = if (includeAddons) {
            AddonRepository.getInstance(context).uiState.value.addons
                .map { it.manifestUrl.trim() }
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        val payload = TvConfigPushPayload(
            repoUrls = repoUrls,
            stremioAddonUrls = stremioUrls,
            activeDebridProvider = if (includeDebridKeys) prefsRepo.activeDebridProvider.first() else null,
            realDebridApiKey = if (includeDebridKeys) prefsRepo.realDebridApiKey.first() else null,
            allDebridApiKey = if (includeDebridKeys) prefsRepo.allDebridApiKey.first() else null,
            torBoxApiKey = if (includeDebridKeys) prefsRepo.torBoxApiKey.first() else null,
            debridEnabled = if (includeDebridKeys) prefsRepo.debridEnabled.first() else null
        )

        try {
            val req = Request.Builder()
                .url("http://${tv.host}:${tv.port}/api/v1/config/push")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .post(json.encodeToString(payload).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    clearSavedTv()
                    return@withContext Result.failure(IOException("TV pairing expired — please re-pair with PIN"))
                }
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("Config push failed (HTTP ${resp.code})"))
                }
            }

            val parts = mutableListOf<String>()
            if (includeAddons) {
                parts.add("${repoUrls.size} repo(s) & ${stremioUrls.size} addon(s)")
            }
            if (includeDebridKeys) {
                parts.add("Debrid keys")
            }
            val summary = if (parts.isEmpty()) "Synced config" else "Pushed ${parts.joinToString(" + ")} to ${tv.name}"
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(IOException("Could not push setup to ${tv.name}: ${e.message ?: "network error"}", e))
        }
    }

    companion object {
        private const val PREFS_NAME = "tv_companion_prefs"
        private const val KEY_TV_ID = "saved_tv_id"
        private const val KEY_TV_NAME = "saved_tv_name"
        private const val KEY_TV_HOST = "saved_tv_host"
        private const val KEY_TV_PORT = "saved_tv_port"
        private const val KEY_TV_TOKEN = "saved_tv_token"

        @Volatile
        private var instance: TvCompanionManager? = null

        fun getInstance(context: Context): TvCompanionManager {
            return instance ?: synchronized(this) {
                instance ?: TvCompanionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
