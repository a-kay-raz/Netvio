package com.netvio.core.model

import com.netvio.core.browser.BrowserResolver
import com.netvio.core.aniskip.AniSkipClient
import com.netvio.core.subtitle.external.SubDlClient
import com.netvio.core.util.TmdbBridge
import okhttp3.OkHttpClient

/**
 * Host Application API provided to Universal Plugins.
 * Bridges external service clients and headless browser capabilities
 * without giving plugins direct Android Context or Activity references.
 */
interface HostApi {
    val browserResolver: BrowserResolver
    val okHttpClient: OkHttpClient
    val defaultUserAgent: String
    val tmdbBridge: TmdbBridge
    val subDlClient: SubDlClient
    val aniSkipClient: AniSkipClient
}
