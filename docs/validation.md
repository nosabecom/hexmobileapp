# Validation — 2026-10-30

## Build and environment

Built on **Godseye**, Windows x64, JDK 21, Android SDK 36, Gradle 9.1.0.
Test devices: `T3_Phone_API_36`, Android API 36 x86_64, `emulator-5554`;
Samsung S21 **SM-G991W**, Android 15, user 0, authorized wireless ADB.
Inference: RTX 4060 Laptop GPU, 8 GB VRAM, transcribe.cpp 0.4.1 Vulkan backend.
The runtime enumerated both GPUs and selected `Vulkan0` (the NVIDIA GPU).

The native Android build and `:app:lintDebug` pass. Lint still reports
non-fatal localization/version/style warnings; no lint errors remain.

## Executed tests

**11 Android instrumentation tests passed** (`OK (11 tests)`, 64.647 seconds
in the final run). The suite checks:

- Replacing selected text while preserving surrounding text.
- Refusing insertion into a different field.
- Refusing to overwrite text edited while a transcript is pending.
- Inserting into an empty field without copying its placeholder.
- Hiding/excluding password fields and hiding on the home screen.
- Discarding a cancelled session's late result.
- WAV header construction and UTF-16 selection replacement.
- Starting and cancelling a real microphone foreground service through the
  visible capture activity on Android 16.
- Transcribing the JFK WAV sample through **both Parakeet Unified English and
  Cohere Transcribe**, then inserting the Parakeet transcript into the separate
  fixture application's field.
- Keeping the server-token input visually masked.

The 0.1.1 appearance update also passed three targeted emulator checks: microphone
startup/cancellation, password hiding, and changing/reopening the appearance sliders
then checking the real overlay's idle and recording widths. The window-inspection
test initially lacked UiAutomation's interactive-window flag; adding that flag made
the measurement work. The production APK was unchanged by that test correction.
The appearance screen was inspected visually (see `appearance.png`).

**7 server tests passed**: auth, audio dispatch, silence suppression, malformed
and oversized uploads, truncated WAV rejection, catalog availability, and failed
checksum handling.

Manual emulator checks: dragged the bubble, tapped it to start actual recording,
verified the microphone foreground notification, inspected the idle and recording
surfaces, and tapped to stop. The existing Gboard keyboard remained available.
The emulator was started with audio input disabled, so live human microphone
recognition and audio-reactive appearance with an actual voice need phone testing.
Real recognition was tested with the prerecorded speech fixture, not fabricated text.

## Private server deployment

Godseye working directory: `C:\Users\nomok\source\hexmobileapp`.
Server listens on **127.0.0.1:8765** only. Tailscale Serve proxies it at:

`https://godseye.tail3123d8.ts.net:8444`

The endpoint requires the private server token in addition to tailnet access.
The existing Tailscale routes on 443 and 8443 were preserved. No Funnel, public
listener, router port forward, or tailnet-wide policy change was created.

Windows scheduled task **Hex Mobile Inference** runs under the existing user at
logon. It was started and its HTTPS health check passed after deployment.
Godseye must remain awake, logged in, and online for this mode.

Both installed models were verified against Hex's pinned SHA-256 checksums.
The four other selectable catalog entries have **not** been downloaded or
benchmarked in this session. They remain explicitly marked not downloaded.

The emulator does not resolve the private MagicDNS hostname, so its app requests
used `adb reverse tcp:8765 tcp:8765` with debug-only loopback HTTP. Real requests to
both models over the Tailscale HTTPS route were tested separately from Godseye.
End-to-end S21 → Tailscale HTTPS → Godseye subsequently passed for both installed
models, with Parakeet text inserted into the separate test app. No ADB reverse or
LAN HTTP endpoint was used on the physical phone.

During visual validation, the settings field was found to display the server token
because Android's single-line transformation replaced password masking. Explicit
password masking and a regression test were added, the credential was rotated,
and the affected screenshots were removed. The final APK contains that fix.

## S21 status and limitations

The S21 is online in Tailscale as `nosakhares-s21.tail3123d8.ts.net`.
Hex, its instrumentation APK, and the separate test-field app were installed via
an authorized wireless ADB connection. Hex accessibility, microphone and
notification permissions are enabled. Wispr Flow's accessibility service is
disabled and its app is force-stopped (not uninstalled).

The initial phone suite passed 10 of 11 tests. The remaining real-model test
transcribed successfully but could not insert because the 30-second screen timeout
locked the phone during the request. The fixture now keeps its own window awake,
and the test captures the target before the network request, matching actual use.
After Nosa unlocked the phone, that test passed with **both models** in 21.776s.
The phone's system screen timeout was not changed.

The **0.1.1 APK passed all 12 tests in one S21 run** (`OK (12 tests)`,
50.767 seconds). This includes the saved size/transparency controls, actual overlay
resizing in idle and recording states, all field/insertion safeguards, microphone
startup/cancellation, token masking, and both real model transcriptions over HTTPS.
Parakeet was selected again after the suite. Default appearance is 100% size and
30% transparency. No paid API was used. After instrumentation ended, Hex’s
accessibility service was rebound and confirmed running outside the test process.
The emulator test script also restores that service after its run.


Wispr Flow's idle bubble was inspected over the test app with the Samsung keyboard
open, then Flow was stopped and Hex restored. No recording was sent to Flow.

On-phone GGUF inference has not been built or benchmarked. The optional local mode
uses Android's on-device SpeechRecognizer. The S21 reports that a recognizer is
available; its downloaded language support and recognition quality remain
unverified. It never falls back to the cloud recognizer.

This first build transcribes after stop, with English selected on the server;
streaming partial transcripts and full desktop-Hex feature parity are not claimed.
`ACTION_SET_TEXT` depends on each app's accessibility support and may replace rich
text formatting. Live human speech, phone-specific Samsung background behavior, Bluetooth microphones,
rotation, long-form accuracy, reconnect/reboot behavior, and power consumption
remain physical-device validation work.

## Returning to the original app

Nosa found that tapping the bubble could bring Hex settings to the foreground.
The microphone bootstrap activity inherited Hex's task affinity, so launching it
reused an existing settings task. Earlier microphone checks did not leave that
settings task open and missed this case.

A new regression leaves Hex settings open in the background and injects a real
touch on the overlay in the separate fixture app. It failed on 0.1.1 with
“Tapping Hex must keep the original app in front.” Version 0.1.2 gives the
transparent microphone activity no task affinity, launches a separate temporary
task without animation, and removes only that task once recording starts.
The regression and microphone startup/cancellation test then both passed on the
emulator in 12.992 seconds. The 0.1.2 build and lint passed.

The final 0.1.2 APK then passed **all 13 tests on the S21** (`OK (13 tests)`,
45.314 seconds), including the actual bubble tap with Hex settings left open,
microphone recording in the original app, and both real model transcriptions.
Hex accessibility was rebound after instrumentation, and Parakeet was selected
again for normal use. The idle overlay was visually confirmed above the Samsung
keyboard outside instrumentation (`s21-bubble.png`); appearance controls are shown
in `s21-appearance.png`.

## APK

`artifacts/hex-mobile-0.1.2-debug.apk`

SHA-256: `690714efc8994f7d3d2835612136756c61475f4abb8aaddda39186209acec827`

This is a development APK signed with Godseye's Android debug key, not a release
or Play Store package. Keep using the same signing key for in-place updates.
