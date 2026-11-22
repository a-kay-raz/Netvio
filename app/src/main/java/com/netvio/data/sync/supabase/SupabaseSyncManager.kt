package com.netvio.data.sync.supabase

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.netvio.app.plugin.PluginRepositoryService
import com.netvio.core.BuildConfig
import com.netvio.core.sync.supabase.*
import com.netvio.data.local.AppDatabase
import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.data.local.WatchlistEntity
import com.netvio.data.preferences.PreferencesRepository
import com.netvio.data.profile.ProfileManager
import com.netvio.data.repository.PlaylistRepository
import com.netvio.data.sync.SyncCoordinator
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

enum class SyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

/**
 * Pure policy helpers governing sync throttling and per-domain dirty detection
 * to minimize unnecessary read/write traffic against Free Tier quotas.
 */
internal object SyncEfficiencyPolicy {
    const val AUTO_PULL_COOLDOWN_MS: Long = 5L * 60L * 1000L

    fun shouldRunDownstreamPull(
        forceFull: Boolean,
        isInitialSignInRestore: Boolean,
        lastPullMs: Long,
        nowMs: Long
    ): Boolean {
        if (forceFull || isInitialSignInRestore || lastPullMs <= 0L) return true
        val elapsed = nowMs - lastPullMs
        return elapsed < 0L || elapsed >= AUTO_PULL_COOLDOWN_MS
    }

    fun hashProfiles(profiles: List<CloudUserProfile>): String {
        val canonical = profiles
            .sortedBy { it.profileId }
            .joinToString("\n") { p ->
                "${p.profileId}|${p.name}|${p.profileType}|${p.avatarId}|${p.customAvatarUrl.orEmpty()}|${p.pinHash.orEmpty()}|${p.isDeleted}"
            }
        return sha256Hex(canonical)
    }

    fun hashSettings(settings: Map<String, String>): String {
        val canonical = settings.entries
            .sortedBy { it.key }
            .joinToString("\n") { (k, v) -> "$k=$v" }
        return sha256Hex(canonical)
    }

    fun hashPlaylists(playlists: List<CloudPlaylist>): String {
        val canonical = playlists
            .sortedBy { it.playlistId }
            .joinToString("\n") { pl ->
                "${pl.playlistId}|${pl.title}|${pl.isSystem}|${pl.itemsJson}|${pl.lastPlayedAtMs}|${pl.isDeleted}"
            }
        return sha256Hex(canonical)
    }

    fun hashAddons(addons: List<CloudUserAddon>): String {
        val canonical = addons
            .sortedBy { "${it.addonType}::${it.manifestUrl}" }
            .joinToString("\n") { a ->
                "${a.addonType}|${a.manifestUrl}|${a.name.orEmpty()}|${a.isDeleted}"
            }
        return sha256Hex(canonical)
    }

    fun parsePendingDeletedAddon(entry: String): Pair<String, String> {
        val idx = entry.indexOf('|')
        if (idx > 0) {
            val prefix = entry.substring(0, idx).trim()
            val url = entry.substring(idx + 1).trim()
            if ((prefix == "stremio" || prefix == "js_repo") && url.isNotBlank()) {
                return prefix to url
            }
        }
        val url = entry.trim()
        val inferredType = if (url.endsWith("/manifest.json", ignoreCase = true) || url.endsWith("manifest.json", ignoreCase = true)) {
            "stremio"
        } else {
            "js_repo"
        }
        return inferredType to url
    }

    fun shouldApplyRemotePlayback(
        localIsDirty: Boolean,
        localIsDeleted: Boolean,
        localIsDismissed: Boolean,
        localClientUpdatedAtMs: Long,
        localPositionMs: Long,
        remoteIsDeleted: Boolean,
        remoteIsDismissed: Boolean,
        remoteLastWatchedTs: Long,
        remotePositionMs: Long
    ): Boolean {
        if (!localIsDirty) return true
        if (remoteLastWatchedTs > localClientUpdatedAtMs) return true
        if (localIsDeleted || localIsDismissed) return false
        if (remoteIsDeleted || remoteIsDismissed) {
            return remoteLastWatchedTs >= localClientUpdatedAtMs
        }
        if (remoteLastWatchedTs == localClientUpdatedAtMs) {
            return remotePositionMs >= localPositionMs
        }
        return abs(remoteLastWatchedTs - localClientUpdatedAtMs) < 60_000L && remotePositionMs > localPositionMs
    }

