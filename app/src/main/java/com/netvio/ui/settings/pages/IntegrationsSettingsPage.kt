package com.netvio.ui.settings.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.netvio.ui.theme.NetvioMotionTokens
import com.netvio.ui.theme.LocalAmoledMode
import kotlinx.coroutines.delay
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netvio.core.sync.trakt.TraktDeviceCodeResponse
import com.netvio.ui.components.NetvioGlassDialog
import com.netvio.ui.settings.components.*
import com.netvio.ui.theme.AppFontFamily
import com.netvio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull

/**
 * Redesigned "Integrations" Settings Screen (formerly "External Integrations").
 *
 * Strict High-Fidelity Design Specifications:
 * - Editorial Top Bar with 40x40dp glass back button & crossfading collapsed title.
 * - Bold 32sp Medium headline ("Integrations").
 * - Removed top "CLOUD & SYNC RADAR" summary box completely; services appear in dedicated sections below.
 * - Section 1: DEBRID CLOUD SERVICES with Custom Glass Toggle (48x28dp).
 *   When ON: smooth 220ms expand of Active Provider popover dropdown and masked API key rows.
 *   Glass inline key editor with Paste, Visibility toggle, and white Save Key pill.
 * - Section 2: TRAKT.TV TRACKING with solid white "Sign in" pill / frosted glass "Disconnect" pill.
 *   Disconnecting confirms via shared Glass Confirmation Dialog. Background scrobbling toggle when linked.
 * - Section 3: SIMKL SYNC with solid white "Connect" pill / frosted glass "Disconnect" pill.
 *   Disconnecting confirms via shared Glass Confirmation Dialog. Simkl scrobbling toggle when linked.
 * - 100% monochrome design language, 98% press scale on all interactive elements.
 */
