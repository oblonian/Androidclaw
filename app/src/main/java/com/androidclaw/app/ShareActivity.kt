package com.androidclaw.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.androidclaw.overlay.OverlayService

/**
 * Invisible trampoline for the Android share sheet.
 * Receives ACTION_SEND text/plain → forwards to the overlay → finishes immediately.
 * Requires the overlay permission to be granted first.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank() && OverlayService.canDraw(this)) {
            val i = Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_SHARED_TEXT)
                .putExtra(OverlayService.EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        }
        finish()
    }
}
