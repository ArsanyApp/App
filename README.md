# Choice Auto Tap

A no-root Android touch-macro app (Kotlin, Jetpack Compose, minSdk 26). Build a sequence of taps,
long-presses, swipes, waits, text input and app launches, then replay it in **any app**, looping as many
times as you want without touching the phone.

It works through an **AccessibilityService**:

- `dispatchGesture()` performs the taps, long-presses, double taps and swipes.
- `performGlobalAction()` handles Back, Home and Recents.
- `TYPE_ACCESSIBILITY_OVERLAY` windows show the floating bubble, toolbar and crosshair markers.
- The accessibility node tree drives "wait until text appears", `ACTION_SET_TEXT` and the Paste popup.
- The key-event filter catches the emergency stop (volume-down 3 times).

## Project layout

| Module | Type | What it contains |
|---|---|---|
| `macro-model` | Pure Kotlin/JVM | Macro and step model, JSON import/export, coordinate scaling (% ↔ px), randomization, loop shift, editing operations, recording → steps conversion |
| `macro-player` | Pure Kotlin/JVM | `MacroPlayer`: loops, pause/resume, time limit, "wait for text" timeout policies, run log. It talks to the device only through the `ActionExecutor` interface |
| `gesture-engine` | Android library | `ActionExecutor` built on `AccessibilityService`: `GestureFactory`, `GestureDispatcher`, `NodeFinder`, `TextInjector`, `AppLauncher`, `ClickRecorder` |
| `overlay-ui` | Android library | Floating `ControlBubble`, `MarkerEditor` (toolbar and draggable numbered crosshairs), `TextPrompt` |
| `app` | Android app | Compose UI (macro list, editor, logs, saved texts, setup), Room database, `AutoTapAccessibilityService`, `PlaybackService` (foreground service) |

The model and player have no Android dependencies, so their unit tests run on a plain JVM.

## Build and install

1. Open the project folder in **Android Studio** (Ladybug or newer, JDK 17+).
2. Let Gradle sync. It downloads AGP 8.7 and Android SDK 35 if needed.
3. Connect your phone with USB debugging on, then press **Run ▶** (or run `./gradlew :app:installDebug`).
4. Run the unit tests with `./gradlew test`.

Every push also runs the GitHub Actions workflow in `.github/workflows/android.yml`, which runs the tests
and uploads a debug APK as a build artifact.

## Enable permissions

Open the app, then **⋮ → Permissions & setup**. Each row shows ✓ once it's granted and has a button that
opens the matching settings page:

1. **Accessibility service** (required). Go to Settings → Accessibility → Installed apps → *Choice Auto Tap* → On.
   On Android 13+ with a sideloaded APK, the switch may be greyed out as a "Restricted setting". If so,
   open **App info → ⋮ → Allow restricted settings**, then try again.
2. **Display over other apps** (recommended). The overlays themselves are accessibility overlays and don't
   need this permission. It lets the playback foreground service start when you press ▶ on the bubble
   while another app is on screen.
3. **Battery optimization → Unrestricted** (recommended for long or infinite runs).
4. **Notifications** (Android 13+). The notification shows progress and has **Pause** and **STOP** buttons.

Once the service is on, a green floating bubble appears. Drag it by ⠿, or tap ⠿ to open the app.

## Your first 3-step macro

1. In the app, tap **New macro** and name it, for example "Test".
2. Tap **Markers**. The app moves to the background and a blue toolbar appears over whatever app is open.
   Switch to the app you want to automate.
3. On the toolbar, tap **+Tap**. A numbered red crosshair appears. Drag it onto the button you want pressed.
4. Tap **+Wait** and enter `1500` (or a range like `1000-3000` for a random wait).
5. Tap **Back** to add a global Back action.
6. Tap **✔ Done**. The steps are saved as percentages of the screen.
7. Back in the app, open the macro. Under **Loop settings**, turn off "Repeat forever" and set
   **Repeat count = 1**. Check the steps, then press **▶**.
8. The bubble counts down 3 s (switch to the target app now), then shows `Loop 1/1 · Step 2/3`.

