package com.androidclaw.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.androidclaw.overlay.OverlayService

/**
 * Invisible trampoline for the Android share sheet.
 * Receives ACTION_SEND text/plain → forwards to the overlay → finishes immediately.
 * Falls back gracefully when the overlay permission isn't granted yet.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }

        if (text == null) {
            finish()
            return
        }

        if (!OverlayService.canDraw(this)) {
            // No overlay permission yet — open the app to set it up rather than dropping silently.
            Toast.makeText(this, "Grant \"Display over other apps\" to share to Claw", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            finish()
            return
        }

        val i = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SHARED_TEXT)
            .putExtra(OverlayService.EXTRA_TEXT, text)
        // A visible (if NoDisplay) activity is a valid FGS start source, but guard anyway:
        // a ForegroundServiceStartNotAllowedException must not crash the share gesture.
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        }.isSuccess
        if (!started) {
            Toast.makeText(this, "Couldn't open Claw — tap the app icon and try again", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
