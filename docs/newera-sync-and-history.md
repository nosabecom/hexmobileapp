# NewEra customization sync and transcript history

NewEra is authoritative. A user-level Windows task exports its Hex settings and
bundled custom transformations to the existing authenticated Godseye server every
minute. The phone checks on opening Hex or focusing an editable field (at most once
every 30 seconds), and every server transcription includes the latest snapshot.
Sync does not require opening desktop Hex, although desktop Hex's transformation
catalog must be available. A desktop config reload error preserves the previous
successful snapshot.

Shared vocabulary terms and their `sounds_like` aliases are also exported. Aliases
become phrase corrections in every mode, before its transformation chain. Explicit
mode corrections take precedence over the same alias. This works with the existing
mobile app, including its offline cache, without installing another APK. Plain
vocabulary terms are retained in the snapshot; recognition-time vocabulary boosting
is not supported by this mobile build. Terms without aliases do not create automatic
capitalization rules for ordinary words such as “go”. Desktop keyboard hotkeys,
microphone/device settings, and provider-backed rewrites remain desktop-specific.

The settings screen shows the desktop snapshot time, the last phone check, the
available modes, and unsupported steps. **Sync now** checks immediately. Disabling
**Use NewEra customizations** leaves dictated text unmodified. With NewEra or
Godseye offline, the phone retains its last successful snapshot. A change to the
server URL prevents reusing settings from a different server.

## NewEra setup

Keep the sync files in a regular local folder outside OneDrive, for example
`C:\Users\nomok.NEWERA\.local\share\hex-mobile-sync`. Copy the repository's
`scripts/sync-newera.ps1`, `scripts/install-newera-sync.ps1`, and `sync/` directory
there, keeping their relative layout. Install the bundler once with:

```powershell
pnpm --dir .\sync install --frozen-lockfile
```

Place the existing Godseye server token in a private local file readable only by
the current user and SYSTEM, then register the task:

```powershell
.\scripts\install-newera-sync.ps1 `
  -ServerUrl 'https://godseye.tail3123d8.ts.net:8444' `
  -TokenFile '.\.newera-sync-token' `
  -SettingsPath "$env:APPDATA\hex\settings.json"