    fun shouldApplyRemoteWatchlist(
        localIsDirty: Boolean,
        localIsDeleted: Boolean,
        localClientUpdatedAtMs: Long,
        remoteIsDeleted: Boolean,
        remoteClientUpdatedAtMs: Long
    ): Boolean {
        if (!localIsDirty) return true
        if (remoteClientUpdatedAtMs > localClientUpdatedAtMs) return true
        if (remoteClientUpdatedAtMs == localClientUpdatedAtMs && !localIsDeleted && remoteIsDeleted) return true
        return false
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

class SupabaseSyncManager private constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val TAG = "SupabaseSyncManager"
    private val preferencesRepository = PreferencesRepository.getInstance(context)
    private val playlistRepository = PlaylistRepository.getInstance(context)
    private val profileManager = ProfileManager.getInstance(context)
    private val db = AppDatabase.getInstance(context)
    private val playbackHistoryDao = db.playbackHistoryDao()
    private val watchlistDao = db.watchlistDao()
    private val json = Json { ignoreUnknownKeys = true }

    val authClient = SupabaseAuthClient()

    private var currentSessionCache: SupabaseSession? = null
    private var scheduledSyncJob: Job? = null
    private val syncMutex = Mutex()
    private val isInitialRestoreInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastInitialRestoreTimestamp = java.util.concurrent.atomic.AtomicLong(0L)
    private val hasPendingDirtySync = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    val tokenAuthenticator = SupabaseTokenAuthenticator(
        authClient = authClient,
        sessionProvider = { currentSessionCache },
        suspendSessionProvider = {
            currentSessionCache ?: preferencesRepository.supabaseSession.first()
        },
        onSessionRefreshed = { refreshed ->
            if (currentSessionCache != null) {
                currentSessionCache = refreshed
                preferencesRepository.setSupabaseSession(refreshed)
            }
        },
        onSessionExpired = {
            sessionGeneration.incrementAndGet()
            currentSessionCache = null
            preferencesRepository.clearSupabaseSession()
        }
    )

    val restClient = SupabaseRestClient(
        tokenAuthenticator = tokenAuthenticator
    )

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncErrorMessage = MutableStateFlow<String?>(null)
    val syncErrorMessage: StateFlow<String?> = _syncErrorMessage.asStateFlow()

    private val isSyncingActive = java.util.concurrent.atomic.AtomicBoolean(false)

    val sessionFlow: StateFlow<SupabaseSession?> = preferencesRepository.supabaseSession
        .stateIn(scope, SharingStarted.Eagerly, null)

    val lastSyncTimestamp: StateFlow<Long> = preferencesRepository.supabaseLastSyncTimestamp
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    init {
        scope.launch {
            sessionFlow.collect { session ->
                currentSessionCache = session
            }
        }
    }

    /**
     * Schedules a debounced background sync. Call whenever local data is added, deleted, or updated.
     */
    fun scheduleSync(delayMs: Long = 1500L) {
        scheduledSyncJob?.cancel()
        scheduledSyncJob = scope.launch {
            val jitter = if (delayMs > 0L) (0L..500L).random() else 0L
            delay(delayMs + jitter)
            // Wait if an initial sign-in restore is currently pulling cloud backup
            while (isInitialRestoreInProgress.get()) {
                delay(250L)
            }
            val session = currentSessionCache ?: preferencesRepository.supabaseSession.first() ?: return@launch
            currentSessionCache = session
            syncAll(forceFull = false)
        }
    }

    /**
     * Cancels pending debounce delay and immediately flushes any dirty local changes to Supabase
     * (e.g., when the app enters background).
     */
    fun flushPendingChangesAsync() {
        scheduledSyncJob?.cancel()
        scope.launch {
            while (isInitialRestoreInProgress.get()) {
                delay(200L)
            }
            val session = currentSessionCache ?: preferencesRepository.supabaseSession.first() ?: return@launch
            currentSessionCache = session
            syncAll(forceFull = false)
        }
    }

    /**
     * Authenticates with Google One-Tap / Credential Manager and exchanges ID token with Supabase.
     * Enforces a strict "Restore-First, Push-Second" sequence so signing into a fresh app
     * never overwrites existing cloud backups.
     */
    suspend fun signInWithGoogle(activity: Activity): Result<SupabaseSession> = withContext(Dispatchers.IO) {
        try {
            val serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
            if (serverClientId.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Google Web Client ID is not configured."))
            }

            val rawNonce = generateRawNonce()
            val hashedNonce = sha256(rawNonce)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .setNonce(hashedNonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(activity)
            val result = try {
                credentialManager.getCredential(
                    request = request,
                    context = activity
                )
            } catch (e: GetCredentialCancellationException) {
                return@withContext Result.failure(Exception("Sign in cancelled by user"))
            } catch (e: NoCredentialException) {
                return@withContext Result.failure(
                    Exception("No Google account found on this device or Google Play Services is unavailable. Add a Google account in Android Settings and try again.")
                )
            } catch (e: GetCredentialException) {
                return@withContext Result.failure(Exception("Google sign-in unavailable: ${e.message ?: "Credential Manager error"}"))
            }

            val credential = result.credential
            val idToken = when {
                credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    googleIdTokenCredential.idToken
                }
                else -> {
                    return@withContext Result.failure(IllegalStateException("Unrecognized credential format returned."))
                }
            }

            // Read previous synced user ID BEFORE setSupabaseSession overwrites it
            val lastUserId = preferencesRepository.supabaseLastSyncedUserId.first()

            val authResult = authClient.signInWithGoogleIdToken(idToken, rawNonce)
            if (authResult.isFailure) {
                return@withContext Result.failure(authResult.exceptionOrNull() ?: Exception("Supabase Auth failed"))
            }

            val session = authResult.getOrThrow()
            sessionGeneration.incrementAndGet()
            isInitialRestoreInProgress.set(true)
            SyncCoordinator.isGoogleInitialRestoreActive.set(true)
            try {
                currentSessionCache = session
                preferencesRepository.setSupabaseSession(session)

                // Always reset watermarks and efficiency caches on fresh sign-in so we pull complete cloud state
                preferencesRepository.setSupabasePlaybackWatermark(null)
                preferencesRepository.setSupabaseWatchlistWatermark(null)
                preferencesRepository.setSupabasePlaylistsWatermark(null)
                preferencesRepository.setSupabaseAddonsWatermark(null)
                preferencesRepository.clearSupabaseSyncCache()
                preferencesRepository.setSupabaseLastSyncedUserId(session.userId)
                // Clear any stale pending deletion tombstones so they don't clobber incoming cloud addons
                val staleDeletions = preferencesRepository.supabasePendingDeletedAddonUrls.first()
                if (staleDeletions.isNotEmpty()) {
                    preferencesRepository.clearPendingDeletedAddonUrls(staleDeletions)
                }

                val account = CloudUserAccount(
                    userId = session.userId,
                    email = session.email,
                    displayName = session.displayName,
                    avatarUrl = session.avatarUrl,
                    activeProfileId = profileManager.activeProfileId.value
                )
                restClient.upsertAccount(account)

                val isSameOrGuestUser = (lastUserId == null || lastUserId == session.userId)
                triggerSafeMergeAndSync(
                    session = session,
                    mergePreExistingLocal = isSameOrGuestUser
                )
            } finally {
                isInitialRestoreInProgress.set(false)
                SyncCoordinator.isGoogleInitialRestoreActive.set(false)
            }

            Result.success(session)
        } catch (e: Exception) {
            isInitialRestoreInProgress.set(false)
            SyncCoordinator.isGoogleInitialRestoreActive.set(false)
            Log.e(TAG, "Sign in with Google failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        sessionGeneration.incrementAndGet()
        hasPendingDirtySync.set(false)
        scheduledSyncJob?.cancel()
        val session = currentSessionCache
        currentSessionCache = null
        preferencesRepository.clearSupabaseSession()
        _syncStatus.value = SyncStatus.IDLE
        _syncErrorMessage.value = null
        try {
            if (session != null) {
                authClient.signOut(session.accessToken)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun triggerSafeMergeAndSync(
        session: SupabaseSession,
        mergePreExistingLocal: Boolean
    ) {
        try {
            if (mergePreExistingLocal) {
                // Preserve any guest/local items created before signing in, using their existing timestamps
                // (or a slight fallback timestamp so newer cloud backups win if there is a conflict).
                val fallbackTs = System.currentTimeMillis() - 60_000L
                val localPlayback = playbackHistoryDao.getAllHistory()
                if (localPlayback.isNotEmpty()) {
                    val dirtyPlayback = localPlayback.map {
                        it.copy(
                            isDirty = true,
                            clientUpdatedAtMs = if (it.clientUpdatedAtMs > 0L) it.clientUpdatedAtMs else fallbackTs
                        )
                    }
                    playbackHistoryDao.upsertBatch(dirtyPlayback)
                }

                val localWatchlist = watchlistDao.getAllWatchlist()
                if (localWatchlist.isNotEmpty()) {
                    val dirtyWatchlist = localWatchlist.map {
                        it.copy(
                            isDirty = true,
                            clientUpdatedAtMs = if (it.clientUpdatedAtMs > 0L) it.clientUpdatedAtMs else fallbackTs
                        )
                    }
                    watchlistDao.insertBatch(dirtyWatchlist)
                }
            }

            // Execute full sync with restoreFirst=true so all cloud data is pulled BEFORE local dirty items are pushed
            syncAll(forceFull = true, isInitialSignInRestore = true)
            lastInitialRestoreTimestamp.set(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Safe merge failed", e)
        }
    }

    suspend fun syncPlaybackDirty(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitialRestoreInProgress.get()) return@withContext Result.success(Unit)
        val syncGen = sessionGeneration.get()
        val session = currentSessionCache ?: preferencesRepository.supabaseSession.first() ?: return@withContext Result.success(Unit)
        currentSessionCache = session
        val cloudSyncEnabled = preferencesRepository.supabaseCloudSyncEnabled.first()
        val syncPlaybackEnabled = preferencesRepository.supabaseSyncPlayback.first()
        if (!cloudSyncEnabled || !syncPlaybackEnabled) return@withContext Result.success(Unit)

        try {
            SyncCoordinator.dataMutex.withLock {
                if (sessionGeneration.get() != syncGen || currentSessionCache == null) {
                    return@withLock Result.success(Unit)
                }
                // Ensure profiles exist in cloud before pushing foreign-keyed playback rows
                syncProfilesUpstreamIfDirty(session, forcePush = false)
                pushDirtyPlaybackBatches(session.userId)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun pushDirtyPlaybackBatches(userId: String): Result<Unit> {
        val dirtyRows = playbackHistoryDao.getDirtyRows()
            .sortedByDescending { it.clientUpdatedAtMs.takeIf { ts -> ts > 0L } ?: it.lastWatchedTimestamp }
        if (dirtyRows.isEmpty()) return Result.success(Unit)

        for (chunk in dirtyRows.chunked(50)) {
            val cloudItems = chunk.map { entity ->
                val cleanProvider = (entity.provider?.trim()?.ifBlank { null } ?: "default").lowercase()
                val cloudMediaId = encodeCloudPlaybackMediaId(entity.mediaId, entity.seasonNumber, entity.episodeNumber)
                CloudPlaybackItem(
                    userId = userId,
                    profileId = entity.profileId,
                    provider = cleanProvider,
                    mediaId = cloudMediaId,
                    title = entity.title,
                    episodeTitle = entity.episodeTitle,
                    seasonNumber = entity.seasonNumber,
                    episodeNumber = entity.episodeNumber,
                    posterUrl = sanitizeCloudUrl(entity.posterUrl),
                    backdropUrl = sanitizeCloudUrl(entity.backdropUrl),
                    currentPositionMs = entity.currentPositionMs,
                    durationMs = entity.durationMs,
                    isCompleted = entity.isCompleted,
                    isDismissed = entity.isDismissedFromContinueWatching,
                    isDeleted = entity.isDeleted,
                    mediaType = entity.mediaType ?: "MOVIE",
                    episodeData = entity.episodeData,
                    lastWatchedTimestamp = entity.clientUpdatedAtMs.takeIf { it > 0L } ?: entity.lastWatchedTimestamp
                )
            }
            val pushRes = restClient.upsertPlaybackBatch(cloudItems)
            if (pushRes.isSuccess) {
                Log.i(TAG, "Successfully pushed ${chunk.size} playback items to cloud")
                for (row in chunk) {
                    playbackHistoryDao.markCleanExact(row.id, row.clientUpdatedAtMs)
                }
            } else {
                val err = pushRes.exceptionOrNull() ?: IOException("Push playback failed")
                Log.e(TAG, "Failed to push playback batch: ${err.message}")
                return Result.failure(err)
            }
        }
        return Result.success(Unit)
    }

    private suspend fun pushDirtyWatchlistBatches(userId: String): Result<Unit> {
        val dirtyWatchlist = watchlistDao.getDirtyRows()
            .sortedByDescending { it.clientUpdatedAtMs }
        if (dirtyWatchlist.isEmpty()) return Result.success(Unit)

        for (chunk in dirtyWatchlist.chunked(100)) {
            val cloudWatchlist = chunk.map { entity ->
                CloudWatchlistItem(
                    userId = userId,
                    profileId = entity.profileId,
                    mediaId = entity.mediaId,
                    title = entity.title,
                    posterUrl = sanitizeCloudUrl(entity.posterUrl),
                    backdropUrl = sanitizeCloudUrl(entity.backdropUrl),
                    mediaType = entity.mediaType,
                    rating = entity.rating,
                    releaseYear = entity.year,
                    status = entity.status,
                    isDeleted = entity.isDeleted,
                    addedTimestamp = entity.addedTimestamp,
                    clientUpdatedAtMs = entity.clientUpdatedAtMs
                )
            }
            val pushRes = restClient.upsertWatchlistBatch(cloudWatchlist)
            if (pushRes.isSuccess) {
                Log.i(TAG, "Successfully pushed ${chunk.size} watchlist items to cloud")
                for (row in chunk) {
                    watchlistDao.markCleanExact(row.mediaId, row.profileId, row.clientUpdatedAtMs)
                }
            } else {
                val err = pushRes.exceptionOrNull() ?: IOException("Push watchlist failed")
                Log.e(TAG, "Failed to push watchlist batch: ${err.message}")
                return Result.failure(err)
            }
        }
        return Result.success(Unit)
    }

    private fun buildLocalCloudProfiles(session: SupabaseSession, now: Long): Pair<List<CloudUserProfile>, Set<String>> {
        val localProfiles = profileManager.profiles.value
        val activeList = if (localProfiles.isNotEmpty()) {
            localProfiles.map { prof ->
                val normId = if (prof.id == "primary_profile") "default_user" else prof.id
                CloudUserProfile(
                    profileId = normId,
                    userId = session.userId,
                    name = prof.name.ifBlank { if (normId == "default_user") (session.displayName ?: "Primary") else "Profile" },
                    profileType = prof.type.name,
                    avatarId = prof.avatarId.ifBlank { "avatar_1" },
                    customAvatarUrl = if (normId == "default_user") session.avatarUrl else null,
                    pinHash = prof.pinHash,
                    isDeleted = false,
                    clientUpdatedAtMs = now
                )
            }
        } else {
            listOf(
                CloudUserProfile(
                    profileId = "default_user",
                    userId = session.userId,
                    name = session.displayName ?: "Primary",
                    profileType = "ADMIN",
                    avatarId = "avatar_1",
                    customAvatarUrl = session.avatarUrl,
                    clientUpdatedAtMs = now
                )
            )
        }
        val activeIds = activeList.map { it.profileId }.toSet()
        val pendingDeletedIds = profileManager.getPendingDeletedProfileIds()
            .filter { it != "default_user" && it !in activeIds }
            .toSet()
        val deletedList = pendingDeletedIds.map { delId ->
            CloudUserProfile(
                profileId = delId,
                userId = session.userId,
                name = "Deleted",
                profileType = "STANDARD",
                avatarId = "avatar_1",
                customAvatarUrl = null,
                pinHash = null,
                isDeleted = true,
                clientUpdatedAtMs = now
            )
        }
        return (activeList + deletedList) to pendingDeletedIds
    }

    private suspend fun syncProfilesUpstreamIfDirty(
        session: SupabaseSession,
        forcePush: Boolean,
        remoteMatchedProfiles: List<CloudUserProfile>? = null
    ): Result<Unit> {
        val (cloudProfiles, pendingDeletedIds) = buildLocalCloudProfiles(session, System.currentTimeMillis())
        val activeIds = cloudProfiles.filterNot { it.isDeleted }.map { it.profileId }.toSet()
        val localHash = SyncEfficiencyPolicy.hashProfiles(cloudProfiles)
        if (!forcePush && pendingDeletedIds.isEmpty()) {
            if (!remoteMatchedProfiles.isNullOrEmpty() &&
                SyncEfficiencyPolicy.hashProfiles(remoteMatchedProfiles.filterNot { it.isDeleted }) == localHash
            ) {
                profileManager.markProfilesSynced(activeIds)
                preferencesRepository.setSupabaseHashProfiles(localHash)
                return Result.success(Unit)
            }
            val storedHash = preferencesRepository.supabaseHashProfiles.first()
            if (storedHash == localHash && (remoteMatchedProfiles == null || remoteMatchedProfiles.isNotEmpty())) {
                return Result.success(Unit)
            }
        }

        val res = restClient.upsertProfilesBatch(cloudProfiles)
        if (res.isSuccess) {
            profileManager.clearPendingDeletedProfileIds(pendingDeletedIds)
            profileManager.markProfilesSynced(activeIds)
            preferencesRepository.setSupabaseHashProfiles(
                SyncEfficiencyPolicy.hashProfiles(cloudProfiles.filterNot { it.isDeleted })
            )
        }
        return res
    }

    suspend fun syncAll(
        forceFull: Boolean = false,
        isInitialSignInRestore: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // If an initial sign-in restore is running and this is a routine background sync, let the initial restore handle it
        if (!isInitialSignInRestore && isInitialRestoreInProgress.get()) {
            return@withContext Result.success(Unit)
        }

        val session = currentSessionCache ?: preferencesRepository.supabaseSession.first() ?: return@withContext Result.success(Unit)
        currentSessionCache = session
        val cloudSyncEnabled = preferencesRepository.supabaseCloudSyncEnabled.first()
        if (!cloudSyncEnabled) return@withContext Result.success(Unit)

        // If a non-forced background sync arrives while syncMutex is already locked, mark a pending follow-up
        // so recent dirty writes/deletions are never silently dropped.
        if (!forceFull && !isInitialSignInRestore && syncMutex.isLocked) {
            hasPendingDirtySync.set(true)
            return@withContext Result.success(Unit)
        }

        val syncGen = sessionGeneration.get()
        val syncResult = syncMutex.withLock {
            if (sessionGeneration.get() != syncGen || currentSessionCache == null) {
                return@withLock Result.success(Unit)
            }
            SyncCoordinator.dataMutex.withLock {
                try {
                    if (sessionGeneration.get() != syncGen || currentSessionCache == null) {
                        _syncStatus.value = SyncStatus.IDLE
                        return@withLock Result.success(Unit)
                    }
                    _syncStatus.value = SyncStatus.SYNCING
                    _syncErrorMessage.value = null

                val now = System.currentTimeMillis()
                val userId = session.userId

                val syncPlayback = preferencesRepository.supabaseSyncPlayback.first()
                val syncWatchlist = preferencesRepository.supabaseSyncWatchlist.first()
                val syncPlaylists = preferencesRepository.supabaseSyncPlaylists.first()
                val syncSettings = preferencesRepository.supabaseSyncSettings.first()
                val syncAddons = preferencesRepository.supabaseSyncAddons.first()

                val lastPullMs = preferencesRepository.supabaseLastPullTimestamp.first()
                val shouldPull = SyncEfficiencyPolicy.shouldRunDownstreamPull(
                    forceFull = forceFull,
                    isInitialSignInRestore = isInitialSignInRestore,
                    lastPullMs = lastPullMs,
                    nowMs = now
                )

                var firstSyncError: Throwable? = null
                fun recordFailure(err: Throwable?) {
                    if (err != null && firstSyncError == null) {
                        firstSyncError = err
                    }
                }

                var pulledProfiles: List<CloudUserProfile>? = null

                // =========================================================================
                // PHASE 1 & 2: DOWNSTREAM PULL (Single-RPC bundle when due, cooldown-gated)
                // =========================================================================
                if (shouldPull) {
                    val resetWatermarks = forceFull || isInitialSignInRestore
                    val playbackWatermark = if (resetWatermarks) null else preferencesRepository.supabasePlaybackWatermark.first()
                    val watchlistWatermark = if (resetWatermarks) null else preferencesRepository.supabaseWatchlistWatermark.first()
                    val playlistsWatermark = if (resetWatermarks) null else preferencesRepository.supabasePlaylistsWatermark.first()
                    val addonsWatermark = if (resetWatermarks) null else preferencesRepository.supabaseAddonsWatermark.first()

                    val bundleRes = restClient.pullSyncBundle(
                        playbackSince = if (syncPlayback) playbackWatermark else null,
                        watchlistSince = if (syncWatchlist) watchlistWatermark else null,
                        playlistsSince = if (syncPlaylists) playlistsWatermark else null,
                        addonsSince = if (syncAddons) addonsWatermark else null,
                        includeProfiles = true,
                        includeSettings = syncSettings
                    )

                    if (bundleRes.isFailure) {
                        val err = bundleRes.exceptionOrNull()
                        Log.e(TAG, "Pull sync bundle failed: ${err?.message}")
                        recordFailure(err)
                    } else {
                        val bundle = bundleRes.getOrNull()
                        if (sessionGeneration.get() != syncGen || currentSessionCache == null) {
                            _syncStatus.value = SyncStatus.IDLE
                            return@withLock Result.success(Unit)
                        }
                        if (bundle != null) {
                            pulledProfiles = bundle.profiles
                            if (bundle.profiles.isNotEmpty()) {
                                profileManager.mergeRemoteProfiles(bundle.profiles)
                            }
                            if (syncAddons) {
                                applyRemoteAddons(userId, bundle.addons, addonsWatermark, isInitialSignInRestore)
                            }
                            if (syncPlayback) {
                                applyRemotePlaybackItems(bundle.playback, playbackWatermark)
                            }
                            if (syncWatchlist) {
                                applyRemoteWatchlistItems(bundle.watchlist, watchlistWatermark)
                            }
                            if (syncPlaylists) {
                                applyRemotePlaylists(userId, bundle.playlists, playlistsWatermark, isInitialSignInRestore)
                            }
                            if (syncSettings) {
                                val deletedRepoUrls = bundle.addons
                                    .filter { it.isDeleted && it.addonType == "js_repo" }
                                    .map { it.manifestUrl.trim() }
                                    .toSet()
                                val settingsRes = reconcileSettingsAfterPull(
                                    now = now,
                                    isInitialSignInRestore = isInitialSignInRestore,
                                    cloudSettings = bundle.settings,
                                    deletedRepoUrls = deletedRepoUrls
                                )
                                if (settingsRes.isFailure) {
                                    recordFailure(settingsRes.exceptionOrNull())
                                }
                            }
                            if (firstSyncError == null) {
                                preferencesRepository.setSupabaseLastPullTimestamp(now)
                            }
                        } else {
                            // Graceful fallback to individual table queries when pull_sync_bundle RPC is absent (HTTP 404)
                            var fallbackHadPullError = false

                            val pullProfilesRes = restClient.pullProfiles(userId)
                            if (pullProfilesRes.isSuccess) {
                                val remoteProfiles = pullProfilesRes.getOrThrow()
                                pulledProfiles = remoteProfiles
                                if (remoteProfiles.isNotEmpty()) {
                                    profileManager.mergeRemoteProfiles(remoteProfiles)
                                }
                            } else {
                                fallbackHadPullError = true
                                recordFailure(pullProfilesRes.exceptionOrNull())
                            }

                            var fallbackDeletedRepoUrls: Set<String> = emptySet()
                            if (syncAddons) {
                                val addonsRes = syncAddonsDownstreamFallback(userId, addonsWatermark, isInitialSignInRestore)
                                if (addonsRes.isFailure) {
                                    fallbackHadPullError = true
                                    recordFailure(addonsRes.exceptionOrNull())
                                } else {
                                    fallbackDeletedRepoUrls = addonsRes.getOrNull().orEmpty()
                                }
                            }

                            if (syncPlayback) {
                                val pullPlaybackRes = restClient.pullPlaybackDelta(userId, playbackWatermark)
                                if (pullPlaybackRes.isSuccess) {
                                    applyRemotePlaybackItems(pullPlaybackRes.getOrThrow(), playbackWatermark)
                                } else {
                                    fallbackHadPullError = true
                                    recordFailure(pullPlaybackRes.exceptionOrNull())
                                }
                            }

                            if (syncWatchlist) {
                                val pullWatchlistRes = restClient.pullWatchlistDelta(userId, watchlistWatermark)
                                if (pullWatchlistRes.isSuccess) {
                                    applyRemoteWatchlistItems(pullWatchlistRes.getOrThrow(), watchlistWatermark)
                                } else {
                                    fallbackHadPullError = true
                                    recordFailure(pullWatchlistRes.exceptionOrNull())
                                }
                            }

                            if (syncPlaylists) {
                                val pullPlaylistsRes = restClient.pullPlaylistsDelta(userId, playlistsWatermark)
                                if (pullPlaylistsRes.isSuccess) {
                                    applyRemotePlaylists(userId, pullPlaylistsRes.getOrThrow(), playlistsWatermark, isInitialSignInRestore)
                                } else {
                                    fallbackHadPullError = true
                                    recordFailure(pullPlaylistsRes.exceptionOrNull())
                                }
                            }

                            if (syncSettings) {
                                val pullSettingsRes = restClient.pullUserSettings(userId)
                                if (pullSettingsRes.isFailure) {
                                    fallbackHadPullError = true
                                    recordFailure(pullSettingsRes.exceptionOrNull())
                                } else {
                                    val reconcileRes = reconcileSettingsAfterPull(
                                        now = now,
                                        isInitialSignInRestore = isInitialSignInRestore,
                                        cloudSettings = pullSettingsRes.getOrNull(),
                                        deletedRepoUrls = fallbackDeletedRepoUrls
                                    )
                                    if (reconcileRes.isFailure) {
                                        recordFailure(reconcileRes.exceptionOrNull())
                                    }
                                }
                            }

                            if (!fallbackHadPullError) {
                                preferencesRepository.setSupabaseLastPullTimestamp(now)
                            }
                        }
                    }
                } else if (syncSettings && !isInitialSignInRestore) {
                    val pushSettingsRes = pushSettingsIfDirty(now)
                    if (pushSettingsRes.isFailure) {
                        recordFailure(pushSettingsRes.exceptionOrNull())
                    }
                }

                // =========================================================================
                // PHASE 3: DIRTY-ONLY UPSTREAM PUSH (0 network calls when domain hash unchanged)
                // =========================================================================
                if (sessionGeneration.get() != syncGen || currentSessionCache == null) {
                    _syncStatus.value = SyncStatus.IDLE
                    return@withLock Result.success(Unit)
                }

                val pushProfilesRes = syncProfilesUpstreamIfDirty(
                    session = session,
                    forcePush = false,
                    remoteMatchedProfiles = pulledProfiles
                )
                if (pushProfilesRes.isFailure) {
                    Log.e(TAG, "Failed to push profiles batch: ${pushProfilesRes.exceptionOrNull()?.message}")
                    recordFailure(pushProfilesRes.exceptionOrNull())
                }

                if (syncPlayback) {
                    val pushPlaybackRes = pushDirtyPlaybackBatches(userId)
                    if (pushPlaybackRes.isFailure) {
                        recordFailure(pushPlaybackRes.exceptionOrNull())
                    }
                }

                if (syncWatchlist) {
                    val pushWatchlistRes = pushDirtyWatchlistBatches(userId)
                    if (pushWatchlistRes.isFailure) {
                        recordFailure(pushWatchlistRes.exceptionOrNull())
                    }
                }

                if (syncPlaylists) {
                    val pushPlaylistsRes = syncPlaylistsUpstreamIfDirty(userId)
                    if (pushPlaylistsRes.isFailure) {
                        Log.e(TAG, "Failed to push playlists batch: ${pushPlaylistsRes.exceptionOrNull()?.message}")
                        recordFailure(pushPlaylistsRes.exceptionOrNull())
                    }
                }

                if (syncAddons && !isInitialSignInRestore) {
                    val pushAddonsRes = syncAddonsUpstreamIfDirty(session)
                    if (pushAddonsRes.isFailure) {
                        Log.e(TAG, "Failed to push addons batch: ${pushAddonsRes.exceptionOrNull()?.message}")
                        recordFailure(pushAddonsRes.exceptionOrNull())
                    }
                }

                if (sessionGeneration.get() != syncGen || currentSessionCache == null) {
                    _syncStatus.value = SyncStatus.IDLE
                    return@withLock Result.success(Unit)
                }

                if (firstSyncError != null) {
                    val err = firstSyncError!!
                    _syncStatus.value = SyncStatus.ERROR
                    _syncErrorMessage.value = err.message ?: "Sync failed"
                    Result.failure(err)
                } else {
                    preferencesRepository.setSupabaseLastSyncTimestamp(System.currentTimeMillis())
                    _syncStatus.value = SyncStatus.SUCCESS
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync all failed", e)
                if (sessionGeneration.get() != syncGen || currentSessionCache == null) {
                    _syncStatus.value = SyncStatus.IDLE
                    Result.success(Unit)
                } else {
                    _syncStatus.value = SyncStatus.ERROR
                    _syncErrorMessage.value = e.message
                    Result.failure(e)
                }
            }
            }
        }
        if (hasPendingDirtySync.getAndSet(false) && currentSessionCache != null && sessionGeneration.get() == syncGen) {
            scope.launch { syncAll(forceFull = false) }
        }
        syncResult
    }

    private suspend fun applyRemotePlaybackItems(
        remoteItems: List<CloudPlaybackItem>,
        initialWatermark: String?
    ) {
        if (remoteItems.isEmpty()) return
        Log.i(TAG, "Applying ${remoteItems.size} playback items from cloud (watermark: $initialWatermark)")
        var maxWatermark = initialWatermark
        for (remote in remoteItems) {
            val (cleanMediaId, parsedSeason, parsedEpisode) = decodeCloudPlaybackMediaId(
                remote.mediaId,
                remote.seasonNumber,
                remote.episodeNumber
            )
            val cleanProvider = remote.provider.trim().ifBlank { "default" }.lowercase()
            val localId = PlaybackHistoryEntity.buildId(
                cleanProvider,
                cleanMediaId,
                parsedSeason,
                parsedEpisode,
                remote.profileId
            )
            val local = playbackHistoryDao.getProgress(localId) ?: playbackHistoryDao.getRawById(localId)
            val shouldUpdate = if (local == null) {
                true
            } else {
                SyncEfficiencyPolicy.shouldApplyRemotePlayback(
                    localIsDirty = local.isDirty,
                    localIsDeleted = local.isDeleted,
                    localIsDismissed = local.isDismissedFromContinueWatching,
                    localClientUpdatedAtMs = local.clientUpdatedAtMs,
                    localPositionMs = local.currentPositionMs,
                    remoteIsDeleted = remote.isDeleted,
                    remoteIsDismissed = remote.isDismissed,
                    remoteLastWatchedTs = remote.lastWatchedTimestamp,
                    remotePositionMs = remote.currentPositionMs
                )
            }

            if (shouldUpdate) {
                val merged = PlaybackHistoryEntity(
                    id = localId,
                    mediaId = cleanMediaId,
                    profileId = remote.profileId,
                    title = remote.title,
                    posterUrl = remote.posterUrl ?: local?.posterUrl,
                    backdropUrl = remote.backdropUrl ?: local?.backdropUrl,
                    mediaType = remote.mediaType,
                    seasonNumber = parsedSeason,
                    episodeNumber = parsedEpisode,
                    episodeTitle = remote.episodeTitle ?: local?.episodeTitle,
                    durationMs = maxOf(remote.durationMs, local?.durationMs ?: 0L),
                    currentPositionMs = remote.currentPositionMs,
                    isCompleted = if (remote.isDeleted) remote.isCompleted else (remote.isCompleted || (local?.isCompleted == true)),
                    lastWatchedTimestamp = remote.lastWatchedTimestamp,
                    provider = cleanProvider,
                    episodeData = remote.episodeData ?: local?.episodeData,
                    isDismissedFromContinueWatching = remote.isDismissed,
                    isDirty = false,
                    isDeleted = remote.isDeleted,
                    clientUpdatedAtMs = remote.lastWatchedTimestamp
                )
                playbackHistoryDao.upsert(merged)
            }

            val serverUpdatedAt = remote.updatedAt
            if (serverUpdatedAt != null && (maxWatermark == null || serverUpdatedAt > maxWatermark)) {
                maxWatermark = serverUpdatedAt
            }
        }
        if (maxWatermark != null) {
            preferencesRepository.setSupabasePlaybackWatermark(maxWatermark)
        }
    }

    private suspend fun applyRemoteWatchlistItems(
        remoteWatchlist: List<CloudWatchlistItem>,
        initialWatermark: String?
    ) {
        if (remoteWatchlist.isEmpty()) return
        Log.i(TAG, "Applying ${remoteWatchlist.size} watchlist items from cloud (watermark: $initialWatermark)")
        var maxWatermark = initialWatermark
        for (remote in remoteWatchlist) {
            val local = watchlistDao.getWatchlistItem(remote.mediaId, remote.profileId) ?: watchlistDao.getRawItem(remote.mediaId, remote.profileId)
            val shouldUpdate = if (local == null) {
                true
            } else {
                SyncEfficiencyPolicy.shouldApplyRemoteWatchlist(
                    localIsDirty = local.isDirty,
                    localIsDeleted = local.isDeleted,
                    localClientUpdatedAtMs = local.clientUpdatedAtMs,
                    remoteIsDeleted = remote.isDeleted,
                    remoteClientUpdatedAtMs = remote.clientUpdatedAtMs
                )
            }

            if (shouldUpdate) {
                val merged = WatchlistEntity(
                    mediaId = remote.mediaId,
                    profileId = remote.profileId,
                    title = remote.title,
                    posterUrl = remote.posterUrl ?: local?.posterUrl,
                    backdropUrl = remote.backdropUrl ?: local?.backdropUrl,
                    mediaType = remote.mediaType,
                    rating = remote.rating ?: local?.rating,
                    year = remote.releaseYear ?: local?.year,
                    status = remote.status,
                    addedTimestamp = remote.addedTimestamp,
                    isDirty = false,
                    isDeleted = remote.isDeleted,
                    clientUpdatedAtMs = remote.clientUpdatedAtMs
                )
                watchlistDao.insert(merged)
            }

            val serverUpdatedAt = remote.updatedAt
            if (serverUpdatedAt != null && (maxWatermark == null || serverUpdatedAt > maxWatermark)) {
                maxWatermark = serverUpdatedAt
            }
        }
        if (maxWatermark != null) {
            preferencesRepository.setSupabaseWatchlistWatermark(maxWatermark)
        }
    }

    private suspend fun buildLocalCloudPlaylists(userId: String): List<CloudPlaylist> {
        val localPlaylists = playlistRepository.getAllPlaylistsIncludingDeleted()
            .sortedByDescending { it.clientUpdatedAtMs }
            .take(50)
        return localPlaylists.map { pl ->
            CloudPlaylist(
                userId = userId,
                profileId = "default_user",
                playlistId = pl.id,
                title = pl.title,
                isSystem = pl.isSystem,
                itemsJson = json.encodeToString(pl.items),
                createdAtMs = pl.createdAt,
                lastPlayedAtMs = pl.lastPlayedAt,
                isDeleted = pl.isDeleted,
                clientUpdatedAtMs = pl.clientUpdatedAtMs
            )
        }
    }

    private suspend fun applyRemotePlaylists(
        userId: String,
        remotePlaylists: List<CloudPlaylist>,
        initialWatermark: String?,
        isInitialSignInRestore: Boolean
    ) {
        if (remotePlaylists.isEmpty()) return
        val storedHash = preferencesRepository.supabaseHashPlaylists.first()
        val localBefore = buildLocalCloudPlaylists(userId)
        val wasLocallyDirty = storedHash == null || SyncEfficiencyPolicy.hashPlaylists(localBefore) != storedHash

        playlistRepository.mergeRemotePlaylists(remotePlaylists)
        val maxWatermark = remotePlaylists.mapNotNull { it.updatedAt }.maxOrNull() ?: initialWatermark
        if (maxWatermark != null) {
            preferencesRepository.setSupabasePlaylistsWatermark(maxWatermark)
        }
        if (!isInitialSignInRestore && !wasLocallyDirty) {
            val postMergeHash = SyncEfficiencyPolicy.hashPlaylists(buildLocalCloudPlaylists(userId))
            preferencesRepository.setSupabaseHashPlaylists(postMergeHash)
        }
    }

    private suspend fun syncPlaylistsUpstreamIfDirty(userId: String): Result<Unit> {
        val cloudPlaylists = buildLocalCloudPlaylists(userId)
        if (cloudPlaylists.isEmpty()) return Result.success(Unit)
        val localHash = SyncEfficiencyPolicy.hashPlaylists(cloudPlaylists)
        val storedHash = preferencesRepository.supabaseHashPlaylists.first()
        if (storedHash == localHash) {
            return Result.success(Unit)
        }
        val res = restClient.upsertPlaylistsBatch(cloudPlaylists)
        if (res.isSuccess) {
            preferencesRepository.setSupabaseHashPlaylists(localHash)
        }
        return res
    }

    private suspend fun pushSettingsIfDirty(now: Long): Result<Unit> {
        val localSettings = preferencesRepository.getAllSyncableSettings()
        if (localSettings.isEmpty()) return Result.success(Unit)
        val localHash = SyncEfficiencyPolicy.hashSettings(localSettings)
        val storedHash = preferencesRepository.supabaseHashSettings.first()
        if (storedHash != null && storedHash == localHash) {
            return Result.success(Unit)
        }
        val pushRes = restClient.mergeUserSettings(localSettings, now)
        if (pushRes.isSuccess) {
            preferencesRepository.setSupabaseHashSettings(localHash)
        }
        return pushRes
    }

    private suspend fun reconcileSettingsAfterPull(
        now: Long,
        isInitialSignInRestore: Boolean,
        cloudSettings: CloudUserSettings?,
        deletedRepoUrls: Set<String> = emptySet()
    ): Result<Unit> {
        val remoteEntries = cloudSettings?.settingsJson ?: emptyMap()
        val storedHash = preferencesRepository.supabaseHashSettings.first()

        if ((isInitialSignInRestore || storedHash == null) && remoteEntries.isNotEmpty()) {
            val remoteValuesMap = remoteEntries.mapValues { it.value.value }
            preferencesRepository.applySyncableSettings(remoteValuesMap, isInitialRestore = isInitialSignInRestore)
            if (isInitialSignInRestore) {
                installRestoredPluginReposIfNeeded(remoteValuesMap["repository_urls"], deletedRepoUrls)
            }
        }

        val localSettings = preferencesRepository.getAllSyncableSettings()
        val localHashBeforePush = SyncEfficiencyPolicy.hashSettings(localSettings)
        val localChangedSinceSync = storedHash != null && localHashBeforePush != storedHash

        if (localSettings.isNotEmpty()) {
            if (isInitialSignInRestore && remoteEntries.isNotEmpty()) {
                val mergedToPush = localSettings.mapValues { (key, value) ->
                    val existingRemote = remoteEntries[key]
                    if (existingRemote != null && key != "favorite_media_ids" && key != "repository_urls") {
                        existingRemote
                    } else {
                        CloudSettingEntry(value = value, ts = now)
                    }
                }
                val pushRes = restClient.mergeUserSettingsEntries(mergedToPush)
                if (pushRes.isFailure) return pushRes
            } else if (localChangedSinceSync || remoteEntries.isEmpty()) {
                val pushRes = restClient.mergeUserSettings(localSettings, now)
                if (pushRes.isFailure) return pushRes
            }
        }

        if (!isInitialSignInRestore && storedHash != null && !localChangedSinceSync && remoteEntries.isNotEmpty()) {
            val settingsMap = remoteEntries.mapValues { it.value.value }
            preferencesRepository.applySyncableSettings(settingsMap, isInitialRestore = false)
        }

        val finalHash = SyncEfficiencyPolicy.hashSettings(preferencesRepository.getAllSyncableSettings())
        preferencesRepository.setSupabaseHashSettings(finalHash)
        return Result.success(Unit)
    }

    private fun installRestoredPluginReposIfNeeded(
        repoUrlsCsv: String?,
        deletedRepoUrls: Set<String> = emptySet()
    ) {
        if (repoUrlsCsv.isNullOrBlank()) return
        val urls = repoUrlsCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in deletedRepoUrls }
        if (urls.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val pluginService = PluginRepositoryService(context)
            for (url in urls) {
                try {
                    pluginService.installAllFromRepo(url)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not auto-install restored repo $url: ${e.message}")
                }
            }
        }
    }

    /**
     * Encodes season & episode numbers into the cloud `media_id` for TV episodes so that
     * `PRIMARY KEY (user_id, profile_id, provider, media_id)` stores each episode as its own row
     * without overwriting previous episodes of the same series.
     */
    private fun encodeCloudPlaybackMediaId(
        baseMediaId: String,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): String {
        val cleanBase = baseMediaId.substringBefore("::s")
        return if (seasonNumber != null && episodeNumber != null) {
            "${cleanBase}::s${seasonNumber}_e${episodeNumber}"
        } else {
            cleanBase
        }
    }

    /**
     * Decodes a cloud `media_id` back into `(baseMediaId, seasonNumber, episodeNumber)`.
     */
    private fun decodeCloudPlaybackMediaId(
        cloudMediaId: String,
        explicitSeason: Int?,
        explicitEpisode: Int?
    ): Triple<String, Int?, Int?> {
        if (cloudMediaId.contains("::s")) {
            val base = cloudMediaId.substringBefore("::s")
            val suffix = cloudMediaId.substringAfter("::s")
            val s = suffix.substringBefore("_e").toIntOrNull() ?: explicitSeason
            val e = suffix.substringAfter("_e").toIntOrNull() ?: explicitEpisode
            return Triple(base, s, e)
        }
        return Triple(cloudMediaId, explicitSeason, explicitEpisode)
    }

    private fun generateRawNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }


    private suspend fun buildActiveCloudAddons(userId: String, now: Long): List<CloudUserAddon> {
        val addonRepository = com.netvio.core.addons.AddonRepository.getInstance(context)
        val installedAddons = addonRepository.uiState.value.addons
        val installedRepoUrls = preferencesRepository.repositoryUrls.first()
        val activeItems = mutableListOf<CloudUserAddon>()

        for (addon in installedAddons) {
            val url = addon.manifestUrl.trim()
            if (url.isBlank()) continue
            activeItems.add(
                CloudUserAddon(
                    userId = userId,
                    manifestUrl = url,
                    addonType = "stremio",
                    name = addon.userSetName ?: addon.manifest?.name,
                    isDeleted = false,
                    clientUpdatedAtMs = now
                )
            )
        }

        for (repoUrl in installedRepoUrls) {
            val url = repoUrl.trim()
            if (url.isBlank()) continue
            activeItems.add(
                CloudUserAddon(
                    userId = userId,
                    manifestUrl = url,
                    addonType = "js_repo",
                    name = null,
                    isDeleted = false,
                    clientUpdatedAtMs = now
                )
            )
        }

        return activeItems
    }

    private suspend fun syncAddonsDownstreamFallback(
        userId: String,
        watermark: String?,
        isInitialSignInRestore: Boolean
    ): Result<Set<String>> {
        val pullRes = restClient.pullAddonsDelta(userId, watermark)
        if (pullRes.isFailure) {
            Log.w(TAG, "Failed to pull addons delta: ${pullRes.exceptionOrNull()?.message}")
            return Result.failure(pullRes.exceptionOrNull() ?: IOException("Pull addons failed"))
        }
        val items = pullRes.getOrThrow()
        applyRemoteAddons(userId, items, watermark, isInitialSignInRestore)
        val deletedRepoUrls = items
            .filter { it.isDeleted && it.addonType == "js_repo" }
            .map { it.manifestUrl.trim() }
            .toSet()
        return Result.success(deletedRepoUrls)
    }

    private suspend fun applyRemoteAddons(
        userId: String,
        remoteAddons: List<CloudUserAddon>,
        initialWatermark: String?,
        isInitialSignInRestore: Boolean
    ) {
        if (remoteAddons.isEmpty()) return
        Log.i(TAG, "Applying ${remoteAddons.size} addons/repos from cloud (watermark: $initialWatermark)")
        val storedHash = preferencesRepository.supabaseHashAddons.first()
        val pendingDeleted = preferencesRepository.supabasePendingDeletedAddonUrls.first()
        val pendingDeletedRawUrls = pendingDeleted.map { SyncEfficiencyPolicy.parsePendingDeletedAddon(it).second }.toSet()
        val pendingDeletedNormUrls = pendingDeletedRawUrls.map {
            com.netvio.core.addons.AddonRepository.normalizeAddonUrl(it)
        }.toSet()

        val now = System.currentTimeMillis()
        val wasLocallyDirty = pendingDeleted.isNotEmpty() || storedHash == null ||
            SyncEfficiencyPolicy.hashAddons(buildActiveCloudAddons(userId, now)) != storedHash

        var maxWatermark = initialWatermark
        val addonRepository = com.netvio.core.addons.AddonRepository.getInstance(context)
        val pluginService = PluginRepositoryService(context)
        val currentRepoUrls = preferencesRepository.repositoryUrls.first()
        val currentInstalledAddons = addonRepository.uiState.value.addons.map { it.manifestUrl }.toSet()

        for (item in remoteAddons) {
            val url = item.manifestUrl.trim()
            if (url.isBlank()) continue
            val norm = com.netvio.core.addons.AddonRepository.normalizeAddonUrl(url)

            if (item.isDeleted) {
                if (item.addonType == "stremio" || currentInstalledAddons.contains(url) || currentInstalledAddons.contains(norm)) {
                    // Remote deletion is a real removal, so the
                    // addon's saved catalog preferences go with it.
                    // The manifest id is captured before removing,
                    // while the addon is still in the installed list;
                    // a failed manifest refresh never removes it from
                    // that list, so this cannot fire on a transient
                    // failure.
                    val manifestId = addonRepository.uiState.value.addons.firstOrNull {
                        it.manifestUrl == url ||
                            it.manifestUrl == com.netvio.core.addons.AddonRepository.normalizeAddonUrl(url)
                    }?.manifest?.id
                    addonRepository.removeAddon(url)
                    if (!manifestId.isNullOrBlank()) {
                        preferencesRepository.removeCatalogPreferencesForManifestId(manifestId)
                    }
                }
                if (item.addonType == "js_repo" || currentRepoUrls.contains(url)) {
                    pluginService.removeRepository(url)
                }
                if (url in pendingDeletedRawUrls || norm in pendingDeletedNormUrls) {
                    preferencesRepository.removePendingDeletedAddonUrl(url)
                }
            } else if (url !in pendingDeletedRawUrls && norm !in pendingDeletedNormUrls) {
                if (item.addonType == "stremio") {
                    if (!currentInstalledAddons.contains(url) && !currentInstalledAddons.contains(norm)) {
                        runCatching { addonRepository.installAddon(url) }
                    }
                } else if (item.addonType == "js_repo") {
                    if (!currentRepoUrls.contains(url)) {
                        preferencesRepository.addRepositoryUrl(url)
                        runCatching { pluginService.installAllFromRepo(url) }
                    }
                }
            }

            val serverUpdatedAt = item.updatedAt
            if (serverUpdatedAt != null && (maxWatermark == null || serverUpdatedAt > maxWatermark)) {
                maxWatermark = serverUpdatedAt
            }
        }

        if (maxWatermark != null) {
            preferencesRepository.setSupabaseAddonsWatermark(maxWatermark)
        }
        if (!isInitialSignInRestore && !wasLocallyDirty) {
            val postApplyHash = SyncEfficiencyPolicy.hashAddons(buildActiveCloudAddons(userId, now))
            preferencesRepository.setSupabaseHashAddons(postApplyHash)
        }
    }

    private suspend fun syncAddonsUpstreamIfDirty(session: SupabaseSession): Result<Unit> {
        val now = System.currentTimeMillis()
        val pendingDeleted = preferencesRepository.supabasePendingDeletedAddonUrls.first()
        val parsedDeletions = pendingDeleted.map { SyncEfficiencyPolicy.parsePendingDeletedAddon(it) }
            .filter { it.second.isNotBlank() }
        val pendingDeletedRawUrls = parsedDeletions.map { it.second }.toSet()
        val pendingDeletedNormUrls = pendingDeletedRawUrls.map {
            com.netvio.core.addons.AddonRepository.normalizeAddonUrl(it)
        }.toSet()

        val activeItems = buildActiveCloudAddons(session.userId, now).filterNot { item ->
            val u = item.manifestUrl.trim()
            val n = com.netvio.core.addons.AddonRepository.normalizeAddonUrl(u)
            u in pendingDeletedRawUrls || n in pendingDeletedNormUrls
        }

        val activeHash = SyncEfficiencyPolicy.hashAddons(activeItems)
        val storedHash = preferencesRepository.supabaseHashAddons.first()
        if (parsedDeletions.isEmpty() && storedHash == activeHash) {
            return Result.success(Unit)
        }

        val itemsToPush = ArrayList<CloudUserAddon>(activeItems.size + parsedDeletions.size)
        itemsToPush.addAll(activeItems)

        for ((addonType, url) in parsedDeletions) {
            itemsToPush.add(
                CloudUserAddon(
                    userId = session.userId,
                    manifestUrl = url,
                    addonType = addonType,
                    name = null,
                    isDeleted = true,
                    clientUpdatedAtMs = now
                )
            )
        }

        if (itemsToPush.isEmpty()) {
            preferencesRepository.setSupabaseHashAddons(activeHash)
            return Result.success(Unit)
        }

        val pushRes = restClient.upsertAddonsBatch(itemsToPush)
        if (pushRes.isSuccess) {
            Log.i(TAG, "Successfully synced ${itemsToPush.size} addons & repos to cloud")
            if (pendingDeleted.isNotEmpty()) {
                preferencesRepository.clearPendingDeletedAddonUrls(pendingDeleted)
            }
            preferencesRepository.setSupabaseHashAddons(activeHash)
        } else {
            Log.e(TAG, "Failed to push addons batch: ${pushRes.exceptionOrNull()?.message}")
        }
        return pushRes
    }

    private fun sanitizeCloudUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("data:", ignoreCase = true) || trimmed.length > 500) {
            return null
        }
        return trimmed
    }
    companion object {
        @Volatile
        private var instance: SupabaseSyncManager? = null

        fun getInstance(context: Context): SupabaseSyncManager {
            return instance ?: synchronized(this) {
                instance ?: SupabaseSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
