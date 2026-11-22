package com.netvio.ui.settings.pages

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netvio.data.backup.BackupSummary
import com.netvio.data.cache.CacheCategory
import com.netvio.ui.components.NetvioGlassDialog
import com.netvio.ui.settings.components.*
import com.netvio.ui.theme.AppFontFamily
import com.netvio.ui.theme.LocalReduceMotion
import com.netvio.download.DownloadEngine
import com.netvio.download.DownloadRepository
import com.netvio.download.DownloadStorageVolume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper to split bytes into numeral + unit for smooth roll-down animations.
 */
private fun formatBytesSplit(bytes: Long): Pair<String, String> {
    return when {
        bytes <= 0L -> "0.00" to "MB"
        bytes < 1024L -> String.format(Locale.US, "%d", bytes) to "B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f", bytes / 1024f) to "KB"
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f", bytes / (1024f * 1024f)) to "MB"
        else -> String.format(Locale.US, "%.2f", bytes / (1024f * 1024f * 1024f)) to "GB"
    }
}

/**
 * Redesigned "Storage & cache" Settings Screen.
 *
 * Strict High-Fidelity Design Specifications:
 * - Editorial Top Bar with 40x40dp glass back button & crossfading collapsed title.
 * - Bold 32sp Medium headline ("Storage & cache").
 * - Hero Telemetry Block: Large 38sp Medium readout on baseline with tabular figures (`tnum`).
 * - Thin 2dp horizontal progress bar (white 90% cache + white 38% config) with 3dp gap.
 * - Legend with 6dp solid circle and 6dp hollow ring.
 * - Open hairline-divided sections (CACHE MAINTENANCE, DOWNLOADS & OFFLINE STORAGE, CONFIGURATION BACKUP).
 * - Custom Glass Toggle (48x28dp) for Download over Wi-Fi only.
 * - Shared Glass Confirmation Dialog for cache clearing (white Cancel pill on left, glass Clear cache pill on right).
 * - Signature Synchronized Cache-Drain Animation: 550ms numeric countdown + 550ms 2dp bar drain + 400ms track release pulse.
 * - Reduce-motion fallback support.
 */
