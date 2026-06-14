package com.androidclaw.app

import com.androidclaw.overlay.OverlayBridge
import com.androidclaw.overlay.OverlayTheme
import com.androidclaw.overlay.OverlayThemeMode

/** Resolves the overlay's look from settings and pushes it to the running overlay. */
fun applyOverlaySettings(settings: SettingsStore) {
    OverlayBridge.theme = OverlayTheme(
        mode = when (settings.overlayThemeMode) {
            "DARK" -> OverlayThemeMode.DARK
            "AUTO" -> OverlayThemeMode.AUTO
            else -> OverlayThemeMode.LIGHT
        },
        accent = settings.overlayAccent,
        panelAlpha = settings.overlayPanelOpacity,
        puckAlpha = settings.overlayPuckOpacity,
        puckGlyph = settings.overlayPuckGlyph,
    )
}
