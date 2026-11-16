package com.netvio.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthClass {
    Compact,   // Phones in portrait (< 600dp)
    Medium,    // Foldables, Small Tablets, Landscape Phones (600dp - 839dp)
    Expanded   // Large Tablets, Desktops, TV (>= 840dp)
}

enum class WindowHeightClass {
    Compact,   // Landscape phones, compact split-screen (< 480dp)
    Medium,    // Tablets landscape, unfolded foldables, portrait phones (480dp - 899dp)
    Expanded   // Tall tablets portrait (>= 900dp)
}

/**
 * Navigation shell clearance context exposed by [com.netvio.ui.navigation.AppNavHost]
 * so nested screens clear the floating/docked navbar or left frosted glass rail cleanly
 * without hardcoding portrait offsets or double-counting left display cutouts.
 */
data class NavigationLayoutState(
    val hasBottomNav: Boolean = true,
    val hasSideRail: Boolean = false,
    val isRailFloating: Boolean = false,
    val isCompactRail: Boolean = false,
    val sideRailStartClearance: Dp = 0.dp,
    val bottomNavClearance: Dp = 94.dp
) {
    val recommendedBottomContentPadding: Dp
        get() = if (hasBottomNav) bottomNavClearance else 28.dp
}

val LocalNavigationLayout = compositionLocalOf { NavigationLayoutState() }

val LocalWindowDimensions = compositionLocalOf {
    WindowDimensions(
        widthClass = WindowWidthClass.Compact,
        widthDp = 412.dp,
        heightDp = 915.dp,
        isLandscape = false,
        isTV = false
    )
}

data class WindowDimensions(
    val widthClass: WindowWidthClass,
    val widthDp: Dp,
    val heightDp: Dp,
    val isLandscape: Boolean,
    val isTV: Boolean,
    val heightClass: WindowHeightClass = when {
        heightDp < 480.dp -> WindowHeightClass.Compact
        heightDp < 900.dp -> WindowHeightClass.Medium
        else -> WindowHeightClass.Expanded
    },
    val smallestWidthDp: Dp = minOf(widthDp, heightDp)
) {
    /** True on actual phone hardware (portrait OR landscape), distinguishing phones from tablets. */
    val isPhone: Boolean get() = smallestWidthDp < 600.dp && !isTV

    /** True on genuine tablets & unfolded foldables (smallest width >= 600dp). */
    val isTablet: Boolean get() = smallestWidthDp >= 600.dp && !isTV

    /** True when viewport height is constrained (landscape phones & compact split-screen < 480dp). */
    val isCompactHeight: Boolean get() = heightClass == WindowHeightClass.Compact

    /** True on any wide viewport >= 600dp (tablets, foldables, or landscape phones). */
    val isWideViewport: Boolean get() = widthClass != WindowWidthClass.Compact || isLandscape

    /** True specifically for phones held horizontally in landscape orientation. */
    val isPhoneLandscape: Boolean get() = isPhone && isLandscape

    /** True when navigation chrome should move from the bottom pill to a left frosted rail. */
    val useSideNavigationRail: Boolean get() = isTV || isTablet || isPhoneLandscape

    val horizontalMargin: Dp get() = when {
        isTV -> Dimens.tvHorizontalMargin(widthDp)
        isTablet && widthClass == WindowWidthClass.Expanded -> Dimens.screenHorizontalMarginExpandedTablet
        isTablet || isPhoneLandscape -> Dimens.screenHorizontalMarginTablet
        else -> Dimens.screenHorizontalMarginPhone
    }

    /** Computes adaptive grid column counts from available container width. */
    fun posterGridColumns(
        minItemWidth: Dp = 150.dp,
        gap: Dp = 12.dp,
        minCols: Int = 2,
        maxCols: Int = 6,
        reservedStartDp: Dp = 0.dp
    ): Int {
        val usable = (widthDp - reservedStartDp - horizontalMargin * 2).coerceAtLeast(minItemWidth)
        return ((usable + gap) / (minItemWidth + gap)).toInt().coerceIn(minCols, maxCols)
    }

    /** Safe height ceiling for dialogs and overlays in compact landscape mode. */
    fun dialogMaxHeight(verticalMargin: Dp = 32.dp): Dp = (heightDp - verticalMargin).coerceAtLeast(220.dp)
}

@Composable
fun rememberWindowDimensions(): WindowDimensions {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val widthDp = configuration.screenWidthDp.dp
    val heightDp = configuration.screenHeightDp.dp
    val smallestWidthDp = if (configuration.smallestScreenWidthDp > 0) {
        configuration.smallestScreenWidthDp.dp
    } else {
        minOf(widthDp, heightDp)
    }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val isTV = remember(context) {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isLeanback = context.packageManager.hasSystemFeature("android.software.leanback")
        val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        isLeanback || isTvMode
    }

    val widthClass = when {
        widthDp < 600.dp -> WindowWidthClass.Compact
        widthDp < 840.dp -> WindowWidthClass.Medium
        else -> WindowWidthClass.Expanded
    }

    val heightClass = when {
        heightDp < 480.dp -> WindowHeightClass.Compact
        heightDp < 900.dp -> WindowHeightClass.Medium
        else -> WindowHeightClass.Expanded
    }

    return WindowDimensions(
        widthClass = widthClass,
        widthDp = widthDp,
        heightDp = heightDp,
        isLandscape = isLandscape,
        isTV = isTV,
        heightClass = heightClass,
        smallestWidthDp = smallestWidthDp
    )
}

