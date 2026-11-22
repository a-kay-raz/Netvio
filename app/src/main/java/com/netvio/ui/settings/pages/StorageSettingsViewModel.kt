package com.netvio.ui.settings.pages

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.datastore.preferences.core.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.netvio.core.network.DohDns
import com.netvio.core.p2p.P2pStreamingEngine
import com.netvio.data.backup.BackupInspectionResult
import com.netvio.data.backup.BackupSummary
import com.netvio.data.backup.ConfigurationBackupManager
import com.netvio.data.cache.AppCacheManager
import com.netvio.data.cache.CacheBreakdown
import com.netvio.data.cache.CacheCategory
import com.netvio.data.preferences.PreferencesRepository
import com.netvio.data.preferences.preferencesDataStore
import com.netvio.download.DownloadEngine
import com.netvio.ui.player.StreamingPlaybackCacheManager
import com.netvio.download.DownloadStorageVolume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Locale

sealed class BackupUiState {
    object Idle : BackupUiState()
    object Exporting : BackupUiState()
    data class ExportSuccess(val message: String) : BackupUiState()
    data class ExportError(val message: String) : BackupUiState()
    object Inspecting : BackupUiState()
    data class ReadyToRestore(val inspection: BackupInspectionResult) : BackupUiState()
    object Restoring : BackupUiState()
    data class RestoreSuccess(val summary: BackupSummary?) : BackupUiState()
    data class RestoreError(val message: String) : BackupUiState()
}

class StorageSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesRepository.getInstance(application)

    private val _cacheSizeFormatted = MutableStateFlow("Calculating...")
    val cacheSizeFormatted: StateFlow<String> = _cacheSizeFormatted.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _cacheBreakdown = MutableStateFlow(CacheBreakdown())
    val cacheBreakdown: StateFlow<CacheBreakdown> = _cacheBreakdown.asStateFlow()

    private val _clearingCategory = MutableStateFlow<CacheCategory?>(null)
    val clearingCategory: StateFlow<CacheCategory?> = _clearingCategory.asStateFlow()

    private val _autoCleanupEnabled = MutableStateFlow(true)
    val autoCleanupEnabled: StateFlow<Boolean> = _autoCleanupEnabled.asStateFlow()

    private val _maxCacheLimitMb = MutableStateFlow(500)
    val maxCacheLimitMb: StateFlow<Int> = _maxCacheLimitMb.asStateFlow()

    private val _cacheRetentionDays = MutableStateFlow(7)
    val cacheRetentionDays: StateFlow<Int> = _cacheRetentionDays.asStateFlow()

    private val _autoClearP2pOnExit = MutableStateFlow(true)
    val autoClearP2pOnExit: StateFlow<Boolean> = _autoClearP2pOnExit.asStateFlow()

    private val _lastAutoCleanTimestamp = MutableStateFlow(0L)
    val lastAutoCleanTimestamp: StateFlow<Long> = _lastAutoCleanTimestamp.asStateFlow()

    private val _lastAutoCleanReclaimedBytes = MutableStateFlow(0L)
    val lastAutoCleanReclaimedBytes: StateFlow<Long> = _lastAutoCleanReclaimedBytes.asStateFlow()

    private val _downloadsSizeBytes = MutableStateFlow(0L)
    val downloadsSizeBytes: StateFlow<Long> = _downloadsSizeBytes.asStateFlow()

    private val _configSizeBytes = MutableStateFlow(0L)
    val configSizeBytes: StateFlow<Long> = _configSizeBytes.asStateFlow()

    private val _isCalculating = MutableStateFlow(true)
    val isCalculating: StateFlow<Boolean> = _isCalculating.asStateFlow()

    private val _isClearing = MutableStateFlow(false)
    val isClearing: StateFlow<Boolean> = _isClearing.asStateFlow()

    private val _wifiOnlyDownloads = MutableStateFlow(true)
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnlyDownloads.asStateFlow()

    private val _customDownloadDir = MutableStateFlow<String?>(null)
    val customDownloadDir: StateFlow<String?> = _customDownloadDir.asStateFlow()

    val defaultDownloadDir: String = try {
        (application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(application.filesDir, "downloads")).absolutePath
    } catch (_: Exception) { "" }

    init {
        viewModelScope.launch { prefs.wifiOnlyDownloads.collect { _wifiOnlyDownloads.value = it } }
        viewModelScope.launch { prefs.autoCacheCleanupEnabled.collect { _autoCleanupEnabled.value = it } }
        viewModelScope.launch { prefs.maxCacheLimitMb.collect { _maxCacheLimitMb.value = it } }
        viewModelScope.launch { prefs.cacheRetentionDays.collect { _cacheRetentionDays.value = it } }
        viewModelScope.launch { prefs.autoClearP2pOnExit.collect { _autoClearP2pOnExit.value = it } }
        viewModelScope.launch { prefs.lastCacheCleanupTimestamp.collect { _lastAutoCleanTimestamp.value = it } }
        viewModelScope.launch { prefs.lastCacheReclaimedBytes.collect { _lastAutoCleanReclaimedBytes.value = it } }
        viewModelScope.launch {
            prefs.customDownloadDir.collect { raw ->
                if (!raw.isNullOrBlank()) {
                    val isInvalidScopedPath = withContext(Dispatchers.IO) {
                        val engine = DownloadEngine(getApplication())
                        val resolved = File(engine.resolveWritableCustomPath(raw))
                        !engine.isDirectoryWritable(resolved) && !engine.isAppManagedDirectory(resolved)
                    }
                    if (isInvalidScopedPath) {
                        _customDownloadDir.value = null
                        prefs.setCustomDownloadDir(null)
                        return@collect
                    }
                }
                _customDownloadDir.value = raw
            }
        }
        refreshAllStorageTelemetry()
    }

    fun refreshAllStorageTelemetry() {
        calculateCacheSize()
        calculateStorageBreakdown()
    }

    fun calculateCacheSize() {
        viewModelScope.launch {
            _isCalculating.value = true
            val breakdown = withContext(Dispatchers.IO) {
                AppCacheManager.inspectBreakdown(getApplication())
            }
            _cacheBreakdown.value = breakdown
            _cacheSizeBytes.value = breakdown.totalBytes
            _cacheSizeFormatted.value = formatSize(breakdown.totalBytes)
            _isCalculating.value = false
        }
    }

    fun calculateStorageBreakdown() {
        viewModelScope.launch {
            val (downloadsBytes, configBytes) = withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val downloadDir = try {
                    DownloadEngine(context).getDownloadDirectory()
                } catch (_: Exception) { null }

                val dSize = getDirSizeSafely(downloadDir, mutableSetOf(), emptySet())

                var cSize = 0L
                try {
                    val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
                    cSize += getDirSizeSafely(sharedPrefsDir, mutableSetOf(), emptySet())
                } catch (_: Exception) {}
                try {
                    val datastoreDir = File(context.filesDir, "datastore")
                    cSize += getDirSizeSafely(datastoreDir, mutableSetOf(), emptySet())
                } catch (_: Exception) {}
                try {
                    val stateDir = File(context.noBackupFilesDir, "nuvio-engine/state")
                    cSize += getDirSizeSafely(stateDir, mutableSetOf(), emptySet())
                } catch (_: Exception) {}
                try {
                    val metaFile = File(context.filesDir, "download_metadata.json")
                    if (metaFile.exists()) cSize += metaFile.length()
                } catch (_: Exception) {}

                dSize to cSize
            }
            _downloadsSizeBytes.value = downloadsBytes
            _configSizeBytes.value = configBytes
        }
    }

    suspend fun clearCacheSuspend(): Boolean {
        _isClearing.value = true
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) {
            AppCacheManager.clearAllCache(app)
        }
        val breakdown = withContext(Dispatchers.IO) {
            AppCacheManager.inspectBreakdown(app)
        }
        _cacheBreakdown.value = breakdown
        _cacheSizeBytes.value = breakdown.totalBytes
        _cacheSizeFormatted.value = formatSize(breakdown.totalBytes)
        _isClearing.value = false
        calculateStorageBreakdown()
        return true
    }

    fun clearCacheCategory(category: CacheCategory, onComplete: (Long) -> Unit = {}) {
        viewModelScope.launch {
            _clearingCategory.value = category
            val app = getApplication<Application>()
            val reclaimed = withContext(Dispatchers.IO) {
                AppCacheManager.clearCategory(app, category)
            }
            val breakdown = withContext(Dispatchers.IO) {
                AppCacheManager.inspectBreakdown(app)
            }
            _cacheBreakdown.value = breakdown
            _cacheSizeBytes.value = breakdown.totalBytes
            _cacheSizeFormatted.value = formatSize(breakdown.totalBytes)
            _clearingCategory.value = null
            calculateStorageBreakdown()
            onComplete(reclaimed)
        }
    }

    fun setAutoCleanupEnabled(enabled: Boolean) {
        _autoCleanupEnabled.value = enabled
        viewModelScope.launch {
            prefs.setAutoCacheCleanupEnabled(enabled)
            if (enabled) {
                AppCacheManager.runAutomaticMaintenance(getApplication(), force = false)
                refreshAllStorageTelemetry()
            }
        }
    }

    fun setMaxCacheLimitMb(limitMb: Int) {
        val clamped = limitMb.coerceIn(250, 2048)
        _maxCacheLimitMb.value = clamped
        viewModelScope.launch {
            prefs.setMaxCacheLimitMb(clamped)
            AppCacheManager.applySubsystemCaps(
                context = getApplication(),
                maxCacheLimitMb = clamped,
                p2pEnabled = prefs.p2pStreamsEnabled.first()
            )
            AppCacheManager.runAutomaticMaintenance(getApplication(), force = true)
            refreshAllStorageTelemetry()
        }
    }

    fun setCacheRetentionDays(days: Int) {
        val clamped = days.coerceIn(1, 90)
        _cacheRetentionDays.value = clamped
        viewModelScope.launch {
            prefs.setCacheRetentionDays(clamped)
            AppCacheManager.runAutomaticMaintenance(getApplication(), force = true)
            refreshAllStorageTelemetry()
        }
    }

    fun setAutoClearP2pOnExit(enabled: Boolean) {
        _autoClearP2pOnExit.value = enabled
        viewModelScope.launch { prefs.setAutoClearP2pOnExit(enabled) }
    }

    fun clearCache(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            clearCacheSuspend()
            onComplete()
        }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { prefs.setWifiOnlyDownloads(enabled) }
    }

    fun setCustomDownloadDir(path: String?) {
        _customDownloadDir.value = path
        viewModelScope.launch {
            prefs.setCustomDownloadDir(path)
            refreshAllStorageTelemetry()
        }
    }

    suspend fun getAvailableDownloadVolumes(): List<DownloadStorageVolume> = withContext(Dispatchers.IO) {
        DownloadEngine(getApplication()).getAvailableWritableVolumes()
    }

    suspend fun isDirectoryWritable(rawPath: String): Boolean = withContext(Dispatchers.IO) {
        val engine = DownloadEngine(getApplication())
        val resolved = File(engine.resolveWritableCustomPath(rawPath))
        engine.isDirectoryWritable(resolved)
    }

    private val backupManager = ConfigurationBackupManager(application)

    private val _backupUiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupUiState: StateFlow<BackupUiState> = _backupUiState.asStateFlow()

    fun dismissBackupState() {
        _backupUiState.value = BackupUiState.Idle
    }

    fun exportConfiguration(uri: Uri) {
        viewModelScope.launch {
            _backupUiState.value = BackupUiState.Exporting
            val result = backupManager.exportConfiguration(uri)
            if (result.isSuccess) {
                _backupUiState.value = BackupUiState.ExportSuccess(result.getOrDefault("Configuration exported successfully"))
                refreshAllStorageTelemetry()
            } else {
                val err = result.exceptionOrNull()?.message ?: "Export failed"
                _backupUiState.value = BackupUiState.ExportError(err)
            }
        }
    }

    fun inspectBackup(uri: Uri) {
        viewModelScope.launch {
            _backupUiState.value = BackupUiState.Inspecting
            val inspection = backupManager.inspectBackup(uri)
            if (inspection.isValid) {
                _backupUiState.value = BackupUiState.ReadyToRestore(inspection)
            } else {
                _backupUiState.value = BackupUiState.RestoreError(inspection.errorMessage ?: "Invalid backup file")
            }
        }
    }

    fun confirmRestore(inspection: BackupInspectionResult) {
        val root = inspection.rootJson ?: return
        viewModelScope.launch {
            _backupUiState.value = BackupUiState.Restoring
            val result = backupManager.applyBackup(root)
            if (result.isSuccess) {
                refreshAllStorageTelemetry()
                _backupUiState.value = BackupUiState.RestoreSuccess(inspection.summary)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Restore failed"
                _backupUiState.value = BackupUiState.RestoreError(err)
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes <= 0L -> "0.00 MB"
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
        }
    }

    /**
     * Traverses a directory tree safely:
     * - Skips symbolic links to avoid escaping or infinite cycles.
     * - Enforces that child files stay within canonical boundary of the root directory.
     * - Skips any paths inside [excludeCanonical] (e.g. offline downloaded media).
     * - Prevents duplicate visits via [visitedCanonical].
     */
    private fun getDirSizeSafely(
        dir: File?,
        visitedCanonical: MutableSet<String>,
        excludeCanonical: Set<String>
    ): Long {
        if (dir == null || !dir.exists()) return 0L
        val canonical = try { dir.canonicalFile } catch (_: Exception) { return 0L }
        val canonicalPath = canonical.absolutePath
        if (!visitedCanonical.add(canonicalPath)) return 0L
        if (excludeCanonical.any { canonicalPath.startsWith(it) }) return 0L

        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            try {
                if (Files.isSymbolicLink(file.toPath())) {
                    continue
                }
                val fileCanonical = file.canonicalFile
                val fileCanonicalPath = fileCanonical.absolutePath
                if (!fileCanonicalPath.startsWith(canonicalPath)) {
                    continue
                }
                if (excludeCanonical.any { fileCanonicalPath.startsWith(it) }) {
                    continue
                }
                if (file.isDirectory) {
                    size += getDirSizeSafely(file, visitedCanonical, excludeCanonical)
                } else {
                    size += file.length()
                }
            } catch (_: Exception) {}
        }
        return size
    }

    /**
     * Recursively and safely deletes directory contents:
     * - Strictly avoids recursing through symbolic links.
     * - Respects [excludeCanonical] to protect user downloads.
     * - Deletes empty child subdirectories (depth-first).
     */
    private fun deleteDirSafely(
        dir: File?,
        visitedCanonical: MutableSet<String>,
        excludeCanonical: Set<String>,
        isRoot: Boolean = true
    ): Boolean {
        if (dir == null || !dir.exists()) return true
        val canonical = try { dir.canonicalFile } catch (_: Exception) { return true }
        val canonicalPath = canonical.absolutePath
        if (!visitedCanonical.add(canonicalPath)) return true
        if (excludeCanonical.any { canonicalPath.startsWith(it) }) return true

        var allDeleted = true
        val files = dir.listFiles() ?: return true
        for (file in files) {
            try {
                if (Files.isSymbolicLink(file.toPath())) {
                    file.delete()
                    continue
                }
                val fileCanonical = file.canonicalFile
                val fileCanonicalPath = fileCanonical.absolutePath
                if (!fileCanonicalPath.startsWith(canonicalPath)) {
                    continue
                }
                if (excludeCanonical.any { fileCanonicalPath.startsWith(it) }) {
                    continue
                }
                if (file.isDirectory) {
                    val childSuccess = deleteDirSafely(file, visitedCanonical, excludeCanonical, isRoot = false)
                    allDeleted = allDeleted && childSuccess
                } else {
                    val deleted = file.delete()
                    if (!deleted) file.deleteOnExit()
                    allDeleted = allDeleted && deleted
                }
            } catch (_: Exception) {
                allDeleted = false
            }
        }
        if (!isRoot && dir.exists()) {
            val dirDeleted = dir.delete()
            if (!dirDeleted) dir.deleteOnExit()
            allDeleted = allDeleted && dirDeleted
        }
        return allDeleted
    }
}
