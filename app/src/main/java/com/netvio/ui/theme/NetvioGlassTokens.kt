package com.netvio.ui.theme

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Gold-standard frosted glassmorphism tokens for glass dialogs.
 *
 * Characteristics:
 * 1. Blur Radius: 24dp Haze blur sampling the underlying screen.
 * 2. Tonal Depth: Obsidian dark 85% alpha (#0F1219) in Dark mode, 92% alpha (#000000) in AMOLED mode.
 * 3. Hairline Specular Border: 0.5dp at 16% white alpha.
 * 4. Hairline Divider: 0.5dp at 10% white alpha.
 * 5. Corner Geometry: 28dp radius for dialogs and sheets.
 * 6. Typography: 20sp Bold (96% white) title, 13sp Normal (54% white) subtitle, 13.5sp Normal (72% white) body.
 * 7. Action Button: 48dp solid white pill (#FFFFFF) with dark near-black text (#0B0B0F).
 */
object NetvioGlassTokens {
    val BlurRadius: Dp = 24.dp
    val RadiusDialog: Dp = 28.dp
    val RadiusSheet: Dp = 28.dp
    val RadiusPill: Dp = 24.dp

    val HairlineWidth: Dp = 0.5.dp
    val HairlineBorder: Color = Color.White.copy(alpha = 0.16f)
    val HairlineDivider: Color = Color.White.copy(alpha = 0.10f)

    const val ScrimAlpha: Float = 0.65f

    // Translucent frosted fills when blur material is enabled; opaque fallback when disabled
    val BaseDark: Color = Color(0xFF0F1219)
    val TextDark: Color = Color(0xFF0B0B0F)

    fun cardFill(isAmoled: Boolean): Color =
        if (isAmoled) Color(0xFF06070A).copy(alpha = 0.60f) else BaseDark.copy(alpha = 0.54f)

    fun sheetWindowFill(isAmoled: Boolean, blurEnabled: Boolean): Color =
        if (blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isAmoled) Color(0xFF06070A).copy(alpha = 0.66f) else BaseDark.copy(alpha = 0.60f)
        } else {
            fallbackFill(isAmoled)
        }

    fun fallbackFill(isAmoled: Boolean): Color =
        if (isAmoled) Color(0xFF050507).copy(alpha = 0.95f) else BaseDark.copy(alpha = 0.95f)

    // Text colors
    val TextWhite96: Color = Color.White.copy(alpha = 0.96f)
    val TextWhite82: Color = Color.White.copy(alpha = 0.82f)
    val TextWhite72: Color = Color.White.copy(alpha = 0.72f)
    val TextWhite54: Color = Color.White.copy(alpha = 0.54f)
    val TextWhite42: Color = Color.White.copy(alpha = 0.42f)

    // Typography styles
    val HeaderTitle: TextStyle = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        color = TextWhite96
    )

    val HeaderSubtitle: TextStyle = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        color = TextWhite54
    )

    val SectionHeader: TextStyle = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.5.sp,
        lineHeight = 21.sp,
        color = TextWhite96
    )

    val BodyText: TextStyle = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        color = TextWhite72
    )

    val PrimaryButtonText: TextStyle = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp,
        color = TextDark
    )

    val SecondaryButtonText: TextStyle = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        color = TextWhite72
    )
}

/**
 * Applies OS-level Android 12+ background blur behind standalone Dialog / ModalBottomSheet windows.
 */
@Composable
fun ApplyWindowBlurEffect(
    enabled: Boolean = LocalUiBlurEnabled.current,
    blurRadiusPx: Int = 56
) {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: generateSequence(view.parent) { it.parent }
                .filterIsInstance<DialogWindowProvider>()
                .firstOrNull()?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setDimAmount(0f)
                val lp = window.attributes
                lp.blurBehindRadius = blurRadiusPx
                window.attributes = lp
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val lp = window.attributes
                lp.blurBehindRadius = 0
                window.attributes = lp
            }
        }
    }
}

/**
 * Standard modifier for modal glass cards (Dialogs & Sheets) adhering to frosted blur material.
 */
fun Modifier.netvioGlassCard(
    shape: Shape = RoundedCornerShape(NetvioGlassTokens.RadiusDialog),
    hazeState: HazeState? = null,
    isAmoled: Boolean = false,
    borderWidth: Dp = NetvioGlassTokens.HairlineWidth,
    borderColor: Color = NetvioGlassTokens.HairlineBorder
): Modifier = composed {
    val uiBlurEnabled = LocalUiBlurEnabled.current
    val cardFill = NetvioGlassTokens.cardFill(isAmoled)
    val fallback = NetvioGlassTokens.fallbackFill(isAmoled)

    this
        .clip(shape)
        .then(
            if (uiBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hazeState != null) {
                Modifier.hazeEffect(state = hazeState) {
                    blurRadius = NetvioGlassTokens.BlurRadius
                    tints = listOf(HazeTint(cardFill))
                    fallbackTint = HazeTint(fallback)
                }
            } else {
                Modifier.background(fallback, shape)
            }
        )
        .border(
            width = borderWidth,
            color = borderColor,
            shape = shape
        )
}
