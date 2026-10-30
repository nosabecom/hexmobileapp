# 0.1.3 — first transcription and keyboard handoff

The model used to load on the first transcription, after the recording was already
finished. Model weights stayed loaded, but each recording created a fresh inference
session. A clean native runtime measured 16.710 seconds for the first JFK clip;
subsequent calls in that process measured 0.110 and 0.109 seconds.

The server now loads and exercises the last prepared model at startup and retains
one model/session pair. The authenticated `/prepare` endpoint does the same work
on demand. Phone preflight runs when the bubble appears, when a model is selected,
and when recording starts; it never opens the microphone, sends field contents, or
blocks recording. Requests are coalesced and throttled to one per 15 seconds for
the same endpoint/model. There is no periodic phone keepalive or wake lock.

After 60 seconds without inference, preparation runs one second of generated
silence through the resident session to wake GPU resources. That result is discarded.
Model switches close the previous session and weights under a shared inference
lock. Warmup cannot run concurrently with inference on the same engine.
`models/active-model.json` remembers only the last prepared model ID, not audio or
transcripts. Health reports ready only after preparation succeeds.

Following a server restart and startup preparation, the first measured Tailscale
HTTPS request from Godseye completed in 493 ms (360 ms server processing), then
275 ms (259 ms server processing). These are individual measurements with the
same 11-second prerecorded clip, not a general latency guarantee or measurements
of the phone network. The clean native baseline includes runtime/library startup;
the prepared HTTP measurements exclude work deliberately performed beforehand.
A separate model-switch check prepared Cohere in 14.192 s and then transcribed
the clip in 214 ms over HTTPS (204 ms server). Switching back to Parakeet prepared
in 658 ms and transcribed in 122 ms (110 ms server). Both recognized the expected
speech. This illustrates why model preparation starts at selection/focus rather
than waiting for the upload.

After several minutes idle with Parakeet resident, `/prepare` reported no reload
and completed its warmup in 175 ms. The following HTTPS transcription completed
in 325 ms (316 ms server), with the expected speech recognized.

A sleeping/offline Godseye, network reconnection, or a model selected immediately
before a short recording can still add waiting time.

The capture activity is now a transparent, floating one-pixel window marked
non-focusable and non-touchable. It has no dimming, window animation, preview or
task affinity. It starts the microphone service while resumed and removes its own
temporary task. It does not replace the editor's focused window or dismiss the IME.
Each attempt carries a generation ID; late/cancelled launches are rejected. A tap
while starting cancels immediately, interruption cancels startup, and a four-second
watchdog resets an abandoned startup. The idle blip remains compact during startup
instead of briefly showing the blue transcription state.

Validation:

- Native Android build and lint passed on Godseye.
- Ten Python tests passed, including preparation auth/readiness, failure recovery,
  idle rewarming without reloading, session reuse, and cleanup on model switch.
- Three targeted Android emulator tests passed in 18.825 seconds. The handoff test
  samples keyboard visibility and application-window focus every 15 ms for 1.5 s
  after an actual overlay tap, with Hex settings already open behind the editor.
  It also verifies microphone foreground operation and subsequent text insertion.
  Another test rejects a cancelled startup without cancelling a newer session.
- The first emulator runs were blocked by a System UI ANR dialog during boot;
  they failed in test setup before exercising the app. After clearing that dialog,
  the actual app checks passed.

- The initial S21 run passed 8 of 14 checks, including the keyboard visibility and
  focus regression. Five failures occurred before the fixture field was available;
  one could not capture that field. These failures were not counted as passing checks.
- With the S21 unlocked and idle, the complete suite subsequently passed:
  **OK (14 tests)** in **39.327 seconds**. This run reached Godseye from the phone
  at school over Tailscale, after reconnecting to Android's changed wireless ADB
  port. No local-network shortcut or USB connection was used.
- Phone-side elapsed time for the Parakeet JFK request was **439 ms**. The Cohere
  request took **12,765 ms**, including switching/loading the other model: this
  test changes the preference directly and does not preflight at model selection.
  These are distinct scenarios, not comparable warmed-model latency measurements.
- The full run covered keyboard visibility/window focus throughout startup,
  stale startup rejection, cancellation, microphone foreground operation, safe
  selection/insertion, password/home hiding, appearance persistence, token masking,
  and real transcription by both installed models. The original selected model
  was restored, and Hex accessibility was rebound after instrumentation ended.

APK: `artifacts/hex-mobile-0.1.3-debug.apk`
SHA-256: `c4136e51d288d5b1d350d39cd9dccc0f559da6cac14b7d112a177d8e3c8705fe`
