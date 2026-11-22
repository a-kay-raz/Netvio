package com.netvio.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netvio.data.tv.TvCompanionManager
import com.netvio.data.tv.TvConnectionState
import com.netvio.data.tv.TvHandoffPayload
import com.netvio.data.tv.TvRemoteCommand
import com.netvio.ui.components.AppBottomSheet
import com.netvio.ui.components.LocalSnackbarController
import com.netvio.ui.player.formatTimecode
import com.netvio.ui.settings.components.SettingsTokens
import com.netvio.ui.settings.plugins.pluginsPressable
import com.netvio.ui.theme.AppFontFamily
import com.netvio.ui.theme.NetvioGlassTokens
import com.netvio.ui.theme.LocalAmoledMode
import com.netvio.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvCompanionSheet(
    pendingHandoff: TvHandoffPayload? = null,
    onHandoffSent: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val snackbarController = LocalSnackbarController.current

    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            TvCompanionPanel(
                pendingHandoff = pendingHandoff,
                onHandoffSent = {
                    onHandoffSent?.invoke()
                },
                onShowMessage = { msg -> snackbarController.show(msg) }
            )
        }
    }
}

@Composable
fun TvCompanionPanel(
    pendingHandoff: TvHandoffPayload? = null,
    onHandoffSent: (() -> Unit)? = null,
    onShowMessage: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isAmoled = LocalAmoledMode.current
    val reduceMotion = LocalReduceMotion.current
    val coroutineScope = rememberCoroutineScope()

    val tvManager = remember { TvCompanionManager.getInstance(context) }
    val connectionState by tvManager.connectionState.collectAsState()
    val discoveredTvs by tvManager.discoveredTvs.collectAsState()
    val connectedTv by tvManager.connectedTv.collectAsState()
    val savedTv by tvManager.savedTv.collectAsState()
    val nowPlaying by tvManager.nowPlaying.collectAsState()
    val isDiscovering by tvManager.isDiscovering.collectAsState()
    val lastError by tvManager.lastError.collectAsState()

    var hostInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }
    var keyboardText by remember { mutableStateOf("") }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var isActionError by remember { mutableStateOf(false) }
    var isSendingHandoff by remember { mutableStateOf(false) }
    var isPushingConfig by remember { mutableStateOf(false) }

    LaunchedEffect(savedTv) {
        if (hostInput.isBlank() && savedTv != null) {
            hostInput = "${savedTv!!.host}:${savedTv!!.port}"
        }
    }

    LaunchedEffect(Unit) {
        if (connectionState == TvConnectionState.DISCONNECTED) {
            tvManager.refreshDiscovery()
        }
    }

    val panelBg = if (isAmoled) Color(0xFF07080B).copy(alpha = 0.94f) else Color(0xFF10131A).copy(alpha = 0.80f)
    val panelShape = RoundedCornerShape(SettingsTokens.RadiusPopover)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (connectionState != TvConnectionState.CONNECTED || connectedTv == null) {
            // Header row + mDNS refresh
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "TV",
                        style = NetvioGlassTokens.HeaderTitle
                    )
                    Text(
                        text = "Pair on the same Wi-Fi network for instant handoff and remote control",
                        style = NetvioGlassTokens.HeaderSubtitle
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pluginsPressable(
                            enabled = !isDiscovering,
                            scaleDown = 0.95f,
                            onClick = { tvManager.refreshDiscovery() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 1.8.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Scan for TV",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (savedTv != null) {
                val saved = savedTv!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(panelShape)
                        .background(panelBg, panelShape)
                        .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, panelShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tv,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = saved.name,
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = NetvioGlassTokens.TextWhite96
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${saved.host}:${saved.port}",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontFeatureSettings = "tnum",
                                    color = NetvioGlassTokens.TextWhite54
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(CircleShape)
                                .background(Color.White, CircleShape)
                                .pluginsPressable(
                                    enabled = connectionState != TvConnectionState.CONNECTING,
                                    scaleDown = 0.97f,
                                    onClick = {
                                        coroutineScope.launch {
                                            actionFeedback = null
                                            val res = tvManager.reconnectSavedTv()
                                            if (res.isSuccess && pendingHandoff != null) {
                                                val handoffRes = tvManager.playOnTv(pendingHandoff)
                                                handoffRes.fold(
                                                    onSuccess = {
                                                        isActionError = false
                                                        actionFeedback = "Playing \"${pendingHandoff.title}\" on ${saved.name}"
                                                        onShowMessage?.invoke(actionFeedback!!)
                                                        onHandoffSent?.invoke()
                                                    },
                                                    onFailure = { err ->
                                                        isActionError = true
                                                        actionFeedback = err.message
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (connectionState == TvConnectionState.CONNECTING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = SettingsTokens.TextDark,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (pendingHandoff != null) "Reconnect & Play" else "Reconnect",
                                    style = NetvioGlassTokens.PrimaryButtonText,
                                    maxLines = 1
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f), CircleShape)
                                .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, CircleShape)
                                .pluginsPressable(
                                    scaleDown = 0.97f,
                                    onClick = { tvManager.disconnect(forgetSaved = true) }
                                )
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Forget",
                                style = NetvioGlassTokens.SecondaryButtonText
                            )
                        }
                    }
                }
            }

            // Discovered TVs on LAN
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(panelBg, panelShape)
                    .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, panelShape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Nearby TVs on Wi-Fi",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = NetvioGlassTokens.TextWhite96
                    )
                )

                if (discoveredTvs.isEmpty()) {
                    Text(
                        text = if (isDiscovering) {
                            "Scanning Wi-Fi for TV…"
                        } else {
                            "No TV detected automatically. Enter the IP address and 6-digit PIN shown on your TV."
                        },
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = NetvioGlassTokens.TextWhite54
                        )
                    )
                } else {
                    discoveredTvs.forEachIndexed { idx, tv ->
                        if (idx > 0) {
                            HorizontalDivider(
                                thickness = SettingsTokens.HairlineWidth,
                                color = SettingsTokens.HairlineDivider
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .pluginsPressable(
                                    scaleDown = 0.98f,
                                    onClick = {
                                        hostInput = "${tv.host}:${tv.port}"
                                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                                    }
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tv,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tv.name,
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = NetvioGlassTokens.TextWhite96
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${tv.host}:${tv.port}",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontFeatureSettings = "tnum",
                                        color = NetvioGlassTokens.TextWhite54
                                    )
                                )
                            }
                            Text(
                                text = if (hostInput == "${tv.host}:${tv.port}") "Selected" else "Select",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // Manual IP & PIN pairing card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(panelBg, panelShape)
                    .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, panelShape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Pair with TV PIN",
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = NetvioGlassTokens.TextWhite96
                    )
                )

                TvCompanionInputField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    placeholder = "TV IP address (e.g. 192.168.1.42:47831)",
                    keyboardType = KeyboardType.Uri
                )

                TvCompanionInputField(
                    value = pinInput,
                    onValueChange = { next -> pinInput = next.filter { !it.isWhitespace() }.take(8) },
                    placeholder = "6-digit pairing PIN from TV",
                    keyboardType = KeyboardType.Number,
                    useMonospace = true
                )

                val canPair = hostInput.isNotBlank() && pinInput.isNotBlank() && connectionState != TvConnectionState.CONNECTING
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(CircleShape)
                        .background(if (canPair) Color.White else Color.White.copy(alpha = 0.14f), CircleShape)
                        .pluginsPressable(
                            enabled = canPair,
                            scaleDown = 0.97f,
                            role = Role.Button,
                            onClick = {
                                coroutineScope.launch {
                                    actionFeedback = null
                                    val pairRes = tvManager.pairWithTv(hostInput, pinInput)
                                    pairRes.fold(
                                        onSuccess = { tv ->
                                            pinInput = ""
                                            isActionError = false
                                            try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                            if (pendingHandoff != null) {
                                                val playRes = tvManager.playOnTv(pendingHandoff)
                                                playRes.fold(
                                                    onSuccess = {
                                                        isActionError = false
                                                        actionFeedback = "Paired and playing on ${tv.name}"
                                                        onShowMessage?.invoke(actionFeedback!!)
                                                        onHandoffSent?.invoke()
                                                    },
                                                    onFailure = { err ->
                                                        isActionError = true
                                                        actionFeedback = err.message
                                                    }
                                                )
                                            } else {
                                                actionFeedback = "Paired with ${tv.name}"
                                                onShowMessage?.invoke(actionFeedback!!)
                                            }
                                        },
                                        onFailure = { err ->
                                            isActionError = true
                                            actionFeedback = err.message ?: "Pairing failed"
                                        }
                                    )
                                }
                            }
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (connectionState == TvConnectionState.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = SettingsTokens.TextDark,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (pendingHandoff != null) "Pair & Play on TV" else "Pair TV",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.5.sp,
                                color = if (canPair) SettingsTokens.TextDark else NetvioGlassTokens.TextWhite42
                            )
                        )
                    }
                }
            }
        } else {
            val tv = connectedTv!!

            // Connected TV Status Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(panelBg, panelShape)
                    .border(SettingsTokens.HairlineWidth, Color.White.copy(alpha = 0.22f), panelShape)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tv.name,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.5.sp,
                            color = NetvioGlassTokens.TextWhite96
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${tv.host}:${tv.port} · Connected",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum",
                            color = NetvioGlassTokens.TextWhite54
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, CircleShape)
                        .pluginsPressable(
                            scaleDown = 0.96f,
                            onClick = { tvManager.disconnect(forgetSaved = false) }
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Disconnect",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.5.sp,
                            color = SettingsTokens.TextWhite88
                        )
                    )
                }
            }

            // Prominent Handoff CTA when launched from Details or Player
            if (pendingHandoff != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(CircleShape)
                        .background(Color.White, CircleShape)
                        .pluginsPressable(
                            enabled = !isSendingHandoff,
                            scaleDown = 0.97f,
                            role = Role.Button,
                            onClick = {
                                coroutineScope.launch {
                                    isSendingHandoff = true
                                    actionFeedback = null
                                    val res = tvManager.playOnTv(pendingHandoff)
                                    isSendingHandoff = false
                                    res.fold(
                                        onSuccess = {
                                            isActionError = false
                                            try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                            actionFeedback = "Sent \"${pendingHandoff.title}\" to ${tv.name}"
                                            onShowMessage?.invoke(actionFeedback!!)
                                            onHandoffSent?.invoke()
                                        },
                                        onFailure = { err ->
                                            isActionError = true
                                            actionFeedback = err.message ?: "Unable to start playback on TV"
                                        }
                                    )
                                }
                            }
                        )
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSendingHandoff) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            color = SettingsTokens.TextDark,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CastConnected,
                                contentDescription = null,
                                tint = SettingsTokens.TextDark,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Play \"${pendingHandoff.title}\" on TV",
                                style = NetvioGlassTokens.PrimaryButtonText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Now Playing & Playback Transport Remote
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(panelBg, panelShape)
                    .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, panelShape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val activeTitle = nowPlaying?.title?.takeIf { it.isNotBlank() }
                if (activeTitle != null) {
                    val np = nowPlaying!!
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = activeTitle,
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = NetvioGlassTokens.TextWhite96
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (np.seasonNumber != null && np.episodeNumber != null) {
                            val epLabel = np.episodeTitle?.takeIf { it.isNotBlank() }
                            Text(
                                text = buildString {
                                    append("S${np.seasonNumber} · E${np.episodeNumber}")
                                    if (epLabel != null) append("  $epLabel")
                                },
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontSize = 12.5.sp,
                                    color = NetvioGlassTokens.TextWhite54
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (np.durationMs > 0L) {
                            val fraction = (np.positionMs.toFloat() / np.durationMs.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { fraction },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatTimecode(np.positionMs),
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontSize = 11.5.sp,
                                        fontFeatureSettings = "tnum",
                                        color = NetvioGlassTokens.TextWhite54
                                    )
                                )
                                Text(
                                    text = formatTimecode(np.durationMs),
                                    style = TextStyle(
                                        fontFamily = AppFontFamily,
                                        fontSize = 11.5.sp,
                                        fontFeatureSettings = "tnum",
                                        color = NetvioGlassTokens.TextWhite54
                                    )
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Nothing actively playing on TV",
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontSize = 13.sp,
                            color = NetvioGlassTokens.TextWhite54
                        )
                    )
                }

                // Primary transport row (-10s / Play-Pause / +10s)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RemoteIconPill(
                        icon = Icons.Outlined.Replay10,
                        contentDescription = "Rewind 10 seconds",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("seek_relative", valueMs = -10_000L)) }
                    )

                    val isTvPlaying = nowPlaying?.isPlaying == true
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White, CircleShape)
                            .pluginsPressable(
                                scaleDown = 0.94f,
                                role = Role.Button,
                                onClick = {
                                    tvManager.sendCommandAsync(
                                        TvRemoteCommand(if (isTvPlaying) "pause" else "play")
                                    )
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isTvPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (isTvPlaying) "Pause TV" else "Play TV",
                            tint = SettingsTokens.TextDark,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    RemoteIconPill(
                        icon = Icons.Outlined.Forward10,
                        contentDescription = "Forward 10 seconds",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("seek_relative", valueMs = 10_000L)) }
                    )
                }

                // Episode & Stop controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RemoteActionPill(
                        icon = Icons.Outlined.SkipPrevious,
                        label = "Prev ep",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("prev_episode")) },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteActionPill(
                        icon = Icons.Outlined.Stop,
                        label = "Stop",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("stop")) },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteActionPill(
                        icon = Icons.Outlined.SkipNext,
                        label = "Next ep",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("next_episode")) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Volume controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RemoteActionPill(
                        icon = Icons.Outlined.VolumeDown,
                        label = "Vol -",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("volume_down")) },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteActionPill(
                        icon = Icons.Outlined.VolumeOff,
                        label = "Mute",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("volume_mute")) },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteActionPill(
                        icon = Icons.Outlined.VolumeUp,
                        label = "Vol +",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("volume_up")) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // D-Pad + System Keys
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(panelBg, panelShape)
                    .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, panelShape)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RemoteIconPill(
                    icon = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "D-Pad Up",
                    onClick = { tvManager.sendCommandAsync(TvRemoteCommand("dpad_up")) }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RemoteIconPill(
                        icon = Icons.Outlined.KeyboardArrowLeft,
                        contentDescription = "D-Pad Left",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("dpad_left")) }
                    )
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White, CircleShape)
                            .pluginsPressable(
                                scaleDown = 0.94f,
                                onClick = { tvManager.sendCommandAsync(TvRemoteCommand("dpad_center")) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "OK",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = SettingsTokens.TextDark
                            )
                        )
                    }
                    RemoteIconPill(
                        icon = Icons.Outlined.KeyboardArrowRight,
                        contentDescription = "D-Pad Right",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("dpad_right")) }
                    )
                }
                RemoteIconPill(
                    icon = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "D-Pad Down",
                    onClick = { tvManager.sendCommandAsync(TvRemoteCommand("dpad_down")) }
                )

                HorizontalDivider(
                    thickness = SettingsTokens.HairlineWidth,
                    color = SettingsTokens.HairlineDivider
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RemoteActionPill(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        label = "Back",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("back")) },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteActionPill(
                        icon = Icons.Outlined.Home,
                        label = "Home",
                        onClick = { tvManager.sendCommandAsync(TvRemoteCommand("home")) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Phone Keyboard Bar for TV Search & URL fields
            val inputActive = nowPlaying?.activeInputFocused == true
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panelShape)
                    .background(panelBg, panelShape)
                    .border(
                        width = if (inputActive) 1.dp else SettingsTokens.HairlineWidth,
                        color = if (inputActive) Color.White.copy(alpha = 0.45f) else SettingsTokens.HairlineBorder,
                        shape = panelShape
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (inputActive) {
                        "TV text input active — type below to fill on TV"
                    } else {
                        "Send text to TV keyboard"
                    },
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp,
                        color = if (inputActive) NetvioGlassTokens.TextWhite96 else NetvioGlassTokens.TextWhite54
                    )
                )

                val sendText = {
                    val trimmed = keyboardText
                    if (trimmed.isNotEmpty()) {
                        tvManager.sendCommandAsync(TvRemoteCommand("text_input", text = trimmed))
                        keyboardText = ""
                        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, RoundedCornerShape(14.dp))
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (keyboardText.isEmpty()) {
                            Text(
                                text = nowPlaying?.currentInputHint?.ifBlank { null } ?: "Search titles or paste URL…",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontSize = 13.5.sp,
                                    color = NetvioGlassTokens.TextWhite42
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = keyboardText,
                            onValueChange = { keyboardText = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontFamily = AppFontFamily,
                                fontSize = 14.sp,
                                color = NetvioGlassTokens.TextWhite96
                            ),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendText() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (keyboardText.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .pluginsPressable(scaleDown = 0.94f, onClick = { keyboardText = "" }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear",
                                tint = NetvioGlassTokens.TextWhite54,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (keyboardText.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.14f))
                            .pluginsPressable(
                                enabled = keyboardText.isNotEmpty(),
                                scaleDown = 0.96f,
                                onClick = sendText
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = "Send text to TV",
                            tint = if (keyboardText.isNotEmpty()) SettingsTokens.TextDark else NetvioGlassTokens.TextWhite42,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // One-tap Setup Sync Push
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RemoteActionPill(
                    icon = Icons.Outlined.Extension,
                    label = "Push addons",
                    enabled = !isPushingConfig,
                    onClick = {
                        coroutineScope.launch {
                            isPushingConfig = true
                            val res = tvManager.pushConfigToTv(includeAddons = true, includeDebridKeys = false)
                            isPushingConfig = false
                            isActionError = res.isFailure
                            actionFeedback = res.getOrElse { it.message ?: "Push failed" }
                            onShowMessage?.invoke(actionFeedback!!)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                RemoteActionPill(
                    icon = Icons.Outlined.Key,
                    label = "Push Debrid keys",
                    enabled = !isPushingConfig,
                    onClick = {
                        coroutineScope.launch {
                            isPushingConfig = true
                            val res = tvManager.pushConfigToTv(includeAddons = false, includeDebridKeys = true)
                            isPushingConfig = false
                            isActionError = res.isFailure
                            actionFeedback = res.getOrElse { it.message ?: "Push failed" }
                            onShowMessage?.invoke(actionFeedback!!)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val displayMessage = if (lastError != null && (actionFeedback == null || isActionError)) {
            lastError
        } else {
            actionFeedback
        }
        val isErrorBanner = lastError != null || isActionError

        AnimatedVisibility(
            visible = !displayMessage.isNullOrBlank(),
            enter = if (reduceMotion) fadeIn(snap()) else expandVertically(spring(dampingRatio = 1.0f, stiffness = 440f)) + fadeIn(tween(150)),
            exit = if (reduceMotion) fadeOut(snap()) else shrinkVertically(spring(dampingRatio = 1.0f, stiffness = 460f)) + fadeOut(tween(120))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isErrorBanner) Color(0xFFEF4444).copy(alpha = 0.12f)
                        else Color.White.copy(alpha = 0.06f)
                    )
                    .border(
                        width = SettingsTokens.HairlineWidth,
                        color = if (isErrorBanner) Color(0xFFEF4444).copy(alpha = 0.34f) else SettingsTokens.HairlineBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isErrorBanner) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFFCA5A5),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = displayMessage.orEmpty(),
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = if (isErrorBanner) Color(0xFFFECACA) else SettingsTokens.TextWhite88
                    )
                )
            }
        }
    }
}

@Composable
private fun TvCompanionInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    useMonospace: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontSize = 13.5.sp,
                    color = NetvioGlassTokens.TextWhite42
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = if (useMonospace) FontFamily.Monospace else AppFontFamily,
                fontSize = 14.sp,
                fontFeatureSettings = "tnum",
                color = NetvioGlassTokens.TextWhite96
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RemoteIconPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f), CircleShape)
            .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, CircleShape)
            .pluginsPressable(scaleDown = 0.94f, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun RemoteActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f), CircleShape)
            .border(SettingsTokens.HairlineWidth, SettingsTokens.HairlineBorder, CircleShape)
            .pluginsPressable(enabled = enabled, scaleDown = 0.96f, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                color = SettingsTokens.TextWhite88
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
