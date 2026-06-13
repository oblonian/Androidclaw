package com.androidclaw.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tuned for a slightly more deliberate, "product" feel than the
 * Material baseline: heavier titles, tighter headline tracking. Built on the
 * default system font so no font assets need shipping.
 */
private val Base = Typography()

val ClawTypography = Base.copy(
    headlineMedium = Base.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = Base.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = Base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    labelLarge = Base.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    bodyMedium = Base.bodyMedium.copy(
        lineHeight = 20.sp,
    ),
)
