# Node Tree Demo — what an accessibility scraper can actually see

A minimal, self-contained Android app that **recreates only the live node-tree
recording technique** from the analyzed Accountable2You APK (its `v2` class),
and **shows the result on-device** instead of storing or uploading it.

## What it does
- Registers an `AccessibilityService` for **every** event type with
  `canRetrieveWindowContent=true` + `flagIncludeNotImportantViews` — the same
  configuration the real app uses.
- On each event it reproduces the real app's extraction:
  - joins the event text,
  - resolves the window / pane title (`getPaneTitle`, `AccessibilityWindowInfo.getTitle`),
  - **recursively walks the live view tree**, reading every node's
    `getText()` / `getContentDescription()`,
  - detects URLs with `URLUtil`.
- It reproduces the real app's **password-field redaction** (inputType password
  variations, `isPassword()`, `•` mask, "password" hint) so you can see both what
  *is* captured and what is deliberately skipped — rendered as
  `[REDACTED password/secret field]`.
- It displays everything two ways: an **in-app log grouped per app** (with a
  dropdown filter to focus on one package), and a floating **overlay HUD**
  (`TYPE_ACCESSIBILITY_OVERLAY`) that hovers over every other app showing "what
  it sees" in real time.

## Reading the capture
- Every record is **timestamped** (`HH:mm:ss.SSS`) and filed under a
  `──────── <package> ────────` header, so you can see per-app extraction without
  relying on the overlay.
- The stream is **coalesced** (identical consecutive captures within 2s are
  dropped) and the view refreshes on a slow, readable cadence.
- **Pause** freezes the view so you can scroll up through earlier captures; the
  buffer keeps filling in the background. **Resume** jumps back to the live tail.
- Use the **app filter** dropdown to show only one app's captures.

## What it deliberately does NOT do
- **No `INTERNET` permission** (verified: the APK requests *zero* permissions).
  Nothing it observes can leave the device.
- No screenshots / MediaProjection, no persistence, no database, no upload.
  This is purely a visualizer of the node-tree read.

## Build
```bash
bash build.sh        # -> build/NodeTreeDemo.apk  (debug-signed)
```
Requires: Android SDK build-tools 35, platform android-35, JDK 21. No Gradle.

## Run (emulator or device)
```bash
adb install -r -g build/NodeTreeDemo.apk
adb shell monkey -p com.example.nodetreedemo -c android.intent.category.LAUNCHER 1
# In the app tap "Enable service" and turn on "Node Tree Demo" under Accessibility,
# then open any other app and watch the log / overlay.
```

## Verified behavior (API 37 emulator)
- Service enabled → overlay appears over the Settings app:
  `NodeTreeDemo sees: app: com.android.settings (155 on-screen nodes read)`.
- Log captured real text scraped from other apps, e.g. from the Google Services
  settings screen: "Sign in to your Google Account to get helpful service
  recommendations", "All services", "Give feedback", with timestamps and event
  types (`TYPE_WINDOW_CONTENT_CHANGED`, `TYPE_WINDOWS_CHANGED`).
- Password/secret nodes shown as `[REDACTED password/secret field]`.

## Files
- `src/com/example/nodetreedemo/NodeTreeService.java` — the recreation (the interesting part).
- `src/.../MainActivity.java` — enable button, live log, overlay toggle.
- `src/.../CaptureLog.java` — in-memory ring buffer (where the real app would queue for upload).
- `res/xml/accessibility_config.xml` — service capability declaration.
