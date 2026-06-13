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
- ✅ **Step-through confirmations** — approve each device action forward / back / chat / stop (in-app + overlay)
- ✅ Credentials stored via Android Keystore (EncryptedSharedPreferences)
- ⏳ P1 remaining: App Recipe engine, audit log

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

## Troubleshooting

### "Restricted setting" when enabling Accessibility (Android 13+)

When AndroidClaw is sideloaded (APK tapped from a browser/Downloads rather than
installed through the Play Store), Android 13+ blocks the Accessibility toggle
as a "Restricted setting." The option to allow it lives on the **App info**
screen, not the Accessibility screen — and only appears *after* you've been
blocked once. Exact steps:

1. **Settings → Accessibility → AndroidClaw** → try to turn it **on**.
   It greys out or shows a "Restricted setting" notice. Dismiss it.
   *(This step is mandatory — Android hides the override until you've attempted
   the blocked action at least once.)*
2. **Settings → Apps → AndroidClaw** → tap the **⋮ (three dots)** in the
   top-right → **"Allow restricted settings"** → confirm with PIN/fingerprint.
3. Go back to **Settings → Accessibility → AndroidClaw** → the toggle now works.

OEM variations:
- **Samsung (One UI):** tapping the toggle shows a pop-up directly — tap
  **"Settings"** in it and follow the prompt.
- **Xiaomi / HyperOS:** the override is a row on the App info screen rather than
  in the ⋮ menu.
- The "Allow restricted settings" item only appears in the ⋮ menu in supported
  locales; look for a lock/shield row otherwise.

**Avoid it entirely:** install or update with the `install.sh` / `install.bat`
from the GitHub release. They run `adb install -i com.android.vending`, which
records the Play Store as the installer so the restriction never triggers.
On-device with no PC, the same `adb` command can be run from Termux or **LADB**
over Wireless Debugging (Android 11+, loopback `127.0.0.1`).

## Design constraints

- **Idle RSS < 60 MB, no background polling** — fully event-driven
- **No bundled ML runtime** — online LLM first; local models arrive later as
  an optional module behind the same `LlmProvider` interface
- **Every capability is a permissioned tool** — auditable, confirmable
