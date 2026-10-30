# Hex / Anomaly

This is an independent Android companion, not an official Anomaly release.
The dot-scope Canvas implementation adapts Hex's `src/dot_scope.rs`,
`src/dot_scope/scope.rs`, `src/rune_hud.wgsl`, and hairline hexagon mark.
The GGUF catalog comes from `src/transcription_models.rs`.
Source: https://github.com/anomalyco/hex/tree/b9d7eff8ab8d20d1cf77ba1fa1282d7763e13183 (inspected 2026-10-30).

The mobile adaptation preserves the 43 × 9 dot field, 112/76/26 logical
recording/processing/height dimensions, paired helices, amplitude spring,
phosphor decay, and pearl-to-blue palette. It uses Canvas rather than WGSL,
a simpler per-dot distance rasterizer, and enlarged touch targets. It is not
a byte-for-byte port of the compositor or all of its transition simulation.

MIT License

Copyright (c) 2026 Christopher Langton

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

# Model runtime

The server installs transcribe.cpp v0.4.1 (https://github.com/handy-computer/transcribe.cpp).
Runtime and model weights retain their respective upstream licenses. Model downloads
are optional, pinned to revisions, and SHA-256 verified. No model weights are bundled
in the Android APK. Consult the linked Hugging Face model cards before redistribution.

# Test audio

`app/src/androidTest/assets/jfk.wav` is the public JFK speech excerpt from
https://github.com/ggml-org/whisper.cpp/blob/master/samples/jfk.wav .
It is included only in the instrumentation test APK, not the main app.

# UI library evaluation

OpenCode’s `@opencode/ui` package is MIT-licensed web/TypeScript UI:
https://github.com/anomalyco/opencode/tree/dev/packages/ui .
It is not bundled in this native Android application. Android views and Canvas
implement the settings, accessibility overlay, and Hex-inspired motion directly.
