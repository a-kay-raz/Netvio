package com.netvio.ui.settings.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.netvio.R
import com.netvio.core.sync.simkl.SimklClient
import com.netvio.core.sync.trakt.TraktClient
import com.netvio.data.preferences.PreferencesRepository
import com.netvio.ui.settings.plugins.pluginsPressable
import com.netvio.ui.theme.AppFontFamily
import com.netvio.ui.theme.ApplyWindowBlurEffect
import com.netvio.ui.theme.NetvioGlassTokens
import com.netvio.ui.theme.LocalAmoledMode
import com.netvio.ui.theme.LocalReduceMotion
import com.netvio.ui.theme.adaptiveHorizontalSafePadding
import com.netvio.ui.theme.netvioGlassCard
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

@Immutable
private data class OAuthTrackerDialogSpec(
    val trackerName: String,
    val subtitle: String,
    val browserHelperCopy: String,
    val inputLabel: String,
    val inputPlaceholder: String,
    val accentColor: Color,
    val headerBrandMark: @Composable () -> Unit
)

/**
 * Vector emblem representing the Trakt checkmark badge.
 */
@Composable
fun TraktBrandEmblem(
    size: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val corner = CornerRadius(w * 0.26f, h * 0.26f)

        drawRoundRect(
            color = Color(0xFFED1C24),
            size = this.size,
            cornerRadius = corner
        )

        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(w * 0.02f, h * 0.02f),
            size = Size(w * 0.96f, h * 0.96f),
            cornerRadius = CornerRadius(w * 0.24f, h * 0.24f),
            style = Stroke(width = (w * 0.04f).coerceAtLeast(1f))
        )

        drawCircle(
            color = Color.White,
            radius = w * 0.27f,
            center = Offset(w * 0.50f, h * 0.50f),
            style = Stroke(width = w * 0.085f)
        )

        val checkPath = Path().apply {
            moveTo(w * 0.37f, h * 0.51f)
            lineTo(w * 0.47f, h * 0.60f)
            lineTo(w * 0.65f, h * 0.40f)
        }
        drawPath(
            path = checkPath,
            color = Color.White,
            style = Stroke(
                width = w * 0.095f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

/**
 * Crisp emblem + monochrome wordmark lockup for Trakt.tv cards and dialogs.
 */
@Composable
fun TraktBrandWordmark(
    emblemSize: Dp = 22.dp,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        TraktBrandEmblem(size = emblemSize)
        Text(
            text = "Trakt",
            style = TextStyle(
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                letterSpacing = (-0.02).em,
                color = NetvioGlassTokens.TextWhite96
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Simkl wordmark with its signature white-to-sky-blue gradient.
 */
@Composable
fun SimklGradientWordmark(
    height: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = R.drawable.ic_simkl_wordmark),
        contentDescription = "Simkl",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .height(height)
            .aspectRatio(720f / 176f)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val gradientBrush = Brush.linearGradient(
                    0.00f to Color.White,
                    0.48f to Color(0xFFD8F3FF),
                    1.00f to Color(0xFF38BDF8),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height * 0.85f)
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = gradientBrush, blendMode = BlendMode.SrcIn)
                }
            }
    )
}