When that works, increase the repeat count or turn on "Repeat forever". The default gap between loops is
a random 20–45 s.

**Stop at any time:** press volume-down 3 times quickly, tap **STOP** in the notification, or tap **■** on the bubble.

## Features

### Step types
- **Tap**, **Long-press** (default 800 ms), **Double tap**, **Swipe/scroll** (start → end, duration)
- **Wait**: fixed or random min–max
- **Back / Home / Recents**
- **Paste text**, in one of two ways:
  - *Direct* (recommended): optionally taps the field, then finds the focused editable node and uses `ACTION_SET_TEXT`.
    Arabic/RTL and emoji work. Can append instead of replacing.
  - *Popup*: copies the text to the clipboard, long-presses the target, then taps the node labelled "Paste"
    (labels are configurable, e.g. `Paste, لصق`). If no popup is found, it taps a fixed position or uses `ACTION_PASTE`.
  - Text can come from **Saved texts** (⋮ → Saved texts).
- **Launch app**: pick from the installed apps. In marker mode, **+App** adds the app currently on screen.
- **Wait until text appears**: polls every window's node tree (contains or exact match) until a timeout.

### Record mode
Tap **●** on the bubble, use the target app normally, then tap **■**. Each `TYPE_VIEW_CLICKED` or
`TYPE_VIEW_LONG_CLICKED` event becomes a Tap or Long-press step at the center of the clicked view, and the
time between clicks becomes the step delay. Clicks are appended to the active macro, or saved as a new one
if none is active. Recording only captures views that report click events. Taps on games or custom-drawn
canvases are not recorded, so use marker mode for those.

### Editor
- Drag ≡ to reorder, or use ▲/▼. Each step can be edited, duplicated, deleted, or turned off.
- Per step: delay after, extra random delay (0–N ms), random offset (±px), and whether the loop shift applies.
- Coordinates are stored as a **% of the screen**, so macros survive rotation and resolution changes.
- Macros are saved in Room. Use ⋮ → **Export all / Import** or a macro's ⋮ → **Export JSON** to back them up or share them.

### Running
- Repeat N times or forever, a random delay between loops, a stop-after-time limit, and a start countdown.
- **Per-loop coordinate shift**: for example ΔY = −150 px moves each target 150 px up on every loop, to
  walk through a list that doesn't reorder.
- If a **"wait until text"** step times out, the macro can **skip the rest of the loop**, **retry the loop
  from step 1** (up to N times, then skip), or **stop the run**. This is set per macro.
- During a run, markers are hidden. The bubble also becomes click-through for the instant each gesture
  is injected, so it never swallows a tap.
- The screen stays awake during a run, and a foreground service with a partial wake lock keeps it alive.
- **Run log** (list icon on the home screen): each run's start and end, why it ended, and per loop the
  start/end time, steps done, retries, failed gestures, and which "wait until text" steps timed out.

## JSON format

```json
{
  "format": "choice-auto-tap",
  "version": 1,
  "macros": [{
    "name": "Example",
    "steps": [
      { "action": { "type": "tap", "point": { "x": 0.5, "y": 0.82 } }, "delayAfterMs": 800, "randomOffsetPx": 6 },
      { "action": { "type": "paste_text", "text": "مرحبا 👋", "mode": "SET_TEXT" } },
      { "action": { "type": "wait_text", "text": "Sent", "timeoutMs": 8000 } },
      { "action": { "type": "global", "action": "BACK" } }
    ],
    "loop": { "repeatCount": 10, "loopDelayMinMs": 20000, "loopDelayMaxMs": 45000 },
    "onWaitTimeout": "SKIP_LOOP"
  }]
}
```

Step `type` values: `tap`, `long_press`, `double_tap`, `swipe`, `wait`, `global`, `paste_text`,
`launch_app`, `wait_text`. Fields you leave out take their default values.

## Limitations
- Android blocks gestures on some secure screens, such as lock screens, some banking apps, and system
  permission dialogs.
- Some apps set `FLAG_SECURE` or hide their views from accessibility. "Wait until text" and "Paste" (direct)
  can't see those; plain taps still work.
- Use it responsibly and follow the terms of the apps you automate.
