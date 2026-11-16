package com.netvio.ui.details.downloads

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.download.DownloadItem
import com.netvio.download.DownloadState
import com.netvio.ui.navigation.LocalNavbarVisible
import com.netvio.ui.theme.AppFontFamily
import com.netvio.ui.theme.Dimens
import com.netvio.ui.theme.NetvioGlassTokens
import com.netvio.ui.theme.LocalAmoledMode
import com.netvio.ui.theme.LocalReduceMotion
import com.netvio.ui.theme.LocalUiBlurEnabled
import com.netvio.ui.theme.adaptiveHorizontalSafePadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * In-window monochrome frosted glass bottom sheet for batch episode downloads.
 * Attaches real-time Haze blur directly to the sheet container (matching [SeasonSheet]
 * rather than blurring the full Activity window behind a modal dialog).
 */
@Composable
fun MultiEpisodeDownloadSheet(
    detail: MediaDetail,
    initialSeason: Int,
    episodePlaybackMap: Map<Pair<Int, Int>, PlaybackHistoryEntity>,
    downloads: List<DownloadItem>,
    batchState: BatchDownloadState,
    onStartBatchDownload: (
        episodes: List<EpisodeItem>,
        preferredQuality: BatchPreferredQuality,
        preferSameProvider: Boolean
    ) -> Unit,
    onCancelBatchDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val isAmoled = LocalAmoledMode.current
    val uiBlurEnabled = LocalUiBlurEnabled.current
    val reduceMotion = LocalReduceMotion.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val navbarVisible = LocalNavbarVisible.current

    DisposableEffect(Unit) {
        navbarVisible.value = false
        onDispose {
            navbarVisible.value = true
        }
    }

    val availableSeasons = remember(detail.episodes) {
        detail.episodes.map { it.seasonNumber }.distinct().sorted()
    }
    var activeSeason by remember(initialSeason, availableSeasons) {
        mutableIntStateOf(
            if (initialSeason in availableSeasons) initialSeason
            else availableSeasons.firstOrNull() ?: initialSeason
        )
    }

    var selectedFilter by remember { mutableStateOf(BatchEpisodeFilter.UNWATCHED) }
    var preferredQuality by remember { mutableStateOf(BatchPreferredQuality.BEST) }
    var preferSameProvider by remember { mutableStateOf(true) }

    val seasonEpisodes = remember(detail.episodes, activeSeason) {
        BatchEpisodeDownloadPlanner.episodesForSeason(detail.episodes, activeSeason)
    }
    val downloadedLookup = remember(downloads, detail.id, detail.title) {
        BatchEpisodeDownloadPlanner.buildDownloadedLookup(downloads, detail)
    }

    var selectedIds by remember(activeSeason) {
        mutableStateOf(
            BatchEpisodeDownloadPlanner.computeSelectionForFilter(
                seasonEpisodes = seasonEpisodes,
                playbackMap = episodePlaybackMap,
                downloadedLookup = downloadedLookup,
                filter = selectedFilter
            )
        )
    }

    LaunchedEffect(seasonEpisodes, episodePlaybackMap, downloadedLookup, selectedFilter) {
        if (selectedFilter != BatchEpisodeFilter.CUSTOM) {
            selectedIds = BatchEpisodeDownloadPlanner.computeSelectionForFilter(
                seasonEpisodes = seasonEpisodes,
                playbackMap = episodePlaybackMap,
                downloadedLookup = downloadedLookup,
                filter = selectedFilter
            )
        } else {
            val validIds = seasonEpisodes.asSequence()
                .filter { !BatchEpisodeDownloadPlanner.isEpisodeAlreadyDownloaded(it, downloadedLookup) }
                .map { it.id }
                .toSet()
            selectedIds = selectedIds.intersect(validIds)
        }
    }

    val selectedEpisodes = remember(seasonEpisodes, selectedIds) {
        seasonEpisodes.filter { selectedIds.contains(it.id) }
    }

    val sheetProgress = remember { Animatable(0f) }
    val dragOffsetYState = remember { mutableFloatStateOf(0f) }
    var isDismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            sheetProgress.snapTo(1f)
        } else {
            withFrameNanos { }
            sheetProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
            )
        }
    }

    val dismissWithAnim: () -> Unit = {
        if (!isDismissing) {
            isDismissing = true
            coroutineScope.launch {
                if (reduceMotion) {
                    onDismiss()
                } else {
                    sheetProgress.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing)
                    )
                    onDismiss()
                }
            }
        }
    }

    BackHandler { dismissWithAnim() }

    val maxSheetHeight = (configuration.screenHeightDp * 0.84f).dp
    val maxSheetHeightPx = with(density) { maxSheetHeight.toPx() }
    val sheetShape = RoundedCornerShape(
        topStart = NetvioGlassTokens.RadiusSheet,
        topEnd = NetvioGlassTokens.RadiusSheet
    )
    val sheetFill = NetvioGlassTokens.cardFill(isAmoled)
    val fallbackFill = NetvioGlassTokens.fallbackFill(isAmoled)

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = dismissWithAnim
            )
    ) {
        // Dim scrim animated purely in draw phase (zero per-frame recomposition)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val animValue = sheetProgress.value
                    val scrimAlpha = if (reduceMotion) {
                        NetvioGlassTokens.ScrimAlpha
                    } else {
                        (NetvioGlassTokens.ScrimAlpha * (animValue / (250f / 340f)))
                            .coerceIn(0f, NetvioGlassTokens.ScrimAlpha)
                    }
                    if (scrimAlpha > 0f) {
                        drawRect(Color.Black.copy(alpha = scrimAlpha))
                    }
                }
        )

        // Sheet Surface animated purely via GPU graphicsLayer with Haze blur clipped strictly to sheet bounds
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .adaptiveHorizontalSafePadding(includeRailClearance = false)
                .widthIn(max = Dimens.maxSheetWidth)
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .graphicsLayer {
                    val slideDistance = if (size.height > 0f) size.height else maxSheetHeightPx
                    translationY = (1f - sheetProgress.value) * slideDistance + dragOffsetYState.floatValue
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume touch */ }
                )
                .draggable(
                    state = rememberDraggableState { delta ->
                        val current = dragOffsetYState.floatValue
                        val next = current + if (current + delta < 0f) delta * 0.18f else delta
                        dragOffsetYState.floatValue = next.coerceAtLeast(-24f)
                    },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val dragOffset = dragOffsetYState.floatValue
                        val projected = dragOffset + (velocity * 0.15f)
                        if (dragOffset > 120f || (dragOffset > 24f && velocity > 900f) || projected > 220f) {
                            dismissWithAnim()
                        } else {
                            coroutineScope.launch {
                                Animatable(dragOffset).animateTo(
                                    targetValue = 0f,
                                    initialVelocity = velocity,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) {
                                    dragOffsetYState.floatValue = value
                                }
                            }
                        }
                    }
                )
                .clip(sheetShape)
                .then(
                    if (uiBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hazeState != null) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = NetvioGlassTokens.BlurRadius
                            tints = listOf(HazeTint(sheetFill))
                            fallbackTint = HazeTint(fallbackFill)
                        }
                    } else {
                        Modifier.background(fallbackFill)
                    }
                )
                .border(
                    width = NetvioGlassTokens.HairlineWidth,
                    color = NetvioGlassTokens.HairlineBorder,
                    shape = sheetShape
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.28f))
                    )
                }

                // Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 14.dp)
                ) {
                    Text(
                        text = "Download episodes",
                        style = NetvioGlassTokens.HeaderTitle
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${detail.title} · Season $activeSeason (${seasonEpisodes.size} eps)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = NetvioGlassTokens.HeaderSubtitle
                    )
                }

                HorizontalDivider(
                    thickness = NetvioGlassTokens.HairlineWidth,
                    color = NetvioGlassTokens.HairlineDivider
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    // Season Switcher
                    if (availableSeasons.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(end = 8.dp)
                        ) {
                            items(availableSeasons) { season ->
                                BatchOptionPill(
                                    label = "Season $season",
                                    selected = season == activeSeason,
                                    enabled = !batchState.isRunning,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeSeason = season
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Selection Filter + Preferred Quality compact glass controls
                    Text(
                        text = "SELECTION",
                        style = NetvioGlassTokens.SectionHeader
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BatchEpisodeFilter.entries.forEach { filter ->
                            BatchOptionPill(
                                label = filter.label,
                                selected = selectedFilter == filter,
                                enabled = !batchState.isRunning,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedFilter = filter
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "PREFERRED QUALITY",
                        style = NetvioGlassTokens.SectionHeader
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(BatchPreferredQuality.entries) { quality ->
                            BatchOptionPill(
                                label = quality.label,
                                selected = preferredQuality == quality,
                                enabled = !batchState.isRunning,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    preferredQuality = quality
                                }
                            )
                        }
                    }

                    // Prefer Same Provider monochrome glass toggle
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(
                                width = NetvioGlassTokens.HairlineWidth,
                                color = Color.White.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = !batchState.isRunning) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                preferSameProvider = !preferSameProvider
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Prefer same source",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = NetvioGlassTokens.TextWhite96
                                )
                            )
                            Text(
                                text = "Use matching provider across selected episodes",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = NetvioGlassTokens.TextWhite54
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        MonochromeGlassSwitch(
                            checked = preferSameProvider,
                            enabled = !batchState.isRunning
                        )
                    }

                    // Active Batch Resolution Banner
                    AnimatedVisibility(visible = batchState.isRunning || !batchState.statusMessage.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val progressText = if (batchState.isRunning) {
                                val label = batchState.currentEpisodeLabel ?: "Preparing..."
                                val queuedNote = if (batchState.enqueuedCount > 0) " · ${batchState.enqueuedCount} queued" else ""
                                "Resolving $label (${batchState.processedCount}/${batchState.totalCount})$queuedNote"
                            } else {
                                batchState.statusMessage.orEmpty()
                            }
                            Text(
                                text = progressText,
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFeatureSettings = "tnum",
                                    color = NetvioGlassTokens.TextWhite96
                                )
                            )
                            if (batchState.isRunning && batchState.totalCount > 0) {
                                LinearProgressIndicator(
                                    progress = { (batchState.processedCount.toFloat() / batchState.totalCount.toFloat()).coerceIn(0.03f, 1f) },
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.5.dp)
                                        .clip(CircleShape)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(
                        thickness = NetvioGlassTokens.HairlineWidth,
                        color = NetvioGlassTokens.HairlineDivider
                    )

                    // Virtualized Episode Checklist with live download state
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                    ) {
                        items(
                            items = seasonEpisodes,
                            key = { "${it.seasonNumber}_${it.episodeNumber}_${it.id}" }
                        ) { ep ->
                            val playback = episodePlaybackMap[ep.seasonNumber to ep.episodeNumber]
                            val isWatched = BatchEpisodeDownloadPlanner.isEpisodeWatched(playback)
                            val downloadItem = BatchEpisodeDownloadPlanner.findEpisodeDownload(ep, downloadedLookup)
                            val isCompleted = downloadItem?.state == DownloadState.COMPLETED
                            val isActiveOrPaused = downloadItem != null && (
                                downloadItem.state == DownloadState.QUEUED ||
                                    downloadItem.state == DownloadState.DOWNLOADING ||
                                    downloadItem.state == DownloadState.PAUSED
                                )
                            val isSelected = selectedIds.contains(ep.id)

                            BatchEpisodeCheckRow(
                                episode = ep,
                                isSelected = isSelected,
                                isWatched = isWatched,
                                downloadItem = downloadItem,
                                enabled = !batchState.isRunning && !isCompleted && !isActiveOrPaused,
                                onToggle = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedFilter = BatchEpisodeFilter.CUSTOM
                                    selectedIds = if (isSelected) selectedIds - ep.id else selectedIds + ep.id
                                }
                            )
                            HorizontalDivider(
                                thickness = NetvioGlassTokens.HairlineWidth,
                                color = Color.White.copy(alpha = 0.08f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Primary Action CTA
                    val ctaInteraction = remember { MutableInteractionSource() }
                    val ctaPressed by ctaInteraction.collectIsPressedAsState()
                    val ctaScale by animateFloatAsState(
                        targetValue = if (ctaPressed && !reduceMotion) 0.96f else 1f,
                        animationSpec = if (reduceMotion) snap() else spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "batchCtaScale"
                    )
                    val canStart = !batchState.isRunning && selectedEpisodes.isNotEmpty()
                    val ctaContainer = when {
                        batchState.isRunning -> Color.White.copy(alpha = 0.14f)
                        canStart -> Color.White
                        else -> Color.White.copy(alpha = 0.14f)
                    }
                    val ctaContentColor = if (canStart) NetvioGlassTokens.TextDark else Color.White

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .graphicsLayer {
                                scaleX = ctaScale
                                scaleY = ctaScale
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .background(ctaContainer)
                            .then(
                                if (batchState.isRunning || !canStart) {
                                    Modifier.border(
                                        0.5.dp,
                                        Color.White.copy(alpha = 0.18f),
                                        RoundedCornerShape(16.dp)
                                    )
                                } else Modifier
                            )
                            .clickable(
                                interactionSource = ctaInteraction,
                                indication = null,
                                enabled = batchState.isRunning || canStart,
                                role = Role.Button
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                if (batchState.isRunning) {
                                    onCancelBatchDownload()
                                } else {
                                    onStartBatchDownload(selectedEpisodes, preferredQuality, preferSameProvider)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (batchState.isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Cancel Batch (${batchState.processedCount}/${batchState.totalCount})",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFeatureSettings = "tnum",
                                        color = Color.White
                                    )
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.FileDownload,
                                    contentDescription = null,
                                    tint = ctaContentColor.copy(alpha = if (canStart) 1f else 0.55f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (selectedEpisodes.isEmpty()) {
                                        "Select episodes to download"
                                    } else {
                                        "Download ${selectedEpisodes.size} ${if (selectedEpisodes.size == 1) "Episode" else "Episodes"}"
                                    },
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFeatureSettings = "tnum",
                                        color = ctaContentColor.copy(alpha = if (canStart) 1f else 0.55f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonochromeGlassSwitch(
    checked: Boolean,
    enabled: Boolean
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) Color.White else Color.White.copy(alpha = 0.14f),
        label = "switchTrack"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color.Black else Color.White.copy(alpha = 0.85f),
        label = "switchThumb"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
        label = "switchThumbOffset"
    )

    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(CircleShape)
            .background(trackColor.copy(alpha = if (enabled) trackColor.alpha else trackColor.alpha * 0.5f))
            .border(0.5.dp, Color.White.copy(alpha = if (checked) 0.0f else 0.18f), CircleShape),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
private fun BatchOptionPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.07f),
        label = "pillBg"
    )
    val borderColor = if (selected) Color.White else Color.White.copy(alpha = 0.14f)
    val textColor = if (selected) NetvioGlassTokens.TextDark else NetvioGlassTokens.TextWhite82

    Box(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(
                fontFamily = AppFontFamily,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor.copy(alpha = if (enabled) 1f else 0.50f)
            )
        )
    }
}

@Composable
private fun BatchEpisodeCheckRow(
    episode: EpisodeItem,
    isSelected: Boolean,
    isWatched: Boolean,
    downloadItem: DownloadItem?,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val epCode = String.format(Locale.US, "E%02d", episode.episodeNumber)
    val state = downloadItem?.state
    val isCompleted = state == DownloadState.COMPLETED
    val isDownloading = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
    val isPaused = state == DownloadState.PAUSED
    val isFailed = state == DownloadState.FAILED
    val pct = downloadItem?.progressPercentage?.toInt()?.coerceIn(0, 100) ?: 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading status / checkbox glyph
        when {
            isCompleted -> {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF00E676).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            isDownloading -> {
                Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (pct > 0) {
                        CircularProgressIndicator(
                            progress = { (pct / 100f).coerceIn(0.05f, 1f) },
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.18f),
                            strokeCap = StrokeCap.Round
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.18f)
                        )
                    }
                }
            }
            isPaused -> {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Pause,
                        contentDescription = "Paused",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) Color.White else Color.Transparent)
                        .border(
                            width = if (isSelected) 0.dp else 1.25.dp,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = epCode,
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                        color = if (isCompleted) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.72f)
                    )
                )
                Text(
                    text = episode.title.ifBlank { "Episode ${episode.episodeNumber}" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isCompleted) Color.White.copy(alpha = 0.45f) else NetvioGlassTokens.TextWhite96
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        when {
            isCompleted -> {
                Text(
                    text = "Downloaded",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF00E676).copy(alpha = 0.85f)
                    )
                )
            }
            isDownloading -> {
                val statusLabel = when {
                    pct > 0 -> "$pct%"
                    !downloadItem?.speed.isNullOrBlank() -> downloadItem?.speed ?: "Starting..."
                    else -> "Starting..."
                }
                Text(
                    text = statusLabel,
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        color = Color.White.copy(alpha = 0.85f)
                    )
                )
            }
            isPaused -> {
                Text(
                    text = if (pct > 0) "Paused $pct%" else "Paused",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        color = NetvioGlassTokens.TextWhite54
                    )
                )
            }
            isFailed -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = Color(0xFFFF8A80),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "Failed",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFF8A80)
                        )
                    )
                }
            }
            isWatched -> {
                Text(
                    text = "Watched",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = NetvioGlassTokens.TextWhite54
                    )
                )
            }
        }
    }
}
