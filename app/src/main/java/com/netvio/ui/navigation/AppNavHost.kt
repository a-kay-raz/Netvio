package com.netvio.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.lifecycle.Lifecycle
import android.os.SystemClock
import com.netvio.core.model.MediaItem
import com.netvio.core.registry.PluginRegistry
import com.netvio.ui.details.MediaDetailsScreen
import com.netvio.ui.home.HomeScreen
import com.netvio.ui.player.PlayerScreen
import com.netvio.ui.search.SearchScreen
import com.netvio.ui.components.AppSnackbarController
import com.netvio.ui.components.AppSnackbarHost
import com.netvio.ui.components.AppSnackbarHostState
import com.netvio.ui.components.LocalSnackbarController
import com.netvio.ui.profile.DetroitProfileUnlockDialog
import com.netvio.ui.profile.QuickProfileSwitcherDialog
import com.netvio.ui.theme.*
import com.netvio.ui.util.findActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource

val LocalNavbarVisible = compositionLocalOf { mutableStateOf(true) }

private var lastNavigationEventTime = -100000L
private const val NAVIGATION_DEBOUNCE_MS = 500L

/**
 * Safely navigates to a target route, preventing duplicate backstack entries and multi-click spam.
 * 1. Checks if the current screen lifecycle is RESUMED (if it is transitioning away or paused, navigation is blocked).
 * 2. Applies a 500ms time-window debounce across the application.
 * 3. Enforces launchSingleTop = true so identical destinations cannot be pushed on top of each other.
 */
internal fun NavController.navigateSafely(
    route: String,
    builder: (NavOptionsBuilder.() -> Unit)? = null
) {
    val currentEntry = currentBackStackEntry
    if (currentEntry != null && currentEntry.lifecycle.currentState != Lifecycle.State.RESUMED) {
        return
    }

    val now = SystemClock.elapsedRealtime()
    if (now - lastNavigationEventTime < NAVIGATION_DEBOUNCE_MS) {
        return
    }
    lastNavigationEventTime = now

    navigate(route) {
        launchSingleTop = true
        builder?.invoke(this)
    }
}

/**
 * Computes directional scroll delta handling item boundary transitions and heterogeneous item sizes.
 * If currentIndex > previousIndex, user scrolled DOWN (content moved UP) past at least one item boundary,
 * so the delta is guaranteed positive and clamped to at least [thresholdPx].
 * If currentIndex < previousIndex, user scrolled UP (content moved DOWN) past at least one item boundary,
 * so the delta is guaranteed negative and clamped to at most -[thresholdPx].
 */
internal fun computeScrollDelta(
    currentIndex: Int,
    currentOffset: Int,
    previousIndex: Int,
    previousOffset: Int,
    itemDimension: Int,
    thresholdPx: Float
): Float {
    return if (currentIndex > previousIndex) {
        val estimatedPassed = (currentIndex - previousIndex) * itemDimension - previousOffset + currentOffset
        maxOf(thresholdPx, estimatedPassed.toFloat())
    } else if (currentIndex < previousIndex) {
        val estimatedPassed = (currentIndex - previousIndex) * itemDimension - previousOffset + currentOffset
        minOf(-thresholdPx, estimatedPassed.toFloat())
    } else {
        (currentOffset - previousOffset).toFloat()
    }
}

/**
 * Accumulates directional scroll deltas against a threshold before requesting navbar visibility changes.
 * Direction reversals immediately reset opposing accumulation, preventing flutter and jitter.
 */
internal class ScrollDirectionAccumulator(val thresholdPx: Float) {
    var accumulatedScroll = 0f
        private set

    /**
     * Updates the accumulator with [delta].
     * Returns true if navbar should show, false if navbar should hide, or null if threshold not reached.
     */
    fun onDelta(delta: Float): Boolean? {
        if (delta > 0f) {
            if (accumulatedScroll < 0f) {
                accumulatedScroll = 0f
            }
            accumulatedScroll += delta
            if (accumulatedScroll >= thresholdPx) {
                accumulatedScroll = 0f
                return false
            }
        } else if (delta < 0f) {
            if (accumulatedScroll > 0f) {
                accumulatedScroll = 0f
            }
            accumulatedScroll += delta
            if (accumulatedScroll <= -thresholdPx) {
                accumulatedScroll = 0f
                return true
            }
        }
        return null
    }

    fun reset() {
        accumulatedScroll = 0f
    }
}

