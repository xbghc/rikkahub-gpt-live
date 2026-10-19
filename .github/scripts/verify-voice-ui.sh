#!/usr/bin/env bash
set -uo pipefail

./gradlew :app:connectedDebugAndroidTest \
  -Prikkahub.firebase.enabled=false \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.voice.VoiceEntryTest \
  --no-daemon --no-configuration-cache --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
result=$?

mkdir -p build/voice-ui-screenshots
for name in entry setup-missing-key setup-ready setup-busy setup-error; do
  adb pull \
    "/sdcard/Android/data/me.rerere.rikkahub.debug/files/voice-ui-$name.png" \
    "build/voice-ui-screenshots/voice-ui-$name.png" >/dev/null 2>&1 || true
done
exit "$result"
