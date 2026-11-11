package com.netvio.ui.settings.plugins

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.netvio.core.model.PluginManifest
import com.netvio.ui.settings.pages.PluginsSettingsPage
import com.netvio.ui.settings.pages.RepoPluginsScreen
import com.netvio.ui.theme.NetvioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PluginsCrashDetectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testPluginsSettingsPageRenderAndTabNavigation() {
        composeTestRule.setContent {
            NetvioTheme {
                PluginsSettingsPage(
                    onBack = {},
                    onNavigateToRepo = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify headline
        composeTestRule.onNodeWithText("Plugins").assertIsDisplayed()
        composeTestRule.onNodeWithText("& addons").assertIsDisplayed()

        // Tab navigation to Addons
        composeTestRule.onNodeWithText("Addons").performClick()
        composeTestRule.waitForIdle()

        // Tab navigation to Scrapers
        composeTestRule.onNodeWithText("Scrapers").performClick()
        composeTestRule.waitForIdle()

        // Tab navigation back to Repositories
        composeTestRule.onNodeWithText("Repositories").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testPluginsSettingsPageSwipeGesture() {
        composeTestRule.setContent {
            NetvioTheme {
                PluginsSettingsPage(
                    onBack = {},
                    onNavigateToRepo = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Page 0 (Repositories) displayed initially
        composeTestRule.onNodeWithText("ADD REPOSITORY").assertIsDisplayed()

        // Swipe left to shift to Page 1 (Addons)
        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ADD STREMIO ADDON").assertIsDisplayed()

        // Swipe left to shift to Page 2 (Scrapers)
        composeTestRule.onRoot().performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        // Swipe right to shift back to Page 1 (Addons)
        composeTestRule.onRoot().performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ADD STREMIO ADDON").assertIsDisplayed()
    }

    @Test
    fun testRepoPluginsScreenRender() {
        composeTestRule.setContent {
            NetvioTheme {
                RepoPluginsScreen(
                    repoUrl = "https://raw.githubusercontent.com/test/manifest.json",
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun testProviderDetailSheetRender() {
        val testPlugin = PluginManifest(
            id = "test.plugin",
            name = "Test Scraper",
            version = 1,
            versionName = "1.2.0",
            author = "Netvio Team",
            siteUrl = "https://example.com",
            description = "High performance movie and anime scraper",
            className = "com.netvio.scraper.TestScraper",
            supportedTypes = listOf("movie", "series", "anime"),
            isEnabled = true
        )

        composeTestRule.setContent {
            NetvioTheme {
                ProviderDetailSheet(
                    plugin = testPlugin,
                    isInstalled = true,
                    isInstalling = false,
                    isTesting = false,
                    health = null,
                    onTest = {},
                    onInstall = {},
                    onUninstall = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Test Scraper").assertIsDisplayed()
        composeTestRule.onNodeWithText("Uninstall provider").assertIsDisplayed()
    }

    @Test
    fun testUpdateActionsRenderWithoutCrash() {
        composeTestRule.setContent {
            NetvioTheme {
                PluginsSettingsPage(
                    onBack = {},
                    onNavigateToRepo = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify headline & initial tab display
        composeTestRule.onNodeWithText("Plugins").assertIsDisplayed()
        composeTestRule.onNodeWithText("Repositories").assertIsDisplayed()
    }
}
