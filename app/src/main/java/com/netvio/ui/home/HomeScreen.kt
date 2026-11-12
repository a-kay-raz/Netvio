package com.netvio.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import com.netvio.ui.theme.LocalReduceMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.netvio.ui.theme.rememberWindowDimensions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import com.netvio.ui.longpress.LocalLongPressHazeState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netvio.core.model.CatalogRow
import com.netvio.core.model.MediaItem
import com.netvio.core.registry.PluginRegistry
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import com.netvio.ui.components.EmptyState
import com.netvio.ui.components.AppFilterChip
import com.netvio.ui.components.SnapWheelSelector
import com.netvio.ui.components.CompactErrorState
import com.netvio.ui.components.ContentRow
import com.netvio.ui.components.ContinueWatchingRow
import com.netvio.ui.components.ErrorState
import com.netvio.ui.components.HeroCarousel
import com.netvio.ui.details.sheets.MediaDetailsActionSheet
import com.netvio.ui.details.sheets.SheetScreen
import com.netvio.ui.components.rememberHeroStretchState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.zIndex
import com.netvio.ui.components.LocalSnackbarController
import com.netvio.ui.components.MediaQuickActionsSheet
import com.netvio.ui.components.OfflineBanner
import com.netvio.ui.library.components.AddToPlaylistSheet
import com.netvio.ui.longpress.LongPressHost
import com.netvio.ui.longpress.SheetActions
import com.netvio.ui.longpress.UserReaction
import com.netvio.ui.longpress.toSheetItem
import com.netvio.ui.components.TopHeaderBar
import com.netvio.ui.components.TopTenRow
import com.netvio.ui.navigation.ObserveScrollForNavbar
import com.netvio.ui.theme.Accent
import com.netvio.ui.theme.LocalAmoledMode
import com.netvio.ui.theme.AppTypography
import com.netvio.ui.theme.SurfaceMid
import com.netvio.ui.theme.Background
import com.netvio.ui.theme.Dimens
import com.netvio.ui.theme.Motion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onContinueWatchingClick: ((com.netvio.data.local.PlaybackHistoryEntity) -> Unit)? = null,
    onPlayHero: ((MediaItem, com.netvio.data.local.PlaybackHistoryEntity?) -> Unit)? = null,
    onNavigateToPluginManager: (() -> Unit)? = null,
    onCatalogClick: ((catalogKey: String, title: String, subtitle: String?) -> Unit)? = null,
    viewModel: HomeViewModel = viewModel(),
    registry: PluginRegistry = PluginRegistry.Default,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    val universalTopTen by viewModel.universalTopTen.collectAsState()
    val playbackHistoryMap by viewModel.playbackHistoryMap.collectAsState()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val watchlistItems by viewModel.watchlistItems.collectAsState()
    val watchlistStatusMap by viewModel.watchlistStatusMap.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val top10Style by viewModel.top10Style.collectAsState()
    val sectionLabelStyle by viewModel.sectionLabelStyle.collectAsState()
    val longPressStyle by viewModel.longPressStyle.collectAsState()
    val mediaReactions by viewModel.mediaReactions.collectAsState()
    val snackbarController = LocalSnackbarController.current
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isProgressiveLoading by viewModel.isProgressiveLoading.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var quickActionItem by remember { mutableStateOf<MediaItem?>(null) }
    var addToPlaylistMedia by remember { mutableStateOf<MediaItem?>(null) }
    var watchlistSheetMedia by remember { mutableStateOf<MediaItem?>(null) }

    // Notify AppLaunchCoordinator when Home catalog data is ready for dynamic early release
    LaunchedEffect(uiState) {
        if (uiState is HomeUiState.Success) {
            com.netvio.ui.splash.AppLaunchCoordinator.notifyHomeDataReady()
        } else if (uiState is HomeUiState.Error) {
            com.netvio.ui.splash.AppLaunchCoordinator.notifyHomeDataReady()
        }
    }

    val lazyListState = rememberLazyListState()
    val navbarVisible = com.netvio.ui.navigation.LocalNavbarVisible.current
    val shouldHideHomeNavbar =
        quickActionItem != null ||
        addToPlaylistMedia != null ||
        watchlistSheetMedia != null

    androidx.compose.runtime.DisposableEffect(shouldHideHomeNavbar) {
        navbarVisible.value = !shouldHideHomeNavbar
        onDispose {
            navbarVisible.value = true
        }
    }

    if (!shouldHideHomeNavbar) {
        ObserveScrollForNavbar(lazyListState)
    }
    val windowDimensions = rememberWindowDimensions()
    val heroPlayFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = lazyListState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMoreRails()
        }
    }

    LaunchedEffect(windowDimensions.isTV, uiState) {
        if (windowDimensions.isTV && uiState is HomeUiState.Success) {
            kotlinx.coroutines.delay(120)
            try {
                heroPlayFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val tabReselectionEvents = com.netvio.ui.navigation.LocalTabReselection.current
    LaunchedEffect(tabReselectionEvents) {
        tabReselectionEvents.collect { tab ->
            if (tab == com.netvio.ui.navigation.NavTab.HOME) {
                if (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0) {
                    lazyListState.animateScrollToItem(0)
                }
            }
        }
    }

    val isHeroOffScreen by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heroScrollOffsetPx by remember {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex == 0) {
                lazyListState.firstVisibleItemScrollOffset.toFloat()
            } else {
                1000f
            }
        }
    }
    val headerScrollFraction by remember {
        derivedStateOf {
            val t = (heroScrollOffsetPx / with(density) { 140.dp.toPx() }).coerceIn(0f, 1f)
            // Smootherstep S(t) = 6t^5 - 15t^4 + 10t^3 ensures zero derivative at both ends (no start/end snap)
            t * t * t * (t * (t * 6f - 15f) + 10f)
        }
    }

    val homeHazeState = rememberHazeState()

    val isAmoled = LocalAmoledMode.current
    val screenBg = if (isAmoled) Color.Black else Background

    CompositionLocalProvider(LocalLongPressHazeState provides homeHazeState) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(screenBg)
        ) {
            // Screen content behind the sheet (marked as hazeSource with screenBg for uniform non-patchy blur)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(screenBg)
                    .hazeSource(state = homeHazeState)
            ) {
        Crossfade(
            targetState = uiState,
            animationSpec = tween(durationMillis = Motion.DurationEmphasized),
            label = "HomeScreenTransition"
        ) { state ->
            when (state) {
                is HomeUiState.Loading -> {
                    // Keep canvas quiet obsidian; never flash wireframe skeleton
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(screenBg),
                        contentAlignment = Alignment.Center
                    ) {
                        val isIgnitionActive by com.netvio.ui.splash.AppLaunchCoordinator.isIgnitionActive.collectAsState()
                        val hasCompletedInitialLaunch = com.netvio.ui.splash.AppLaunchCoordinator.hasCompletedInitialLaunch
                        if (!isIgnitionActive && hasCompletedInitialLaunch) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = Color.White.copy(alpha = 0.65f),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                is HomeUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.error is com.netvio.ui.util.UserError.NoSources) {
                            EmptyState(
                                icon = Icons.Outlined.Extension,
                                title = "No sources yet",
                                message = "Add a source to start watching.",
                                actionLabel = if (onNavigateToPluginManager != null) "Browse Sources" else null,
                                onActionClick = onNavigateToPluginManager
                            )
                        } else {
                            ErrorState(
                                error = state.error,
                                onRetry = { viewModel.loadCatalog() }
                            )
                        }
                    }
                }

                is HomeUiState.Success -> {
                    if (state.filteredRows.isEmpty() && state.heroItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyState(
                                icon = Icons.Outlined.Extension,
                                title = "No sources yet",
                                message = "Add a source to start watching.",
                                actionLabel = if (onNavigateToPluginManager != null) "Browse Sources" else null,
                                onActionClick = onNavigateToPluginManager
                            )
                        }
                    } else {
                        val heroItemCount = state.heroItems.size
                        val pagerState = rememberPagerState(
                            initialPage = remember(heroItemCount) {
                                if (heroItemCount > 1) (1000 / 2) * heroItemCount else 0
                            },
                            pageCount = { if (heroItemCount > 1) heroItemCount * 1000 else heroItemCount.coerceAtLeast(1) }
                        )
                    val rankedTop10Row = remember(state.filteredRows) {
                        state.filteredRows.firstOrNull { row ->
                            val t = row.title.trim().lowercase()
                            (t == "top 10" || t.startsWith("top 10") || t.startsWith("top ten") || t == "top 10 today") &&
                            row.items.size >= 5
                        }
                    }
                    val topTenDisplayItems = remember(universalTopTen, rankedTop10Row) {
                        if (universalTopTen.isNotEmpty()) universalTopTen.take(10) else rankedTop10Row?.items?.take(10) ?: emptyList()
                    }
                    val displayContentRows = remember(state.filteredRows, rankedTop10Row, topTenDisplayItems) {
                        val base = if (rankedTop10Row != null) {
                            state.filteredRows.filter { it.title != rankedTop10Row.title }
                        } else {
                            state.filteredRows
                        }
                        deduplicateProminentRows(
                            topTenItems = topTenDisplayItems,
                            rows = base,
                            maxRowsToFilter = 3
                        )
                    }

                    val heroStretchState = rememberHeroStretchState(
                        listState = lazyListState,
                        onRefresh = null
                    )
                    val isFeedScrolling by remember {
                        derivedStateOf {
                            lazyListState.isScrollInProgress || heroStretchState.stretchPx > 0f
                        }
                    }
                    val heroStretchModifier = if (state.heroItems.isNotEmpty()) {
                        Modifier.nestedScroll(heroStretchState.nestedScrollConnection)
                    } else {
                        Modifier
                    }

                    val isReducedMotion = LocalReduceMotion.current
                    var isFirstHomeComposition by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        isFirstHomeComposition = false
                    }

                    val currentTabIndex = state.availableTabs.indexOf(selectedTab).coerceAtLeast(0)
                    val rowsForTab = state.rowsByTab[selectedTab] ?: displayContentRows
                    val rankedTop10 = remember(rowsForTab) {
                        rowsForTab.firstOrNull { row ->
                            val t = row.title.trim().lowercase()
                            (t == "top 10" || t.startsWith("top 10") || t.startsWith("top ten") || t == "top 10 today") &&
                            row.items.size >= 5
                        }
                    }
                    val topTenForTab = remember(selectedTab, universalTopTen, rankedTop10) {
                        if (universalTopTen.isNotEmpty()) {
                            universalTopTen.take(10)
                        } else {
                            rankedTop10?.items?.take(10) ?: emptyList()
                        }
                    }
                    val contentRowsForTab = remember(rowsForTab, rankedTop10, topTenForTab) {
                        val base = if (rankedTop10 != null) {
                            rowsForTab.filter { it.title != rankedTop10.title }
                        } else {
                            rowsForTab
                        }
                        val distinctRows = base.distinctBy { "${it.title}_${it.subtitle.orEmpty()}_${it.eyebrow.orEmpty()}" }
                        deduplicateProminentRows(
                            topTenItems = topTenForTab,
                            rows = distinctRows,
                            maxRowsToFilter = 3
                        )
                    }

                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {

                        val lazyTopPadding = if (state.heroItems.isEmpty()) {
                            Dimens.spacing48 + 56.dp
                        } else {
                            0.dp
                        }
                        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.foundation.LocalOverscrollConfiguration provides null
                        ) {
                            LazyColumn(
                                state = lazyListState,
                                contentPadding = PaddingValues(top = lazyTopPadding),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(heroStretchModifier)
                            ) {
                            // 1. Apple TV Style Hero Carousel
                            item(key = "hero_carousel", contentType = "hero") {
                                HeroCarousel(
                                    items = state.heroItems,
                                    pagerState = pagerState,
                                    bookmarkedIds = bookmarkedIds,
                                    playbackMap = playbackHistoryMap,
                                    onPlayClick = { item, history ->
                                        if (onPlayHero != null) {
                                            onPlayHero(item, history)
                                        } else if (history != null && onContinueWatchingClick != null) {
                                            onContinueWatchingClick(history)
                                        } else {
                                            onMediaClick(item)
                                        }
                                    },
                                    onDetailsClick = { onMediaClick(it) },
                                    onBookmarkClick = { watchlistSheetMedia = it },
                                    isOffScreen = isHeroOffScreen,
                                    isFeedScrolling = { isFeedScrolling },
                                    playFocusRequester = heroPlayFocusRequester,
                                    stretchPx = { heroStretchState.stretchPx },
                                    scrollOffsetPx = { heroScrollOffsetPx }
                                )
                            }

                            // 2. Category Snap Wheel Selector
                            if (state.availableTabs.size > 1) {
                                item(key = "category_tabs", contentType = "snap_wheel") {
                                    val currentTabIndex = state.availableTabs.indexOf(selectedTab).coerceAtLeast(0)
                                    SnapWheelSelector(
                                        options = state.availableTabs.map { it.label },
                                        selectedIndex = currentTabIndex,
                                        onSelected = { index ->
                                            if (index in state.availableTabs.indices) {
                                                viewModel.selectTab(state.availableTabs[index])
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = Dimens.spacing4)
                                    )
                                }
                            }

                            if (contentRowsForTab.isEmpty() && state.heroItems.isEmpty()) {
                                item(key = "empty_catalog_loading", contentType = "loader") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(240.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            trackColor = Color.White.copy(alpha = 0.2f),
                                            strokeWidth = 3.dp
                                        )
                                    }
                                }
                            } else {
                                // 3. Continue Watching Row
                                if (continueWatching.isNotEmpty()) {
                                    item(key = "continue_watching", contentType = "continue_watching") {
                                        ContinueWatchingRow(
                                            items = continueWatching,
                                            sectionLabelStyle = sectionLabelStyle,
                                            onItemClick = { item ->
                                                if (onContinueWatchingClick != null) {
                                                    onContinueWatchingClick(item)
                                                } else {
                                                    val type = if (item.seasonNumber != null) {
                                                        com.netvio.core.model.MediaType.TV_SERIES
                                                    } else {
                                                        com.netvio.core.model.MediaType.MOVIE
                                                    }
                                                    onMediaClick(
                                                        MediaItem(
                                                            id = item.mediaId,
                                                            title = item.title,
                                                            url = "",
                                                            posterUrl = item.posterUrl,
                                                            backdropUrl = item.backdropUrl,
                                                            type = type
                                                        )
                                                    )
                                                }
                                            },
                                            onDeleteItem = { item ->
                                                viewModel.removeFromContinueWatchingWithUndo(item.id) { undo ->
                                                    snackbarController.show(
                                                        message = "Removed from Continue Watching",
                                                        actionLabel = "Undo",
                                                        durationMillis = 5000L
                                                    ) { undo() }
                                                }
                                            },
                                            onClearAll = {
                                                viewModel.clearContinueWatchingWithUndo { undo ->
                                                    snackbarController.show(
                                                        message = "Cleared Continue Watching",
                                                        actionLabel = "Undo",
                                                        durationMillis = 5000L
                                                    ) { undo() }
                                                }
                                            },
                                            modifier = Modifier.staggeredEntrance(
                                                tabKey = "persistent_home_rows",
                                                rowIndex = 0,
                                                isEntering = true,
                                                isFirstComposition = isFirstHomeComposition,
                                                reduceMotion = isReducedMotion
                                            )
                                        )
                                    }
                                }

                                // 3.5 My List / Watchlist Row (UX Guide §4)
                                if (watchlistItems.isNotEmpty()) {
                                    item(key = "watchlist_row", contentType = "watchlist") {
                                        ContentRow(
                                            row = com.netvio.core.model.CatalogRow(
                                                title = "My List",
                                                items = watchlistItems,
                                                subtitle = "Your saved movies & shows",
                                                eyebrow = "MY LIST"
                                            ),
                                            sectionLabelStyle = sectionLabelStyle,
                                            onItemClick = { onMediaClick(it) },
                                            onItemLongClick = { quickActionItem = it },
                                            modifier = Modifier.staggeredEntrance(
                                                tabKey = "persistent_home_rows",
                                                rowIndex = 1,
                                                isEntering = true,
                                                isFirstComposition = isFirstHomeComposition,
                                                reduceMotion = isReducedMotion
                                            )
                                        )
                                    }
                                }

                                // 4. Universal TMDB Top 10 today Row (Bridged across all plugins)
                                if (topTenForTab.isNotEmpty()) {
                                    item(key = "universal_top_10", contentType = "top_10") {
                                        TopTenRow(
                                            title = "Top 10 today",
                                            items = topTenForTab,
                                            style = top10Style,
                                            sectionLabelStyle = sectionLabelStyle,
                                            subtitle = "Updated daily",
                                            eyebrow = "THIS WEEK",
                                            onItemClick = { onMediaClick(it) },
                                            onItemLongClick = { quickActionItem = it },
                                            modifier = Modifier.staggeredEntrance(
                                                tabKey = selectedTab,
                                                rowIndex = 2,
                                                isEntering = true,
                                                isFirstComposition = isFirstHomeComposition,
                                                reduceMotion = isReducedMotion
                                            )
                                        )
                                    }
                                }

                                // 5. Multi-Category Content Rows (Progressively Loaded & Virtualized)
                                itemsIndexed(
                                    items = contentRowsForTab,
                                    key = { _, row -> "stremio_${row.title}_${row.subtitle.orEmpty()}_${row.eyebrow.orEmpty()}" },
                                    contentType = { _, _ -> "catalog_row" }
                                ) { index, row ->
                                    val stagger = index + 3
                                    if (row.items.isEmpty()) {
                                        CompactErrorState(
                                            message = "Could not load ${row.title}",
                                            onRetry = { viewModel.refreshCatalog() },
                                            modifier = Modifier.staggeredEntrance(
                                                tabKey = selectedTab,
                                                rowIndex = stagger,
                                                isEntering = true,
                                                isFirstComposition = isFirstHomeComposition,
                                                reduceMotion = isReducedMotion
                                            )
                                        )
                                    } else {
                                        // Nuvio-style View all: only when the catalog
                                        // likely holds more than this preview strip
                                        // (rows are fetched at a 20-item cap).
                                        val catalogKey = row.catalogKey
                                        ContentRow(
                                            row = row,
                                            sectionLabelStyle = sectionLabelStyle,
                                            onItemClick = { onMediaClick(it) },
                                            onItemLongClick = { quickActionItem = it },
                                            onSeeAllClick = if (catalogKey != null && row.items.size >= 20) {
                                                onCatalogClick?.let { cb ->
                                                    { cb(catalogKey, row.title, row.subtitle) }
                                                }
                                            } else null,
                                            modifier = Modifier.staggeredEntrance(
                                                tabKey = selectedTab,
                                                rowIndex = stagger,
                                                isEntering = true,
                                                isFirstComposition = isFirstHomeComposition,
                                                reduceMotion = isReducedMotion
                                            )
                                        )
                                    }
                                }

                                // Progressive rail loading indicator if more rails are fetching
                                if (isProgressiveLoading) {
                                    item(key = "progressive_rail_loader", contentType = "loader") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(80.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = Color.White.copy(alpha = 0.7f),
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Bottom Spacer for Navigation Bar clearance (dynamic system insets)
                            val bottomSpacerHeight = if (windowDimensions.isPhone) {
                                Dimens.navBarHeight + navBarBottom + Dimens.spacing16
                            } else {
                                navBarBottom + Dimens.spacing24
                            }
                            item(key = "home_bottom_spacer", contentType = "spacer") {
                                Spacer(modifier = Modifier.height(bottomSpacerHeight))
                            }
                        }
                    }
                }
            }
        }
    }
}
} // Close Box(hazeSource)

        val isPreviewActive = quickActionItem != null
        val headerAlpha by animateFloatAsState(
            targetValue = if (isPreviewActive) 0f else 1f,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "HeaderPreviewAlpha"
        )

        // Pinned Top Header Bar, Offline Banner & Update Banner (outside hazeSource, samples homeHazeState)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = headerAlpha
                }
        ) {
            TopHeaderBar(
                registry = registry,
                scrollFraction = { headerScrollFraction },
                hazeState = if (isPreviewActive) null else homeHazeState
            )
            OfflineBanner()
        }

    // Long-Press Quick Actions Host (sits on top of blurred hazeSource content)
    quickActionItem?.let { item ->
        val isBookmarked = item.id in bookmarkedIds
        val reactionStr = mediaReactions[item.id]
        val reaction = UserReaction.fromId(reactionStr)
        val history = playbackHistoryMap[item.id]
        val sheetItem = item.toSheetItem(
            history = history,
            inWatchlist = isBookmarked,
            reaction = reaction
        )

        val actions = SheetActions(
            onPlay = {
                quickActionItem = null
                if (onPlayHero != null) {
                    onPlayHero(item, history)
                } else if (history != null && onContinueWatchingClick != null) {
                    onContinueWatchingClick(history)
                } else {
                    onMediaClick(item)
                }
            },
            onResume = {
                quickActionItem = null
                if (onPlayHero != null) {
                    onPlayHero(item, history)
                } else if (history != null && onContinueWatchingClick != null) {
                    onContinueWatchingClick(history)
                } else {
                    onMediaClick(item)
                }
            },
            onToggleWatchlist = {
                viewModel.toggleBookmark(item)
                snackbarController.show(
                    message = if (isBookmarked) "Removed from watchlist" else "Added to watchlist",
                    actionLabel = "Undo",
                    durationMillis = 4000L
                ) {
                    viewModel.toggleBookmark(item)
                }
            },
            onReact = { nextReaction ->
                viewModel.setMediaReaction(item.id, nextReaction)
            },
            onShare = {
                quickActionItem = null
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, item.title)
                    putExtra(android.content.Intent.EXTRA_TEXT, "Watch ${item.title} on Netvio")
                }
                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share ${item.title}"))
            },
            onDownload = {
                quickActionItem = null
                onMediaClick(item)
            },
            onOpenDetails = {
                quickActionItem = null
                onMediaClick(item)
            },
            onNotInterested = {
                quickActionItem = null
                snackbarController.show("We'll show fewer recommendations like this")
            },
            onAddToPlaylist = {
                val target = item
                quickActionItem = null
                addToPlaylistMedia = target
            }
        )

        LongPressHost(
            item = sheetItem,
            style = longPressStyle,
            actions = actions,
            onDismiss = { quickActionItem = null }
        )
    }

    addToPlaylistMedia?.let { media ->
        AddToPlaylistSheet(
            mediaId = media.id,
            title = media.title,
            posterUrl = media.posterUrl ?: media.backdropUrl,
            backdropUrl = media.backdropUrl ?: media.posterUrl,
            isTvSeries = media.type == com.netvio.core.model.MediaType.TV_SERIES,
            year = media.year,
            provider = media.provider,
            hazeState = homeHazeState,
            onDismiss = { addToPlaylistMedia = null },
            onPlaylistAction = { msg -> snackbarController.show(msg) }
        )
    }

    watchlistSheetMedia?.let { media ->
        val currentStatus = watchlistStatusMap[media.id]
        MediaDetailsActionSheet(
            mediaId = media.id,
            title = media.title,
            posterUrl = media.posterUrl ?: media.backdropUrl,
            backdropUrl = media.backdropUrl ?: media.posterUrl,
            isTvSeries = media.type == com.netvio.core.model.MediaType.TV_SERIES || media.type == com.netvio.core.model.MediaType.ANIME,
            year = media.year,
            provider = media.provider,
            initialScreen = SheetScreen.WATCHLIST_CATEGORY,
            watchlistStatus = currentStatus,
            hazeState = homeHazeState,
            onSelectWatchlistCategory = { statusKey, label ->
                val isMove = !currentStatus.isNullOrBlank() && currentStatus != statusKey
                val toastMsg = if (isMove) "Moved to $label" else "Added to $label"
                viewModel.setWatchlistStatus(media, statusKey)
                snackbarController.show(toastMsg)
            },
            onRemoveFromWatchlist = {
                val previousStatus = currentStatus ?: "TO_BE_WATCHED"
                viewModel.removeFromWatchlist(media.id)
                snackbarController.show(
                    message = "Removed from watchlist",
                    actionLabel = "Undo",
                    durationMillis = 4000L
                ) {
                    viewModel.setWatchlistStatus(media, previousStatus)
                }
            },
            onPlaylistAction = { msg -> snackbarController.show(msg) },
            onDismiss = { watchlistSheetMedia = null }
        )
    }

} // Close outer Box
} // Close CompositionLocalProvider
} // Close fun HomeScreen

