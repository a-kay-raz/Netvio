package com.netvio.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Tv
import com.netvio.ui.icons.LucideIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netvio.R
import com.netvio.core.model.StreamSource
import com.netvio.data.tv.TvCompanionManager
import com.netvio.data.tv.TvConnectionState
import com.netvio.data.tv.TvHandoffPayload
import com.netvio.ui.components.AppBottomSheet
import com.netvio.ui.components.LocalSnackbarController
import com.netvio.ui.theme.HairlineWidth

import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPickerSheet(
    streamSource: StreamSource?,
    mediaTitle: String,
    onDismiss: () -> Unit,
    handoffContext: TvHandoffPayload? = null,
    onPlaybackHandedOffToTv: (() -> Unit)? = null,
    onOpenTvRemote: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val snackbarController = LocalSnackbarController.current
    val coroutineScope = rememberCoroutineScope()

    val isScanning by CastController.isScanning.collectAsState()
    val discoveredDevices by CastController.discoveredDevices.collectAsState()
    val activeDevice by CastController.activeCastDevice.collectAsState()
    val scanError by CastController.scanError.collectAsState()

    val tvManager = remember { TvCompanionManager.getInstance(context) }
    val tvConnectionState by tvManager.connectionState.collectAsState()
    val connectedTv by tvManager.connectedTv.collectAsState()
    val savedTv by tvManager.savedTv.collectAsState()
    val discoveredTvs by tvManager.discoveredTvs.collectAsState()
    val isDiscoveringTvs by tvManager.isDiscovering.collectAsState()

    var isSendingToTv by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { CastController.scanDevices(context) }
        tvManager.refreshDiscovery()
    }

    AppBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cast_title),
                        style = PlayerTokens.SheetHeadline.copy(fontSize = 20.sp),
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.cast_subtitle),
                        style = PlayerTokens.SheetSubtitle,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(PlayerTokens.TouchTargetMin)
                        .playerPressable(
                            role = Role.Button,
                            onClickLabel = "Scan for devices",
                            onClick = {
                                tvManager.refreshDiscovery()
                                coroutineScope.launch {
                                    CastController.scanDevices(context)
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isScanning || isDiscoveringTvs || isSendingToTv) {
                        BufferingSpinnerArc()
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Scan for devices",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Active DLNA Cast Banner if connected
            if (activeDevice != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .videoOverlayGlass(PlayerTokens.RadiusMedium)
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = LucideIcons.CastConnected,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Casting to ${activeDevice?.name}",
                                color = Color.White,
                                style = PlayerTokens.RowTitle.copy(fontSize = 14.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .videoOverlayGlass(shape = PlayerTokens.RadiusPill)
                                .playerPressable(
                                    role = Role.Button,
                                    onClickLabel = "Disconnect",
                                    onClick = { CastController.disconnect() }
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Disconnect",
                                color = Color.White.copy(alpha = 0.85f),
                                style = PlayerTokens.PillText
                            )
                        }
                    }
                }
            }

            // Section 1: TV Companion Handoff
            Text(
                text = "TV COMPANION",
                style = PlayerTokens.SectionLabel,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val pairedTv = connectedTv ?: savedTv
            if (pairedTv != null) {
                val effectiveHandoff = handoffContext ?: TvHandoffPayload(
                    mediaId = mediaTitle.ifBlank { "direct_stream" },
                    title = mediaTitle.ifBlank { "Netvio Stream" },
                    mediaType = "MOVIE",
                    streamUrl = streamSource?.url,
                    streamHeaders = streamSource?.headers ?: emptyMap(),
                    isM3u8 = streamSource?.isM3u8 == true
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(PlayerTokens.RadiusMedium)
                        .background(Color.White)
                        .playerPressable(
                            enabled = !isSendingToTv,
                            isWhiteElement = true,
                            role = Role.Button,
                            onClickLabel = "Play on ${pairedTv.name}",
                            onClick = {
                                coroutineScope.launch {
                                    isSendingToTv = true
                                    val res = tvManager.playOnTv(effectiveHandoff)
                                    isSendingToTv = false
                                    res.fold(
                                        onSuccess = {
                                            onPlaybackHandedOffToTv?.invoke()
                                            snackbarController.show("Playing on ${pairedTv.name}")
                                            if (onOpenTvRemote != null) {
                                                onOpenTvRemote()
                                            } else {
                                                onDismiss()
                                            }
                                        },
                                        onFailure = { err ->
                                            snackbarController.show(err.message ?: "Unable to reach ${pairedTv.name}")
                                        }
                                    )
                                }
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (tvConnectionState == TvConnectionState.CONNECTED) {
                                LucideIcons.CastConnected
                            } else {
                                Icons.Outlined.Tv
                            },
                            contentDescription = null,
                            tint = PlayerTokens.LabelDark,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Play on ${pairedTv.name}",
                                style = PlayerTokens.RowTitle.copy(
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = PlayerTokens.LabelDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (tvConnectionState == TvConnectionState.CONNECTED) {
                                    "Connected · Instant handoff with resume & subtitles"
                                } else {
                                    "${pairedTv.host}:${pairedTv.port} · Tap to play on TV"
                                },
                                style = PlayerTokens.SheetSubtitle.copy(fontSize = 11.5.sp),
                                color = PlayerTokens.LabelDark.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isSendingToTv) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PlayerTokens.LabelDark,
                            strokeWidth = 2.dp
                        )
                    } else if (onOpenTvRemote != null) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(PlayerTokens.LabelDark.copy(alpha = 0.08f))
                                .playerPressable(
                                    isWhiteElement = true,
                                    role = Role.Button,
                                    onClickLabel = "Open TV Remote",
                                    onClick = onOpenTvRemote
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SettingsRemote,
                                contentDescription = "Open TV Remote",
                                tint = PlayerTokens.LabelDark,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                discoveredTvs.forEach { tv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .videoOverlayGlass(shape = PlayerTokens.RadiusMedium)
                            .playerPressable(
                                role = Role.Button,
                                onClickLabel = "Pair ${tv.name}",
                                onClick = {
                                    if (onOpenTvRemote != null) {
                                        onOpenTvRemote()
                                    } else {
                                        onDismiss()
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Outlined.Tv,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = tv.name,
                                    style = PlayerTokens.RowTitle.copy(fontSize = 14.sp),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${tv.host}:${tv.port} · Tap to pair with PIN",
                                    style = PlayerTokens.SheetSubtitle.copy(fontSize = 11.5.sp),
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                        }
                        Text(
                            text = "Pair",
                            style = PlayerTokens.PillText,
                            color = Color.White
                        )
                    }
                }

                if (onOpenTvRemote != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .videoOverlayGlass(shape = PlayerTokens.RadiusMedium)
                            .playerPressable(
                                role = Role.Button,
                                onClickLabel = "Pair or connect to TV",
                                onClick = onOpenTvRemote
                            )
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.SettingsRemote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Pair or open TV remote",
                                    style = PlayerTokens.RowTitle.copy(fontSize = 14.sp),
                                    color = Color.White
                                )
                                Text(
                                    text = "Connect via Wi-Fi IP & 6-digit PIN shown on TV",
                                    style = PlayerTokens.SheetSubtitle.copy(fontSize = 11.5.sp),
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: External Player Apps
            Text(
                text = stringResource(R.string.cast_external_player_header).uppercase(),
                style = PlayerTokens.SectionLabel,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ExternalPlaybackHelper.POPULAR_PLAYERS.forEach { player ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .videoOverlayGlass(shape = PlayerTokens.RadiusMedium)
                        .playerPressable(
                            role = Role.Button,
                            onClickLabel = player.name,
                            onClick = {
                                if (streamSource != null) {
                                    val launched = ExternalPlaybackHelper.launchExternalPlayer(
                                        context = context,
                                        streamSource = streamSource,
                                        title = mediaTitle,
                                        playerOption = player,
                                        onError = { msg -> snackbarController.show(msg) }
                                    )
                                    if (launched) onDismiss()
                                } else {
                                    snackbarController.show("Video source not loaded yet")
                                }
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (player.id) {
                                "vlc" -> Icons.Outlined.PlayCircle
                                "mx" -> Icons.Outlined.SmartDisplay
                                "system" -> Icons.Outlined.Share
                                else -> Icons.Outlined.PlayArrow
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = player.name,
                            style = PlayerTokens.RowTitle.copy(fontSize = 14.sp),
                            color = Color.White
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 3: Discovered Smart TVs (DLNA / UPnP)
            Text(
                text = stringResource(R.string.cast_network_header).uppercase(),
                style = PlayerTokens.SectionLabel,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (discoveredDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .videoOverlayGlass(shape = PlayerTokens.RadiusMedium)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = scanError ?: if (isScanning) {
                            stringResource(R.string.cast_searching_devices)
                        } else {
                            stringResource(R.string.cast_no_devices_found)
                        },
                        style = PlayerTokens.SheetSubtitle,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }
            } else {
                discoveredDevices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .videoOverlayGlass(shape = PlayerTokens.RadiusMedium)
                            .playerPressable(
                                enabled = !isSendingToTv,
                                role = Role.Button,
                                onClickLabel = device.name,
                                onClick = {
                                    if (streamSource == null) {
                                        snackbarController.show("Video source not loaded yet")
                                        return@playerPressable
                                    }
                                    coroutineScope.launch {
                                        isSendingToTv = true
                                        val res = CastController.castToDlnaDevice(
                                            device = device,
                                            streamUrl = streamSource.url,
                                            title = mediaTitle
                                        )
                                        isSendingToTv = false
                                        res.fold(
                                            onSuccess = {
                                                onPlaybackHandedOffToTv?.invoke()
                                                snackbarController.show("Casting to ${device.name}")
                                                onDismiss()
                                            },
                                            onFailure = { err ->
                                                snackbarController.show(err.message ?: "Failed to cast to ${device.name}")
                                            }
                                        )
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Outlined.Tv,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = device.name,
                                    style = PlayerTokens.RowTitle.copy(fontSize = 14.sp),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${device.deviceType} · ${device.ipAddress}",
                                    style = PlayerTokens.SheetSubtitle.copy(fontSize = 11.5.sp),
                                    color = Color.White.copy(alpha = 0.58f)
                                )
                            }
                        }
                        Icon(
                            imageVector = LucideIcons.Cast,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val copyLinkCopiedMsg = stringResource(R.string.cast_stream_link_copied)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .videoOverlayGlass(shape = PlayerTokens.RadiusPill)
                    .playerPressable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.cast_copy_stream_link),
                        onClick = {
                            if (streamSource != null) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Stream URL", streamSource.url)
                                clipboard.setPrimaryClip(clip)
                                snackbarController.show(copyLinkCopiedMsg)
                                onDismiss()
                            } else {
                                snackbarController.show("Video source not loaded yet")
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.cast_copy_stream_link),
                        style = PlayerTokens.RowTitle.copy(fontSize = 14.sp),
                        color = Color.White
                    )
                }
            }
        }
    }
}
