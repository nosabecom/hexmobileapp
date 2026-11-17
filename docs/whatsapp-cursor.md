# 0.2.1 — dictation in fields that hide their cursor

The reported WhatsApp error came from `BubbleService.captureTarget()`: it refused
to start recording when accessibility reported a selection of `-1`. The test
fixture now reproduces that behavior while keeping a working editor connection.

On Android 13 and newer, Hex enables Android's accessibility input-method APIs.
When the accessibility selection is unavailable or invalid, `EditorInput` captures
the editor's input connection, selection, surrounding text, and input-session
generation. It checks them again before committing the transcript through that
captured connection. The focused accessibility node and its text must also match
the original target. This uses the existing keyboard and leaves the clipboard
alone. See Android's [accessibility input connection documentation](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection).

An accepted commit is verified by reading the resulting text and selection.
An unconfirmed result remains recoverable; Hex never retries an accepted action
automatically. Hold-to-paste also supports the input-connection path. If neither
interface provides a safe insertion point, dictation can still start and the
transcript is retained for explicit recovery. Android 12 retains the existing
accessibility insertion and explicit clipboard recovery paths.

## Validation — 2026-11-02

- Debug app, fixture, instrumentation APK, five unit tests, and lint passed.
  Lint has no errors; existing warnings and API-annotation style warnings remain.
- All 27 insertion/recovery instrumentation tests passed on Godseye's Android 16
  phone emulator (`OK (27 tests)`, 85.909 seconds). Real-model latency and
  dual-model inference tests were excluded; recognition code was unchanged.
- Six new regressions passed: replacement of a hidden selection with Unicode,
  insertion in the middle of text, refusing a moved cursor, refusing a changed
  field, recovery through the input connection, and retaining a transcript when
  neither cursor nor input connection is available.
- The final 0.2.1 APK was signed with Godseye's original debug key. Its certificate
  matches the previous 0.2.0 APK, allowing an in-place update that preserves data.
- The S21 was initially unavailable over ADB. After it reconnected through
  Godseye, the signed update installed successfully and the phone reported
  version 0.2.1 (code 7). Hex accessibility was rebound after installation.
  Actual WhatsApp behavior remains to be checked; emulator fixtures are the
  compatibility evidence.

APK: `artifacts/hex-mobile-0.2.1-debug.apk` (version code 7).

SHA-256: `8a62561c10f9c636034128b37966bc8b2cd1aaff202110b14cb675200a0594ba`.
