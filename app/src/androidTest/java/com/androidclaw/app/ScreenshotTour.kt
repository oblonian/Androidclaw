package com.androidclaw.app

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Walks the app's screens on a real (emulated) device and saves a scaled PNG of
 * each one to the app's external files dir. CI pulls these so the UI can be
 * reviewed visually — this is a screenshot harness, not a pass/fail test.
 *
 * Screenshots land in the additionalTestOutputDir (auto-pulled by AGP to
 * app/build/outputs/connected_android_test_additional_output/) or, as a
 * fallback, in the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dir: File
        get() {
            val additionalOutputDir = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir")
            val base = when {
                additionalOutputDir != null -> File(additionalOutputDir)
                ctx.getExternalFilesDir(null) != null -> ctx.getExternalFilesDir(null)!!
                else -> ctx.filesDir
            }
            val d = File(base, "screenshots")
            val created = d.mkdirs()
            Log.i(TAG, "Screenshot dir: ${d.absolutePath} created=$created exists=${d.exists()}")
            return d
        }

    private fun shoot(name: String) {
        try {
            compose.waitForIdle()
            val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
            val targetW = 480
            val scale = targetW.toFloat() / bmp.width
            val scaled = Bitmap.createScaledBitmap(bmp, targetW, (bmp.height * scale).toInt(), true)
            val file = File(dir, "$name.png")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }
            Log.i(TAG, "Saved screenshot: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot $name", e)
        }
    }

    @Before
    fun clearCreds() = SettingsStore(ctx).signOut()

    @After
    fun resetCreds() = SettingsStore(ctx).signOut()

    @Test
    fun setupScreen() {
        compose.waitForIdle()
        shoot("01_setup_initial")
        runCatching {
            compose.onNodeWithText("Claude account").performClick()
            compose.waitForIdle()
            shoot("02_setup_oauth")
        }
        runCatching {
            compose.onNodeWithText("OpenRouter").performClick()
            compose.waitForIdle()
            shoot("03_setup_openrouter")
        }
    }

    @Test
    fun chatAndSettings() {
        SettingsStore(ctx).apply {
            backend = LlmBackend.ANTHROPIC
            anthropicUseOAuth = false
            anthropicKey = "sk-ant-demo-not-a-real-key"
        }
        // Relaunch so the activity sees the new credentials
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        shoot("04_chat_landing")
        runCatching {
            compose.onNodeWithContentDescription("More").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Settings").performClick()
            compose.waitForIdle()
            shoot("05_settings_top")
        }
    }

    companion object {
        private const val TAG = "ScreenshotTour"
    }
}
