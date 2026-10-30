# 0.1.4 — keep the editor active and recover with a hold

The 0.1.3 recording bootstrap preserved window focus but still launched an
activity. A new lifecycle counter in the separate editor fixture reproduced an
extra pause/resume every time the bubble started recording: `Resumes 2, pauses 1`
became `Resumes 3, pauses 2`. An app that refreshes on resume can therefore show
its own loading screen even though Hex never draws a full-screen window.

Recording now starts directly from the enabled accessibility bubble. The capture
activity, manifest entry, and theme were removed. Microphone permission, visible
recording indication, foreground notification, cancellation, and session checks
remain. No temporary task or activity opens. Both Android 16 and Samsung Android
15 allowed microphone access through this path, including outside instrumentation.

## Recovering a transcript

- Hold the idle bubble for 600 ms to insert the last transcript at the current
  cursor/selection. A haptic response confirms the gesture. No recording starts.
- The current field is checked again; password fields, locked screens, and Hex's
  own settings remain excluded. Surrounding text is preserved.
- Rejected or unconfirmed automatic insertions leave a small gold dot on the blip.
  An accessibility action returning true is no longer treated as proof that the
  text changed. The last transcript remains recoverable even after a confirmed
  automatic insertion, for editors that subsequently discard it.
- If recovery's direct edit is rejected, Hex copies the text and requests the
  editor's Paste action. If an edit was accepted but cannot be confirmed, Hex
  copies it without issuing a second insertion that could duplicate the text.
  Feedback tells the user when the editor's normal Paste command is needed.
- Clipboard fallback happens only after the explicit hold or Copy action. The
  clip is marked sensitive to suppress supported system previews. Recovery data
  stays in memory; it is not persistent history.
- Holding while busy still cancels. Dragging and cancelled touches do neither.
  A hold that began while busy cannot turn into a paste if a result arrives
  before the 600 ms threshold.

## Validation

- Android build and lint passed on Godseye.
- The old build failed the new lifecycle assertion, establishing the regression.
- The initial updated build passed all 21 emulator tests in 205.604 seconds,
  including both installed models, native selection handling, rejected/ignored
  edits, clipboard/Paste fallback, recovery into a different field, password
  exclusion, drag/touch cancellation, keyboard visibility, and lifecycle continuity.
- The S21 passed all 20 UI/recovery checks in 44.606 seconds over Tailscale. The
  dual-model test was excluded from this phone run to keep the selected model
  available for normal dictation.
- After the final cancellation-race fix, the emulator passed the lifecycle and
  hold-to-recover checks. Its first attempt at the added race test timed out in
  accessibility-service setup during boot; once boot finished, that test passed
  in 3.405 seconds. This setup failure is not counted as a passing test.
- The final APK then passed all three targeted S21 regressions (startup lifecycle,
  hold-to-recover, and result-arrives-during-cancel) in 9.346 seconds. The installed
  package was checked against the release APK hash.
- A normal tap in the actual T3 Android app started the microphone, with Android
  reporting `RECORD_AUDIO: allow (running)` and the microphone foreground service.
  T3 remained the focused window, its last pause/resume event did not change, and
  the keyboard remained visible in a captured frame. Holding cancelled recording.
  This check did not send a message or publish the private screen capture.

## Processing latency

The larger waits observed during this session coincided with model switching and
preparation while emulator/build work was running on Godseye. The dual-model test
measured 76,784 ms for its Parakeet request and 43,591 ms for Cohere. These include
preparation/queueing and are not warmed inference measurements. A separate prepare
request waited 56,379 ms before returning; subsequent local Parakeet requests took
about 140 ms. The emulator was stopped and Parakeet left loaded afterward.

An additional phone test sent the same 352,078-byte, approximately 11-second JFK
sample three times through the configured Tailscale HTTPS connection:

| Attempt | Phone request | Server processing including upload |
| --- | ---: | ---: |
| 1 | 188 ms | 168 ms |
| 2 | 151 ms | 135 ms |
| 3 | 220 ms | 207 ms |

All three returned the expected speech. These measurements exclude recording time
and field insertion, and do not guarantee latency after a host sleep, model switch,
or network change. Model-switching tests share the single resident server engine;
avoid running them while using the live dictation service. No periodic GPU keepalive
or additional phone wake lock was added.

APK: `hex-mobile-0.1.4-debug.apk` (version code 5).
SHA-256: `20c102646305fee783d46dfd32eeb2d4cf91e6578da184590d14c881e24e023c`.
