# Android codemode

Interface alignment baseline: `earendil-works/pi` commit `a276dabe5`.
Core-tool names, contracts and remaining platform differences:
[CORE_TOOLS.md](CORE_TOOLS.md).

The tool is named **codemode**. Model-written JavaScript runs in **native
QuickJS-NG v0.15.1**, built by the Android NDK and called via JNI. Pi's
`packages/codemode/src/runtime/prelude-source.ts` is **unmodified**. Its bridge,
run/settle API and stalled-promise detection are used directly.

There is **no WASM, AndroidX JavaScriptEngine or System WebView dependency** for
codemode. The app's separate browser functionality still uses WebView.

## Architecture

- Core QuickJS sources are vendored in `app/src/main/cpp/quickjs/`, pinned to
  `fd0a0210b7be00957751871e7e01b8291268fc29`. No quickjs-libc or OS/module-loader
  bindings are compiled. JNI handles are not exposed to script globals.
- Each invocation owns a runtime, context and dedicated single-thread executor.
  All QuickJS API calls and Promise jobs run on that thread. The only operation
  permitted from other threads is setting an atomic interrupt flag.
- Bridge events travel directly through JNI as UTF-8 JSON byte arrays; tools
  execute asynchronously through the existing Kotlin dispatcher. There is no
  outer JS realm, console transport or chunked-message protocol.
- Successful tool settlements re-enter the owner thread and drain Promise jobs.
  Output is delivered while scripts execute, so nested calls do not wait for the
  whole script to return. Closure interrupts busy JS/microtasks, then queues VM
  disposal on its owner thread; it never frees a running runtime.
- This is an **in-process language sandbox**, not OS process isolation. Scripts
  cannot directly access files/network/Java, but a native engine defect can affect
  the app process. The previous WASM host's separate-process isolation is not
  retained; adding an isolated native service would be a separate architecture.

## Build and test

```sh
cd src/android/codemode
npm ci --ignore-scripts
npm run build
npm test
```

`build.mjs` verifies the upstream prelude hash, extracts its exported JS string
and writes `prelude.js`, licenses and a SHA-256 manifest. Checked-in assets let
ordinary Gradle builds run without Node. `verifyCodemodeAssets` rejects stale
assets. The native engine is compiled by the existing CMake/NDK build.

`npm test` requires a Linux C compiler and JDK. It compiles **the production JNI
bridge and engine**, then runs five smoke scenarios: Unicode/store and absent
host bindings, parallel Promise settlement, VM memory limits, and cross-thread
interruption of CPU loops and Promise loops. It is not a
mock runtime and does not require WebView. It does not substitute for ARM64/ART
instrumentation on a device.

Android tests are intentionally minimal: two JVM files (six behavior tests) and
one device smoke file (three tests). No source-string assertion suites or legacy
compatibility suites are retained. This is smoke coverage, not full-app coverage.

```sh
cd src/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.openminis.app.tools.CodemodeSandboxTest \
  com.openminis.app.test/androidx.test.runner.AndroidJUnitRunner
```

On a personal device, back up app data before installation and run instrumentation
manually as above. **Do not use `connectedDebugAndroidTest` on an installation
with data you need:** Gradle/UTP can uninstall the tested app during teardown.
Never uninstall or clear storage to resolve an installation signature mismatch.

## Contract

- Android user-facing task titles use the raw-source header, e.g.
  `// @options: {"tool_title":"并行整理账单并生成报告","timeout_ms":60000}`.
  This optional Android metadata extends Pi's options, without wrapping custom
  Responses input in JSON. Titles update the streaming card, foreground status,
  and saved transcript. Discovery functions (`searchTools`, `describeTool`,
  `describeNamespace`) are asynchronous and must be awaited.
- Input is JavaScript with optional first-line `// @options:`; JSON-schema
  providers receive `{code: string}`. Supported first-party GPT-5/GPT-6 Responses
  routes use Pi's raw-source Lark grammar/custom-tool representation.
- Async body, `tools`, `ALL_TOOLS`, `text`, `image`, `console`, `exit`,
  `store`/`load`, errors, image validation, stalled-promise detection and output
  limits come from the upstream prelude.