@Composable
fun ObserveScrollForNavbar(lazyListState: androidx.compose.foundation.lazy.LazyListState) {
    val navbarVisible = LocalNavbarVisible.current
    val thresholdPx = with(LocalDensity.current) { 28.dp.toPx() }

    LaunchedEffect(lazyListState, thresholdPx) {
        var previousIndex = lazyListState.firstVisibleItemIndex
        var previousOffset = lazyListState.firstVisibleItemScrollOffset
        val accumulator = ScrollDirectionAccumulator(thresholdPx)

        snapshotFlow {
            Pair(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset)
        }
            .distinctUntilChanged()
            .collect { (currentIndex, currentOffset) ->
                val isNearTop = currentIndex == 0 && currentOffset < thresholdPx
                if (isNearTop) {
                    navbarVisible.value = true
                    accumulator.reset()
                    previousIndex = currentIndex
                    previousOffset = currentOffset
                    return@collect
                }

                val itemSize = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: thresholdPx.toInt()
                val delta = computeScrollDelta(
                    currentIndex = currentIndex,
                    currentOffset = currentOffset,
                    previousIndex = previousIndex,
                    previousOffset = previousOffset,
                    itemDimension = itemSize,
                    thresholdPx = thresholdPx
                )

                previousIndex = currentIndex
                previousOffset = currentOffset

                val visibilityChange = accumulator.onDelta(delta)
                if (visibilityChange != null) {
                    navbarVisible.value = visibilityChange
                }
            }
    }
}

