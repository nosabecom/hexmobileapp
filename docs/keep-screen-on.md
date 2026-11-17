# 0.2.2 — keep the screen awake while using Hex

**Keep screen on while using Hex** appears on the main screen and defaults to on,
including on existing installations. It is saved locally and applies immediately.

All Hex activities keep their visible windows awake. The accessibility overlay
keeps the screen awake during microphone startup, recording, transcription,
customization processing, and transcription retries. Completion, failure, and
cancellation clear the overlay flag. An idle bubble allows the normal screen
timeout. Removing the overlay or stopping the accessibility service removes its
window as well. Disabling the setting restores normal timeout behavior everywhere.

The implementation uses the existing windows' Android
[`FLAG_KEEP_SCREEN_ON`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON)
flag. It adds no wake-lock permission, does not change system timeout settings,
and does not open a temporary activity above the editor. Manually locking the
phone still cancels dictation through the existing screen-off handling.

## Validation — 2026-11-17

- Debug app and instrumentation APK builds passed.
- All 10 JVM unit tests and 19 Python server tests passed.
- Android lint passed with zero errors and 31 warnings.
- Added instrumentation coverage for startup, recording and transcription screen
  flags, changing the preference during a session, release on completion/failure/
  cancellation, idle behavior, and preserving the editor lifecycle. A separate
  test covers the real settings switch, preference persistence after reopening,
  and screen flags in appearance, history, customizations, and model screens.
- At the initial build, instrumentation tests compiled but no device was
  available. The S21 was connected wirelessly later the same day; results follow.

## S21 installation and verification — 2026-11-17

- Installed the signed 0.2.2 update over the phone's existing wireless debugging
  pairing. Android reports version 0.2.2, code 8. App data was preserved.
- All three targeted instrumentation tests passed in 11.606 seconds:
  `ScreenAwakeTest#togglePersistsAcrossScreensAndReopening`,
  `DictationTest#screenStaysAwakeOnlyDuringEnabledDictationAndReleasesAfterEveryOutcome`,
  and `DictationTest#microphoneStartsFromBubbleAndCancels`.
- The fixture now has a screen-timeout test mode that omits its own keep-awake
  flag. The regression checks Android's actual `mHoldScreenWindow`, confirming
  that Hex owns the screen hold during startup and transcription and releases it
  when the setting is disabled or dictation finishes. It also checks the overlay
  flags during recording and after completion, failure, and cancellation, and
  verifies that the editor lifecycle is unchanged.
- The settings test covers toggling, reopening, and all Hex settings screens.
  The microphone test starts and cancels a real microphone foreground service.
- Instrumentation stops the app process when it exits. Reinstalled the same
  signed app afterward to restore the accessibility binding, then confirmed Hex
  is enabled and bound, the crashed-service list is empty, and no dictation
  foreground service remains.
- App and fixture lint passed after the test changes. These changes affect only
  the tests and fixture; the installed app is the signed APK identified below.
- The Device panel still cannot discover the connected phone, so installation
  and the project's test runner used the existing wireless ADB connection. No
  manual device control was performed. An elapsed-time screen-timeout check and
  physical power-button locking remain manual checks.

Test-run output: `artifacts/s21-screen-awake-tests-20261117.txt`.

On-device check: set a short screen timeout, record past it, and verify the screen
stays lit while Hex records and transcribes. Finish or cancel, leave the idle
bubble alone, and verify the normal timeout returns. Repeat with the switch off,
then on again, and verify that the power button still locks and cancels recording.

The previous outstanding source changes were committed separately before this
feature, including transcript history, NewEra customization sync, recording retry,
hidden-cursor insertion, and server startup fixes. Runtime tokens, private desktop
settings, signing keys, dependencies, and build outputs remain excluded from Git.

APK: `artifacts/hex-mobile-0.2.2-debug.apk` (version code 8). Signed with Godseye's
original Android debug key; the certificate matches the prior retry APK, allowing
an in-place update that preserves app data. The temporary signing-key copy was
removed after signing.

APK SHA-256: `0035ff5c103ea8b254e10cd2a2383d831e4615df4140ed73b3c95b4b3508526d`.
