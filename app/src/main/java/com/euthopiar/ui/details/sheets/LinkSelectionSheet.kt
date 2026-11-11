package com.euthopiar.ui.details.sheets

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
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
import com.euthopiar.core.model.EpisodeItem
import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.streams.AddonStreamGroup
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.StreamsEmptyStateReason
import com.euthopiar.core.streams.StreamsRepository
import com.euthopiar.core.streams.sortedForGroupedDisplay
import com.euthopiar.ui.details.downloads.DownloadTokens
import com.euthopiar.ui.theme.AppFontFamily
import com.euthopiar.ui.theme.pressable
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
                com.euthopiar.core.util.TmdbBridge.resolveImdbId(
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
                    groups.forEach { group ->
                        item(key = "link_group_header_${group.addonId}") {
                            Text(
                                text = "${group.addonName} · ${group.streams.size}",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = DownloadTokens.textMuted
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 4.dp)
                            )
                        }
                        items(
                            items = group.streams,
                            key = { stream ->
                                val id = stream.playableDirectUrl ?: stream.effectiveMagnetOrHash ?: stream.url
                                    ?: "${stream.name}_${stream.title}_${stream.size}"
                                "${stream.addonId}_${id}_${stream.quality.orEmpty()}"
                            }
                        ) { stream ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                LinkStreamRow(
                                    stream = stream,
                                    onClick = { onStreamSelected(stream) },
                                    onLongClick = onLongClick?.let { { it(stream) } }
                                )
                                HorizontalDivider(
                                    thickness = DownloadTokens.hairlineHeight,
                                    color = DownloadTokens.hairline
                                )
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