@Composable
fun ObserveScrollForNavbar(gridState: androidx.compose.foundation.lazy.grid.LazyGridState) {
    val navbarVisible = LocalNavbarVisible.current
    val thresholdPx = with(LocalDensity.current) { 28.dp.toPx() }

    LaunchedEffect(gridState, thresholdPx) {
        var previousIndex = gridState.firstVisibleItemIndex
        var previousOffset = gridState.firstVisibleItemScrollOffset
        val accumulator = ScrollDirectionAccumulator(thresholdPx)

        snapshotFlow {
            Pair(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }
            .distinctUntilChanged()
            .collect { (currentIndex, currentOffset) ->
                val isNearTop = currentIndex == 0 && currentOffset < thresholdPx
                if (isNearTop) {
                    navbarVisible.value = true
                    accumulator.reset()
                    previousIndex = currentIndex
                    previousOffset = currentOffset
                    return@collect
                }

                val itemHeight = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: thresholdPx.toInt()
                val delta = computeScrollDelta(
                    currentIndex = currentIndex,
                    currentOffset = currentOffset,
                    previousIndex = previousIndex,
                    previousOffset = previousOffset,
                    itemDimension = itemHeight,
                    thresholdPx = thresholdPx
                )

                previousIndex = currentIndex
                previousOffset = currentOffset

                val visibilityChange = accumulator.onDelta(delta)
                if (visibilityChange != null) {
                    navbarVisible.value = visibilityChange
                }
            }
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    registry: PluginRegistry = PluginRegistry.Default
) {
    val reduceMotion = LocalReduceMotion.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavTab.HOME.route
    val isFullscreen = currentRoute.startsWith("details") || currentRoute.startsWith("player") || currentRoute.startsWith("plugin_manager") || currentRoute.startsWith("settings/")

    val navbarVisible = remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val preferencesRepository = remember { com.netvio.data.preferences.PreferencesRepository.getInstance(context) }
    val cardScaleMode by preferencesRepository.cardScaleMode.collectAsState(initial = com.netvio.data.preferences.CardScaleMode.FLAGSHIP)
    val navbarStyle by preferencesRepository.navbarStyle.collectAsState(initial = com.netvio.data.preferences.NavbarStyle.FLOATING)
    val isAmoledMode by preferencesRepository.amoledMode.collectAsState(initial = true)

    val windowDimensions = com.netvio.ui.theme.rememberWindowDimensions()

    val snackbarHostState = remember { AppSnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, coroutineScope) {
        AppSnackbarController(snackbarHostState, coroutineScope)
    }

    val tabReselectionEvents = remember { kotlinx.coroutines.flow.MutableSharedFlow<NavTab>(extraBufferCapacity = 1) }
    val navbarHazeState = rememberHazeState()

    CompositionLocalProvider(
        LocalNavbarVisible provides navbarVisible,
        LocalNavbarHazeState provides navbarHazeState,
        com.netvio.ui.components.LocalCardScaleMode provides cardScaleMode,
        LocalSnackbarController provides snackbarController,
        LocalTabReselection provides tabReselectionEvents,
        com.netvio.ui.theme.LocalAmoledMode provides isAmoledMode
    ) {
        val profileManager = remember { com.netvio.data.profile.ProfileManager.getInstance(context) }
        val activeProfile by profileManager.activeProfile.collectAsState()
        val unlockedProfileIds by profileManager.unlockedProfileIds.collectAsState()
        val isCurrentProfileUnlocked = !activeProfile.isPinLocked || unlockedProfileIds.contains(activeProfile.id)
        var showColdStartSwitcher by remember { mutableStateOf(false) }

        // No setup guide: every launch goes straight to Home (or the PIN
        // lock for a locked profile). ProfileManager already creates the
        // default profiles on a fresh install, so Home always has a usable
        // active profile without any onboarding step.
        LaunchedEffect(isCurrentProfileUnlocked) {
            com.netvio.ui.splash.AppLaunchCoordinator.notifyDestinationMode(
                if (isCurrentProfileUnlocked) {
                    com.netvio.ui.splash.LaunchDestinationMode.HOME
                } else {
                    com.netvio.ui.splash.LaunchDestinationMode.PIN_LOCK
                }
            )
        }

        if (!isCurrentProfileUnlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                DetroitProfileUnlockDialog(
                    profile = activeProfile,
                    onDismissRequest = {
                        activity?.finish()
                    },
                    onUnlocked = {
                        profileManager.unlockProfile(activeProfile.id)
                    },
                    onSwitchProfileClick = {
                        showColdStartSwitcher = true
                    }
                )

                if (showColdStartSwitcher) {
                    QuickProfileSwitcherDialog(
                        onDismissRequest = { showColdStartSwitcher = false },
                        onManageProfilesClick = {
                            showColdStartSwitcher = false
                        }
                    )
                }
            }
            return@CompositionLocalProvider
        }

        val showBottomNav = windowDimensions.isPhone && !isFullscreen
        val showNavRail = !windowDimensions.isPhone && !isFullscreen

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize()) {
                        if (showNavRail) {
                            AdaptiveNavigationRail(
                                currentRoute = currentRoute,
                                onTabSelected = { tab ->
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onTabReselected = { tab ->
                                    val currentDest = navController.currentDestination?.route
                                    if (currentDest != null && currentDest != tab.route) {
                                        navController.popBackStack(tab.route, inclusive = false)
                                    } else {
                                        coroutineScope.launch {
                                            tabReselectionEvents.emit(tab)
                                        }
                                    }
                                }
                            )
                        }

                    NavHost(
                        navController = navController,
                        startDestination = NavTab.HOME.route,
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { fadeIn(animationSpec = tween(150)) },
                        exitTransition = { fadeOut(animationSpec = tween(150)) },
                        popEnterTransition = { fadeIn(animationSpec = tween(150)) },
                        popExitTransition = { fadeOut(animationSpec = tween(150)) }
                    ) {
            composable(NavTab.HOME.route) {
                HomeScreen(
                    registry = registry,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = navbarHazeState),
                    onMediaClick = { item: MediaItem ->
                        navController.navigateSafely(
                            buildDetailsRoute(
                                mediaId = item.id,
                                provider = item.provider,
                                type = item.type.name,
                                title = item.title,
                                posterUrl = item.posterUrl ?: item.backdropUrl,
                                logoUrl = item.logoUrl,
                                year = item.year,
                                rating = item.rating
                            )
                        )
                    },
                    onContinueWatchingClick = { item ->
                        val isTv = item.mediaType != "LIVE_TV" && (item.mediaType == "TV_SERIES" || item.mediaType == "ANIME" || (item.mediaType == null && item.seasonNumber != null && item.seasonNumber > 0 && item.episodeNumber != null))
                        val typeStr = if (isTv) (item.mediaType ?: "TV_SERIES") else (item.mediaType ?: "MOVIE")
                        val epData = item.episodeData ?: if (isTv) "${item.mediaId}:${item.seasonNumber ?: 1}:${item.episodeNumber ?: 1}" else item.mediaId
                        val season = if (isTv) (item.seasonNumber ?: 1) else 0
                        val episode = if (isTv) (item.episodeNumber ?: 1) else 0
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = item.mediaId,
                                provider = item.provider,
                                type = typeStr,
                                episodeData = epData,
                                mediaTitle = item.title,
                                episodeTitle = if (isTv) item.episodeTitle else null,
                                season = season,
                                episode = episode
                            )
                        )
                    },
                    onPlayHero = { item, history ->
                        val isTv = item.type != com.netvio.core.model.MediaType.LIVE_TV &&
                            history?.mediaType != "LIVE_TV" &&
                            (item.type == com.netvio.core.model.MediaType.TV_SERIES ||
                            item.type == com.netvio.core.model.MediaType.ANIME ||
                            history?.mediaType == "TV_SERIES" ||
                            history?.mediaType == "ANIME" ||
                            (history?.seasonNumber != null && history.seasonNumber > 0))
                        val typeStr = if (isTv) (history?.mediaType ?: item.type.name) else "MOVIE"
                        val epData = history?.episodeData ?: if (isTv) "${item.id}:${history?.seasonNumber ?: 1}:${history?.episodeNumber ?: 1}" else item.id
                        val season = if (isTv) (history?.seasonNumber ?: 1) else 0
                        val episode = if (isTv) (history?.episodeNumber ?: 1) else 0
                        val title = history?.title?.ifBlank { item.title } ?: item.title
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = item.id,
                                provider = history?.provider ?: item.provider,
                                type = typeStr,
                                episodeData = epData,
                                mediaTitle = title,
                                episodeTitle = if (isTv) history?.episodeTitle else null,
                                season = season,
                                episode = episode
                            )
                        )
                    },
                    onNavigateToPluginManager = {
                        navController.navigateSafely("settings/plugins")
                    }
                )
            }

            composable(NavTab.SEARCH.route) {
                SearchScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = navbarHazeState),
                    onMediaClick = { item: MediaItem ->
                        navController.navigateSafely(
                            buildDetailsRoute(
                                mediaId = item.id,
                                provider = item.provider,
                                type = item.type.name,
                                title = item.title,
                                posterUrl = item.posterUrl ?: item.backdropUrl,
                                logoUrl = item.logoUrl,
                                year = item.year,
                                rating = item.rating
                            )
                        )
                    },
                    onPlayMedia = { item: MediaItem ->
                        val isTv = item.type != com.netvio.core.model.MediaType.LIVE_TV &&
                            (item.type == com.netvio.core.model.MediaType.TV_SERIES || item.type == com.netvio.core.model.MediaType.ANIME)
                        val typeStr = item.type.name
                        val epData = if (isTv) "${item.id}:1:1" else item.id
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = item.id,
                                provider = item.provider,
                                type = typeStr,
                                episodeData = epData,
                                mediaTitle = item.title,
                                episodeTitle = null,
                                season = if (isTv) 1 else -1,
                                episode = if (isTv) 1 else -1
                            )
                        )
                    },
                    onNavigateToPluginManager = {
                        navController.navigateSafely("settings/plugins")
                    }
                )
            }

            composable(
                route = "details/{mediaId}?provider={provider}&type={type}&depth={depth}&title={title}&posterUrl={posterUrl}&logoUrl={logoUrl}&year={year}&rating={rating}",
                arguments = listOf(
                    navArgument("mediaId") { type = NavType.StringType },
                    navArgument("provider") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("depth") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("posterUrl") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("logoUrl") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("year") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("rating") { type = NavType.StringType; nullable = true; defaultValue = null }
                ),
                enterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                }
            ) { backStackEntry ->
                val rawMediaId = backStackEntry.arguments?.getString("mediaId") ?: return@composable
                val mediaId = safeDecode(rawMediaId)
                val provider = backStackEntry.arguments?.getString("provider")
                val type = backStackEntry.arguments?.getString("type")
                val depth = backStackEntry.arguments?.getInt("depth") ?: 1
                val rawTitle = backStackEntry.arguments?.getString("title")
                val initialTitle = rawTitle?.takeIf { it.isNotBlank() }
                val rawPosterUrl = backStackEntry.arguments?.getString("posterUrl")
                val initialPosterUrl = rawPosterUrl?.takeIf { it.isNotBlank() }
                val rawLogoUrl = backStackEntry.arguments?.getString("logoUrl")
                val initialLogoUrl = rawLogoUrl?.takeIf { it.isNotBlank() }
                val initialYear = backStackEntry.arguments?.getInt("year")?.takeIf { it >= 0 }
                val rawRating = backStackEntry.arguments?.getString("rating")
                val initialRating = rawRating?.takeIf { it.isNotBlank() }
                MediaDetailsScreen(
                    mediaId = mediaId,
                    provider = provider,
                    type = type,
                    initialTitle = initialTitle,
                    initialPosterUrl = initialPosterUrl,
                    initialLogoUrl = initialLogoUrl,
                    initialYear = initialYear,
                    initialRating = initialRating,
                    depth = depth,
                    onBack = {
                        val currentEntry = navController.currentBackStackEntry
                        if (currentEntry != null && currentEntry.lifecycle.currentState != Lifecycle.State.RESUMED) {
                            return@MediaDetailsScreen
                        }
                        if (!navController.popBackStack()) {
                            navController.navigateSafely("home") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onCloseToRoot = {
                        navController.popBackStack(navController.graph.startDestinationId, inclusive = false)
                    },
                    onNavigateToMedia = { rec ->
                        val p = rec.provider ?: provider ?: "tmdb"
                        navController.navigateSafely(
                            buildDetailsRoute(
                                mediaId = rec.id,
                                provider = p,
                                type = rec.type.name,
                                depth = depth + 1,
                                title = rec.title,
                                posterUrl = rec.posterUrl ?: rec.backdropUrl,
                                logoUrl = rec.logoUrl,
                                year = rec.year,
                                rating = rec.rating
                            )
                        )
                    },
                    onPlayMedia = { detail, episode, streamKey ->
                        val prov = detail.provider ?: provider ?: "tmdb"
                        val firstEp = detail.episodes.firstOrNull()
                        val isTv = detail.type != com.netvio.core.model.MediaType.LIVE_TV &&
                            (firstEp == null || firstEp.seasonNumber > 0) &&
                            (detail.type == com.netvio.core.model.MediaType.TV_SERIES || detail.type == com.netvio.core.model.MediaType.ANIME)
                        val season = if (isTv) (episode?.seasonNumber ?: 1) else -1
                        val epNum = if (isTv) (episode?.episodeNumber ?: 1) else -1
                        val rawEpData = if (isTv) episode?.data else (firstEp?.data ?: episode?.data)
                        val epData = if (isTv) {
                            if (!rawEpData.isNullOrBlank() && rawEpData.contains(":")) rawEpData else "${detail.id}:$season:$epNum"
                        } else {
                            rawEpData ?: detail.id
                        }
                        val epTitle = if (isTv) (episode?.title ?: "") else ""
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = detail.id,
                                provider = prov,
                                type = detail.type.name,
                                episodeData = epData,
                                mediaTitle = detail.title,
                                episodeTitle = epTitle,
                                season = season,
                                episode = epNum,
                                streamKey = streamKey
                            )
                        )
                    },
                    onNavigateToPluginManager = {
                        navController.navigateSafely("settings/plugins")
                    }
                )
            }

            composable(
                route = "player/{mediaId}?provider={provider}&type={type}&episodeData={episodeData}&mediaTitle={mediaTitle}&episodeTitle={episodeTitle}&season={season}&episode={episode}&streamKey={streamKey}",
                arguments = listOf(
                    navArgument("mediaId") { type = NavType.StringType },
                    navArgument("provider") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("episodeData") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("mediaTitle") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("episodeTitle") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("season") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("episode") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("streamKey") { type = NavType.StringType; nullable = true; defaultValue = null }
                ),
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(200)) },
                popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                popExitTransition = { fadeOut(animationSpec = tween(200)) }
            ) { backStackEntry ->
                val rawMediaId = backStackEntry.arguments?.getString("mediaId") ?: return@composable
                val mediaId = safeDecode(rawMediaId)
                val provider = backStackEntry.arguments?.getString("provider")
                val type = backStackEntry.arguments?.getString("type")
                val rawEpData = backStackEntry.arguments?.getString("episodeData")
                val episodeData = rawEpData?.takeIf { it.isNotBlank() }
                val rawTitle = backStackEntry.arguments?.getString("mediaTitle")
                val mediaTitle = rawTitle?.takeIf { it.isNotBlank() }
                val isTv = type == "TV_SERIES" || type == "ANIME"
                val rawEpTitle = backStackEntry.arguments?.getString("episodeTitle")
                val episodeTitle = if (isTv) rawEpTitle?.takeIf { it.isNotBlank() } else null
                val rawSeason = backStackEntry.arguments?.getInt("season") ?: 0
                val rawEpisode = backStackEntry.arguments?.getInt("episode") ?: 0
                val season = if (isTv && rawSeason > 0) rawSeason else null
                val episode = if (isTv && rawEpisode > 0) rawEpisode else null
                // Pre-selected stream key from the long-press stream picker, if any.
                // Navigation already percent-decodes query args once, matching the
                // single rfc3986Encode applied in buildPlayerRoute, so read it as-is.
                val streamKey = backStackEntry.arguments?.getString("streamKey")?.takeIf { it.isNotBlank() }

                PlayerScreen(
                    mediaId = mediaId,
                    provider = provider,
                    type = type,
                    episodeData = episodeData,
                    mediaTitle = mediaTitle,
                    episodeTitle = episodeTitle,
                    initialSeason = season,
                    initialEpisode = episode,
                    preselectedStreamKey = streamKey,
                    onBack = {
                        val currentEntry = navController.currentBackStackEntry
                        if (currentEntry != null && currentEntry.lifecycle.currentState != Lifecycle.State.RESUMED) {
                            return@PlayerScreen
                        }
                        val popped = navController.popBackStack()
                        if (!popped) {
                            navController.navigateSafely(NavTab.HOME.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                    onNavigateToPluginManager = { navController.navigateSafely("settings/plugins") }
                )
            }

            composable(NavTab.LIBRARY.route) {
                com.netvio.ui.library.LibraryScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = navbarHazeState),
                    onMediaClick = { mediaId, type, prov ->
                        val providerName = prov ?: registry.activePlugin.value.name
                        navController.navigateSafely(
                            buildDetailsRoute(
                                mediaId = mediaId,
                                provider = providerName,
                                type = type.name
                            )
                        )
                    },
                    onPlayMedia = { mediaId, title, epTitle, season, episode, type, prov, epData ->
                        val providerName = prov ?: registry.activePlugin.value.name
                        val isTv = type == com.netvio.core.model.MediaType.TV_SERIES || type == com.netvio.core.model.MediaType.ANIME
                        val streamEpData = if (isTv) {
                            if (!epData.isNullOrBlank() && epData.contains(":")) epData else "$mediaId:${season ?: 1}:${episode ?: 1}"
                        } else {
                            epData ?: mediaId
                        }
                        val s = if (isTv) (season ?: 1) else 0
                        val ep = if (isTv) (episode ?: 1) else 0
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = mediaId,
                                provider = providerName,
                                type = type.name,
                                episodeData = streamEpData,
                                mediaTitle = title,
                                episodeTitle = if (isTv) epTitle else null,
                                season = s,
                                episode = ep
                            )
                        )
                    },
                    onPlayDownload = { downloadItem ->
                        val epData = downloadItem.episodeId ?: downloadItem.id
                        val typeStr = if (downloadItem.isTvSeries) "TV_SERIES" else "MOVIE"
                        val prov = downloadItem.provider ?: "Offline"
                        val s = if (downloadItem.isTvSeries) (downloadItem.seasonNumber ?: 1) else 0
                        val ep = if (downloadItem.isTvSeries) (downloadItem.episodeNumber ?: 1) else 0
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = downloadItem.mediaId,
                                provider = prov,
                                type = typeStr,
                                episodeData = epData,
                                mediaTitle = downloadItem.title,
                                episodeTitle = downloadItem.subtitle,
                                season = s,
                                episode = ep
                            )
                        )
                    },
                    onExploreClick = {
                        navController.navigateSafely(NavTab.HOME.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(
                route = "${NavTab.LIBRARY.route}?filter={filter}",
                arguments = listOf(
                    navArgument("filter") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val filterArg = backStackEntry.arguments?.getString("filter")
                val initialFilter = when (filterArg?.lowercase()) {
                    "downloads" -> com.netvio.ui.library.LibraryFilter.DOWNLOADS
                    "watchlist" -> com.netvio.ui.library.LibraryFilter.WATCHLIST
                    "history" -> com.netvio.ui.library.LibraryFilter.HISTORY
                    "favorites" -> com.netvio.ui.library.LibraryFilter.FAVORITES
                    else -> null
                }
                com.netvio.ui.library.LibraryScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = navbarHazeState),
                    onMediaClick = { mediaId, type, prov ->
                        val providerName = prov ?: registry.activePlugin.value.name
                        navController.navigateSafely(
                            buildDetailsRoute(
                                mediaId = mediaId,
                                provider = providerName,
                                type = type.name
                            )
                        )
                    },
                    onPlayMedia = { mediaId, title, epTitle, season, episode, type, prov, epData ->
                        val providerName = prov ?: registry.activePlugin.value.name
                        val isTv = type == com.netvio.core.model.MediaType.TV_SERIES || type == com.netvio.core.model.MediaType.ANIME
                        val streamEpData = if (isTv) {
                            if (!epData.isNullOrBlank() && epData.contains(":")) epData else "$mediaId:${season ?: 1}:${episode ?: 1}"
                        } else {
                            epData ?: mediaId
                        }
                        val s = if (isTv) (season ?: 1) else 0
                        val ep = if (isTv) (episode ?: 1) else 0
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = mediaId,
                                provider = providerName,
                                type = type.name,
                                episodeData = streamEpData,
                                mediaTitle = title,
                                episodeTitle = if (isTv) epTitle else null,
                                season = s,
                                episode = ep
                            )
                        )
                    },
                    onPlayDownload = { downloadItem ->
                        val epData = downloadItem.episodeId ?: downloadItem.id
                        val typeStr = if (downloadItem.isTvSeries) "TV_SERIES" else "MOVIE"
                        val prov = downloadItem.provider ?: "Offline"
                        val s = if (downloadItem.isTvSeries) (downloadItem.seasonNumber ?: 1) else 0
                        val ep = if (downloadItem.isTvSeries) (downloadItem.episodeNumber ?: 1) else 0
                        navController.navigateSafely(
                            buildPlayerRoute(
                                mediaId = downloadItem.mediaId,
                                provider = prov,
                                type = typeStr,
                                episodeData = epData,
                                mediaTitle = downloadItem.title,
                                episodeTitle = downloadItem.subtitle,
                                season = s,
                                episode = ep
                            )
                        )
                    },
                    onExploreClick = {
                        navController.navigateSafely(NavTab.HOME.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    initialFilter = initialFilter
                )
            }

            composable("downloads") {
                LaunchedEffect(Unit) {
                    navController.navigate("${NavTab.LIBRARY.route}?filter=downloads") {
                        popUpTo("downloads") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable(NavTab.PROFILE.route) {
                com.netvio.ui.profile.ProfileScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = navbarHazeState),
                    onNavigateToPage = { route -> navController.navigateSafely(route) }
                )
            }

            composable("settings") {
                com.netvio.ui.profile.ProfileScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = navbarHazeState),
                    onNavigateToPage = { route -> navController.navigateSafely(route) }
                )
            }

            composable(
                route = "settings/playback",
                enterTransition = {
                    if (reduceMotion) fadeIn(animationSpec = tween(180)) else slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) fadeOut(animationSpec = tween(140)) else slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(160, easing = FastOutLinearInEasing)
                    ) + fadeOut(animationSpec = tween(140))
                }
            ) {
                com.netvio.ui.settings.pages.PlaybackSettingsPage(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "settings/appearance",
                enterTransition = {
                    if (reduceMotion) fadeIn(animationSpec = tween(180)) else slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) fadeOut(animationSpec = tween(140)) else slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(160, easing = FastOutLinearInEasing)
                    ) + fadeOut(animationSpec = tween(140))
                }
            ) {
                com.netvio.ui.settings.pages.AppearanceSettingsPage(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "settings/catalogs",
                enterTransition = {
                    if (reduceMotion) fadeIn(animationSpec = tween(180)) else slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) fadeOut(animationSpec = tween(140)) else slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(160, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(140))
                }
            ) {
                com.netvio.ui.settings.pages.CatalogManagerPage(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "settings/integrations",
                enterTransition = {
                    if (reduceMotion) fadeIn(animationSpec = tween(180)) else slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) fadeOut(animationSpec = tween(140)) else slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(160, easing = FastOutLinearInEasing)
                    ) + fadeOut(animationSpec = tween(140))
                }
            ) {
                com.netvio.ui.settings.pages.IntegrationsSettingsPage(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "settings/plugins",
                enterTransition = {
                    if (reduceMotion) fadeIn(animationSpec = tween(180)) else slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) fadeOut(animationSpec = tween(140)) else slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(160, easing = FastOutLinearInEasing)
                    ) + fadeOut(animationSpec = tween(140))
                }
            ) {
                com.netvio.ui.settings.pages.PluginsSettingsPage(
                    onBack = { navController.popBackStack() },
                    onNavigateToRepo = { repoRoute -> navController.navigateSafely(repoRoute) }
                )
            }

            composable(
                route = "settings/plugins/repo/{encodedRepoUrl}",
                arguments = listOf(
                    androidx.navigation.navArgument("encodedRepoUrl") {
                        type = androidx.navigation.NavType.StringType
                    }
                ),
                enterTransition = {
                    if (reduceMotion) fadeIn(animationSpec = tween(180)) else slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) fadeOut(animationSpec = tween(140)) else slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(160, easing = FastOutLinearInEasing)
                    ) + fadeOut(animationSpec = tween(140))
                }
            ) { backStackEntry ->
                val encodedUrl = backStackEntry.arguments?.getString("encodedRepoUrl") ?: ""
                val decodedUrl = try {
                    java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                } catch (_: Exception) {
                    encodedUrl
                }
                com.netvio.ui.settings.pages.RepoPluginsScreen(
                    repoUrl = decodedUrl,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "settings/storage",
                enterTransition = {
                    if (reduceMotion) fadeIn(animationSpec = tween(180)) else slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200))
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) fadeOut(animationSpec = tween(140)) else slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = tween(160, easing = FastOutLinearInEasing)
                    ) + fadeOut(animationSpec = tween(140))
                }
            ) {
                com.netvio.ui.settings.pages.StorageSettingsPage(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = "plugin_manager",
                enterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(280, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(180))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(180))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(250, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                }
            ) {
                com.netvio.ui.settings.PluginManagerScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

            if (showBottomNav) {
                FloatingNavbar(
                    currentRoute = currentRoute,
                    style = navbarStyle,
                    onTabSelected = { tab ->
                        if (tab == NavTab.HOME) {
                            navController.popBackStack(NavTab.HOME.route, inclusive = false)
                        } else {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onTabReselected = { tab ->
                        val currentDest = navController.currentDestination?.route
                        if (currentDest != null && currentDest != tab.route) {
                            navController.popBackStack(tab.route, inclusive = false)
                        } else {
                            coroutineScope.launch {
                                tabReselectionEvents.emit(tab)
                            }
                        }
                    },
                    isVisible = navbarVisible.value,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            val targetSnackbarPadding = if (showBottomNav && navbarVisible.value) 94.dp else Dimens.spacing16
            val snackbarBottomPadding by animateDpAsState(
                targetValue = targetSnackbarPadding,
                animationSpec = if (reduceMotion) {
                    snap()
                } else if (targetSnackbarPadding > Dimens.spacing16) {
                    tween(durationMillis = 220, easing = AppleDecelCurve)
                } else {
                    tween(durationMillis = 140, easing = HudExitEasing)
                },
                label = "snackbarBottomPadding"
            )
            AppSnackbarHost(
                hostState = snackbarHostState,
                bottomPadding = snackbarBottomPadding
            )
        }
    }
}
}

@Composable
fun PlaceholderScreen(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Dimens.spacing24, vertical = Dimens.spacing24)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = AppTypography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(Dimens.spacing8))
            Text(
                text = subtitle,
                style = AppTypography.bodyMedium,
                color = TextTertiary
            )
        }
    }
}

