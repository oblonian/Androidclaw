# AndroidClaw — Technical Specification

> A lightweight, on-device personal AI agent harness for Android.
> Inspired by [OpenClaw](https://github.com/openclaw/openclaw) (Peter Steinberger), reimagined as a native, resource-frugal Android agent that can connect to and control other apps.

**Status:** Draft v0.1
**Target platform:** Android 10 (API 29) and above
**Primary goal:** A battery- and RAM-friendly agent that talks to online LLMs today, with a clean path to on-device local LLMs later.

---

## 1. Vision & Principles

AndroidClaw is a personal agent that lives on your phone. You talk to it (text or voice), it reasons with an LLM, and it *acts* — opening apps, tapping buttons, reading the screen, sending messages, toggling settings, fetching data — all on-device.

### Design principles

1. **Lightweight first.** Idle footprint must be negligible. No always-loaded model, no busy loops, no wakelocks held longer than necessary. Target idle RSS **< 60 MB**, idle battery draw indistinguishable from a messaging app.
2. **Event-driven, not poll-driven.** The agent sleeps until something wakes it (user message, scheduled trigger, notification). No background spinning.
3. **Online LLM by default.** Reasoning happens in the cloud (BYO API key). The phone is the *actuator and sensor*, not the brain — until local models are added.
4. **Capability-scoped & auditable.** Every action the agent can take is a declared, permissioned "tool." Nothing happens silently; sensitive actions require confirmation.
5. **Local-first data.** Conversation history, memory, and credentials never leave the device except as part of an explicit LLM request the user authorized.
6. **Graceful degradation.** Works without root. Uses the strongest API available (Accessibility, Intents, ADB-over-Wi-Fi if user enables it) and falls back cleanly.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       AndroidClaw App                         │
│                                                               │
│  ┌────────────┐   ┌──────────────┐   ┌────────────────────┐  │
│  │   UI Layer │   │   Gateway     │   │   Foreground       │  │
│  │ (Compose)  │◄─►│  (Orchestr.)  │◄─►│   Service (light)  │  │
│  └────────────┘   └──────┬───────┘   └────────────────────┘  │
│                          │                                    │
│        ┌─────────────────┼────────────────────┐              │
│        ▼                 ▼                     ▼              │
│  ┌───────────┐    ┌─────────────┐      ┌──────────────┐       │
│  │  LLM      │    │  Tool       │      │  Memory &    │       │
│  │  Client   │    │  Registry   │      │  Context     │       │
│  │ (online)  │    │  + Executor │      │  Store       │       │
│  └─────┬─────┘    └──────┬──────┘      └──────┬───────┘       │
│        │                 │                    │               │
└────────┼─────────────────┼────────────────────┼──────────────┘
         │                 │                    │
         ▼                 ▼                    ▼
   Cloud LLM API     Android Surfaces      Encrypted SQLite
   (Anthropic/        - Accessibility       (Room + SQLCipher)
    OpenAI/etc.)      - Intents/Deep links
                      - NotificationListener
                      - System settings
                      - Other apps
```

### Component summary

| Component | Responsibility | Notes |
|---|---|---|
| **Gateway (Orchestrator)** | Core loop: receive input → build context → call LLM → parse tool calls → execute → loop. | Pure Kotlin, no heavy deps. The "brain stem." |
| **LLM Client** | Streaming HTTP client to online providers. Pluggable provider interface. | OkHttp + SSE. Model-agnostic. |
| **Tool Registry & Executor** | Declares available tools (JSON schema), validates args, dispatches to Android surfaces. | The capability boundary. |
| **Memory & Context Store** | Conversation history, long-term facts, app-control recipes. | Encrypted Room DB. Token-budgeted retrieval. |
| **Foreground Service** | Keeps a minimal listener alive for triggers; spins up work, then idles. | Notification-backed, low priority. |
| **UI Layer** | Chat interface, permission/consent prompts, tool audit log, settings. | Jetpack Compose. |

---

## 3. Technology Stack

| Concern | Choice | Rationale |
|---|---|---|
| Language | **Kotlin** (+ coroutines) | First-class Android, structured concurrency for cheap async. |
| UI | **Jetpack Compose** | Declarative, small, no XML bloat. |
| Networking | **OkHttp + okio**, SSE for streaming | Lightweight, battle-tested, supports streaming token output. |
| JSON | **kotlinx.serialization** | Reflection-free, fast, small. |
| Storage | **Room** + **SQLCipher** | Encrypted local DB, typed queries. |
| Secrets | **Android Keystore** + EncryptedSharedPreferences | API keys never stored in plaintext. |
| Background | **Foreground Service** (lowest viable) + **WorkManager** for deferred jobs | Event-driven, OS-friendly. |
| DI | **Manual / Hilt-lite** (or Koin) | Avoid heavy DI graphs; keep startup fast. |
| App control | **AccessibilityService**, **Intents**, **NotificationListenerService**, **Settings** APIs | No root required. |

**Deliberately avoided:** bundled ML runtimes (until phase for local LLM), heavyweight reactive frameworks, anything that forces a persistent high-priority process.

---

## 4. The Agent Loop (Gateway)

The orchestrator runs a bounded ReAct-style loop:

```
1. Receive trigger (user text / voice / scheduled / notification event)
2. Assemble context:
     - system prompt (capabilities + safety rules)
     - relevant long-term memory (retrieved, token-budgeted)
     - recent conversation turns (sliding window)
     - available tool schemas (only ones currently permitted)
3. Stream request to online LLM
4. If LLM returns tool_call(s):
     - validate args against schema
     - check permission tier (auto / confirm / blocked)
     - execute via Tool Executor → capture result
     - append result, goto 3   (max N iterations, default 8)
5. If LLM returns final text:
     - render to user, persist turn, update memory
6. Release resources, return service to idle
```

Key properties:
- **Bounded iterations** prevent runaway loops and token burn.
- **Streaming** so the user sees progress immediately (low perceived latency).
- **Cancelable** — user can abort mid-loop; coroutines make this clean.
- **Stateless between triggers** — nothing kept hot in RAM after a turn completes.

---

## 5. App Control & Device Interaction

This is the heart of AndroidClaw. Without root, we layer multiple mechanisms by capability and reliability.

### 5.1 Mechanisms (strongest → weakest)

| Mechanism | What it enables | Permission cost | Reliability |
|---|---|---|---|
| **AccessibilityService** | Read screen content (UI tree), tap/scroll/swipe/type in *any* app, detect app state. | User must enable in Settings; sensitive. | High — the core actuator. |
| **Intents / Deep links** | Launch apps, prefill actions (send SMS, compose email, open URL, share, dial, navigate). | Normal/declared. | High for supported actions. |
| **NotificationListenerService** | Read incoming notifications, reply inline (e.g., messaging apps), dismiss. | User-granted special access. | High for messaging triggers. |
| **Settings / system toggles** | Wi-Fi, Bluetooth, DND, brightness, volume, alarms (via AlarmManager), clipboard. | Mix of normal + special. | High. |
| **App-specific APIs / Content Providers** | Calendar, Contacts, MediaStore, SMS (read with permission). | Runtime permissions. | High. |
| **ADB-over-Wi-Fi (optional, power user)** | Shell-level control, `input`, `am`, `pm` without root. | User enables wireless debugging; AndroidClaw acts as local ADB client. | Very high, but setup friction. |

### 5.2 The "App Recipe" abstraction

Controlling arbitrary apps via the accessibility tree is brittle. AndroidClaw introduces **App Recipes** — declarative, learnable maps of how to accomplish a task in a specific app:

```jsonc
{
  "app": "com.whatsapp",
  "action": "send_message",
  "params": ["contact", "text"],
  "steps": [
    { "op": "launch", "target": "com.whatsapp" },
    { "op": "tap",   "find": { "desc": "Search" } },
    { "op": "type",  "value": "$contact" },
    { "op": "tap",   "find": { "text": "$contact", "first": true } },
    { "op": "type",  "find": { "class": "EditText" }, "value": "$text" },
    { "op": "tap",   "find": { "desc": "Send" } }
  ],
  "confirm": true
}
```

- Recipes ship for popular apps; users/community can add more.
- The LLM picks a recipe and fills params, rather than blindly driving the UI tree (cheaper, more reliable, fewer tokens).
- A **fallback "freeform" mode** lets the LLM reason over the live accessibility tree when no recipe exists — slower, used sparingly.

### 5.3 Screen perception

- Default: serialize the accessibility node tree to a **compact text representation** (role, text, bounds, actionable flags) — cheap, no image tokens.
- Optional: screenshot + vision model for apps with custom-rendered canvases (Flutter/games). Off by default to save tokens and RAM.

### 5.4 The Floating Overlay Assistant ("Claw")

The signature interaction. Claw is a **system-wide floating presence** that hovers over whatever app is in the foreground, so the user can issue an intent in natural language, have Claw **open the target app and perform the action**, and keep Claw docked alongside for follow-up — without leaving the app they're in.

**Interaction model**

1. User issues an intent ("reply to Mum on WhatsApp", "book the 7 pm slot", "summarise this thread") — by voice or text into the overlay.
2. Claw routes the intent through the same stateless **Gateway**, which launches the app (`open_app`) and drives it (recipe or `read_screen` + `ui_action`).
3. A **floating handle stays docked over the app**, so Claw can confirm a step, ask a clarifying question, or take the next instruction in-context.
4. The handle is **dismissible to a screen-edge tab** ("side-rail") and **recalled with a tap**, so it never blocks the underlying UI.

**Two visual states**

| State | Form | Purpose |
|---|---|---|
| **Collapsed** | A thin tab clinging to the screen edge (draggable vertically, snaps to nearest edge). | Out of the way; one tap to summon. Obscures less than a chat-head bubble and reads as a drawer. |
| **Expanded** | A compact card: transcript, text input, mic button, tool-status chips, and a confirmation strip for `Confirm`-tier actions. | Active conversation + action approval, overlaid right where the action will land. |

**Mechanism**

- Built on **`SYSTEM_ALERT_WINDOW`** ("draw over other apps") — the same primitive as Messenger chat-heads — hosted by a **low-priority foreground service** (`FOREGROUND_SERVICE` + ongoing notification, which Android requires for a persistent overlay and which doubles as a one-tap kill switch).
- The overlay is the **presence**; the **AccessibilityService** (§5.1) is the **hands**. The overlay holds no model and no wakelock — it wakes only on tap/voice, honouring the idle-RAM and battery budget (§10).
- **Voice-in is the primary fast path** (on-device `SpeechRecognizer`); text is the fallback — the whole point is "don't make me touch the other app".

**Safety**

- The expanded card surfaces a **confirmation strip** for every `Confirm`-tier action (*"Send 'I'll be late' to Mum? — Send / Edit / Cancel"*) before Claw acts in another app — the §11 consent model made visible at the point of action.
- The card shows **how** Claw is acting — "Using WhatsApp recipe…" vs "Improvising…" — so the user knows how much to trust a given step.

**Architecture fit**

The overlay is a second **front-end** alongside the in-app chat, talking to the *same* Gateway via a thin `OverlayAgent` bridge (the app injects its gateway; the overlay module never depends on `app`). New actuator tools (`read_screen`, `ui_tap`, `ui_type`) join the existing registry. Nothing in `core-gateway`/`core-llm`/`core-tools` changes.

**Constraints (flagged up front)**

- Requires **two sensitive grants** — *Draw over other apps* + *Accessibility* — each a manual toggle in system Settings; onboarding must explain *why* per permission.
- Google Play restricts non-accessibility use of AccessibilityService, so this is realistically a **sideload / F-Droid** build (acceptable for this project).
- Per-app automation **breaks when apps restyle their UI**; recipes need maintenance, with the freeform `read_screen` loop as the safety net.

---

## 6. Tool Registry

Every capability is a typed tool with a JSON schema exposed to the LLM. Tools are grouped and individually permissioned.

### Core tool set (v1)

| Tool | Description | Permission tier |
|---|---|---|
| `open_app` | Launch an app by name/package. | Auto |
| `send_message` | Send via SMS / messaging app (recipe-driven). | **Confirm** |
| `read_screen` | Return compact UI tree of foreground app. | Auto |
| `ui_action` | Tap/type/scroll/swipe on a described element. | Auto (Confirm for destructive) |
| `read_notifications` | List/inspect recent notifications. | Auto |
| `reply_notification` | Inline-reply to a notification. | **Confirm** |
| `get_contacts` / `get_calendar` | Read PIM data. | Auto (read-only) |
| `create_event` / `set_alarm` | Write to calendar / set alarm. | **Confirm** |
| `device_setting` | Toggle Wi-Fi/BT/DND/brightness/volume. | Auto |
| `web_fetch` / `web_search` | Fetch a URL / search the web. | Auto |
| `clipboard_get` / `clipboard_set` | Read/write clipboard. | Auto |
| `remember` / `recall` | Write/read long-term memory. | Auto |
| `run_recipe` | Execute a named App Recipe with params. | Per-recipe `confirm` flag |

### Permission tiers

- **Auto** — executes immediately (read-only or low-risk).
- **Confirm** — surfaces a one-tap approval card before executing (sending messages, spending, deleting, writing PIM).
- **Blocked** — disabled until the user explicitly enables in settings (e.g., financial apps, payments).

The active tool set is **context-trimmed**: only relevant tools are sent to the LLM per request, keeping prompts small.

---

## 7. Memory & Context

| Layer | Storage | Eviction |
|---|---|---|
| **Working context** | In-memory during a turn only | Discarded after turn |
| **Conversation history** | Encrypted Room table | Sliding window + summarization |
| **Long-term memory** | Encrypted Room table (key facts, preferences) | Manual + relevance decay |
| **App recipes** | Bundled assets + user/DB additions | Versioned |

- **Token budgeting:** a retriever assembles only what fits a configurable budget (default conservative to control cost).
- **Summarization:** old turns are compressed into running summaries to keep context small (and cheap).
- Memory writes are explicit tools (`remember`) so the user can audit what the agent retains.

---

## 8. LLM Integration (Online-First)

### Provider abstraction

```kotlin
interface LlmProvider {
    suspend fun stream(request: LlmRequest): Flow<LlmChunk>  // tokens + tool calls
    val supportsTools: Boolean
    val supportsVision: Boolean
}
```

- **Adapters:** Anthropic (Claude), OpenAI (GPT), Google (Gemini), plus an **OpenAI-compatible** generic adapter (covers most gateways).
- **BYO key:** stored in Keystore-backed encrypted prefs. Never logged, never in plaintext.
- **Streaming + tool use** are required capabilities; the provider maps native tool-calling formats to AndroidClaw's internal `ToolCall` type.
- **Cost guardrails:** per-day token/spend cap, model selector (cheap model for routing, stronger model for hard tasks), and a "dry-run" mode that shows planned tool calls without executing.

### Why online-first

The phone provides *sensing and acting*; the cloud provides *reasoning*. This keeps the app tiny and the battery happy. No model weights on device, no thermal throttling, instant capability upgrades as cloud models improve.

---

## 9. Local LLM Path (Later Phase)

Designed-in, not bolted-on. The `LlmProvider` interface already abstracts the backend, so a local provider drops in cleanly.

- **Runtime candidates:** `llama.cpp` (JNI), MediaPipe LLM Inference, or ONNX Runtime Mobile.
- **Models:** small quantized instruct models (1–4B, e.g. Gemma/Phi/Qwen class) in `gguf`/`task` format.
- **Loaded on demand only**, unloaded after idle timeout — never resident, to honor the RAM budget.
- **Hybrid routing:** local model handles cheap/private intents (classification, simple commands, offline use); escalates to cloud for hard reasoning.
- **Hardware awareness:** detect NPU/GPU (NNAPI/GPU delegate); refuse or warn on low-RAM devices.

---

## 10. Resource & Battery Budget (Non-Negotiable)

| Metric | Target |
|---|---|
| APK size | < 15 MB (online-only build) |
| Cold start to chat | < 1.5 s on mid-range device |
| Idle RSS | < 60 MB |
| Active (during a turn) RSS | < 180 MB |
| Idle battery | ≈ a messaging app; no measurable wakelock drain |
| Background CPU at idle | ~0 (event-driven) |

### Tactics

- **No hot model, no polling.** Triggers are push/event-based (notification callbacks, user input, `WorkManager`/`AlarmManager` schedules).
- **Single low-priority foreground service**, only when listeners are needed; otherwise fully idle.
- **Lazy everything** — accessibility tree serialized only on demand; vision/screenshots off by default.
- **Connection reuse** (OkHttp pool), **streaming** to minimize buffering.
- **Coroutine scopes tied to turn lifecycle** — released immediately when a turn ends.
- **Respect Doze & App Standby** — defer non-urgent work, batch via WorkManager.
- **No bundled native ML libs** in the base build (keeps APK and memory down; local-LLM is a separate flavor/module).

---

## 11. Security, Privacy & Safety

- **Encrypted at rest:** SQLCipher DB; API keys in Android Keystore.
- **Consent gates:** every dangerous tool requires explicit, contextual confirmation (with a clear preview of the action).
- **Audit log:** an append-only, user-visible log of every tool call, its args, and result.
- **Permission transparency:** a dashboard showing exactly which Android permissions and special accesses are granted and what they unlock.
- **Kill switch:** one tap pauses all autonomous activity and revokes the accessibility actuator.
- **No silent exfiltration:** the only outbound data is the LLM request the user's action initiated; show a per-request data preview option.
- **Prompt-injection defense:** content read from screens/notifications/web is treated as **untrusted** and clearly delimited; the agent will not execute instructions found in such content without user confirmation.
- **Scoped sessions:** optional per-domain isolation (e.g., a "work" agent vs "personal" agent) with separate memory.

---

## 12. UX Surface

- **Chat screen** — primary interface; text + push-to-talk voice (on-device STT first, e.g. Android `SpeechRecognizer`).
- **Confirmation cards** — inline approve/deny for Confirm-tier actions, with action preview.
- **Tool audit timeline** — scrollable history of what the agent did.
- **Permissions dashboard** — grant/revoke, with plain-language explanations.
- **Recipe manager** — view, edit, import/share App Recipes.
- **Settings** — provider/model, spend caps, default permission tiers, idle timeouts.
- **Quick tile / assistant intent** — invoke AndroidClaw as the device assistant (long-press home / power) for instant access.

---

## 13. Module Layout (Proposed)

```
app/                      # Compose UI, navigation, DI wiring
core-gateway/             # Orchestrator loop, ReAct controller
core-llm/                 # Provider interface + online adapters
core-tools/               # Tool registry, schemas, executor
core-control/             # AccessibilityService, Intents, recipes, notifications
core-overlay/             # Floating "Claw" overlay: foreground service + edge-tab UI
core-memory/              # Room + SQLCipher, retrieval, summarization
core-common/              # Serialization, logging, result types
feature-local-llm/        # (later) on-device inference flavor — optional module
```

Online-only build excludes `feature-local-llm` entirely, keeping the base APK minimal.

---

## 14. Roadmap

| Phase | Scope |
|---|---|
| **P0 — Skeleton** | App shell, chat UI, one online provider, gateway loop, `web_fetch` + `open_app`. |
| **P1 — Overlay & Actuator** | Floating "Claw" overlay (foreground service, edge-tab, expand/collapse), AccessibilityService, `read_screen`, `ui_action`, recipe engine, confirmation cards, audit log. |
| **P2 — Comms** | NotificationListener triggers, `send_message`/`reply_notification`, PIM tools, device settings. |
| **P3 — Memory & polish** | Long-term memory, summarization, spend caps, permissions dashboard, voice input. |
| **P4 — Multi-provider & recipes** | Gemini/OpenAI/compatible adapters, recipe sharing/import, scoped sessions. |
| **P5 — Local LLM** | `feature-local-llm` module, hybrid routing, offline mode. |

---

## 15. Open Questions / Risks

- **Accessibility fragility** across OEM skins (Samsung One UI, MIUI, etc.) — mitigated by recipes + freeform fallback, needs broad device testing.
- **Background restrictions** vary by OEM (aggressive battery killers) — may require user whitelisting; document per-OEM steps.
- **Play Store policy** on AccessibilityService usage is strict — may need to distribute via APK/F-Droid, or carefully justify accessibility use.
- **Prompt-injection from screen/notification content** — the biggest safety surface; treat all read content as hostile by default.
- **Cost predictability** for online LLMs driving multi-step UI loops — guardrails + cheap-model routing essential.

---

*This spec is a living document. Contributions and recipe additions welcome.*
