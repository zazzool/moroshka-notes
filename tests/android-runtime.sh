#!/usr/bin/env bash
set -euo pipefail
app=$(realpath "$1")
test_apk=$(realpath "$2")
logs="$(pwd)/runtime-logs"
mkdir -p "$logs"
sdk=${ANDROID_HOME:?Android SDK is required}
export PATH="$sdk/platform-tools:$sdk/emulator:$sdk/cmdline-tools/latest/bin:$PATH"
image='system-images;android-35;google_apis;x86_64'
# sdkmanager, avdmanager and emulator must resolve the same user/AVD directory.
# Explicit configuration also avoids runner-specific ANDROID_SDK_HOME settings.
sandbox=$(mktemp -d "${RUNNER_TEMP:-/tmp}/moroshka-avd.XXXXXX")
export ANDROID_USER_HOME="$sandbox/android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
unset ANDROID_SDK_HOME
export ANDROID_SERIAL=emulator-5554
mkdir -p "$ANDROID_AVD_HOME"
name=moroshka-ci
avd="$ANDROID_AVD_HOME/$name.avd"
pid=
cleanup(){
  local result=$?
  trap - EXIT
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    timeout -k 2s 10s adb -s "$ANDROID_SERIAL" logcat -d -t 3000 >"$logs/logcat.txt" 2>&1 || true
    timeout -k 2s 10s adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
    kill "$pid" 2>/dev/null || true
  fi
  if ((result != 0)); then
    echo "Android runtime failed (exit $result)"
    for log in avd.log emulator.log; do
      [[ ! -f "$logs/$log" ]] || tail -n 60 "$logs/$log"
    done
  fi
  rm -rf "$sandbox"
  exit "$result"
}
trap cleanup EXIT
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
echo "Installing Android 35 system image"
timeout -k 10s 300s sdkmanager --install "$image" >"$logs/sdk-install.log" 2>&1
echo "Creating AVD at $avd"
# Explicit --path for data, and shared ANDROID_AVD_HOME for the registration.
printf 'no\n' | timeout -k 5s 60s avdmanager create avd --force --name "$name" --package "$image" --path "$avd" >"$logs/avd.log" 2>&1
test -s "$avd/config.ini"
# Some CLI releases create the registration in a legacy user-home directory.
# Register the already-created, verified config in the emulator's explicit AVD home.
if [[ ! -s "$ANDROID_AVD_HOME/$name.ini" ]]; then
  printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' "$avd" >"$ANDROID_AVD_HOME/$name.ini"
fi
emulator -list-avds | tee "$logs/avd-list.txt"
grep -Fx "$name" "$logs/avd-list.txt"
timeout -k 2s 20s adb start-server
echo "Starting Android emulator"
emulator -avd "$name" -port 5554 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on -memory 2048 >"$logs/emulator.log" 2>&1 &
pid=$!
# An immediate emulator exit is a hard failure, not an unbounded adb wait.
deadline=$((SECONDS+300))
while ((SECONDS < deadline)); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "Emulator exited before boot" >&2
    exit 1
  fi
  boot=$(timeout -k 2s 5s adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$boot" == 1 ]]; then break; fi
  sleep 2
done
[[ "${boot:-}" == 1 ]] || { echo "Android boot timed out" >&2; exit 1; }
echo "Android boot completed"
adbc(){ timeout -k 5s 45s adb -s "$ANDROID_SERIAL" "$@"; }
adbc shell input keyevent 82
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adbc shell settings put global "$scale" 0
done
adbc install -r "$app"
adbc install -r "$test_apk"
runner='app.moroshka.notes.debug.test/app.moroshka.notes.SmokeRunner'
for fixture in recoveryFixture backupFixture historyFixture incrementalFixture knowledgeFixture syncGateFixture photoFixture designFixture default; do
  echo "Running $fixture"
  adbc shell pm clear app.moroshka.notes.debug | grep -Fx Success
  if [[ "$fixture" == default ]]; then
    args=()
  else
    args=(-e "$fixture" 1)
  fi
  timeout -k 5s 120s adb -s "$ANDROID_SERIAL" shell am instrument -w -r "${args[@]}" "$runner" | tr -d '\r' | tee "$logs/$fixture.txt"
  grep -Fx 'INSTRUMENTATION_CODE: -1' "$logs/$fixture.txt"
done
echo "All Android runtime fixtures passed"
