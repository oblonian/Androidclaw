package com.androidclaw.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Walks the app's screens on a real (emulated) device and saves a scaled PNG of
 * each one to the app's external files dir. CI pulls these so the UI can be
 * reviewed visually — this is a screenshot harness, not a pass/fail test, so
 * every capture is wrapped to never abort the tour.
 *
 * Screenshots land in /sdcard/Android/data/com.androidclaw.app/files/screenshots
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dir: File
        get() = File(ctx.getExternalFilesDir(null), "screenshots").apply { mkdirs() }

    private fun shoot(name: String) {
        runCatching {
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val targetW = 480
            val scale = targetW.toFloat() / bmp.width
            val scaled = Bitmap.createScaledBitmap(bmp, targetW, (bmp.height * scale).toInt(), true)
            FileOutputStream(File(dir, "$name.png")).use {
                scaled.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
        }
    }

    private fun clearCreds() = SettingsStore(ctx).signOut()

    private fun seedFakeCreds() = SettingsStore(ctx).apply {
        backend = LlmBackend.ANTHROPIC
        anthropicUseOAuth = false
        anthropicKey = "sk-ant-demo-not-a-real-key"
    }

    @Test
    fun setupScreen() {
        clearCreds()
        ActivityScenario.launch(MainActivity::class.java).use {
            shoot("01_setup_initial")
            // Toggle to the "Claude account" OAuth path
            runCatching {
                compose.onNodeWithText("Claude account").performClick()
                compose.waitForIdle()
                shoot("02_setup_oauth")
            }
            // Switch provider to OpenRouter
            runCatching {
                compose.onNodeWithText("OpenRouter").performClick()
                compose.waitForIdle()
                shoot("03_setup_openrouter")
            }
        }
    }

    @Test
    fun chatAndSettings() {
        seedFakeCreds()
        ActivityScenario.launch(MainActivity::class.java).use {
            shoot("04_chat_landing")
            // Open overflow → Settings
            runCatching {
                compose.onAllNodes(hasContentDescription("More")).onFirst().performClick()
                compose.waitForIdle()
                compose.onNodeWithText("Settings").performClick()
                compose.waitForIdle()
                shoot("05_settings_top")
            }
        }
        clearCreds()
    }
}