@Composable
fun StorageSettingsPage(
    onBack: () -> Unit,
    viewModel: StorageSettingsViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    val coroutineScope = rememberCoroutineScope()

    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
    val cacheBreakdown by viewModel.cacheBreakdown.collectAsState()
    val clearingCategory by viewModel.clearingCategory.collectAsState()
    val autoCleanupEnabled by viewModel.autoCleanupEnabled.collectAsState()
    val maxCacheLimitMb by viewModel.maxCacheLimitMb.collectAsState()
    val cacheRetentionDays by viewModel.cacheRetentionDays.collectAsState()
    val autoClearP2pOnExit by viewModel.autoClearP2pOnExit.collectAsState()
    val lastAutoCleanTimestamp by viewModel.lastAutoCleanTimestamp.collectAsState()
    val lastAutoCleanReclaimedBytes by viewModel.lastAutoCleanReclaimedBytes.collectAsState()
    val downloadsSizeBytes by viewModel.downloadsSizeBytes.collectAsState()
    val configSizeBytes by viewModel.configSizeBytes.collectAsState()
    val customDownloadDir by viewModel.customDownloadDir.collectAsState()
    val isCalculating by viewModel.isCalculating.collectAsState()
    val isClearing by viewModel.isClearing.collectAsState()
    val wifiOnlyDownloads by viewModel.wifiOnlyDownloads.collectAsState()

    var showClearConfirmation by remember { mutableStateOf(false) }
    var showDownloadVolumeDialog by remember { mutableStateOf(false) }
    var availableDownloadVolumes by remember { mutableStateOf<List<DownloadStorageVolume>>(emptyList()) }
    var unwritablePathWarning by remember { mutableStateOf<String?>(null) }
    var isDrainAnimating by remember { mutableStateOf(false) }
    var startingDrainBytes by remember { mutableStateOf(0L) }

    val backupUiState by viewModel.backupUiState.collectAsState()
    val isExporting = backupUiState is BackupUiState.Exporting
    val isImporting = backupUiState is BackupUiState.Inspecting || backupUiState is BackupUiState.Restoring

    // Handle toast notification for export completion
    LaunchedEffect(backupUiState) {
        val state = backupUiState
        if (state is BackupUiState.ExportSuccess) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            viewModel.dismissBackupState()
        }
    }

    // Animated float tracking the displayed byte size for the 550ms countdown roll-down
    val animatedByteSize = remember { Animatable(cacheSizeBytes.toFloat().coerceAtLeast(0f)) }

    // Pulse alpha sweep across the 2dp track upon drain completion
    val trackPulseAlpha = remember { Animatable(0f) }

    // Keep animatedByteSize in sync when not in the middle of clearing
    LaunchedEffect(cacheSizeBytes) {
        if (!isDrainAnimating && !isClearing) {
            animatedByteSize.snapTo(cacheSizeBytes.toFloat())
        }
    }

    val (currentNumeral, currentUnit) = remember(animatedByteSize.value, isDrainAnimating) {
        if (isDrainAnimating) {
            val v = animatedByteSize.value
            if (startingDrainBytes >= 1024L * 1024L * 1024L) {
                val gbs = maxOf(0f, v) / (1024f * 1024f * 1024f)
                val unit = if (v <= 0.001f) "MB" else "GB"
                String.format(Locale.US, "%.2f", gbs) to unit
            } else {
                val mbs = maxOf(0f, v) / (1024f * 1024f)
                String.format(Locale.US, "%.2f", mbs) to "MB"
            }
        } else {
            formatBytesSplit(animatedByteSize.value.toLong().coerceAtLeast(0L))
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            val resolvedPath = DownloadEngine.resolveTreeUriToPath(uri)
            coroutineScope.launch {
                val isWritable = viewModel.isDirectoryWritable(resolvedPath)
                if (isWritable) {
                    viewModel.setCustomDownloadDir(resolvedPath)
                    Toast.makeText(context, "Download directory updated", Toast.LENGTH_SHORT).show()
                } else {
                    unwritablePathWarning = resolvedPath
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportConfiguration(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectBackup(uri)
        }
    }

    // Trigger the cache drain sequence
    fun triggerCacheClear() {
        showClearConfirmation = false
        isDrainAnimating = true
        startingDrainBytes = animatedByteSize.value.toLong()

        coroutineScope.launch {
            try {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } catch (_: Exception) {}

            // Await actual IO file deletion
            viewModel.clearCacheSuspend()

            if (reduceMotion) {
                // Immediate update for reduced motion
                animatedByteSize.snapTo(0f)
                isDrainAnimating = false
            } else {
                // Snappy 220ms cubic-bezier roll-down & bar drain
                val drainEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

                launch {
                    animatedByteSize.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 220, easing = drainEasing)
                    )
                }

                delay(220L)

                // Track Release Pulse (200ms sweep shimmer)
                launch {
                    trackPulseAlpha.animateTo(
                        targetValue = 0.20f,
                        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
                    )
                    trackPulseAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
                    )
                }

                delay(200L)
                isDrainAnimating = false
            }

            try {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } catch (_: Exception) {}

            Toast.makeText(context, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScaffold(
        title = "Storage & cache",
        onBack = onBack,
        modifier = modifier
    ) { hazeState ->

        // Hero Telemetry Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 20.dp)
                .drawBehind {
                    // 0.5dp white 14% hairline divider along bottom edge
                    drawLine(
                        color = SettingsTokens.HairlineDivider,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = SettingsTokens.HairlineWidth.toPx()
                    )
                }
        ) {
            // Eyebrow Label: "LOCAL DISK USAGE"
            Text(
                text = "LOCAL DISK USAGE",
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    letterSpacing = (11 * 0.08).sp,
                    color = SettingsTokens.TextWhite45
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Typographic Readout Row on Baseline
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isCalculating && cacheSizeBytes == 0L) "—" else "$currentNumeral $currentUnit",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 38.sp,
                        lineHeight = 38.sp,
                        letterSpacing = (-1.14).sp,
                        fontFeatureSettings = "tnum",
                        color = Color.White
                    )
                )

                Text(
                    text = if (isCalculating && cacheSizeBytes == 0L) "Calculating..." else "Cached",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                        color = SettingsTokens.TextWhite54
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Thin 2dp Horizontal Progress Bar
            val currentCache = animatedByteSize.value.toLong().coerceAtLeast(0L)
            val totalBytesSum = maxOf(currentCache + downloadsSizeBytes + configSizeBytes, 1024L * 1024L * 64L).toFloat()
            val cacheFraction = (currentCache.toFloat() / totalBytesSum).coerceIn(0f, 0.70f)
            val downloadsFraction = (downloadsSizeBytes.toFloat() / totalBytesSum).coerceIn(0f, 0.70f)
            val configFraction = (configSizeBytes.toFloat() / totalBytesSum).coerceIn(0.04f, 0.25f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (cacheFraction > 0.005f) {
                        // Cache Segment (Bright white at 90%)
                        Box(
                            modifier = Modifier
                                .weight(cacheFraction)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.90f))
                        )

                        // 3dp transparent gap between segments
                        Spacer(modifier = Modifier.width(3.dp))
                    }

                    if (downloadsFraction > 0.005f) {
                        // Downloads Segment (Medium white at 60%)
                        Box(
                            modifier = Modifier
                                .weight(downloadsFraction)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.60f))
                        )

                        Spacer(modifier = Modifier.width(3.dp))
                    }

                    // Data & Config Segment (Muted white at 38%)
                    Box(
                        modifier = Modifier
                            .weight(configFraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.38f))
                    )

                    // Remaining Free Space
                    val remainingFraction = (1f - cacheFraction - downloadsFraction - configFraction).coerceAtLeast(0f)
                    if (remainingFraction > 0.005f) {
                        Spacer(modifier = Modifier.weight(remainingFraction))
                    }
                }

                // Shimmer pulse highlight upon drain release
                if (trackPulseAlpha.value > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = trackPulseAlpha.value))
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Legend Row
            val (dlNumeral, dlUnit) = remember(downloadsSizeBytes) { formatBytesSplit(downloadsSizeBytes) }
            val (cfgNumeral, cfgUnit) = remember(configSizeBytes) { formatBytesSplit(configSizeBytes) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Item 1: Cache
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.90f))
                    )
                    Text(
                        text = "Cache · $currentNumeral $currentUnit",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = SettingsTokens.TextWhite54
                        )
                    )
                }

                // Item 2: Downloads
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.60f))
                    )
                    Text(
                        text = "Downloads · $dlNumeral $dlUnit",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = SettingsTokens.TextWhite54
                        )
                    )
                }

                // Item 3: Data & Config
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                    )
                    Text(
                        text = "Config · $cfgNumeral $cfgUnit",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = SettingsTokens.TextWhite54
                        )
                    )
                }
            }
        }

        val retentionOptions = remember {
            listOf(
                PopoverOption(3, "3 Days", "Aggressive cleanup · Best for tight storage"),
                PopoverOption(7, "7 Days", "Recommended · Balances speed and space"),
                PopoverOption(14, "14 Days", "Extended · Keeps recent posters longer"),
                PopoverOption(30, "30 Days", "Minimal pruning · Keeps cache up to 1 month")
            )
        }
        val cacheLimitOptions = remember { listOf(250, 500, 1024, 2048) }

        // Section 1: Smart Auto-Cleanup & Cache Maintenance
        OpenSettingsSectionHeader(title = "Cache Maintenance")

        OpenSettingsToggleRow(
            title = "Automatic cache cleanup",
            subtitle = "Prunes expired video & P2P chunks automatically while keeping posters fast",
            checked = autoCleanupEnabled,
            onCheckedChange = { viewModel.setAutoCleanupEnabled(it) },
            hazeState = hazeState
        )

        // Max Cache Limit Pill Selector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = SettingsTokens.HairlineDivider,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = SettingsTokens.HairlineWidth.toPx()
                    )
                }
                .padding(vertical = 15.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        text = "Max cache limit",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            color = if (autoCleanupEnabled) SettingsTokens.TextWhite96 else SettingsTokens.TextWhite42
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Evicts oldest P2P & stream chunks first when exceeded",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = SettingsTokens.TextWhite54
                        )
                    )
                }

                SettingsMetadataChip(
                    text = if (maxCacheLimitMb >= 1024) "${maxCacheLimitMb / 1024} GB" else "$maxCacheLimitMb MB",
                    hazeState = hazeState
                )
            }

            MonochromeSegmentedPillRow(
                options = cacheLimitOptions,
                selected = maxCacheLimitMb,
                onSelected = { viewModel.setMaxCacheLimitMb(it) },
                hazeState = hazeState,
                labelProvider = { if (it >= 1024) "${it / 1024}G" else "${it}M" },
                isAllowed = { autoCleanupEnabled }
            )
        }

        OpenSettingsRow(
            title = "Auto-delete stale cache after",
            subtitle = if (lastAutoCleanTimestamp > 0L) {
                val (recNum, recUnit) = formatBytesSplit(lastAutoCleanReclaimedBytes)
                val dateStr = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(lastAutoCleanTimestamp))
                "Last auto-clean: $dateStr ($recNum $recUnit freed)"
            } else {
                "Automatically expires unused video & P2P files"
            },
            enabled = autoCleanupEnabled,
            trailingContent = {
                FrostedGlassDropdown(
                    options = retentionOptions,
                    selected = cacheRetentionDays,
                    onSelected = { if (autoCleanupEnabled) viewModel.setCacheRetentionDays(it) },
                    hazeState = hazeState,
                    popoverWidth = 230.dp
                )
            }
        )

        OpenSettingsToggleRow(
            title = "Auto-clear P2P cache on exit",
            subtitle = "Frees BitTorrent stream chunks right after leaving playback",
            checked = autoClearP2pOnExit,
            onCheckedChange = { viewModel.setAutoClearP2pOnExit(it) },
            hazeState = hazeState
        )

        val isCacheEmpty = animatedByteSize.value <= 0f && !isDrainAnimating && !isCalculating
        OpenSettingsRow(
            title = "Clear all cache",
            subtitle = "Frees artwork, video buffers, P2P pieces & HTTP temp files",
            trailingContent = {
                val pillText = when {
                    isDrainAnimating || isClearing || isCalculating -> ""
                    isCacheEmpty -> "Cleared"
                    else -> "Clear all"
                }

                SettingsPillButton(
                    text = pillText,
                    onClick = {
                        if (!isCacheEmpty && !isDrainAnimating && !isCalculating) {
                            showClearConfirmation = true
                        }
                    },
                    isLoading = isDrainAnimating || isClearing || isCalculating,
                    enabled = !isCacheEmpty && !isDrainAnimating && !isCalculating,
                    hazeState = hazeState
                )
            }
        )

        // Section 1B: Cache Breakdown by User Category
        OpenSettingsSectionHeader(title = "Cache Breakdown")

        val categoryEntries = remember {
            listOf(
                Triple(CacheCategory.IMAGES, "Images & artwork", "Cached posters, backdrops & logos"),
                Triple(CacheCategory.VIDEO_BUFFERS, "Video & stream buffers", "Temporary playback & seek segments"),
                Triple(CacheCategory.P2P_TORRENT, "P2P torrent cache", "Cached BitTorrent piece data"),
                Triple(CacheCategory.METADATA_AND_TEMP, "Metadata, updates & temp", "TMDB API cache & old APK installers")
            )
        }
        categoryEntries.forEach { (category, label, desc) ->
            val catBytes = cacheBreakdown.bytesFor(category)
            val (catNum, catUnit) = formatBytesSplit(catBytes)
            val isCatClearing = clearingCategory == category
            val isCatEmpty = catBytes <= 0L && !isCatClearing && !isCalculating
            OpenSettingsRow(
                title = label,
                subtitle = "$desc · $catNum $catUnit",
                trailingContent = {
                    SettingsPillButton(
                        text = if (isCatClearing || isCalculating) "" else if (isCatEmpty) "Empty" else "Clear",
                        onClick = {
                            viewModel.clearCacheCategory(category) { reclaimed ->
                                val (rNum, rUnit) = formatBytesSplit(reclaimed)
                                Toast.makeText(context, "Cleared $label ($rNum $rUnit)", Toast.LENGTH_SHORT).show()
                            }
                        },
                        isLoading = isCatClearing || isCalculating,
                        enabled = !isCatEmpty && !isCatClearing && !isClearing && !isCalculating,
                        hazeState = hazeState
                    )
                }
            )
        }

        // Section 2: Downloads & Offline Storage
        OpenSettingsSectionHeader(title = "Downloads & Offline Storage")

        OpenSettingsToggleRow(
            title = "Download over Wi-Fi only",
            subtitle = "Prevents downloading large media tracks over cellular data",
            checked = wifiOnlyDownloads,
            onCheckedChange = { viewModel.setWifiOnlyDownloads(it) },
            hazeState = hazeState
        )

        val isCustomDownloadDirSet = !customDownloadDir.isNullOrBlank()
        val displayDownloadDir = if (isCustomDownloadDirSet) customDownloadDir!! else viewModel.defaultDownloadDir

        OpenSettingsRow(
            title = "Download directory",
            subtitle = if (isCustomDownloadDirSet) "Custom: $displayDownloadDir" else "Default: $displayDownloadDir",
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCustomDownloadDirSet) {
                        SettingsPillButton(
                            text = "Reset",
                            onClick = {
                                viewModel.setCustomDownloadDir(null)
                                Toast.makeText(context, "Reset to default download location", Toast.LENGTH_SHORT).show()
                            },
                            hazeState = hazeState
                        )
                    }
                    SettingsPillButton(
                        text = "Change",
                        onClick = {
                            coroutineScope.launch {
                                availableDownloadVolumes = viewModel.getAvailableDownloadVolumes()
                                showDownloadVolumeDialog = true
                            }
                        },
                        hazeState = hazeState
                    )
                }
            }
        )

        // Section 3: Configuration Backup
        OpenSettingsSectionHeader(title = "Configuration Backup")

        OpenSettingsRow(
            title = "Export configuration",
            subtitle = "Save preferences, user profiles, and repository configurations to JSON",
            enabled = !isExporting && !isImporting,
            onClick = {
                if (!isExporting && !isImporting) {
                    val timestamp = System.currentTimeMillis() / 1000
                    exportLauncher.launch("netvio_backup_$timestamp.json")
                }
            },
            trailingContent = {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 1.7.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = SettingsTokens.TextWhite42,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        )

        OpenSettingsRow(
            title = "Import configuration",
            subtitle = "Restore app layout, user profiles, and repository sources from backup file",
            enabled = !isImporting && !isExporting,
            onClick = {
                if (!isImporting && !isExporting) {
                    importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                }
            },
            trailingContent = {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 1.7.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = SettingsTokens.TextWhite42,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        )

        // Shared Glass Confirmation Dialog for Cache Clear
        if (showClearConfirmation) {
            NetvioGlassDialog(
                title = "Clear all cache?",
                message = "This will free $currentNumeral $currentUnit of cached artwork, video buffers, P2P pieces, and temporary files. Your offline downloads and settings stay intact.",
                confirmText = "Clear all",
                dismissText = "Cancel",
                icon = Icons.Outlined.CleaningServices,
                onConfirm = { triggerCacheClear() },
                onDismiss = { showClearConfirmation = false }
            )
        }

        if (showDownloadVolumeDialog) {
            val activeDirPath = customDownloadDir ?: viewModel.defaultDownloadDir
            NetvioGlassDialog(
                title = "Download location",
                message = "Choose a verified app storage volume so direct, HLS, and torrent downloads save cleanly.",
                confirmText = "Browse Folder",
                dismissText = "Cancel",
                icon = Icons.Outlined.CheckCircleOutline,
                itemPreview = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableDownloadVolumes.forEach { volume ->
                            val isSelected = activeDirPath == volume.directory.absolutePath ||
                                (customDownloadDir.isNullOrBlank() && volume.isDefault)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) Color.White.copy(alpha = 0.12f)
                                        else Color.White.copy(alpha = 0.05f)
                                    )
                                    .border(
                                        width = if (isSelected) 1.dp else SettingsTokens.HairlineWidth,
                                        color = if (isSelected) SettingsTokens.HairlineBorderBright
                                        else SettingsTokens.HairlineBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        val nextDir = if (volume.isDefault) null else volume.directory.absolutePath
                                        viewModel.setCustomDownloadDir(nextDir)
                                        showDownloadVolumeDialog = false
                                        Toast.makeText(
                                            context,
                                            "Download storage set to ${volume.label}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = volume.label,
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                    )
                                    Text(
                                        text = "${DownloadRepository.formatBytes(volume.availableBytes)} free",
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontSize = 11.sp,
                                            color = SettingsTokens.TextWhite54,
                                            fontFeatureSettings = "tnum"
                                        )
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircleOutline,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                onConfirm = {
                    showDownloadVolumeDialog = false
                    folderPickerLauncher.launch(null)
                },
                onDismiss = { showDownloadVolumeDialog = false }
            )
        }

        if (unwritablePathWarning != null) {
            NetvioGlassDialog(
                title = "Folder not supported",
                message = "Android restricts direct file and torrent writes to \"$unwritablePathWarning\".\n\nPlease select an app storage volume (Internal Storage or SD Card) instead.",
                confirmText = "Choose App Storage",
                dismissText = "Dismiss",
                icon = Icons.Outlined.ErrorOutline,
                onConfirm = {
                    unwritablePathWarning = null
                    coroutineScope.launch {
                        availableDownloadVolumes = viewModel.getAvailableDownloadVolumes()
                        showDownloadVolumeDialog = true
                    }
                },
                onDismiss = { unwritablePathWarning = null }
            )
        }

        // Configuration Restore Confirmation Dialog with Glass Inspection Preview Card
        when (val state = backupUiState) {
            is BackupUiState.ReadyToRestore -> {
                NetvioGlassDialog(
                    title = "Restore configuration?",
                    message = "Restoring this backup will replace current settings, profiles, and repository configurations.",
                    confirmText = "Restore backup",
                    dismissText = "Cancel",
                    icon = Icons.Outlined.SettingsBackupRestore,
                    itemPreview = {
                        BackupInspectionPreviewCard(state.inspection.summary)
                    },
                    onConfirm = {
                        viewModel.confirmRestore(state.inspection)
                    },
                    onDismiss = {
                        viewModel.dismissBackupState()
                    }
                )
            }
            is BackupUiState.RestoreSuccess -> {
                NetvioGlassDialog(
                    title = "Backup restored",
                    message = "Configuration has been restored successfully. Restart Netvio now to apply all changes.",
                    confirmText = "Restart now",
                    dismissText = "Later",
                    icon = Icons.Outlined.CheckCircleOutline,
                    onConfirm = {
                        restartApp(context)
                    },
                    onDismiss = {
                        viewModel.dismissBackupState()
                    }
                )
            }
            is BackupUiState.RestoreError -> {
                NetvioGlassDialog(
                    title = "Restore failed",
                    message = state.message,
                    confirmText = "Dismiss",
                    dismissText = null,
                    icon = Icons.Outlined.ErrorOutline,
                    onConfirm = {
                        viewModel.dismissBackupState()
                    }
                )
            }
            is BackupUiState.ExportError -> {
                NetvioGlassDialog(
                    title = "Export failed",
                    message = state.message,
                    confirmText = "Dismiss",
                    dismissText = null,
                    icon = Icons.Outlined.ErrorOutline,
                    onConfirm = {
                        viewModel.dismissBackupState()
                    }
                )
            }
            else -> {}
        }
    }
}

