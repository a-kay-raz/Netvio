package com.netvio.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pStreamPolicyTest {

    @Test
    fun shouldIncludeStream_alwaysAllowsDirectHttpsStreams() {
        assertTrue(P2pStreamPolicy.shouldIncludeStream(isTorrentStream = false, p2pEnabled = false, hasActiveDebrid = false))
        assertTrue(P2pStreamPolicy.shouldIncludeStream(isTorrentStream = false, p2pEnabled = false, hasActiveDebrid = true))
        assertTrue(P2pStreamPolicy.shouldIncludeStream(isTorrentStream = false, p2pEnabled = true, hasActiveDebrid = false))
        assertTrue(P2pStreamPolicy.shouldIncludeStream(isTorrentStream = false, p2pEnabled = true, hasActiveDebrid = true))
    }

    @Test
    fun shouldIncludeStream_filtersTorrentStreamsWhenP2pDisabledWithoutDebrid() {
        assertFalse(
            P2pStreamPolicy.shouldIncludeStream(
                isTorrentStream = true,
                p2pEnabled = false,
                hasActiveDebrid = false
            )
        )
        assertTrue(
            P2pStreamPolicy.shouldIncludeStream(
                isTorrentStream = true,
                p2pEnabled = false,
                hasActiveDebrid = true
            )
        )
        assertTrue(
            P2pStreamPolicy.shouldIncludeStream(
                isTorrentStream = true,
                p2pEnabled = true,
                hasActiveDebrid = false
            )
        )
    }

    @Test
    fun canUseNativeP2p_matchesUserToggleStrictly() {
        assertFalse(P2pStreamPolicy.canUseNativeP2p(p2pEnabled = false))
        assertTrue(P2pStreamPolicy.canUseNativeP2p(p2pEnabled = true))
    }

    @Test
    fun cacheSizesAndDefaultState_reflectThoughtfulBudgets() {
        val defaultState = P2pSettingsUiState()
        assertEquals(P2pCacheSize.MB_512, defaultState.cacheSize)
        assertTrue(defaultState.p2pEnabled)
        assertEquals(0L, P2pCacheSize.NONE.bytes)
        assertEquals(512L * 1024L * 1024L, P2pCacheSize.MB_512.bytes)
        assertEquals(1024L * 1024L * 1024L, P2pCacheSize.GB_1.bytes)
        assertEquals(2L * 1024L * 1024L * 1024L, P2pCacheSize.GB_2.bytes)
    }
}