@Composable
private fun Modifier.staggeredEntrance(
    tabKey: Any,
    rowIndex: Int,
    isEntering: Boolean,
    isFirstComposition: Boolean,
    reduceMotion: Boolean
): Modifier {
    if (reduceMotion || !isEntering || rowIndex >= 6) return this

    val animatable = remember(tabKey, rowIndex) { Animatable(0f) }
    LaunchedEffect(tabKey, rowIndex) {
        delay(rowIndex * 35L)
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 240, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f))
        )
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val translateYPx = with(density) { 10.dp.toPx() }

    return this.graphicsLayer {
        val progress = animatable.value
        alpha = progress
        translationY = (1f - progress) * translateYPx
    }
}


/**
 * Deduplicates content cards across the Top 10 row and the first [maxRowsToFilter] catalog rows.
 * Prevents prominent repetitive cards while leaving subsequent/genre rows untouched.
 */
private fun deduplicateProminentRows(
    topTenItems: List<MediaItem>,
    rows: List<CatalogRow>,
    maxRowsToFilter: Int = 3
): List<CatalogRow> {
    if (rows.isEmpty()) return rows

    val seenIds = HashSet<String>()
    val seenNormalizedTitles = HashSet<String>()

    fun normTitle(t: String): String = t.lowercase().filter { it.isLetterOrDigit() }
    fun normId(id: String): String = id.trim().removePrefix("movie_").removePrefix("tv_")

    for (item in topTenItems) {
        val cleanId = normId(item.id)
        if (cleanId.isNotEmpty()) seenIds.add(cleanId)
        val cleanTitle = normTitle(item.title)
        if (cleanTitle.isNotEmpty()) seenNormalizedTitles.add(cleanTitle)
    }

    return rows.mapIndexed { index, row ->
        if (index < maxRowsToFilter) {
            val uniqueItems = row.items.filter { item ->
                val cleanId = normId(item.id)
                val cleanTitle = normTitle(item.title)

                val isDuplicate = (cleanId.isNotEmpty() && cleanId in seenIds) ||
                        (cleanTitle.isNotEmpty() && cleanTitle in seenNormalizedTitles)

                if (!isDuplicate) {
                    if (cleanId.isNotEmpty()) seenIds.add(cleanId)
                    if (cleanTitle.isNotEmpty()) seenNormalizedTitles.add(cleanTitle)
                    true
                } else {
                    false
                }
            }
            if (uniqueItems.size >= 3) {
                row.copy(items = uniqueItems)
            } else {
                row
            }
        } else {
            row
        }
    }
}