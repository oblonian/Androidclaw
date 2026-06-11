package com.androidclaw.common

import kotlinx.serialization.json.Json

/** Shared JSON configuration: lenient on input, never crashes on unknown fields. */
val ClawJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}