/**
 * Applies horizontal safe insets for display cutouts (notches/camera holes) and side navigation bars,
 * optionally honoring [LocalNavigationLayout.sideRailStartClearance] without double-counting the start inset.
 */
fun Modifier.adaptiveHorizontalSafePadding(
    baseStart: Dp = 0.dp,
    baseEnd: Dp = 0.dp,
    includeRailClearance: Boolean = true
): Modifier = composed {
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).asPaddingValues()
    val startSysInset = insets.calculateLeftPadding(layoutDirection)
    val endSysInset = insets.calculateRightPadding(layoutDirection)
    val navLayout = LocalNavigationLayout.current
    val railClearance = if (includeRailClearance && navLayout.hasSideRail) {
        navLayout.sideRailStartClearance
    } else {
        0.dp
    }
    val effectiveStart = maxOf(startSysInset, railClearance) + baseStart
    val effectiveEnd = endSysInset + baseEnd
    this.padding(start = effectiveStart, end = effectiveEnd)
}

/**
 * Computes [androidx.compose.foundation.layout.PaddingValues] combining [baseStart]/[baseEnd]
 * with horizontal display cutout, navigation bar, and left rail clearances (without double-counting).
 */
@Composable
fun rememberAdaptiveHorizontalPadding(
    baseStart: Dp = 0.dp,
    baseEnd: Dp = baseStart,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    includeRailClearance: Boolean = true
): androidx.compose.foundation.layout.PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).asPaddingValues()
    val startSysInset = insets.calculateLeftPadding(layoutDirection)
    val endSysInset = insets.calculateRightPadding(layoutDirection)
    val navLayout = LocalNavigationLayout.current
    val railClearance = if (includeRailClearance && navLayout.hasSideRail) {
        navLayout.sideRailStartClearance
    } else {
        0.dp
    }
    return androidx.compose.foundation.layout.PaddingValues(
        start = maxOf(startSysInset, railClearance) + baseStart,
        end = endSysInset + baseEnd,
        top = top,
        bottom = bottom
    )
}

/**
 * Computes [androidx.compose.foundation.layout.PaddingValues] that clears cutouts, system bars,
 * and side navigation rail while symmetrically centering content within [maxContentWidth] on wide
 * viewports (e.g. tablets), keeping lazy scroll containers full-width for touch responsiveness.
 */
@Composable
fun rememberCenteredContentPadding(
    maxContentWidth: Dp,
    baseStart: Dp = 0.dp,
    baseEnd: Dp = baseStart,
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    includeRailClearance: Boolean = true
): androidx.compose.foundation.layout.PaddingValues {
    val windowDimensions = rememberWindowDimensions()
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.displayCutout.union(WindowInsets.navigationBars).asPaddingValues()
    val startSysInset = insets.calculateLeftPadding(layoutDirection)
    val endSysInset = insets.calculateRightPadding(layoutDirection)
    val navLayout = LocalNavigationLayout.current
    val railClearance = if (includeRailClearance && navLayout.hasSideRail) {
        navLayout.sideRailStartClearance
    } else {
        0.dp
    }
    val startBase = maxOf(startSysInset, railClearance) + baseStart
    val endBase = endSysInset + baseEnd
    val availableWidth = (windowDimensions.widthDp - startBase - endBase).coerceAtLeast(0.dp)
    val extraEachSide = if (availableWidth > maxContentWidth) {
        (availableWidth - maxContentWidth) / 2f
    } else {
        0.dp
    }
    return androidx.compose.foundation.layout.PaddingValues(
        start = startBase + extraEachSide,
        end = endBase + extraEachSide,
        top = top,
        bottom = bottom
    )
}

/**
 * Adaptive typography scale ensuring body text is minimum 16sp on Android TV.
 */
object AdaptiveTypography {
    fun getTypography(isTV: Boolean): androidx.compose.material3.Typography {
        if (!isTV) return Typography
        return androidx.compose.material3.Typography(
            displayLarge = AppTypography.displayHero.copy(fontSize = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(46f, androidx.compose.ui.unit.TextUnitType.Sp)),
            displayMedium = AppTypography.displayHero.copy(fontSize = androidx.compose.ui.unit.TextUnit(36f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(42f, androidx.compose.ui.unit.TextUnitType.Sp)),
            displaySmall = AppTypography.titleLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)),
            headlineLarge = AppTypography.titleLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)),
            headlineMedium = AppTypography.titleMedium.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)),
            headlineSmall = AppTypography.titleSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)),
            titleLarge = AppTypography.titleLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp)),
            titleMedium = AppTypography.titleMedium.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp)),
            titleSmall = AppTypography.titleSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)),
            bodyLarge = AppTypography.bodyLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp)),
            bodyMedium = AppTypography.bodyMedium.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
            bodySmall = AppTypography.bodySmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
            labelLarge = AppTypography.labelLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
            labelMedium = AppTypography.labelMedium.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
            labelSmall = AppTypography.labelSmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp))
        )
    }
}

val WindowDimensions.typography: androidx.compose.material3.Typography get() = AdaptiveTypography.getTypography(isTV)