/**
 * Editorial Frosted-Glass Card displaying metadata of a verified configuration snapshot before restore.
 */
@Composable
private fun BackupInspectionPreviewCard(summary: BackupSummary?) {
    if (summary == null) return

    val formattedDate = remember(summary.exportedAt) {
        if (summary.exportedAt > 0L) {
            val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
            sdf.format(Date(summary.exportedAt))
        } else {
            "Unknown date"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val headerTitle = if (!summary.deviceModel.isNullOrBlank()) {
                "BACKUP SNAPSHOT · ${summary.deviceModel.uppercase()}"
            } else {
                "BACKUP SNAPSHOT"
            }
            Text(
                text = headerTitle,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.12.em,
                    color = Color.White.copy(alpha = 0.45f)
                ),
                maxLines = 1
            )
            Text(
                text = formattedDate,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.65f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.10f))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            BackupStatItem(label = "Settings", count = summary.preferencesCount)
            BackupStatItem(label = "Profiles", count = summary.profilesCount)
            BackupStatItem(label = "Sources", count = summary.repositoriesCount)
            BackupStatItem(label = "Addons", count = summary.addonsCount)
        }

        if (summary.playlistsCount > 0 || summary.savedContentCount > 0 || summary.historyCount > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                BackupStatItem(label = "Playlists", count = summary.playlistsCount)
                BackupStatItem(label = "Saved", count = summary.savedContentCount)
                BackupStatItem(label = "History", count = summary.historyCount)
            }
        }
    }
}

@Composable
private fun BackupStatItem(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.50f)
            )
        )
    }
}

/**
 * Clean application restart trigger recreating the task cleanly.
 */
private fun restartApp(context: android.content.Context) {
    try {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        val restartIntent = Intent.makeRestartActivityTask(componentName)
        context.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    } catch (e: Exception) {
        android.util.Log.e("StorageSettings", "Failed to restart app: ${e.message}", e)
    }
}
