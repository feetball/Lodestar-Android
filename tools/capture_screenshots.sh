#!/bin/bash
# Captures the Play Store screenshots and a smoke-test log from a running emulator (or device) with the debug
# build installed. Screenshot mode (debug builds only) fixes the site, time, tab, target and pointing.
#   tools/capture_screenshots.sh <output-dir>
set -euo pipefail
out=${1:-screenshots}
mkdir -p "$out"
package=net.kuecker.lodestar
activity=$package/.app.MainActivity
common="date=2026-07-17T05:30:00Z lat=36.0544 lon=-112.1401 elevation=2130 site=Grand_Canyon tz=America/Phoenix"

# Phone screenshot geometry Play accepts (long side at most twice the short side), clean status bar.
adb shell wm size 1080x2160
adb shell wm density 420
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 2230 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
adb logcat -c

shoot() {
    local name=$1; shift
    adb shell am force-stop $package
    # adb shell joins its arguments into one remote command line: quote the extra for the device's shell.
    adb shell "am start -W -n $activity --es lodestarScreenshot '$common $*'" >/dev/null
    sleep 14
    adb exec-out screencap -p > "$out/$name.png"
    echo "captured $name"
}

shoot 1-aim-guidance tab=aim target=special.galacticCenter pointing=172,18
shoot 2-tonight tab=tonight
shoot 3-sky-chart tab=sky target=special.galacticCenter
shoot 4-objects tab=objects target=special.galacticCenter
shoot 5-night-mode tab=aim target=dso.M8 pointing=178,22 night=1
shoot 6-settings tab=settings

adb logcat -d > "$out/logcat.txt"
if grep -E "FATAL EXCEPTION|AndroidRuntime: .*$package" "$out/logcat.txt" >/dev/null; then
    echo "CRASH detected — see logcat.txt"
    grep -A 30 "FATAL EXCEPTION" "$out/logcat.txt" | head -60
    exit 1
fi
echo "no crashes"
