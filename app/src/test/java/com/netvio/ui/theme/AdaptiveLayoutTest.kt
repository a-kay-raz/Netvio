package com.netvio.ui.theme

import androidx.compose.ui.unit.dp
import com.netvio.data.preferences.NavbarStyle
import com.netvio.ui.navigation.calculateSideRailStartClearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {

    @Test
    fun `phone portrait uses compact bottom nav layout`() {
        val dims = WindowDimensions(
            widthClass = WindowWidthClass.Compact,
            widthDp = 390.dp,
            heightDp = 844.dp,
            isLandscape = false,
            isTV = false,
            heightClass = WindowHeightClass.Medium,
            smallestWidthDp = 390.dp
        )

        assertTrue(dims.isPhone)
        assertFalse(dims.isTablet)
        assertFalse(dims.isCompactHeight)
        assertFalse(dims.isPhoneLandscape)
        assertFalse(dims.isWideViewport)
        assertFalse(dims.useSideNavigationRail)
        assertEquals(WindowHeightClass.Medium, dims.heightClass)
    }

    @Test
    fun `phone landscape enables compact side rail layout`() {
        val dims = WindowDimensions(
            widthClass = WindowWidthClass.Expanded,
            widthDp = 844.dp,
            heightDp = 390.dp,
            isLandscape = true,
            isTV = false,
            heightClass = WindowHeightClass.Compact,
            smallestWidthDp = 390.dp
        )

        assertTrue(dims.isPhone)
        assertFalse(dims.isTablet)
        assertTrue(dims.isCompactHeight)
        assertTrue(dims.isPhoneLandscape)
        assertTrue(dims.isWideViewport)
        assertTrue(dims.useSideNavigationRail)
        assertEquals(WindowHeightClass.Compact, dims.heightClass)
    }

    @Test
    fun `unfolded foldable or medium tablet enables side rail layout`() {
        val dims = WindowDimensions(
            widthClass = WindowWidthClass.Medium,
            widthDp = 700.dp,
            heightDp = 840.dp,
            isLandscape = false,
            isTV = false,
            heightClass = WindowHeightClass.Medium,
            smallestWidthDp = 700.dp
        )

        assertFalse(dims.isPhone)
        assertTrue(dims.isTablet)
        assertFalse(dims.isCompactHeight)
        assertFalse(dims.isPhoneLandscape)
        assertTrue(dims.isWideViewport)
        assertTrue(dims.useSideNavigationRail)
    }

    @Test
    fun `landscape tablet enables expanded tablet side rail layout`() {
        val dims = WindowDimensions(
            widthClass = WindowWidthClass.Expanded,
            widthDp = 1280.dp,
            heightDp = 800.dp,
            isLandscape = true,
            isTV = false,
            heightClass = WindowHeightClass.Medium,
            smallestWidthDp = 800.dp
        )

        assertFalse(dims.isPhone)
        assertTrue(dims.isTablet)
        assertFalse(dims.isCompactHeight)
        assertFalse(dims.isPhoneLandscape)
        assertTrue(dims.isWideViewport)
        assertTrue(dims.useSideNavigationRail)
    }

    @Test
    fun `android tv uses expanded side rail without classifying as phone or tablet`() {
        val dims = WindowDimensions(
            widthClass = WindowWidthClass.Expanded,
            widthDp = 1920.dp,
            heightDp = 1080.dp,
            isLandscape = true,
            isTV = true,
            heightClass = WindowHeightClass.Expanded,
            smallestWidthDp = 1080.dp
        )

        assertFalse(dims.isPhone)
        assertFalse(dims.isTablet)
        assertFalse(dims.isCompactHeight)
        assertFalse(dims.isPhoneLandscape)
        assertTrue(dims.isWideViewport)
        assertTrue(dims.useSideNavigationRail)
        assertEquals(WindowHeightClass.Expanded, dims.heightClass)
    }

    @Test
    fun `posterGridColumns accounts for reserved rail clearance and enforces bounds`() {
        val phonePortrait = WindowDimensions(
            widthClass = WindowWidthClass.Compact,
            widthDp = 390.dp,
            heightDp = 844.dp,
            isLandscape = false,
            isTV = false,
            heightClass = WindowHeightClass.Medium,
            smallestWidthDp = 390.dp
        )
        assertEquals(2, phonePortrait.posterGridColumns(minItemWidth = 150.dp, gap = 12.dp, minCols = 2, maxCols = 6))

        val mediumLandscape = WindowDimensions(
            widthClass = WindowWidthClass.Medium,
            widthDp = 720.dp,
            heightDp = 420.dp,
            isLandscape = true,
            isTV = false,
            heightClass = WindowHeightClass.Compact,
            smallestWidthDp = 420.dp
        )
        assertEquals(
            4,
            mediumLandscape.posterGridColumns(
                minItemWidth = 150.dp,
                gap = 12.dp,
                minCols = 2,
                maxCols = 6,
                reservedStartDp = 0.dp
            )
        )
        assertEquals(
            3,
            mediumLandscape.posterGridColumns(
                minItemWidth = 150.dp,
                gap = 12.dp,
                minCols = 2,
                maxCols = 6,
                reservedStartDp = 76.dp
            )
        )

        val tabletLandscape = WindowDimensions(
            widthClass = WindowWidthClass.Expanded,
            widthDp = 1280.dp,
            heightDp = 800.dp,
            isLandscape = true,
            isTV = false,
            heightClass = WindowHeightClass.Medium,
            smallestWidthDp = 800.dp
        )
        assertEquals(
            6,
            tabletLandscape.posterGridColumns(
                minItemWidth = 150.dp,
                gap = 12.dp,
                minCols = 2,
                maxCols = 6,
                reservedStartDp = 88.dp
            )
        )
        assertEquals(
            7,
            tabletLandscape.posterGridColumns(
                minItemWidth = 150.dp,
                gap = 12.dp,
                minCols = 2,
                maxCols = 8,
                reservedStartDp = 88.dp
            )
        )
    }

    @Test
    fun `dialogMaxHeight respects vertical margin and 220dp floor`() {
        val phoneLandscape = WindowDimensions(
            widthClass = WindowWidthClass.Expanded,
            widthDp = 844.dp,
            heightDp = 390.dp,
            isLandscape = true,
            isTV = false,
            heightClass = WindowHeightClass.Compact,
            smallestWidthDp = 390.dp
        )
        assertEquals(358.dp, phoneLandscape.dialogMaxHeight(verticalMargin = 32.dp))

        val tinyViewport = WindowDimensions(
            widthClass = WindowWidthClass.Compact,
            widthDp = 320.dp,
            heightDp = 230.dp,
            isLandscape = false,
            isTV = false,
            heightClass = WindowHeightClass.Compact,
            smallestWidthDp = 230.dp
        )
        assertEquals(220.dp, tinyViewport.dialogMaxHeight(verticalMargin = 32.dp))
    }

    @Test
    fun `navigation layout state computes recommended bottom padding cleanly`() {
        val bottomNavState = NavigationLayoutState(
            hasBottomNav = true,
            hasSideRail = false,
            isRailFloating = false,
            isCompactRail = false,
            sideRailStartClearance = 0.dp,
            bottomNavClearance = 104.dp
        )
        assertEquals(104.dp, bottomNavState.recommendedBottomContentPadding)

        val sideRailState = NavigationLayoutState(
            hasBottomNav = false,
            hasSideRail = true,
            isRailFloating = true,
            isCompactRail = true,
            sideRailStartClearance = 76.dp,
            bottomNavClearance = 104.dp
        )
        assertEquals(28.dp, sideRailState.recommendedBottomContentPadding)
    }

    @Test
    fun `calculateSideRailStartClearance handles style, compactness, and left system inset`() {
        // Floating compact (64 + 12 = 76dp) and tablet (76 + 12 = 88dp)
        assertEquals(
            76.dp,
            calculateSideRailStartClearance(
                style = NavbarStyle.FLOATING,
                isCompactHeight = true,
                leftSysInset = 0.dp
            )
        )
        assertEquals(
            88.dp,
            calculateSideRailStartClearance(
                style = NavbarStyle.FLOATING,
                isCompactHeight = false,
                leftSysInset = 0.dp
            )
        )

        // Docked compact (64dp) and tablet (84dp)
        assertEquals(
            64.dp,
            calculateSideRailStartClearance(
                style = NavbarStyle.DOCKED,
                isCompactHeight = true,
                leftSysInset = 0.dp
            )
        )
        assertEquals(
            84.dp,
            calculateSideRailStartClearance(
                style = NavbarStyle.DOCKED,
                isCompactHeight = false,
                leftSysInset = 0.dp
            )
        )

        // With display cutout / left system inset (e.g. 24dp)
        assertEquals(
            100.dp,
            calculateSideRailStartClearance(
                style = NavbarStyle.FLOATING,
                isCompactHeight = true,
                leftSysInset = 24.dp
            )
        )
        assertEquals(
            108.dp,
            calculateSideRailStartClearance(
                style = NavbarStyle.DOCKED,
                isCompactHeight = false,
                leftSysInset = 24.dp
            )
        )
    }
}
