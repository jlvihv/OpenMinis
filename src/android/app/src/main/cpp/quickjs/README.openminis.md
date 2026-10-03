# Vendored QuickJS-NG

Upstream: https://github.com/quickjs-ng/quickjs
Version: v0.15.1
Commit: fd0a0210b7be00957751871e7e01b8291268fc29
License: MIT (LICENSE; individual source headers retain author notices).

Only the core engine, generated builtin headers and required headers are included.
Sources are unmodified. quickjs-libc and CLI programs are deliberately excluded;
no OS/module-loader/filesystem/network bindings are exposed to user scripts.

The app CMake target `codemode_jni` builds the core plus ../codemode_jni.c.
See ../../../../../codemode/README.md for runtime behavior and validation.
