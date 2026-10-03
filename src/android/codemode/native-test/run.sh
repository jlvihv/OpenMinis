#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cpp=../app/src/main/cpp
cc -std=c11 -D_GNU_SOURCE -O2 -fPIC -shared -pthread \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$cpp" \
  "$cpp/codemode_jni.c" "$cpp/quickjs/quickjs.c" "$cpp/quickjs/dtoa.c" \
  "$cpp/quickjs/libregexp.c" "$cpp/quickjs/libunicode.c" -lm -o "$work/libcodemode_jni.so"
"$JAVA_HOME/bin/javac" -d "$work" native-test/com/openminis/app/tools/CodemodeNative.java
timeout 90 "$JAVA_HOME/bin/java" -Xcheck:jni -Xmx768m -Djava.library.path="$work" -cp "$work" \
  com.openminis.app.tools.CodemodeNative ../app/src/main/assets/codemode/prelude.js