internal fun rfc3986Encode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun safeDoubleEncode(value: String): String {
    return rfc3986Encode(rfc3986Encode(value))
}

internal fun safeDecode(value: String): String {
    return try {
        val sanitized = value.replace("+", "%2B")
        URLDecoder.decode(sanitized, "UTF-8")
    } catch (_: Exception) {
        value
    }
}

internal fun buildDetailsRoute(
    mediaId: String,
    provider: String? = null,
    type: String? = null,
    depth: Int = 1,
    title: String? = null,
    posterUrl: String? = null,
    logoUrl: String? = null,
    year: Int? = null,
    rating: String? = null
): String {
    val safeId = mediaId.trim().ifBlank { "unknown" }
    val encodedId = safeDoubleEncode(safeId)
    val prov = if (provider.isNullOrBlank()) "tmdb" else provider
    val encodedProv = rfc3986Encode(prov)
    val typeStr = type ?: "MOVIE"
    val encodedTitle = title?.let { rfc3986Encode(it) } ?: ""
    val posterQuery = if (!posterUrl.isNullOrBlank()) "&posterUrl=${rfc3986Encode(posterUrl)}" else ""
    val logoQuery = if (!logoUrl.isNullOrBlank()) "&logoUrl=${rfc3986Encode(logoUrl)}" else ""
    val yearQuery = if (year != null) "&year=$year" else ""
    val ratingQuery = if (!rating.isNullOrBlank()) "&rating=${rfc3986Encode(rating)}" else ""
    return "details/$encodedId?provider=$encodedProv&type=$typeStr&depth=$depth&title=$encodedTitle$posterQuery$logoQuery$yearQuery$ratingQuery"
}

