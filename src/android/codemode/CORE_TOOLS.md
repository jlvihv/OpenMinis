# Android core tools

The tool registry exposes `read`, `write`, `edit`, `bash`, `codemode`, plus
Android's `browser` and `subagent`. There are no old core-tool aliases
or old argument adapters. Image is not a separate tool.

## Contracts

- Relative paths and Bash start at `/var/minis/workspace`; absolute mounted Linux
  paths and `minis://` URLs are supported. `~` means `/root`.
- `read`: 1-based `offset`/`limit`, 2,000-line/50 KiB text head limit with
  continuation hints. Image signatures route through the read image decoder;
  the pixels go directly to the current conversation model. Like Pi, non-vision
  models receive an omission note, not a failed read; provider adapters omit pixels.
  Defaults are 2000x2000 and less than 4.5 MiB of base64, overridden by the current
  model's optional `inputLimits.images.resize` profile. Small supported images,
  including GIF, retain original bytes. Larger images try PNG/JPEG quality steps
  before reducing dimensions; hints explain conversion and coordinate scaling.
  No auxiliary image model, Vision Group or cross-model description request exists.
  Images retain their sandbox path for file operations.
- `write`: overwrite/create UTF-8 text and create missing parent directories.
  Empty content is valid; no append mode.
- `edit`: only `edits: [{oldText, newText}]`. All replacements match the original
  file, are validated before writing, and reject ambiguous/overlapping/no-op
  edits. BOM/line endings and untouched original lines are preserved; fuzzy
  matches rewrite touched lines without overriding the requested newText.
  No single-edit field adapter, serialized-array coercion or replace-all mode.
  Diff/patch details are returned for UI; large patches spill to offloads.
- `write` and `edit` share a canonical-host-path mutation queue. Mount writability
  is checked again before the write; offload placeholders are refused.
- `bash`: actual Bash in a fresh PRoot process; no implicit deadline, optional
  positive seconds timeout (maximum 2147483.647 seconds). No delay parameter or
  sh/bashism fallback. Bash is checked/installed before user code runs, never
  retried based on the script's exit code.
  Output streams to a private temporary file with bounded prefix/tail windows.
  Model text uses a 2,000-line/50 KiB tail; complete output spills when truncated.
  Codemode receives `{output, exit_code, wall_time_seconds, truncated,
  full_output_path?}`. Up to 1 MiB is complete; longer output retains the first
  and last 512 KiB around a byte-omission marker. Ordinary nonzero exit resolves;
  genuine timeout, cancellation and execution failure reject. Cancellation stops
  this invocation's process group. Privacy masking is a cross-chunk streaming
  pipeline; its prose reminder is model-only. Temporary files are cleaned up and
  incomplete archives are never advertised as complete output.

Scheduled presets use only `bash`, share its timeout semantics, and execute even
in codemode-only mode. They do not map shell/sh/old tool names or impose the old
one-hour/16,000-character ceilings. Android CLI command flags, permissions,
mounts, privacy, browser, subagents and the interactive terminal remain available.
The terminal's warm-shell backend is not an agent-tool compatibility path.

## Remaining platform differences

The implementation is Kotlin/Android, with native QuickJS-NG and PRoot. Pi's
codemode prelude is unmodified. Diff formatting is a bounded-context single hunk;
image processing uses Android Bitmap/EXIF rather than Photon, so encoded bytes
and sampling differ. Missing catalog resize profiles use Pi defaults; Android
hasn't imported Pi's complete provider-specific model catalog. Full Bash
spools retain raw Android OSC URL markers, while inline/script text strips complete
markers. Optional Pi `grep`, `find`, `ls` aren't separate tools (use Bash);
`models.*` isn't advertised.

Host JNI/Kotlin tests do not replace ARM64/ART, PRoot and provider-streaming device
tests. See [README.md](README.md) for runtime guarantees.
