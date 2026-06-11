# AndroidClaw 🦞📱

A lightweight, on-device personal AI agent for Android, inspired by
[OpenClaw](https://github.com/openclaw/openclaw). Your phone is the sensor and
actuator; an online LLM is the brain.

See [SPEC.md](SPEC.md) for the full technical specification.

## Status — P0 skeleton

- ✅ Compose chat UI with streaming responses
- ✅ Bounded ReAct agent loop (`core-gateway`)
- ✅ Anthropic Messages API provider with tool use (`core-llm`)
- ✅ OpenAI-compatible provider (OpenRouter, OpenAI, self-hosted gateways)
- ✅ **OAuth sign-in via OpenRouter PKCE** — no manual API key needed
- ✅ **OAuth sign-in via Anthropic (claude.ai)** — PKCE S256, Bearer token auth
- ✅ Tool registry with permission tiers (`core-tools`)
- ✅ Tools: `web_fetch`, `open_app`, `read_screen`, `ui_action`
- ✅ **Floating "Claw" overlay** — draggable edge-tab over other apps (`core-overlay`)
- ✅ **AccessibilityService actuator** — reads the screen and taps/types/scrolls in other apps (`core-control`)
- ✅ Credentials stored via Android Keystore (EncryptedSharedPreferences)
- ⏳ P1 remaining: App Recipe engine, confirmation cards for `CONFIRM`-tier actions, audit log

## Modules

| Module | Type | Purpose |
|---|---|---|
| `app` | Android app | Compose UI, settings, wiring |
| `core-gateway` | Kotlin/JVM | Orchestrator (ReAct loop) |
| `core-llm` | Kotlin/JVM | `LlmProvider` interface + Anthropic adapter |
| `core-tools` | Kotlin/JVM | Tool interface, registry, `web_fetch` |
| `core-control` | Android lib | Device/app control: `open_app`, AccessibilityService actuator (`read_screen`, `ui_action`) |
| `core-overlay` | Android lib | Floating "Claw" overlay (foreground service + edge-tab UI) |
| `core-common` | Kotlin/JVM | Shared JSON config |

The pure-JVM core modules have no Android dependency, so the agent logic is
unit-testable on the JVM (`./gradlew :core-gateway:test`).

## Build & run

Requires JDK 17+ and the Android SDK (API 35).

```bash
./gradlew :app:assembleDebug
```

Install the APK, open the app, and either **sign in with OpenRouter** (OAuth,
no key handling) or paste an Anthropic API key in Settings. Credentials are
stored encrypted and never leave the device. Defaults: `claude-sonnet-4-6`
(Anthropic) / `anthropic/claude-sonnet-4.6` (OpenRouter).

## Design constraints

- **Idle RSS < 60 MB, no background polling** — fully event-driven
- **No bundled ML runtime** — online LLM first; local models arrive later as
  an optional module behind the same `LlmProvider` interface
- **Every capability is a permissioned tool** — auditable, confirmable