@Composable
fun IntegrationsSettingsPage(
    onBack: () -> Unit,
    viewModel: IntegrationsSettingsViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val reduceMotion = LocalReduceMotion.current

    val traktToken by viewModel.traktAccessToken.collectAsState()
    val traktUsername by viewModel.traktUsername.collectAsState()
    val traktScrobble by viewModel.traktScrobbleEnabled.collectAsState()
    val traktSyncWatchlist by viewModel.traktSyncWatchlist.collectAsState()
    val traktSyncPlayback by viewModel.traktSyncPlayback.collectAsState()
    val traktSyncHistory by viewModel.traktSyncHistory.collectAsState()
    val traktSyncLists by viewModel.traktSyncLists.collectAsState()

    val simklToken by viewModel.simklAccessToken.collectAsState()
    val simklScrobble by viewModel.simklScrobbleEnabled.collectAsState()
    val simklSyncWatchlist by viewModel.simklSyncWatchlist.collectAsState()
    val simklSyncHistory by viewModel.simklSyncHistory.collectAsState()

    val traktLastSyncTimestamp by viewModel.traktLastSyncTimestamp.collectAsState()
    val simklLastSyncTimestamp by viewModel.simklLastSyncTimestamp.collectAsState()
    val isTraktSyncing by viewModel.isTraktSyncing.collectAsState()
    val isSimklSyncing by viewModel.isSimklSyncing.collectAsState()
    val traktStatusMessage by viewModel.traktStatusMessage.collectAsState()
    val simklStatusMessage by viewModel.simklStatusMessage.collectAsState()
    val traktSyncError by viewModel.traktSyncError.collectAsState()
    val simklSyncError by viewModel.simklSyncError.collectAsState()
    val debridEnabled by viewModel.debridEnabled.collectAsState()
    val activeDebridProvider by viewModel.activeDebridProvider.collectAsState()
    val rdKey by viewModel.realDebridApiKey.collectAsState()
    val adKey by viewModel.allDebridApiKey.collectAsState()
    val tbKey by viewModel.torBoxApiKey.collectAsState()

    var activeKeyEditor by remember { mutableStateOf<String?>(null) }
    var tempKeyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    var showDisconnectTraktDialog by remember { mutableStateOf(false) }
    var showDisconnectSimklDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // Trakt PKCE Connect states
    var showTraktConnectDialog by remember { mutableStateOf(false) }
    var isTraktValidating by remember { mutableStateOf(false) }
    var traktAuthError by remember { mutableStateOf<String?>(null) }

    val openTraktConnect: () -> Unit = {
        traktAuthError = null
        isTraktValidating = false
        showTraktConnectDialog = true
    }

    LaunchedEffect(traktToken, traktUsername) {
        if (traktToken.isNotBlank() || traktUsername.isNotBlank()) {
            showTraktConnectDialog = false
            isTraktValidating = false
        }
    }

    // Simkl Connect states
    var showSimklConnectDialog by remember { mutableStateOf(false) }
    var isSimklValidating by remember { mutableStateOf(false) }
    var simklAuthError by remember { mutableStateOf<String?>(null) }

    val openSimklConnect: () -> Unit = {
        simklAuthError = null
        isSimklValidating = false
        showSimklConnectDialog = true
    }

    LaunchedEffect(simklToken) {
        if (simklToken.isNotBlank()) {
            showSimklConnectDialog = false
            isSimklValidating = false
        }
    }

    val debridOptions = remember {
        listOf(
            PopoverOption("realdebrid", "Real-Debrid", "High-speed multi-hoster & cloud cache"),
            PopoverOption("alldebrid", "AllDebrid", "European premium cloud multi-downloader"),
            PopoverOption("torbox", "TorBox", "Direct cloud seeding & Usenet caching")
        )
    }

    SettingsScaffold(
        title = "Integrations",
        onBack = onBack,
        modifier = modifier
    ) { hazeState ->

        // Section 1: Debrid Cloud Services
        OpenSettingsSectionHeader(title = "Debrid Cloud Services")

        OpenSettingsToggleRow(
            title = "Enable Debrid services",
            subtitle = "Stream high-bitrate torrents directly from cloud caches",
            checked = debridEnabled,
            onCheckedChange = { viewModel.setDebridEnabled(it) },
            hazeState = hazeState
        )

        // Conditional Child Rows when Debrid is enabled (smooth spring expand/collapse)
        AnimatedVisibility(
            visible = debridEnabled,
            enter = if (reduceMotion) fadeIn(snap()) else expandVertically(spring(dampingRatio = 0.82f, stiffness = 380f)) + fadeIn(tween(180)),
            exit = if (reduceMotion) fadeOut(snap()) else shrinkVertically(spring(dampingRatio = 0.82f, stiffness = 380f)) + fadeOut(tween(180))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Active Debrid Provider Selector
                OpenSettingsRow(
                    title = "Active Debrid provider",
                    subtitle = "Primary engine used to resolve cloud torrent streams",
                    trailingContent = {
                        FrostedGlassDropdown(
                            options = debridOptions,
                            selected = activeDebridProvider,
                            onSelected = { viewModel.setActiveDebridProvider(it) },
                            hazeState = hazeState,
                            popoverWidth = 270.dp
                        )
                    }
                )

                // Real-Debrid API Key Row
                OpenSettingsRow(
                    title = "Real-Debrid API key",
                    subtitle = if (rdKey.isNotBlank()) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022${rdKey.takeLast(4)}" else "Not configured",
                    trailingContent = {
                        SettingsPillButton(
                            text = if (activeKeyEditor == "Real-Debrid") "Close" else if (rdKey.isNotBlank()) "Edit" else "Set key",
                            onClick = {
                                if (activeKeyEditor == "Real-Debrid") {
                                    activeKeyEditor = null
                                } else {
                                    activeKeyEditor = "Real-Debrid"
                                    tempKeyInput = rdKey
                                    keyVisible = false
                                }
                            },
                            hazeState = hazeState
                        )
                    }
                )

                // AllDebrid API Key Row
                OpenSettingsRow(
                    title = "AllDebrid API key",
                    subtitle = if (adKey.isNotBlank()) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022${adKey.takeLast(4)}" else "Not configured",
                    trailingContent = {
                        SettingsPillButton(
                            text = if (activeKeyEditor == "AllDebrid") "Close" else if (adKey.isNotBlank()) "Edit" else "Set key",
                            onClick = {
                                if (activeKeyEditor == "AllDebrid") {
                                    activeKeyEditor = null
                                } else {
                                    activeKeyEditor = "AllDebrid"
                                    tempKeyInput = adKey
                                    keyVisible = false
                                }
                            },
                            hazeState = hazeState
                        )
                    }
                )

                // TorBox API Key Row
                OpenSettingsRow(
                    title = "TorBox API key",
                    subtitle = if (tbKey.isNotBlank()) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022${tbKey.takeLast(4)}" else "Not configured",
                    trailingContent = {
                        SettingsPillButton(
                            text = if (activeKeyEditor == "TorBox") "Close" else if (tbKey.isNotBlank()) "Edit" else "Set key",
                            onClick = {
                                if (activeKeyEditor == "TorBox") {
                                    activeKeyEditor = null
                                } else {
                                    activeKeyEditor = "TorBox"
                                    tempKeyInput = tbKey
                                    keyVisible = false
                                }
                            },
                            hazeState = hazeState
                        )
                    }
                )
            }
        }

        // Inline Key Editor Card
        activeKeyEditor?.let { provider ->
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .settingsGlass(
                        shape = RoundedCornerShape(SettingsTokens.RadiusPopover),
                        hazeState = hazeState,
                        blurRadius = 18.dp
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Configure $provider token",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                    color = SettingsTokens.TextWhite96
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Paste Button
                            IconButton(
                                onClick = {
                                    val text = clipboardManager.getText()?.text
                                    if (!text.isNullOrBlank()) {
                                        tempKeyInput = text.trim()
                                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                    tint = SettingsTokens.TextWhite82,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Visibility Toggle
                            IconButton(
                                onClick = { keyVisible = !keyVisible },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Toggle visibility",
                                    tint = SettingsTokens.TextWhite82,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Paste your secret token from your $provider account settings. It will be stored on-device in encrypted DataStore.",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = SettingsTokens.TextWhite54
                        )
                    )

                    // Text Input Field
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(SettingsTokens.RadiusControl))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, RoundedCornerShape(SettingsTokens.RadiusControl))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (tempKeyInput.isEmpty()) {
                            Text(
                                text = "Paste secret API key here...",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 13.sp,
                                    color = SettingsTokens.TextWhite40
                                )
                            )
                        }
                        BasicTextField(
                            value = tempKeyInput,
                            onValueChange = { tempKeyInput = it },
                            singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = Color.White
                            ),
                            cursorBrush = SolidColor(Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Bottom Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsPillButton(
                            text = "Cancel",
                            onClick = { activeKeyEditor = null },
                            hazeState = hazeState
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        SettingsPillButton(
                            text = "Save key",
                            isSolidWhite = true,
                            onClick = {
                                val clean = tempKeyInput.trim()
                                when (provider) {
                                    "Real-Debrid" -> viewModel.setRealDebridKey(clean)
                                    "AllDebrid" -> viewModel.setAllDebridKey(clean)
                                    "TorBox" -> viewModel.setTorBoxKey(clean)
                                }
                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                activeKeyEditor = null
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Section 2: Simkl Watchlist & History (Primary Tracker)
        OpenSettingsSectionHeader(title = "Simkl Watchlist & History")

        val isSimklConnected = simklToken.isNotBlank()
        val simklSyncTelemetry = remember(isSimklConnected, isSimklSyncing, simklStatusMessage, simklLastSyncTimestamp) {
            when {
                !isSimklConnected -> "Automatic episode scrobbling, watchlist sync, and watch history for anime, series, and movies."
                isSimklSyncing -> simklStatusMessage ?: "Syncing watchlist and episode history with Simkl…"
                simklLastSyncTimestamp > 0L ->
                    "Last synced ${formatRelativeSyncTime(simklLastSyncTimestamp)} • Two-way scrobbling and watchlist sync active."
                else -> "Account linked • Ready to sync watchlist and episode history."
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        SimklSyncSectionCard(
            isConnected = isSimklConnected,
            isValidating = isSimklValidating,
            isSyncing = isSimklSyncing,
            capabilitySummary = "Watchlist · Watch history · Anime",
            statusConnectedLabel = "Linked",
            telemetryText = simklSyncTelemetry,
            syncError = if (isSimklConnected) simklSyncError else null,
            hazeState = hazeState,
            onConnectClick = openSimklConnect,
            onSyncNowClick = {
                viewModel.triggerSimklSyncNow()
                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
            },
            onDisconnectClick = { showDisconnectSimklDialog = true }
        )

        AnimatedVisibility(
            visible = isSimklConnected,
            enter = if (reduceMotion) {
                fadeIn(snap())
            } else {
                expandVertically(spring(dampingRatio = 1.0f, stiffness = 440f)) +
                    fadeIn(tween(160, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            },
            exit = if (reduceMotion) {
                fadeOut(snap())
            } else {
                shrinkVertically(spring(dampingRatio = 1.0f, stiffness = 460f)) +
                    fadeOut(tween(120))
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                OpenSettingsToggleRow(
                    title = "Live scrobbling",
                    subtitle = "Mark episodes and movies watched automatically at 80% playback",
                    checked = simklScrobble,
                    onCheckedChange = { viewModel.setSimklScrobble(it) },
                    hazeState = hazeState
                )

                OpenSettingsToggleRow(
                    title = "Plan to Watch",
                    subtitle = "Two-way sync between your Netvio watchlist and Simkl",
                    checked = simklSyncWatchlist,
                    onCheckedChange = { viewModel.setSimklSyncWatchlist(it) },
                    hazeState = hazeState
                )

                OpenSettingsToggleRow(
                    title = "Watch history",
                    subtitle = "Import and export completed series, anime, and movie records",
                    checked = simklSyncHistory,
                    onCheckedChange = { viewModel.setSimklSyncHistory(it) },
                    hazeState = hazeState
                )
            }
        }

        // Section 3: Trakt.tv Tracking
        OpenSettingsSectionHeader(title = "Trakt.tv Tracking")

        val isTraktConnected = traktToken.isNotBlank() || traktUsername.isNotBlank()
        val displayTraktUsername = traktUsername.ifBlank { "Trakt User" }
        val traktSyncTelemetry = remember(isTraktConnected, isTraktSyncing, traktStatusMessage, traktLastSyncTimestamp) {
            when {
                !isTraktConnected -> "Automatic episode scrobbling, two-way watchlist sync, resume points, watch history, and custom lists."
                isTraktSyncing -> traktStatusMessage ?: "Syncing watchlist, resume points, and history with Trakt…"
                traktLastSyncTimestamp > 0L ->
                    "Last synced ${formatRelativeSyncTime(traktLastSyncTimestamp)} • Resume points and scrobbling active."
                else -> "Ready to sync watchlist, resume points, and watch history."
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        TraktSyncSectionCard(
            isConnected = isTraktConnected,
            isValidating = isTraktValidating,
            isSyncing = isTraktSyncing,
            capabilitySummary = "Watchlist · Resume points · History · Lists",
            statusConnectedLabel = "@$displayTraktUsername",
            telemetryText = traktSyncTelemetry,
            syncError = if (isTraktConnected) traktSyncError else null,
            hazeState = hazeState,
            onConnectClick = openTraktConnect,
            onSyncNowClick = {
                viewModel.triggerTraktSyncNow()
                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
            },
            onDisconnectClick = { showDisconnectTraktDialog = true }
        )

        AnimatedVisibility(
            visible = isTraktConnected,
            enter = if (reduceMotion) {
                fadeIn(snap())
            } else {
                expandVertically(spring(dampingRatio = 1.0f, stiffness = 440f)) +
                    fadeIn(tween(160, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            },
            exit = if (reduceMotion) {
                fadeOut(snap())
            } else {
                shrinkVertically(spring(dampingRatio = 1.0f, stiffness = 460f)) +
                    fadeOut(tween(120))
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                OpenSettingsToggleRow(
                    title = "Live scrobbling",
                    subtitle = "Send playback progress & mark watched automatically",
                    checked = traktScrobble,
                    onCheckedChange = { viewModel.setTraktScrobble(it) },
                    hazeState = hazeState
                )

                OpenSettingsToggleRow(
                    title = "Watchlist two-way sync",
                    subtitle = "Keep bookmarks synchronized between Netvio and Trakt",
                    checked = traktSyncWatchlist,
                    onCheckedChange = { viewModel.setTraktSyncWatchlist(it) },
                    hazeState = hazeState
                )

                OpenSettingsToggleRow(
                    title = "Resume points sync",
                    subtitle = "Synchronize Continue Watching progress with Trakt playback",
                    checked = traktSyncPlayback,
                    onCheckedChange = { viewModel.setTraktSyncPlayback(it) },
                    hazeState = hazeState
                )

                OpenSettingsToggleRow(
                    title = "Watch history sync",
                    subtitle = "Sync completed watch history and badges with your profile",
                    checked = traktSyncHistory,
                    onCheckedChange = { viewModel.setTraktSyncHistory(it) },
                    hazeState = hazeState
                )

                OpenSettingsToggleRow(
                    title = "Custom lists & playlists",
                    subtitle = "Sync personal Trakt lists as in-app playlists",
                    checked = traktSyncLists,
                    onCheckedChange = { viewModel.setTraktSyncLists(it) },
                    hazeState = hazeState
                )
            }
        }

        // Trakt Connect Glass Dialog
        if (showTraktConnectDialog) {
            TraktConnectDialog(
                hazeState = hazeState,
                isTraktValidating = isTraktValidating,
                traktAuthError = traktAuthError,
                onDismiss = { showTraktConnectDialog = false },
                onClearError = { traktAuthError = null },
                onConnect = { codeOrUrl ->
                    if (!isTraktValidating) {
                        isTraktValidating = true
                        traktAuthError = null
                        coroutineScope.launch {
                            try {
                                val traktClient = com.netvio.core.sync.trakt.TraktClient.getInstance()
                                val prefs = com.netvio.data.preferences.PreferencesRepository.getInstance(context)
                                val syncVerifier = context.getSharedPreferences("trakt_auth_prefs", android.content.Context.MODE_PRIVATE)
                                    .getString("pending_code_verifier", null)
                                val storedVerifier = prefs.traktCodeVerifier.firstOrNull()?.ifBlank { null }
                                val verifier = syncVerifier ?: storedVerifier ?: traktClient.pendingCodeVerifier

                                val extractedCode = Regex("""code=([^&#]+)""").find(codeOrUrl)?.groupValues?.get(1) ?: codeOrUrl.trim()
                                val exchangeRes = traktClient.exchangeCodeForToken(extractedCode, codeVerifier = verifier)
                                if (exchangeRes.isSuccess) {
                                    val tokenResp = exchangeRes.getOrThrow()
                                    val profile = traktClient.getUserProfile(tokenResp.accessToken).getOrNull()
                                    val username = profile?.username?.ifBlank { "Trakt User" } ?: "Trakt User"
                                    viewModel.setTraktAuth(tokenResp.accessToken, tokenResp.refreshToken, username)
                                    prefs.clearTraktCodeVerifier()
                                    context.getSharedPreferences("trakt_auth_prefs", android.content.Context.MODE_PRIVATE)
                                        .edit().clear().commit()
                                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                    showTraktConnectDialog = false
                                } else {
                                    traktAuthError = exchangeRes.exceptionOrNull()?.message
                                        ?: "Unable to verify Trakt authorization code."
                                }
                            } catch (e: Exception) {
                                traktAuthError = e.message ?: "Unable to verify Trakt authorization code."
                            } finally {
                                isTraktValidating = false
                            }
                        }
                    }
                }
            )
        }

        // Simkl Connect Glass Dialog
        if (showSimklConnectDialog) {
            SimklConnectDialog(
                hazeState = hazeState,
                isSimklValidating = isSimklValidating,
                simklAuthError = simklAuthError,
                onDismiss = { showSimklConnectDialog = false },
                onClearError = { simklAuthError = null },
                onConnect = { codeOrToken ->
                    if (!isSimklValidating) {
                        isSimklValidating = true
                        simklAuthError = null
                        coroutineScope.launch {
                            val simklClient = com.netvio.core.sync.simkl.SimklClient.getInstance()
                            val prefs = com.netvio.data.preferences.PreferencesRepository.getInstance(context)
                            val syncVerifier = context.getSharedPreferences("simkl_auth_prefs", android.content.Context.MODE_PRIVATE)
                                .getString("pending_code_verifier", null)
                            val storedVerifier = prefs.simklCodeVerifier.firstOrNull()?.ifBlank { null }
                            val verifier = syncVerifier ?: storedVerifier ?: simklClient.pendingCodeVerifier

                            val extractedCode = Regex("""code=([^&#]+)""").find(codeOrToken)?.groupValues?.get(1) ?: codeOrToken.trim()
                            val exchangeRes = simklClient.exchangeCodeForToken(extractedCode, codeVerifier = verifier)
                            val resolvedToken = if (exchangeRes.isSuccess && exchangeRes.getOrThrow().accessToken.isNotBlank()) {
                                exchangeRes.getOrThrow().accessToken
                            } else {
                                extractedCode
                            }

                            val profileRes = simklClient.getUserProfile(resolvedToken)
                            isSimklValidating = false
                            if (profileRes.isSuccess) {
                                viewModel.setSimklAuth(resolvedToken)
                                prefs.clearSimklCodeVerifier()
                                context.getSharedPreferences("simkl_auth_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()
                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                showSimklConnectDialog = false
                            } else {
                                simklAuthError = exchangeRes.exceptionOrNull()?.message
                                    ?: profileRes.exceptionOrNull()?.message
                                    ?: "Invalid Simkl authorization code or token."
                            }
                        }
                    }
                }
            )
        }

        // Confirmation Dialog for Trakt Disconnect
        if (showDisconnectTraktDialog) {
            NetvioGlassDialog(
                title = "Disconnect Trakt.tv?",
                message = "Your scrobbling and playback history syncing with @$displayTraktUsername will be paused until you sign in again.",
                confirmText = "Disconnect",
                dismissText = "Cancel",
                icon = Icons.Outlined.LinkOff,
                isDestructive = true,
                hazeState = hazeState,
                onConfirm = {
                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                    viewModel.disconnectTrakt()
                    showDisconnectTraktDialog = false
                },
                onDismiss = { showDisconnectTraktDialog = false }
            )
        }

        // Confirmation Dialog for Simkl Disconnect
        if (showDisconnectSimklDialog) {
            NetvioGlassDialog(
                title = "Disconnect Simkl?",
                message = "Your automated watchlist and episode progress sync with Simkl will be unlinked.",
                confirmText = "Disconnect",
                dismissText = "Cancel",
                icon = Icons.Outlined.LinkOff,
                isDestructive = true,
                hazeState = hazeState,
                onConfirm = {
                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                    viewModel.disconnectSimkl()
                    showDisconnectSimklDialog = false
                },
                onDismiss = { showDisconnectSimklDialog = false }
            )
        }
    }
}

private fun formatRelativeSyncTime(timestamp: Long): String {
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val mins = diff / 60_000L
    return when {
        mins < 1L -> "just now"
        mins < 60L -> "$mins min${if (mins == 1L) "" else "s"} ago"
        else -> {
            val hours = mins / 60L
            "$hours hr${if (hours == 1L) "" else "s"} ago"
        }
    }
}

private data class TrackerCardPalette(
    val amoledFill: Color,
    val lcdFill: Color,
    val borderHighlight: Color,
    val borderAccent: Color,
    val glowPrimary: Color,
    val statusActiveColor: Color,
    val statusSyncingColor: Color,
    val statusBgTint: Color,
    val statusBorderTint: Color,
    val statusLabelConnected: Color,
    val telemetryColor: Color,
    val dividerColor: Color,
    val connectLabel: String
)

private val SimklCardPalette = TrackerCardPalette(
    amoledFill = Color(0xFF05080D).copy(alpha = 0.94f),
    lcdFill = Color(0xFF0C121C).copy(alpha = 0.80f),
    borderHighlight = Color(0xFF7DD3FC),
    borderAccent = Color(0xFF38BDF8),
    glowPrimary = Color(0xFF38BDF8),
    statusActiveColor = Color(0xFF38BDF8),
    statusSyncingColor = Color(0xFF7DD3FC),
    statusBgTint = Color(0xFF38BDF8).copy(alpha = 0.13f),
    statusBorderTint = Color(0xFF7DD3FC).copy(alpha = 0.30f),
    statusLabelConnected = Color(0xFFE0F2FE),
    telemetryColor = Color(0xFFE2F1FA).copy(alpha = 0.78f),
    dividerColor = Color(0xFFBAE6FD).copy(alpha = 0.11f),
    connectLabel = "Connect Simkl"
)

private val TraktCardPalette = TrackerCardPalette(
    amoledFill = Color(0xFF0B0507).copy(alpha = 0.94f),
    lcdFill = Color(0xFF170B0E).copy(alpha = 0.80f),
    borderHighlight = Color(0xFFFF8A8E),
    borderAccent = Color(0xFFED1C24),
    glowPrimary = Color(0xFFED1C24),
    statusActiveColor = Color(0xFFFF5C62),
    statusSyncingColor = Color(0xFFFF9498),
    statusBgTint = Color(0xFFED1C24).copy(alpha = 0.14f),
    statusBorderTint = Color(0xFFFF8A8E).copy(alpha = 0.30f),
    statusLabelConnected = Color(0xFFFFECEE),
    telemetryColor = Color(0xFFFCE7E9).copy(alpha = 0.78f),
    dividerColor = Color(0xFFFECDD3).copy(alpha = 0.11f),
    connectLabel = "Connect Trakt"
)

/**
 * Architectural Frosted-Glass Surface for the Simkl Integration Section.
 */
@Composable
private fun SimklSyncSectionCard(
    isConnected: Boolean,
    isValidating: Boolean,
    isSyncing: Boolean,
    capabilitySummary: String,
    statusConnectedLabel: String,
    telemetryText: String,
    syncError: String? = null,
    hazeState: dev.chrisbanes.haze.HazeState?,
    onConnectClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TrackerSyncSectionCard(
        palette = SimklCardPalette,
        wordmark = { SimklGradientWordmark(height = 19.dp) },
        isConnected = isConnected,
        isValidating = isValidating,
        isSyncing = isSyncing,
        capabilitySummary = capabilitySummary,
        statusConnectedLabel = statusConnectedLabel,
        telemetryText = telemetryText,
        syncError = syncError,
        hazeState = hazeState,
        onConnectClick = onConnectClick,
        onSyncNowClick = onSyncNowClick,
        onDisconnectClick = onDisconnectClick,
        modifier = modifier
    )
}

/**
 * Architectural Frosted-Glass Surface for the Trakt.tv Integration Section.
 */
@Composable
private fun TraktSyncSectionCard(
    isConnected: Boolean,
    isValidating: Boolean,
    isSyncing: Boolean,
    capabilitySummary: String,
    statusConnectedLabel: String,
    telemetryText: String,
    syncError: String? = null,
    hazeState: dev.chrisbanes.haze.HazeState?,
    onConnectClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TrackerSyncSectionCard(
        palette = TraktCardPalette,
        wordmark = { TraktBrandWordmark(emblemSize = 21.dp) },
        isConnected = isConnected,
        isValidating = isValidating,
        isSyncing = isSyncing,
        capabilitySummary = capabilitySummary,
        statusConnectedLabel = statusConnectedLabel,
        telemetryText = telemetryText,
        syncError = syncError,
        hazeState = hazeState,
        onConnectClick = onConnectClick,
        onSyncNowClick = onSyncNowClick,
        onDisconnectClick = onDisconnectClick,
        modifier = modifier
    )
}

/**
 * Shared frosted-glass tracker card for Simkl and Trakt.tv.
 * Keeps unlinked surfaces neutral and reserves brand color for active/syncing status.
 */
@Composable
private fun TrackerSyncSectionCard(
    palette: TrackerCardPalette,
    wordmark: @Composable () -> Unit,
    isConnected: Boolean,
    isValidating: Boolean,
    isSyncing: Boolean,
    capabilitySummary: String,
    statusConnectedLabel: String,
    telemetryText: String,
    syncError: String? = null,
    hazeState: dev.chrisbanes.haze.HazeState?,
    onConnectClick: () -> Unit,
    onSyncNowClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAmoled = LocalAmoledMode.current
    val haptic = LocalHapticFeedback.current
    val cardShape = RoundedCornerShape(SettingsTokens.RadiusPopover)
    val isActiveSurface = isConnected || isSyncing

    val baseFill = if (isActiveSurface) {
        if (isAmoled) palette.amoledFill else palette.lcdFill
    } else {
        if (isAmoled) Color(0xFF07080B).copy(alpha = 0.94f) else Color(0xFF10131A).copy(alpha = 0.78f)
    }

    val specularBorderBrush = remember(isActiveSurface, palette) {
        if (isActiveSurface) {
            Brush.linearGradient(
                0.00f to palette.borderHighlight.copy(alpha = 0.34f),
                0.50f to Color.White.copy(alpha = 0.14f),
                1.00f to palette.borderAccent.copy(alpha = 0.20f)
            )
        } else {
            Brush.linearGradient(
                0.00f to Color.White.copy(alpha = 0.18f),
                1.00f to Color.White.copy(alpha = 0.08f)
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .settingsGlass(
                shape = cardShape,
                hazeState = hazeState,
                blurRadius = 22.dp,
                borderWidth = 0.dp,
                customFill = baseFill
            )
            .then(
                if (isActiveSurface) {
                    Modifier.drawBehind {
                        val glowRadius = size.width * 0.65f
                        drawRect(
                            brush = Brush.radialGradient(
                                0.00f to palette.glowPrimary.copy(alpha = 0.11f),
                                0.65f to palette.glowPrimary.copy(alpha = 0.02f),
                                1.00f to Color.Transparent,
                                center = Offset(size.width * 0.16f, size.height * 0.14f),
                                radius = glowRadius.coerceAtLeast(1f)
                            )
                        )
                    }
                } else {
                    Modifier
                }
            )
            .border(
                width = SettingsTokens.HairlineWidth,
                brush = specularBorderBrush,
                shape = cardShape
            )
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.padding(end = 12.dp)) {
                    wordmark()
                }

                val statusLabel = when {
                    isSyncing -> "Syncing"
                    isConnected -> statusConnectedLabel
                    else -> "Not linked"
                }

                Row(
                    modifier = Modifier
                        .widthIn(max = 172.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActiveSurface) palette.statusBgTint else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            width = SettingsTokens.HairlineWidth,
                            color = if (isActiveSurface) palette.statusBorderTint else SettingsTokens.HairlineBorder,
                            shape = CircleShape
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        isSyncing -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                color = palette.statusSyncingColor,
                                strokeWidth = 1.6.dp
                            )
                        }
                        isConnected -> {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(palette.statusActiveColor)
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .size(6.5.dp)
                                    .border(
                                        width = 1.2.dp,
                                        color = Color.White.copy(alpha = 0.42f),
                                        shape = CircleShape
                                    )
                            )
                        }
                    }

                    Text(
                        text = statusLabel,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.5.sp,
                            lineHeight = 14.sp,
                            color = if (isActiveSurface) {
                                palette.statusLabelConnected
                            } else {
                                Color.White.copy(alpha = 0.62f)
                            }
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!isConnected) {
                    Text(
                        text = capabilitySummary,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Color.White.copy(alpha = 0.84f)
                        )
                    )
                    Text(
                        text = telemetryText,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.5.sp,
                            lineHeight = 17.5.sp,
                            color = Color.White.copy(alpha = 0.54f)
                        )
                    )
                } else {
                    Text(
                        text = telemetryText,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            lineHeight = 18.5.sp,
                            fontFeatureSettings = "tnum",
                            color = palette.telemetryColor
                        )
                    )
                    if (!syncError.isNullOrBlank()) {
                        Text(
                            text = syncError,
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                lineHeight = 16.5.sp,
                                color = Color(0xFFFCA5A5)
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = capabilitySummary,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.5.sp,
                            lineHeight = 15.5.sp,
                            color = Color.White.copy(alpha = 0.46f)
                        )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SettingsTokens.HairlineWidth)
                    .background(
                        if (isActiveSurface) palette.dividerColor else SettingsTokens.HairlineDivider
                    )
            )

            if (!isConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .settingsPressable(
                            enabled = !isValidating,
                            scaleDown = 0.97f,
                            onClick = {
                                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                onConnectClick()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(Color.White, CircleShape)
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = SettingsTokens.TextDark,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = palette.connectLabel,
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    color = SettingsTokens.TextDark
                                )
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .settingsPressable(
                                enabled = !isSyncing,
                                scaleDown = 0.97f,
                                onClick = onSyncNowClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(Color.White, CircleShape)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSyncing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        color = SettingsTokens.TextDark,
                                        strokeWidth = 1.8.dp
                                    )
                                    Text(
                                        text = "Syncing…",
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp,
                                            color = SettingsTokens.TextDark
                                        ),
                                        maxLines = 1
                                    )
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = null,
                                        tint = SettingsTokens.TextDark,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Sync now",
                                        style = TextStyle(
                                            fontFamily = AppFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp,
                                            color = SettingsTokens.TextDark
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .settingsPressable(
                                scaleDown = 0.97f,
                                onClick = {
                                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                    onDisconnectClick()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f), CircleShape)
                                .border(
                                    SettingsTokens.HairlineWidth,
                                    SettingsTokens.HairlineBorder,
                                    CircleShape
                                )
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Disconnect",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.5.sp,
                                    color = SettingsTokens.TextWhite88
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
