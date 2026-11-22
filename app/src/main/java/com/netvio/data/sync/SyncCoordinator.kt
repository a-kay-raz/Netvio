package com.netvio.data.sync

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared cross-engine coordination primitives for Google Cloud (Supabase) and
 * third-party trackers (Trakt.tv / Simkl).
 *
 * Lock ordering (strict 2-level DAG, deadlock-free):
 *   engine-private syncMutex -> SyncCoordinator.dataMutex
 *
 * Never call another engine's suspending sync entry point while holding [dataMutex];
 * schedule async follow-up syncs only after releasing [dataMutex].
 */
object SyncCoordinator {
    val dataMutex = Mutex()
    val isGoogleInitialRestoreActive = AtomicBoolean(false)
}
