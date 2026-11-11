package com.netvio.ui.details.sheets

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.core.model.MediaType
import com.netvio.core.streams.AddonStreamGroup
import com.netvio.core.streams.StreamItem
import com.netvio.core.streams.StreamsEmptyStateReason
import com.netvio.core.streams.StreamsRepository
import com.netvio.core.streams.sortedForGroupedDisplay
import com.netvio.ui.details.downloads.DownloadTokens
import com.netvio.ui.theme.AppFontFamily
import com.netvio.ui.theme.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stream picker sheet, opened by long-pressing the Play button (or an episode's
 * play control). Loads the same streams the player would auto-pick, grouped by
 * addon, and hands the tapped stream back so the player plays that exact stream
 * instead of auto-picking one. Follows the DownloadSourcesSheet ModalBottomSheet
 * styling (shape, background, scrim, drag handle).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSelectionSheet(
    detail: MediaDetail,
    episode: EpisodeItem? = null,
    onDismiss: () -> Unit,
    onStreamSelected: (StreamItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current

    val effectiveEpisode = remember(detail, episode) {
        if (detail.type == MediaType.TV_SERIES || detail.type == MediaType.ANIME) {
            episode ?: detail.episodes.firstOrNull() ?: EpisodeItem(
                id = "${detail.id}:1:1",
                title = "Episode 1",
                seasonNumber = 1,
                episodeNumber = 1,
                data = "${detail.id}:1:1"
            )
        } else {
            episode
        }
    }

    val s = effectiveEpisode?.seasonNumber
    val e = effectiveEpisode?.episodeNumber
    val mediaType = if (detail.type == MediaType.TV_SERIES || detail.type == MediaType.ANIME) "tv" else "movie"

    LaunchedEffect(detail.id, s, e) {
        val detailImdb = detail.imdbId
        val resolvedImdb = if (!detailImdb.isNullOrBlank() && detailImdb.startsWith("tt")) {
            detailImdb
        } else {
            withContext(Dispatchers.IO) {
                com.netvio.core.util.TmdbBridge.resolveImdbId(
                    type = detail.type,
                    tmdbId = detail.id
                )
            }
        }
        StreamsRepository.load(
            type = mediaType,
            videoId = detail.id,
            season = s,
            episode = e,
            title = detail.title,
            year = detail.year,
            forceRefresh = false,
            imdbId = resolvedImdb ?: detail.imdbId
        )
    }

    val streamsUiState by StreamsRepository.uiState.collectAsState()

    // Grouped by addon (group key is addonName/addonId), each group sorted best-first.
    // Only playable streams are offered: a direct URL, or a torrent magnet/hash.
    // Same dedupe key as DownloadSourcesSheet.
    val groups = remember(streamsUiState.groups) {
        streamsUiState.groups.mapNotNull { group ->
            val valid = group.streams.filter { stream ->
                stream.playableDirectUrl != null ||
                    (stream.isTorrentStream && !stream.effectiveMagnetOrHash.isNullOrBlank())
            }.sortedForGroupedDisplay()
            val seen = HashSet<String>()
            val deduped = valid.filter { stream ->
                val id = stream.playableDirectUrl ?: stream.effectiveMagnetOrHash ?: stream.url
                    ?: "${stream.name}_${stream.title}_${stream.size}"
                val dedupeKey = "${stream.addonId}_${id}_${stream.quality.orEmpty()}"
                seen.add(dedupeKey)
            }
            if (deduped.isEmpty()) null else group.copy(streams = deduped)
        }
    }
    val hasStreams = groups.any { it.streams.isNotEmpty() }

    val subtitleText = remember(detail, effectiveEpisode) {
        if (detail.type == MediaType.TV_SERIES || detail.type == MediaType.ANIME) {
            val ep = effectiveEpisode
            if (ep != null) {
                "${detail.title} · S${ep.seasonNumber ?: 1}E${ep.episodeNumber ?: 1}"
            } else {
                detail.title
            }
        } else {
            detail.title
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val onRetry = {
        StreamsRepository.load(
            type = mediaType,
            videoId = detail.id,
            season = s,
            episode = e,
            title = detail.title,
            year = detail.year,
            forceRefresh = true,
            imdbId = detail.imdbId
        )
    }

    val onLongClickCopy: (StreamItem) -> Unit = { stream ->
        val link = stream.effectiveMagnetOrHash ?: stream.playableDirectUrl ?: stream.url
        if (!link.isNullOrBlank()) {
            clipboardManager.setText(AnnotatedString(link))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(
            topStart = DownloadTokens.sheetCornerRadius,
            topEnd = DownloadTokens.sheetCornerRadius
        ),
        containerColor = DownloadTokens.sheetBackground,
        scrimColor = DownloadTokens.scrimColor,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            width = DownloadTokens.dragHandleWidth,
                            height = DownloadTokens.dragHandleHeight
                        )
                        .background(
                            color = DownloadTokens.dragHandleColor,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        },
        modifier = modifier
    ) {
        LinkSelectionContent(
            title = subtitleText,
            groups = groups,
            isLoading = streamsUiState.isAnyLoading && !hasStreams,
            isAnyLoading = streamsUiState.isAnyLoading,
            isError = streamsUiState.emptyStateReason == StreamsEmptyStateReason.StreamFetchFailed && !hasStreams,
            emptyReason = streamsUiState.emptyStateReason,
            onStreamSelected = onStreamSelected,
            onLongClick = onLongClickCopy,
            onRetry = onRetry
        )
    }
}

/**
 * Pure sheet body: pinned header plus grouped stream rows, with loading, empty
 * and error states. Kept separate from the loading logic above for previews.
 */
@Composable
fun LinkSelectionContent(
    title: String,
    groups: List<AddonStreamGroup>,
    isLoading: Boolean,
    isAnyLoading: Boolean,
    isError: Boolean,
    emptyReason: StreamsEmptyStateReason?,
    onStreamSelected: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((StreamItem) -> Unit)? = null,
    onRetry: () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val maxSheetHeight = (configuration.screenHeightDp * DownloadTokens.sheetMaxHeightRatio).dp
    val hasStreams = groups.any { it.streams.isNotEmpty() }

    // Best match: fast, reliable, buffer-free playback — NOT highest quality
    // (DownloadTokens.selectPlaybackBestMatch: direct or server-cached first,
    // known size at most 4 GB, smallest file wins; no filter UI). The hero is
    // lifted out of the grouped list and shown once as a card on top; the
    // groups below keep their order, minus the hero.
    val heroStream = remember(groups) {
        DownloadTokens.selectPlaybackBestMatch(groups.flatMap { it.streams })
    }
    val heroKey = remember(heroStream) {
        heroStream?.let { stream ->
            val id = stream.playableDirectUrl ?: stream.effectiveMagnetOrHash ?: stream.url
                ?: "${stream.name}_${stream.title}_${stream.size}"
            "${stream.addonId}_${id}_${stream.quality.orEmpty()}"
        }
    }
    val displayGroups = remember(groups, heroKey) {
        if (heroKey == null) {
            groups
        } else {
            groups.mapNotNull { group ->
                val rest = group.streams.filterNot { stream ->
                    val id = stream.playableDirectUrl ?: stream.effectiveMagnetOrHash ?: stream.url
                        ?: "${stream.name}_${stream.title}_${stream.size}"
                    "${stream.addonId}_${id}_${stream.quality.orEmpty()}" == heroKey
                }
                if (rest.isEmpty()) null else group.copy(streams = rest)
            }
        }
    }

    // Per-addon boxes can be collapsed from the card header. Session-only:
    // nothing is persisted, so every sheet open starts fully expanded.
    var collapsedAddonIds by remember { mutableStateOf(emptySet<String>()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            .padding(
                start = DownloadTokens.contentPaddingHorizontal,
                end = DownloadTokens.contentPaddingHorizontal,
                top = DownloadTokens.contentPaddingTop,
                bottom = DownloadTokens.contentPaddingBottom
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Pinned Header: Line 1 (title · episode), Line 2 ("Choose Stream" 30sp Medium)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = DownloadTokens.textMuted
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Choose Stream",
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 30.sp,
                    lineHeight = 30.sp,
                    letterSpacing = (-0.04).em,
                    color = DownloadTokens.textPrimary
                ),
                modifier = Modifier.semantics { heading() }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = DownloadTokens.textMuted
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Searching for sources...",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = DownloadTokens.textMuted
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            isError -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Couldn't load sources",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                color = DownloadTokens.textMuted
                            ),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Retry",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = DownloadTokens.textPrimary
                            ),
                            modifier = Modifier
                                .pressable(onClick = onRetry)
                                .padding(8.dp)
                        )
                    }
                }
            }
            !hasStreams -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No streams found",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                color = DownloadTokens.textMuted
                            ),
                            textAlign = TextAlign.Center
                        )
                        if (emptyReason == StreamsEmptyStateReason.NoCompatibleAddons ||
                            emptyReason == StreamsEmptyStateReason.NoAddonsInstalled
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No streaming addons are available for this title",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 13.sp,
                                    color = DownloadTokens.textMuted
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    // Best match hero card on top, same as the Downloads sheet.
                    if (heroStream != null) {
                        item(key = "link_hero_slot") {
                            LinkHeroCard(
                                stream = heroStream,
                                onPlayClick = { onStreamSelected(heroStream) },
                                onLongClick = onLongClick?.let { { it(heroStream) } }
                            )
                            Spacer(modifier = Modifier.height(DownloadTokens.heroGapBottom))
                        }
                    }

                    // Each addon gets its own box, managed from the box header (collapse/expand).
                    displayGroups.forEach { group ->
                        item(key = "link_group_${group.addonId}") {
                            val isCollapsed = group.addonId in collapsedAddonIds
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(DownloadTokens.pressedSurface)
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pressable(
                                                onClickLabel = if (isCollapsed) "Expand ${group.addonName}" else "Collapse ${group.addonName}",
                                                onClick = {
                                                    collapsedAddonIds = if (isCollapsed) {
                                                        collapsedAddonIds - group.addonId
                                                    } else {
                                                        collapsedAddonIds + group.addonId
                                                    }
                                                }
                                            )
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${group.addonName} · ${group.streams.size}",
                                            style = TextStyle(
                                                fontFamily = AppFontFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp,
                                                color = DownloadTokens.textPrimary
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = if (isCollapsed) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                                            contentDescription = if (isCollapsed) "Expand ${group.addonName}" else "Collapse ${group.addonName}",
                                            tint = DownloadTokens.textMuted,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    if (!isCollapsed) {
                                        group.streams.forEachIndexed { index, stream ->
                                            LinkStreamRow(
                                                stream = stream,
                                                onClick = { onStreamSelected(stream) },
                                                onLongClick = onLongClick?.let { { it(stream) } }
                                            )
                                            if (index < group.streams.lastIndex) {
                                                HorizontalDivider(
                                                    thickness = DownloadTokens.hairlineHeight,
                                                    color = DownloadTokens.hairline
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                    }

                    // Subtle loading indicator at bottom of list while background scrapers are still running
                    if (isAnyLoading) {
                        item(key = "link_loading_footer") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 1.5.dp,
                                    color = DownloadTokens.textMuted
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Searching for more sources...",
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 12.sp,
                                        color = DownloadTokens.textMuted
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

/**
 * Best-match hero card for the stream picker: the same white editorial card as
 * the Downloads sheet (same ranking rule, same tokens), but the black pill
 * plays the stream instead of downloading it. Long-press copies the link,
 * like the rows below.
 */
@Composable
private fun LinkHeroCard(
    stream: StreamItem,
    onPlayClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val sizeNumeralSp = if (fontScale > 1.15f) 42.sp else 58.sp
    val seedsNumeralSp = if (fontScale > 1.15f) 22.sp else 28.sp

    val parsedSize = remember(stream.size) {
        DownloadTokens.parseStreamSize(stream.size)
    }
    val sourceLabel = stream.sourceName?.takeIf { it.isNotBlank() } ?: stream.addonName
    val accessibilityLabel = "Best match: " + DownloadTokens.getStreamAccessibilityDescription(
        stream = stream,
        displaySize = "${parsedSize.formattedNumeral} ${parsedSize.unit}"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DownloadTokens.heroCornerRadius))
            .background(DownloadTokens.heroSurface)
            .padding(
                horizontal = DownloadTokens.heroPaddingHorizontal,
                vertical = DownloadTokens.heroPaddingVertical
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top row: "Best match" & resolution (Direct) or source name (P2P)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Best match",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = DownloadTokens.onHero
                    )
                )
                if (stream.isTorrentStream) {
                    Text(
                        text = sourceLabel,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            color = DownloadTokens.textMuted
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val res = DownloadTokens.extractResolution(stream.quality, stream.name ?: stream.title) ?: stream.quality
                    if (!res.isNullOrBlank()) {
                        Text(
                            text = res,
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = DownloadTokens.textMuted
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Middle row: size numeral + unit, and seeds count or "Direct"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = parsedSize.formattedNumeral,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = sizeNumeralSp,
                            lineHeight = sizeNumeralSp,
                            letterSpacing = (-0.04).em,
                            fontFeatureSettings = "tnum",
                            color = DownloadTokens.onHero
                        ),
                        modifier = Modifier.alignByBaseline()
                    )
                    Text(
                        text = parsedSize.unit,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp,
                            color = DownloadTokens.textMuted
                        ),
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .alignByBaseline()
                    )
                }

                if (stream.isTorrentStream) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = (stream.seeders ?: 0).toString(),
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = seedsNumeralSp,
                                lineHeight = seedsNumeralSp,
                                letterSpacing = (-0.04).em,
                                fontFeatureSettings = "tnum",
                                color = DownloadTokens.accentCrimson
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "seeds",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                color = DownloadTokens.textMuted
                            )
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Direct",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = seedsNumeralSp,
                                lineHeight = seedsNumeralSp,
                                letterSpacing = (-0.04).em,
                                fontFeatureSettings = "tnum",
                                color = DownloadTokens.onHero
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = sourceLabel,
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                color = DownloadTokens.textMuted
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Play pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DownloadTokens.pillHeight)
                    .clip(RoundedCornerShape(DownloadTokens.pillCornerRadius))
                    .background(DownloadTokens.onHero)
                    .pressable(onClick = onPlayClick, onLongClick = onLongClick)
                    .semantics {
                        this.role = Role.Button
                        this.contentDescription = accessibilityLabel
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = Color.White
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkStreamRow(
    stream: StreamItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val metaParts = buildList {
        stream.quality?.takeIf { it.isNotBlank() }?.let { add(it) }
        stream.size?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (stream.isTorrentStream) {
            stream.seeders?.let { add("$it seeders") }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.streamLabel,
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = DownloadTokens.textPrimary
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = metaParts.joinToString(" • "),
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = DownloadTokens.textMuted
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = DownloadTokens.textMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

private val sampleLinkStreams = listOf(
    StreamItem(
        name = "Example.2024.2160p.HDR.DDP5.1",
        quality = "4K 2160p",
        size = "11.98 GB",
        seeders = 180,
        sourceName = "Torrentio",
        addonName = "Torrentio",
        addonId = "torrentio",
        magnetUrl = "magnet:?xt=urn:btih:samplehash1"
    ),
    StreamItem(
        name = "Example.2024.1080p.Web-DL",
        quality = "1080p",
        size = "4.20 GB",
        seeders = 65,
        sourceName = "Comet",
        addonName = "Comet",
        addonId = "comet",
        magnetUrl = "magnet:?xt=urn:btih:samplehash2"
    ),
    StreamItem(
        name = "Example.2024.1080p.Direct",
        quality = "1080p",
        size = "2.50 GB",
        sourceName = "4KHDHub",
        addonName = "4KHDHub",
        addonId = "4khdhub",
        url = "https://example.com/stream1.mp4"
    )
)

@Preview(name = "Link picker (grouped)", showBackground = true, backgroundColor = 0xFF0B0B0F)
@Composable
fun PreviewLinkSelectionContent() {
    LinkSelectionContent(
        title = "Example · S1E1",
        groups = listOf(
            AddonStreamGroup(
                addonName = "Torrentio",
                addonId = "torrentio",
                streams = sampleLinkStreams.filter { it.addonId == "torrentio" }
            ),
            AddonStreamGroup(
                addonName = "Comet",
                addonId = "comet",
                streams = sampleLinkStreams.filter { it.addonId == "comet" }
            ),
            AddonStreamGroup(
                addonName = "4KHDHub",
                addonId = "4khdhub",
                streams = sampleLinkStreams.filter { it.addonId == "4khdhub" }
            )
        ),
        isLoading = false,
        isAnyLoading = false,
        isError = false,
        emptyReason = null,
        onStreamSelected = {}
    )
}