```

The initial export must succeed before task registration. The installed task is
**Hex Mobile NewEra Sync**. It runs once per minute while the user is logged in,
without waking the computer. It resumes on the next available run. Last export
status is in `scripts/newera-sync-status.json`; it contains no token or transcript.
Stop and unregister this task to disable desktop publishing. Delete the private
token copy when removing the sync setup. Desktop Hex settings are never modified.

The Godseye server adds authenticated `PUT /customizations` and
`GET /customizations` routes. It stores its last validated snapshot atomically in
`.desktop-customizations.json` (overridable with `--customizations-file`). Request
size, rule counts, script size, and transformation counts are bounded. Invalid
updates leave the previous snapshot intact. Provider settings, rewrite prompts,
desktop history, and microphone settings are excluded from the exporter.

## Custom scripts

The exporter bundles `~/.config/hex/hex.config.ts` or `hex.config.js` using esbuild,
with `defineHexConfig` provided locally. Relative TypeScript, JavaScript, and JSON
imports must stay within that config folder; other package imports fail the
export. Bundling compiles code without executing it on NewEra.

The phone caches the bundle privately and evaluates it with AndroidX
JavaScriptEngine, in a separate JavaScript sandbox with no filesystem or network
APIs. Each dictation gets a fresh isolate, a 64 MiB heap limit, and a 1.5-second
evaluation deadline. Android System WebView must support sandboxing, isolate
termination, and heap limits. A script error, timeout, missing transformation, or
unsupported engine keeps the corrected text from before the transformation chain
and reports the failure. Failed partial transformations are discarded.

Corrections run first and match phrases without recursion. Transformations run in
desktop order, including built-in lowercase, SpongeBob case, and trailing
punctuation removal. Desktop OpenCode rewrites are not executed. Desktop app and
website matching do not map to Android; choose a mode explicitly in Hex. Custom
scripts receive an empty foreground context, so context-dependent transformations
need Android-specific handling. NewEra's current `nosa-names` and `inline-insert`
transformations use only the dictated text and are directly portable.

## History

Completed, non-cancelled dictations are saved to a private SQLite database before
insertion is attempted. Each entry contains a timestamp, original recognition
text, transformed text, selected mode, and recognition engine. Dictations remain
available after process death and app updates. Repeated phrases are separate
entries; no automatic retention limit deletes older entries.

**Transcript history** searches the original and transformed text. It loads 100
entries initially, with **Load older transcripts** for more. Open an entry to read
the complete selectable text, copy it, share it through Android, inspect the
original, or delete it. **Clear history** asks for confirmation. Cancelled sessions
and empty recognition results are not saved. If customizations remove all text,
the original is still saved. History is phone-local, excluded from Android backup,
and never uploaded by the settings sync.

The bubble's hold-to-paste recovery remains a separate in-memory convenience.
Copying or discarding recovery does not delete saved history. Old dictations from
before this update were never persisted and cannot be reconstructed.

## Verification

```sh
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 -m unittest discover -s server/tests -v
```

`TextTransformsTest` covers phrase boundaries, longest-match precedence,
non-recursive replacement, filler removal, Unicode, punctuation, and order.
`HistoryTest` covers database reopening, duplicates, originals, search literals,
deletion, and clearing, using isolated test databases. `CustomizationsTest` covers
script isolation, literal input, failure, timeout recovery, and the actual current
NewEra name and inline-insert scripts. The real-NewEra test requires an exported
snapshot and the phone's saved server connection.

### Verified on 2026-11-08

- Fixed the exporter to include NewEra's shared vocabulary. The live authenticated
  server snapshot contains all 257 terms, 248 pronunciation aliases represented as
  corrections in every mode, and the matching NewEra script bundle. Its active
  chain is `nosa-names`, `inline-insert`, then `programming-symbols`, with no
  unsupported transformations.
- Removed stale Hex inference launcher/worker processes on Godseye. Windows now
  uses an exclusive listening socket so a second server cannot silently share
  port 8765 and alternate old/new settings. Duplicate binding was verified to fail
  on Godseye, and the single scheduled server reports Parakeet ready.
- Pinned the bundler's working directory to the config folder. Bundling from two
  different directories on NewEra now produces identical output and revisions.
- All 19 Python server tests pass. All 248 live aliases pass the actual Java
  phone correction engine on the JVM; nine combined correction/script cases pass
  against the published bundle, including insert, names, C++, C#, F#, and slash.
- The phone was not connected to ADB during this check, and the device tools had
  no Android device available. Its current cache and Android sandbox execution
  were not reverified. Opening Hex or focusing a field fetches the new snapshot;
  **NewEra customizations → Sync now** requests it immediately. No APK update is
  needed for these alias corrections.

### Executed on 2026-10-31

- Android debug build, instrumentation APK build, lint (no errors), and all five
  text-transformation unit tests passed on Linux.
- All 15 Python server tests passed.
- All five new instrumentation tests passed on the S21 (2.315 seconds), including
  its actual NewEra bundle over the saved private server connection. These covered
  name variants, the literal “no sir” escape, refusal preservation, inline-insert
  punctuation/capitalization, sandbox failure and timeout recovery, and history
  reopening/search/deletion.
- The updated server is running on Godseye, with Parakeet ready. NewEra's scheduled
  task completed with result `0`, and its server snapshot contains one Global mode
  and the actual 4,444-character transformation bundle. A scheduled export was
  verified after installation, separately from the initial interactive export.
- Installed 0.2.0 on the S21 using Godseye's original debug signing key, preserving
  app data. Rebound Hex accessibility after instrumentation and confirmed the
  BubbleService running. MainActivity was opened for the user.
- After Nosa unlocked the S21, all seven additional recording/insertion regression
  checks passed in 49.751 seconds. These cover keeping the original editor and
  keyboard focused, microphone startup/cancellation, rejecting late results from
  cancelled sessions, rejecting changed fields and edited text, and real Parakeet
  and Cohere transcription with text insertion. Parakeet took 485 ms and Cohere
  took 34,433 ms for their prerecorded-speech requests in this run. Hex's floating
  bubble was restored after instrumentation.

APK: `artifacts/hex-mobile-0.2.0-debug.apk`.
SHA-256: `d35dea7c5c9a3b99df4a842ae350497aeef8cff1e8c04a00aa6b9d8c8e712dc7`.
