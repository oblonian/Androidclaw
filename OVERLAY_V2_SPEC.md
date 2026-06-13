# Overlay V2 — UX Spec

> Scope: fixes and features for the floating "Claw" overlay
> (`core-overlay/.../OverlayService.kt`). Grounded in the current
> View-based implementation (the overlay is **not** Compose — it builds
> `View`s directly so it can keep `FLAG_NOT_FOCUSABLE` and avoid stealing
> accessibility focus from the target app).

**Status:** Draft v0.1 — for the next release
**Target:** Android 10+ (same as the app)

---

## 0. Constraints that every item below must respect

These are load-bearing and must not regress:

1. **`FLAG_NOT_FOCUSABLE` stays set** except the brief moment the input box is
   tapped. If the overlay holds focus, `AccessibilityService.getRootInActiveWindow()`
   returns *our* view tree instead of the target app's, and every `read_screen`
   goes blind. See `restoreNotFocusable()` / `resolveConfirm()`.
2. **No dependency from `core-overlay` on `app` or `core-gateway`.** Customisation
   values and persistence must flow through the existing seams
   (`OverlayBridge`, `OverlayAgent`, `AppContainer` held by `OverlayAgentImpl`).
3. **Idle footprint.** The overlay is a low-priority foreground service; new
   features must not add polling, wakelocks, or always-on animations beyond the
   existing puck pulse.
4. **Credentials never touch the overlay theme store.** Customisation prefs are
   non-sensitive and live alongside other settings, but must not be cleared by
   `signOut()` (they are device preferences, not credentials).

---

## 1. BUG — Confirmation feels like a full-screen takeover

**Symptom (reported):** "a full screen appears when I have to press yes/no on
the overlay."

**Cause:** `handleConfirm()` calls `showExpanded()`, which rebuilds the entire
320 dp card — header + transcript area sized to `0.28 × screenH` (clamped
120–260 dp) + the confirm panel + the input row — at `alpha = 0.96`. With the
confirm panel stacked on top of a tall transcript and an input row, the panel
occupies a large, near-opaque slab of the screen. On smaller phones this reads
as "full screen," and it covers the very UI the user is being asked to approve.

**Fix — a compact confirm chip, decoupled from the full panel:**

- When `pendingConfirmText != null`, do **not** force the full expanded card.
  Render a **compact confirm bar**: one line describing the action
  (`🔔 Claw wants to: Tap "Subscribe"`) plus the three buttons
  (`Do it / Skip / Stop`), at `WRAP_CONTENT` height, anchored near the puck's
  last position (`collapsedX/collapsedY`) rather than re-centering a big card.
- Collapse the transcript area to a **single-line peek** (or hide it) while a
  confirm is pending; a "show transcript" affordance expands it on demand.
- Keep `alpha` lower for the confirm bar (≈0.92) and make it as **narrow as the
  content** so the underlying app stays visible behind/around it — the user can
  see what they're approving.
- The full card remains available via the header/expand control; confirm should
  be the *lightweight* path.

**Acceptance:** triggering a confirm over YouTube shows a small bar, not a slab;
the video remains visible; `FLAG_NOT_FOCUSABLE` stays set; tapping `Do it`
resolves via `resolveConfirm(OverlayDecision.Proceed)` exactly as today.

---

## 2. BUG — Overlay sessions don't appear in the app

**Symptom (reported):** conversations held through the overlay never show up in
the app's history (Playbook screen).

**Cause:** two independent histories.
- The **app** path: `ChatViewModel` owns `items`/`conversation` and persists via
  `SessionStore.save(items)`.
- The **overlay** path: `OverlayAgentImpl` keeps its own
  `conversation: List<ChatMessage>` in memory and **never** calls
  `SessionStore`. `reset()` just drops it. So overlay turns are invisible to
  `PlaybookScreen`, which reads `SessionStore.loadAll()`.

**Fix — persist overlay turns through the shared `SessionStore`:**

- `OverlayAgentImpl` already holds `AppContainer`; give it access to the same
  `SessionStore` instance the app uses (add `sessions` to `AppContainer` if not
  already shared, so app + overlay write to the one `claw_sessions.json`).
- On `AgentEvent.TurnComplete` in `OverlayAgentImpl.runTurn`, convert the
  running `conversation` into `List<SavedMessage>` and save it. Two viable
  models:
  - **Append-to-current-session** (preferred): keep a `currentSessionId`. The
    first turn creates a `SavedSession`; subsequent turns in the same overlay
    "sitting" update that session in place (so a multi-turn overlay chat is one
    history entry, mirroring the app).
  - **Save-per-completed-conversation:** save the whole `conversation` on each
    `TurnComplete`, replacing the prior snapshot for that session id.
- `SessionStore` needs an **upsert** (`save(id, messages)`) so overlay updates
  replace rather than always prepend. Today `save()` always creates a new id and
  prepends; reuse the title logic but key on id.
- `reset()` (overlay "clear") starts a fresh `currentSessionId` so the next
  sitting is a new history entry.
- Reverse direction (nice-to-have): when the overlay opens, it could *load* the
  most recent app session so the user continues the same thread. Out of scope
  for V2 unless cheap.

**Acceptance:** run a 2-turn conversation entirely from the overlay, open the
app → the conversation appears as a single entry in Playbook with the correct
title and both turns. Deleting it in Playbook removes it everywhere.

**Note:** keep this on-device only (SPEC §1.5 local-first). No new network.

---

## 3. FEATURE — Window controls: minimize, resize, close

