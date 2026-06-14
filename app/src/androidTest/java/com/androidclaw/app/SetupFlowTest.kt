package com.androidclaw.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests the setup / sign-in flow UI without actually calling any APIs.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun anthropicAndOpenRouterOptionsAreVisible() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Anthropic").assertIsDisplayed()
        composeTestRule.onNodeWithText("OpenRouter").assertIsDisplayed()
    }

    @Test
    fun apiKeyFieldAppearsForAnthropic() {
        composeTestRule.waitForIdle()
        // Anthropic is default; API key field should be visible immediately
        composeTestRule.onNodeWithText("Anthropic API key").assertIsDisplayed()
    }

    @Test
    fun saveButtonDisabledWhenApiKeyEmpty() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save API key").assertIsNotEnabled()
    }

    @Test
    fun saveButtonEnabledAfterTypingKey() {
        composeTestRule.waitForIdle()
        // Use hasSetTextAction() to reliably target the text field node in Compose
        composeTestRule.onNode(hasSetTextAction()).performTextInput("sk-ant-testkey123")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save API key").assertIsEnabled()
    }

    @Test
    fun switchingToOpenRouterHidesAnthropicField() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("OpenRouter").performClick()
        composeTestRule.waitForIdle()
        // After switching backend, Anthropic API key field should not be shown
        composeTestRule.onNodeWithText("Anthropic API key").assertDoesNotExist()
    }

    @Test
    fun claudeAccountToggleShowsOAuthFlow() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Claude account").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Open Anthropic sign-in").assertIsDisplayed()
    }
}