- `searchTools` uses Pi's BM25 tokenization/stemming/scoring. `describeTool`
  provides descriptions and declarations. `describeNamespace` returns undefined
  because current Android tools have no namespaces.
- Nested calls use the existing dispatcher and preflight. Browser/agent switches
  and permission/confirmation checks still apply. All callable tools except
  codemode itself are available, including subagent; no extra delegation ban.
- Limits match Pi: VM memory 256 MiB and native stack guard 512 KiB, output 16 Mi
  characters / 100000 items, individual store value 256 Ki characters, total store
  1 Mi characters. Output budget defaults to 10000 estimated tokens. No default
  script deadline. A supplied timeout includes nested calls. Ending a script
  cancels unawaited calls. The VM memory limit is not a total-process RSS limit.
- Only script output reaches the model. Call previews/status/errors are UI-only
  metadata, with partial output retained on script errors.
- Successful store writes are committed immediately as invisible `codemode-store`
  transcript entries, before output spilling. Entries are atomically inserted in
  small rows to avoid SQLite/Android's existing row-size caps. The full transcript
  supplies state, not the compacted LLM context. Fork/rewind follows copied/removed
  transcript rows. Failed scripts commit no writes.
- Images reach the model, are materialized as session-visible files and referenced
  by transcript metadata for replay. Text overflow preserves its head/tail and
  saves the full output in `/var/minis/offloads/`.
- Settings > Agent Runtime > Tools exposes off/on/only. `on` retains direct tools;
  `only` hides direct declarations but keeps tools callable from scripts.
  `minis-config` exposes `codemode.mode` and `codemode.inlineBudget` (default 3000).

## Platform differences / incomplete parity

- The engine is native QuickJS-NG, **not Pi's quickjs-wasi binary**. The prelude
  and API contract are reused, not the engine artifact. Engine-specific stack
  traces, intrinsics and version-dependent behavior can differ.
- Pi's optional `models.*` classifier/image-model registry is **not connected**.
  It is not advertised or stubbed. Android has no corresponding typed non-chat
  model registry/API yet. This matches upstream `models: false`, not Pi's
  model-enabled CLI setup.
- Raw grammar capability is inferred for current first-party GPT-5/GPT-6 routes;
  other providers use code JSON. Android has no general Pi-style per-model
  constrained-sampling capability registry yet.
- Wi-Fi device smoke verification passed on Pixel 10 / Android 17 (API 37):
  ARM64/ART JNI, Unicode/parallel calls, VM timeout/cancellation, actual PRoot
  read/write/edit/Bash calls, nonzero 119/124 exits, large head/tail output and
  archives, user stderr, process timeout/cancellation, GIF retention, EXIF rotation,
  image resize/encoding caps and non-vision notes. These remain three smoke tests,
  not full device coverage. GPT-6.1 Sol (OpenAI OAuth) was also exercised through
  a real streamed conversation: all seven tool types, a same-model subagent,
  raw codemode with nested Bash, edit followed by read-back, and image read with
  correct identification of a staged red/blue fixture. Other providers remain
  untested. This caught a direct-call repair bug absent from isolated smoke
  checks: the generic repair pass stringified edits arrays. Core calls now retain
  their declared types and field names. A subsequent real-model workflow used
  21 nested calls to write/read six JSONL inputs (360 invoices), handle one
  expected missing-file rejection, aggregate paid invoices, perform a two-edit
  batch, save reports, and independently verify 288 paid invoices / 180000 cents
  with awk. Cross-call store/load and restoration after a real app-process restart
  both passed without regenerating inputs or writing state in the verification
  calls. Persisted task titles matched the raw-source options headers. After
  shortening the API names to `browser` and `subagent`, both direct calls and
  parallel nested codemode calls passed on the same model; discovery declarations
  contained the new methods and no previous names.
- A Gradle/UTP test run uninstalled the target app during teardown. Subsequent
  verification used manual ADB installation/instrumentation and confirmed a
  data-retention marker survived. Use the safe manual workflow above.