internal fun buildPlayerRoute(
    mediaId: String,
    provider: String? = null,
    type: String? = null,
    episodeData: String? = null,
    mediaTitle: String? = null,
    episodeTitle: String? = null,
    season: Int = 0,
    episode: Int = 0,
    streamKey: String? = null
): String {
    val safeId = mediaId.trim().ifBlank { "unknown" }
    val encodedMediaId = safeDoubleEncode(safeId)
    val prov = if (provider.isNullOrBlank()) "tmdb" else provider
    val encodedProv = rfc3986Encode(prov)
    val typeStr = type ?: "MOVIE"
    val encodedEpData = episodeData?.let { rfc3986Encode(it) } ?: ""
    val encodedTitle = mediaTitle?.let { rfc3986Encode(it) } ?: ""
    val encodedEpTitle = episodeTitle?.let { rfc3986Encode(it) } ?: ""
    // Only present when a stream was picked in the long-press picker; a null key
    // keeps the route byte-identical to the tap path.
    val streamKeySuffix = streamKey?.takeIf { it.isNotBlank() }?.let { "&streamKey=${rfc3986Encode(it)}" } ?: ""
    return "player/$encodedMediaId?provider=$encodedProv&type=$typeStr&episodeData=$encodedEpData&mediaTitle=$encodedTitle&episodeTitle=$encodedEpTitle&season=$season&episode=$episode$streamKeySuffix"
}
