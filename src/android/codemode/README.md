# Android codemode

Interface alignment baseline: `earendil-works/pi` commit `a276dabe5`.

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
bridge and engine**, then runs JVM integration tests covering Unicode, parallel
calls, rejection, discovery, partial errors, store writes, stalled promises,
catchable recursion, unavailable host capabilities, Pi bounds, VM memory limits,
and cross-thread interruption of both CPU loops and Promise loops. It is not a
mock runtime and does not require WebView. It does not substitute for ARM64/ART
instrumentation on a device.

Android tests:

```sh
cd src/android
./gradlew :app:testDebugUnitTest --tests 'com.openminis.app.tools.Codemode*' \
  --tests 'com.openminis.app.provider.CodemodeResponsesTest'
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.openminis.app.tools.CodemodeSandboxTest
```

## Contract

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
  codemode itself are available, including subagent_task; no extra delegation ban.
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
- No real Android device was connected during implementation. Native ARM64/ART
  lifecycle, interrupts and actual provider streaming still need device testing
  before claiming full parity. Host-JVM JNI tests and instrumentation compilation
  alone do not establish that.
