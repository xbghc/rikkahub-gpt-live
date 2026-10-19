#!/usr/bin/env bash
set -uo pipefail

# The API 35 emulator can leave a Pixel Launcher ANR dialog after boot.
# Clear that unrelated process before capturing UI, without hiding app failures.
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]]; then
  adb shell am force-stop com.google.android.apps.nexuslauncher || exit $?
fi

./gradlew :app:connectedDebugAndroidTest \
  -Prikkahub.firebase.enabled=false \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.voice.VoiceEntryTest \
  --no-daemon --no-configuration-cache --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
result=$?

mkdir -p build/voice-ui-screenshots
for name in entry setup-missing-key setup-ready setup-busy setup-error; do
  destination="build/voice-ui-screenshots/voice-ui-$name.png"
  if ! adb pull \
    "/sdcard/Android/data/me.rerere.rikkahub.debug/files/voice-ui-$name.png" \
    "$destination" || [[ ! -s "$destination" ]]; then
    echo "::error::Voice UI screenshot was not collected: voice-ui-$name.png"
    if [[ "$result" -eq 0 ]]; then
      result=1
    fi
  fi
done
exit "$result"