@Composable
fun TraktConnectDialog(
    hazeState: HazeState? = null,
    isTraktValidating: Boolean = false,
    traktAuthError: String? = null,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    onClearError: () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    val spec = remember {
        OAuthTrackerDialogSpec(
            trackerName = "Trakt",
            subtitle = "Link Trakt • OAuth 2.0 PKCE",
            browserHelperCopy = "Sign in on Trakt in your browser to automatically return and authorize Netvio.",
            inputLabel = "Or paste redirect URL / code",
            inputPlaceholder = "Paste redirect URL or code…",
            accentColor = Color(0xFFED1C24),
            headerBrandMark = { TraktBrandWordmark(emblemSize = 20.dp) }
        )
    }

    OAuthTrackerConnectDialog(
        spec = spec,
        hazeState = hazeState,
        isValidating = isTraktValidating,
        authError = traktAuthError,
        onLaunchBrowser = {
            val authUrl = TraktClient.getInstance().getAuthorizationUrl()
            val verifier = TraktClient.getInstance().pendingCodeVerifier
            if (!verifier.isNullOrBlank()) {
                context.getSharedPreferences("trakt_auth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("pending_code_verifier", verifier)
                    .commit()
                coroutineScope.launch {
                    PreferencesRepository.getInstance(context).setTraktCodeVerifier(verifier)
                }
            }
            uriHandler.openUri(authUrl)
        },
        onDismiss = onDismiss,
        onConnect = onConnect,
        onClearError = onClearError
    )
}

@Composable
fun SimklConnectDialog(
    hazeState: HazeState? = null,
    isSimklValidating: Boolean = false,
    simklAuthError: String? = null,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    onClearError: () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    val spec = remember {
        OAuthTrackerDialogSpec(
            trackerName = "Simkl",
            subtitle = "Link Simkl • OAuth 2.0 PKCE",
            browserHelperCopy = "Authorize Simkl in your browser to return automatically with your session.",
            inputLabel = "Or paste redirect URL / code",
            inputPlaceholder = "Paste redirect URL or code…",
            accentColor = Color(0xFF38BDF8),
            headerBrandMark = { SimklGradientWordmark(height = 17.dp) }
        )
    }

    OAuthTrackerConnectDialog(
        spec = spec,
        hazeState = hazeState,
        isValidating = isSimklValidating,
        authError = simklAuthError,
        onLaunchBrowser = {
            val authUrl = SimklClient.getInstance().getAuthorizationUrl()
            val verifier = SimklClient.getInstance().pendingCodeVerifier
            if (!verifier.isNullOrBlank()) {
                context.getSharedPreferences("simkl_auth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("pending_code_verifier", verifier)
                    .commit()
                coroutineScope.launch {
                    PreferencesRepository.getInstance(context).setSimklCodeVerifier(verifier)
                }
            }
            uriHandler.openUri(authUrl)
        },
        onDismiss = onDismiss,
        onConnect = onConnect,
        onClearError = onClearError
    )
}

@Composable
private fun OAuthTrackerConnectDialog(
    spec: OAuthTrackerDialogSpec,
    hazeState: HazeState?,
    isValidating: Boolean,
    authError: String?,
    onLaunchBrowser: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    onClearError: () -> Unit
) {
    val isAmoled = LocalAmoledMode.current
    val reduceMotion = LocalReduceMotion.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    var tokenInput by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }

    var entered by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) {
        entered = true
    }

    val cardScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.95f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(dampingRatio = 1.0f, stiffness = 460f)
        },
        label = "oauthDialogScale"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = 160, easing = LinearOutSlowInEasing)
        },
        label = "oauthDialogAlpha"
    )

    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(authError) {
        if (!authError.isNullOrBlank() && !reduceMotion) {
            for (delta in listOf(-8f, 8f, -5f, 5f, -2f, 0f)) {
                shakeOffset.animateTo(
                    targetValue = delta,
                    animationSpec = tween(durationMillis = 36)
                )
            }
        } else {
            shakeOffset.snapTo(0f)
        }
    }

    val inputBorderColor by animateColorAsState(
        targetValue = when {
            !authError.isNullOrBlank() -> Color(0xFFEF4444).copy(alpha = 0.65f)
            isInputFocused -> Color.White.copy(alpha = 0.42f)
            else -> NetvioGlassTokens.HairlineBorder
        },
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 150),
        label = "oauthInputBorder"
    )

    val extractAndSubmit = {
        val raw = tokenInput.trim()
        if (raw.isNotEmpty() && !isValidating) {
            val code = Regex("""code=([^&#]+)""").find(raw)?.groupValues?.get(1) ?: raw
            onConnect(code)
        }
    }

    Dialog(
        onDismissRequest = { if (!isValidating) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ApplyWindowBlurEffect(blurRadiusPx = 56)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = NetvioGlassTokens.ScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isValidating,
                    onClick = onDismiss
                )
                .imePadding()
                .adaptiveHorizontalSafePadding(
                    baseStart = 20.dp,
                    baseEnd = 20.dp,
                    includeRailClearance = false
                )
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                        alpha = cardAlpha
                        translationX = shakeOffset.value
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .netvioGlassCard(
                        shape = RoundedCornerShape(NetvioGlassTokens.RadiusDialog),
                        hazeState = hazeState,
                        isAmoled = isAmoled,
                        borderWidth = NetvioGlassTokens.HairlineWidth,
                        borderColor = NetvioGlassTokens.HairlineBorder
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        spec.headerBrandMark()
                        Text(
                            text = spec.subtitle,
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp,
                                color = NetvioGlassTokens.TextWhite54
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .pluginsPressable(
                                enabled = !isValidating,
                                scaleDown = 0.94f,
                                onClick = onDismiss
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(
                                    width = NetvioGlassTokens.HairlineWidth,
                                    color = NetvioGlassTokens.HairlineBorder,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Close ${spec.trackerName} sign-in",
                                tint = NetvioGlassTokens.TextWhite82,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                StepIndicatorBadge(stepNumber = 1, label = "Authorize in browser")

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(NetvioGlassTokens.RadiusPill))
                        .background(Color.White)
                        .pluginsPressable(
                            enabled = !isValidating,
                            scaleDown = 0.97f,
                            pressedAlpha = 0.90f,
                            role = Role.Button,
                            onClick = onLaunchBrowser
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            tint = NetvioGlassTokens.TextDark,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = "Continue in browser",
                            style = NetvioGlassTokens.PrimaryButtonText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = spec.browserHelperCopy,
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = NetvioGlassTokens.TextWhite54
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(
                    thickness = NetvioGlassTokens.HairlineWidth,
                    color = NetvioGlassTokens.HairlineDivider
                )

                Spacer(modifier = Modifier.height(16.dp))

                StepIndicatorBadge(stepNumber = 2, label = "Paste redirect URL or code")

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = spec.inputLabel,
                    style = TextStyle(
                        fontFamily = AppFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = NetvioGlassTokens.TextWhite72
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(
                            width = if (isInputFocused || !authError.isNullOrBlank()) 1.dp else NetvioGlassTokens.HairlineWidth,
                            color = inputBorderColor,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (tokenInput.isEmpty()) {
                            Text(
                                text = spec.inputPlaceholder,
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 13.5.sp,
                                    color = NetvioGlassTokens.TextWhite42
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        BasicTextField(
                            value = tokenInput,
                            onValueChange = { updated ->
                                tokenInput = updated
                                if (!authError.isNullOrBlank()) {
                                    onClearError()
                                }
                            },
                            enabled = !isValidating,
                            singleLine = true,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = NetvioGlassTokens.TextWhite96
                            ),
                            cursorBrush = SolidColor(spec.accentColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isInputFocused = it.isFocused }
                        )
                    }

                    if (tokenInput.isNotEmpty() && !isValidating) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .pluginsPressable(
                                    scaleDown = 0.94f,
                                    onClick = {
                                        tokenInput = ""
                                        if (!authError.isNullOrBlank()) {
                                            onClearError()
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear field",
                                tint = NetvioGlassTokens.TextWhite54,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.09f))
                            .border(
                                width = NetvioGlassTokens.HairlineWidth,
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .pluginsPressable(
                                enabled = !isValidating,
                                scaleDown = 0.96f,
                                onClick = {
                                    val pasted = clipboardManager.getText()?.text?.trim()
                                    if (!pasted.isNullOrEmpty()) {
                                        tokenInput = pasted
                                        if (!authError.isNullOrBlank()) {
                                            onClearError()
                                        }
                                        try {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste code",
                            tint = NetvioGlassTokens.TextWhite82,
                            modifier = Modifier.size(13.5.dp)
                        )
                        Text(
                            text = "Paste",
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                color = NetvioGlassTokens.TextWhite82
                            )
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !authError.isNullOrBlank(),
                    enter = if (reduceMotion) {
                        fadeIn(snap())
                    } else {
                        expandVertically(spring(dampingRatio = 1.0f, stiffness = 440f)) +
                            fadeIn(tween(durationMillis = 160, easing = LinearOutSlowInEasing))
                    },
                    exit = if (reduceMotion) {
                        fadeOut(snap())
                    } else {
                        shrinkVertically(spring(dampingRatio = 1.0f, stiffness = 460f)) +
                            fadeOut(tween(durationMillis = 120))
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.12f))
                            .border(
                                width = NetvioGlassTokens.HairlineWidth,
                                color = Color(0xFFEF4444).copy(alpha = 0.34f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = authError.orEmpty(),
                            style = TextStyle(
                                fontFamily = AppFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                                color = Color(0xFFFECACA)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                val canSubmit = tokenInput.trim().isNotEmpty() && !isValidating
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.40f)
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(NetvioGlassTokens.RadiusPill))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(
                                width = NetvioGlassTokens.HairlineWidth,
                                color = NetvioGlassTokens.HairlineBorder,
                                shape = RoundedCornerShape(NetvioGlassTokens.RadiusPill)
                            )
                            .pluginsPressable(
                                enabled = !isValidating,
                                scaleDown = 0.97f,
                                role = Role.Button,
                                onClick = onDismiss
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cancel",
                            style = NetvioGlassTokens.SecondaryButtonText,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(0.60f)
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(NetvioGlassTokens.RadiusPill))
                            .background(
                                if (canSubmit || isValidating) {
                                    Color.White
                                } else {
                                    Color.White.copy(alpha = 0.14f)
                                }
                            )
                            .pluginsPressable(
                                enabled = canSubmit,
                                scaleDown = 0.97f,
                                pressedAlpha = 0.90f,
                                role = Role.Button,
                                onClick = extractAndSubmit
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isValidating) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = NetvioGlassTokens.TextDark,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Verifying…",
                                    style = NetvioGlassTokens.PrimaryButtonText,
                                    maxLines = 1
                                )
                            }
                        } else {
                            Text(
                                text = "Verify & link",
                                style = TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.5.sp,
                                    color = if (canSubmit) {
                                        NetvioGlassTokens.TextDark
                                    } else {
                                        NetvioGlassTokens.TextWhite42
                                    }
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

/**
 * Compact Step Indicator Pill Badge ("STEP 1", "STEP 2").
 */
@Composable
private fun StepIndicatorBadge(
    stepNumber: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(
                    NetvioGlassTokens.HairlineWidth,
                    NetvioGlassTokens.HairlineBorder,
                    CircleShape
                )
                .padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "STEP $stepNumber",
                style = TextStyle(
                    fontFamily = AppFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.90f)
                )
            )
        }

        Text(
            text = label,
            style = TextStyle(
                fontFamily = AppFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.65f)
            )
        )
    }
}
