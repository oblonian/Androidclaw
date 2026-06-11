# AndroidClaw 🦞📱

A lightweight, on-device personal AI agent for Android, inspired by
[OpenClaw](https://github.com/openclaw/openclaw). Your phone is the sensor and
actuator; an online LLM is the brain.

See [SPEC.md](SPEC.md) for the full technical specification.

## Status — P0 skeleton

- ✅ Compose chat UI with streaming responses
- ✅ Bounded ReAct agent loop (`core-gateway`)
- ✅ Anthropic Messages API provider with tool use (`core-llm`)
- ✅ Tool registry with permission tiers (`core-tools`)
- ✅ Tools: `web_fetch`, `open_app`
- ✅ API key stored via Android Keystore (EncryptedSharedPreferences)
- ⏳ P1: AccessibilityService actuator, screen reading, app recipes, confirmation cards

## Modules

| Module | Type | Purpose |
|---|---|---|
| `app` | Android app | Compose UI, settings, wiring |
| `core-gateway` | Kotlin/JVM | Orchestrator (ReAct loop) |
| `core-llm` | Kotlin/JVM | `LlmProvider` interface + Anthropic adapter |
| `core-tools` | Kotlin/JVM | Tool interface, registry, `web_fetch` |
| `core-control` | Android lib | Device/app control tools (`open_app`) |
| `core-common` | Kotlin/JVM | Shared JSON config |

The pure-JVM core modules have no Android dependency, so the agent logic is
unit-testable on the JVM (`./gradlew :core-gateway:test`).

## Build & run

Requires JDK 17+ and the Android SDK (API 35).

```bash
./gradlew :app:assembleDebug
```

Install the APK, open the app, and paste your Anthropic API key in Settings
(stored encrypted, never leaves the device). Default model:
`claude-sonnet-4-6`.

## Design constraints

- **Idle RSS < 60 MB, no background polling** — fully event-driven
- **No bundled ML runtime** — online LLM first; local models arrive later as
  an optional module behind the same `LlmProvider` interface
- **Every capability is a permissioned tool** — auditable, confirmable