Today the only chrome is the `▾` collapse button (→ puck) and the foreground
notification's "Stop." Add an explicit, discoverable control cluster in the
header.

- **Minimize** (`▾`, exists): collapse to the puck. Keep.
- **Close** (`✕`): stop the overlay service entirely (`OverlayService.stop`),
  equivalent to the notification's Stop action. Confirm-on-close optional.
- **Resize:** a drag handle (bottom-right corner grip) that adjusts the card
  width (`params.width`) and the transcript height within sane bounds
  (e.g. width 260–`screenW-16dp`, transcript height current 120–260 dp range but
  user-driven). Persist last size to settings (see §4) and restore on
  `showExpanded()`. Reuse the existing `attachDragAndTap` touch-slop pattern but
  mutate `params.width`/transcript `LayoutParams` instead of `x/y`.
- All controls must call `restoreNotFocusable()` where a focus change could have
  occurred, before any teardown.

**Acceptance:** header shows minimize + close; a corner grip resizes the card;
size survives collapse→expand and service restart (persisted).

---

## 4. FEATURE — Overlay customisation (color, opacity, theme, logo, background)

The overlay currently hardcodes its palette (e.g. header gradient
`#D2512A → #7366BD`, panel `#F4F5FB`, puck ring `#5C6BC0`, idle alpha `0.55`,
expanded alpha `0.96`). Make these user-driven.

**4.1 Storage — new non-credential prefs in `SettingsStore`:**

| Pref | Type | Default | Notes |
|---|---|---|---|
| `overlayTheme` | enum `LIGHT/DARK/AUTO/CUSTOM` | `LIGHT` | drives the base palette |
| `overlayAccent` | color int (hex) | `#D2512A` (coral) | header + send button + puck ring |
| `overlayPanelOpacity` | float 0.6–1.0 | `0.96` | expanded card `params.alpha` |
| `overlayPuckOpacity` | float 0.3–1.0 | `0.55` | idle puck `params.alpha` |
| `overlayPuckGlyph` | string | `🦞` | the puck logo/emoji; allow any emoji/char |
| `overlayBackground` | enum `SOLID/GRADIENT/...` | `SOLID` | transcript/card background style |

These are device preferences — **exclude them from `signOut()`**.

**4.2 Overlay reads a theme object, not hardcoded literals:**

- Introduce an `OverlayTheme` data class (accent, panelColor, textColor,
  panelAlpha, puckAlpha, puckGlyph, ringColor…). `core-overlay` exposes it; the
  app supplies it via a new `OverlayBridge.theme` field (volatile), refreshed
  whenever settings change, the same injection pattern as `OverlayBridge.agent`.
- Replace every `Color.parseColor("#…")` / `params.alpha = …` literal in
  `showCollapsed()`, `showExpanded()`, `buildHeader()`, `buildInputRow()`,
  `buildConfirmPanel()` with reads from the current `OverlayTheme`. Keep current
  values as the defaults so nothing changes until the user customises.
- `AUTO` theme follows system dark mode (`Configuration.uiMode`).

**4.3 Settings UI:**

- New **"Overlay appearance"** `SettingsSection` in `SettingsScreen.kt`:
  theme radio (Light/Dark/Auto/Custom), accent color picker, two opacity
  sliders (panel, puck), a puck-glyph text field, background style selector.
- A small **live preview** card showing the puck + header with current choices
  (optional but recommended; pure Compose, no overlay needed for the preview).

**Scope guard:** "logo" / "background images" = ship emoji/char glyph + solid &
gradient backgrounds in V2. Arbitrary user image backgrounds (bitmap from
gallery) is a stretch goal — gate behind a follow-up to avoid bitmap/memory
work in the foreground service.

**Acceptance:** changing accent + panel opacity in Settings is reflected the
next time the overlay is shown (and live if it's open); defaults reproduce
today's look exactly.

---

## 5. FEATURE — Copy conversation from the overlay

**Symptom (reported):** no way to copy text out of the overlay transcript.

The transcript is a `SpannableStringBuilder` rendered into a plain `TextView`
(`transcriptView`), so there's no selection or copy today.

**Fix — add explicit copy affordances:**

- **Copy all:** a small `⧉`/"Copy" control in the header (or an overflow menu
  next to minimize/close) that copies the full transcript plain text to the
  clipboard via `ClipboardManager`, with a brief toast ("Copied"). Strip the
  `You:` / `Claw:` span colors — copy the raw text.
- **Copy last reply:** optional secondary action that copies only the most
  recent assistant turn (track `assistantLineStart` / last turn range).
- **Selection (stretch):** set `transcriptView.setTextIsSelectable(true)`. Note
  this can request focus — guard with `restoreNotFocusable()` after any
  selection interaction so accessibility reads aren't broken. If it proves
  fiddly with `FLAG_NOT_FOCUSABLE`, ship the button-based "Copy all / Copy last"
  only and skip inline selection.

**Acceptance:** a Copy control puts the conversation on the clipboard; pasting
elsewhere yields clean `You: … / Claw: …` text; accessibility reads still work
afterward (flag restored).

---

## 6. Suggested sequencing

1. **§2 sessions persistence** and **§1 compact confirm** — these are bugs and
   the highest-value fixes.
2. **§5 copy** — small, self-contained.
3. **§3 window controls** — minimize/close are trivial; resize is the larger bit.
4. **§4 customisation** — biggest surface; theme indirection first, then the
   Settings UI, then stretch goals (image backgrounds).

Each item above is independently shippable and preserves the four constraints in
§0.
