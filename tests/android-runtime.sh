#!/usr/bin/env bash
set -euo pipefail
app=$1
test_apk=$2
mkdir -p runtime-logs
sdk=${ANDROID_HOME:?Android SDK is required}
export PATH="$sdk/platform-tools:$sdk/emulator:$sdk/cmdline-tools/latest/bin:$PATH"
image='system-images;android-35;google_apis;x86_64'
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
sdkmanager --install "$image" >runtime-logs/sdk-install.log 2>&1
printf 'no\n' | avdmanager create avd --force --name moroshka-ci --package "$image" >runtime-logs/avd.log 2>&1
emulator -avd moroshka-ci -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on -memory 2048 >runtime-logs/emulator.log 2>&1 &
pid=$!
cleanup(){
  adb logcat -d -t 3000 >runtime-logs/logcat.txt 2>&1 || true
  adb emu kill >/dev/null 2>&1 || kill "$pid" 2>/dev/null || true
}
trap cleanup EXIT
timeout 300 adb wait-for-device
timeout 300 bash -c 'until [[ "$(adb shell getprop sys.boot_completed | tr -d "\r")" == 1 ]]; do sleep 2; done'
adb shell input keyevent 82
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$scale" 0
done
adb install -r "$app"
adb install -r "$test_apk"
runner='app.moroshka.notes.debug.test/app.moroshka.notes.SmokeRunner'
for fixture in recoveryFixture backupFixture historyFixture incrementalFixture knowledgeFixture syncGateFixture designFixture default; do
  adb shell pm clear app.moroshka.notes.debug >/dev/null
  if [[ "$fixture" == default ]]; then
    args=()
  else
    args=(-e "$fixture" 1)
  fi
  timeout 120 adb shell am instrument -w -r "${args[@]}" "$runner" | tr -d '\r' | tee "runtime-logs/$fixture.txt"
  grep -Fx 'INSTRUMENTATION_CODE: -1' "runtime-logs/$fixture.txt"
done
