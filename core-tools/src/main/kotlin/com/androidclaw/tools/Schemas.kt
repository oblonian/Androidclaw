package com.androidclaw.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds a JSON Schema object for tool inputs with string properties. */
fun objectSchema(
    properties: Map<String, String>,
    required: List<String> = properties.keys.toList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        properties.forEach { (name, description) ->
            put(name, buildJsonObject {
                put("type", "string")
                put("description", description)
            })
        }
    })
    put("required", buildJsonArray { required.forEach { add(it) } })
}
