package com.netvio.data.sync

import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.data.local.WatchlistEntity
import com.netvio.data.profile.ProfileType

/**
 * Pure conflict-resolution and eligibility rules for merging Trakt.tv and Simkl state
 * with local Room entities (including soft-delete and dismissal tombstones).
 */
object TrackerReconciliationPolicy {

    enum class WatchlistPullAction {
        INSERT_LOCAL,
        SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE,
        RESURRECT_REMOTE_NEWER,
        KEEP_LOCAL
    }

    enum class PlaybackPullAction {
        APPLY_REMOTE,
        SKIP_LOCAL_DELETED_OR_DISMISSED,
        SKIP_LOCAL_NEWER_OR_COMPLETED
    }

    private const val CLOCK_SKEW_GUARD_MS = 5_000L

    fun decideWatchlistPull(
        local: WatchlistEntity?,
        remoteAddedAtMs: Long?
    ): WatchlistPullAction {
        if (local == null) return WatchlistPullAction.INSERT_LOCAL
        if (local.isDeleted) {
            return if (remoteAddedAtMs != null && remoteAddedAtMs > local.clientUpdatedAtMs + CLOCK_SKEW_GUARD_MS) {
                WatchlistPullAction.RESURRECT_REMOTE_NEWER
            } else {
                WatchlistPullAction.SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE
            }
        }
        return WatchlistPullAction.KEEP_LOCAL
    }

    fun decidePlaybackPull(
        localRowsForMedia: List<PlaybackHistoryEntity>,
        remoteEventTsMs: Long?,
        remoteIsCompleted: Boolean,
        isSyntheticPlaceholder: Boolean
    ): PlaybackPullAction {
        if (isSyntheticPlaceholder && localRowsForMedia.isNotEmpty()) {
            return PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED
        }

        val deletedRows = localRowsForMedia.filter { it.isDeleted }
        if (deletedRows.isNotEmpty()) {
            val maxDeletedTs = deletedRows.maxOf { maxOf(it.clientUpdatedAtMs, it.lastWatchedTimestamp) }
            if (remoteEventTsMs == null || maxDeletedTs >= remoteEventTsMs) {
                return PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED
            }
        }

        val activeRows = localRowsForMedia.filterNot { it.isDeleted }
        if (!remoteIsCompleted) {
            if (activeRows.any { it.isCompleted }) {
                return PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED
            }
            val dismissedRows = activeRows.filter { it.isDismissedFromContinueWatching }
            if (dismissedRows.isNotEmpty()) {
                val maxDismissedTs = dismissedRows.maxOf { maxOf(it.clientUpdatedAtMs, it.lastWatchedTimestamp) }
                if (remoteEventTsMs == null || maxDismissedTs >= remoteEventTsMs) {
                    return PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED
                }
            }
        }

        val hasNewerOrMatchedLocal = activeRows.any { row ->
            row.currentPositionMs > 1000L &&
                row.lastWatchedTimestamp >= (remoteEventTsMs ?: 0L) &&
                (!remoteIsCompleted || row.isCompleted)
        }
        if (hasNewerOrMatchedLocal) {
            return PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED
        }

        return PlaybackPullAction.APPLY_REMOTE
    }

    fun shouldSyncTrackersForProfile(profileType: ProfileType?): Boolean =
        profileType != ProfileType.KIDS && profileType != ProfileType.GUEST

    fun isTvMediaType(mediaType: String?): Boolean =
        mediaType?.contains("TV", ignoreCase = true) == true ||
            mediaType?.contains("ANIME", ignoreCase = true) == true
}
