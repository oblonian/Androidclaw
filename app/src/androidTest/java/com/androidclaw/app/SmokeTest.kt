package com.androidclaw.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the app launches without crashing and shows the expected initial screen.
 * On a fresh emulator (no credentials stored) this is always the SetupScreen.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    companion object {
        // Other test classes (e.g. ScreenshotTour) may seed credentials into the
        // shared, persisted store. Clear before the activity launches so we always
        // land on the SetupScreen regardless of test execution order.
        @JvmStatic
        @BeforeClass
        fun clearCreds() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            SettingsStore(ctx).signOut()
        }
    }

    @Test
    fun appLaunchesAndShowsSetupScreen() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("AndroidClaw").assertIsDisplayed()
    }

    @Test
    fun setupScreenShowsSubtitle() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Your AI agent on Android").assertIsDisplayed()
    }

    @Test
    fun setupScreenShowsSignInStep() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sign in").assertIsDisplayed()
    }
}
