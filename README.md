# Multi-Touch Tester

A QA / accessibility tool for testing how Android apps handle **two simultaneous, continuously
held touch contacts** — the kind of input needed to test pinch/rotate gesture handling,
touch-state persistence, and multi-touch accessibility behavior.

## How it works

- The floating control panel and two draggable markers are drawn with `TYPE_ACCESSIBILITY_OVERLAY`
  windows, owned by an `AccessibilityService`.
- **Start Test** builds a `GestureDescription` with two `StrokeDescription`s (one per marker),
  each starting with `willContinue(true)`.
- When the OS reports a segment complete, the service immediately calls `continueStroke()` at
  the same coordinates for both fingers and re-dispatches. Chained tightly, this reads to the
  receiving app as one continuous two-finger press — not a sequence of taps — until you press
  **Release** or **Emergency Stop**, which ends both strokes in the same `GestureDescription`
  so they lift together.
- `onDestroy`, `onUnbind`, and `onInterrupt` all force-release any active touches, so the
  service can never leave synthetic contacts stuck down if it's killed or disabled.
- Screen rotation / display changes during an active test are treated as unsafe: the service
  releases immediately and reports `ERROR`, rather than continuing to press coordinates that
  may now land on something else.
- The accessibility config requests `canPerformGestures` only — `canRetrieveWindowContent` is
  explicitly `false`. The service cannot read the screen or other apps' content; it only
  injects the two touches you positioned.

## Honest limitations

- `dispatchGesture` is a documented, public API, but Android does not guarantee indefinite
  gesture duration in one call — this app works around that with the continuation technique
  above, but very aggressive OEM battery/background restrictions could still throttle a
  backgrounded accessibility service on some devices.
- This cannot and does not attempt to inject touches into another app that itself blocks
  accessibility-synthesized input (e.g. apps using `FLAG_SECURE`-adjacent anti-automation
  checks, or ones that specifically detect `AccessibilityService`-originated `MotionEvent`
  flags). If `dispatchGesture` returns `false` or the OS cancels the gesture, the UI reports
  `ERROR` with that fact — it does not try alternate/undocumented paths to force it through.
- Requires the user to manually grant "draw over other apps" and manually enable the
  Accessibility Service in Settings; neither can be nor is auto-granted.

## Building

The Gradle wrapper jar is a binary and isn't included here — generate it once locally with
Gradle installed:

```bash
gradle wrapper --gradle-version 8.7
```

commit the resulting `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`, then
either run `./gradlew assembleRelease` locally or push to `main` / trigger the included
`.github/workflows/android-release.yml` workflow to build it in CI (unsigned `app-release.apk`
unless you add a `signingConfig` backed by GitHub Secrets, as described in that workflow).
