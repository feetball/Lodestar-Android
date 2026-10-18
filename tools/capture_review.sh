#!/bin/bash
# Captures every screen added in 1.1 from a running emulator (or device) with the debug build installed, for a visual
# review: the events calendar and event details, the mosaic planner, skyline (horizon) recording, the star-trail and
# time-lapse planners, GoTo mount control with the built-in simulated mount, and the Tonight widgets, each in the day
# palette and, where it matters, the night one (red only). Screenshot mode (debug builds only, see ScreenshotMode.kt)
# fixes the site, time, tab, target, pointing and the sub-screen to open; long screens are scrolled by swiping and
# captured page by page (-p2, -p3, ...). Ends with a crash check, like capture_screenshots.sh.
#   tools/capture_review.sh <output-dir> [<width>x<height> <density>]   (default 1080x2160 at 420 dpi: a phone)
set -euo pipefail
out=${1:-review}
size=${2:-1080x2160}
density=${3:-420}
width=${size%x*}
height=${size#*x}
mkdir -p "$out"
package=net.kuecker.lodestar
activity=$package/.app.MainActivity
widgetHost=$package/.debug.WidgetHostActivity
common="date=2026-07-17T05:30:00Z lat=36.0544 lon=-112.1401 elevation=2130 site=Grand_Canyon tz=America/Phoenix"

# The same geometry and clean status bar as the store screenshots.
adb shell wm size "$size"
adb shell wm density "$density"
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -p com.android.systemui -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
sleep 1
demo clock -e hhmm 2230
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false
# The debug widget host binds widgets with this permission, which the shell can give.
adb shell appwidget grantbind --package $package --user 0 || echo "grantbind failed: the widgets will not bind"
adb logcat -c
: > "$out/crashes.txt"
: > "$out/errors.txt"

# Waits at least $1 seconds and then until the screen stops changing (two equal pictures in a row), at most $2.
settle() {
    local waited=$1 previous="" current
    sleep "$1"
    while [ "$waited" -lt "$2" ]; do
        current=$(adb exec-out screencap -p | md5sum | cut -d' ' -f1)
        [ "$current" = "$previous" ] && return 0
        previous=$current
        sleep 1
        waited=$((waited + 2))
    done
}

snap() {
    adb exec-out screencap -p > "$out/$1.png"
    echo "captured $1"
}

# Collects what went wrong since the last call (the crash buffer is small; the main one is not kept for long).
check() {
    adb logcat -d -b crash -v threadtime >> "$out/crashes.txt" || true
    adb logcat -d -v threadtime AndroidRuntime:E WidgetHost:E ActivityManager:E '*:S' >> "$out/errors.txt" || true
    adb logcat -c
}

# Starts $1 (an activity) with the screenshot argument $2, from a stopped app. adb shell joins its arguments into one
# remote command line: quote the extra for the device's shell.
launch() {
    adb shell am force-stop $package
    adb shell "am start -W -n $1 --es lodestarScreenshot '$2'" >/dev/null || echo "could not start $1"
}

# One finger drags the content up by half the screen: the next page of a long screen.
page_down() {
    adb shell input swipe $((width / 2)) $((height * 7 / 10)) $((width / 2)) $((height * 2 / 10)) 1200
}

# shot <name> <extra pages> <screenshot arguments...>: the first page, then each further page after a swipe.
# MIN_WAIT (seconds the screen needs at least) and MAX_WAIT (at most; 20 s more by default) say how long it may take;
# with NO_SETTLE the picture is taken after MIN_WAIT, whatever moves (a slew in progress).
shot() {
    local name=$1 pages=$2 min=${MIN_WAIT:-6}
    shift 2
    launch $activity "$common $*"
    if [ -n "${NO_SETTLE:-}" ]; then sleep "$min"; else settle "$min" "${MAX_WAIT:-$((min + 20))}"; fi
    snap "$name"
    local page
    for page in $(seq 2 $((pages + 1))); do
        page_down
        settle 2 8
        snap "$name-p$page"
    done
    check
}

# widgets <name> <screenshot arguments...>: the Tonight widgets in the debug widget host.
widgets() {
    local name=$1
    shift
    launch $widgetHost "$common $*"
    settle 8 30
    snap "$name"
    check
}

# Warm-up: the first start after installing compiles the app and decodes the catalog for the first time.
adb shell "am start -W -n $activity --es lodestarScreenshot '$common tab=tonight'" >/dev/null
sleep 30

aimed="tab=aim target=dso.M8 pointing=178,25"
veil="tab=aim target=dso.NGC6960 pointing=84,50 focal=600"

# Order of capture: the cheap screens first, the ones that draw the whole sky (Aim, Sky) after they have been warmed up.

# 1. Events: the card on Tonight, the calendar, and the detail of every kind of event.
MIN_WAIT=10 shot ev-01-tonight-upcoming 9 tab=tonight
MIN_WAIT=12 shot ev-02-calendar 3 tab=tonight screen=events
MIN_WAIT=12 shot ev-03-calendar-night 1 tab=tonight screen=events night=1
# (earthApsis is not in the calendar: the engine leaves it out unless asked.)
for kind in meteorShower conjunction opposition greatestElongation season fullMoonSpecial lunarEclipse solarEclipse; do
    MIN_WAIT=14 shot "ev-04-detail-$kind" 2 tab=tonight screen=event-detail event=$kind
done
MIN_WAIT=14 shot ev-05-detail-meteorShower-night 2 tab=tonight screen=event-detail event=meteorShower night=1
MIN_WAIT=14 shot ev-06-detail-solarEclipse-night 2 tab=tonight screen=event-detail event=solarEclipse night=1

# 2. Mount control: the entry in Settings, the form, the connected screen, and the Aim screen with the mount.
MIN_WAIT=8 shot mt-01-settings 4 tab=settings
MIN_WAIT=8 shot mt-02-setup 3 tab=settings screen=mount-setup
MIN_WAIT=8 shot mt-03-setup-night 1 tab=settings screen=mount-setup night=1
MIN_WAIT=14 shot mt-04-control 3 tab=settings screen=mount-control
MIN_WAIT=14 shot mt-05-control-night 2 tab=settings screen=mount-control night=1
MIN_WAIT=20 shot mt-06-aim-card 0 $aimed screen=aim-mount
MIN_WAIT=20 shot mt-07-aim-card-night 0 $aimed screen=aim-mount night=1
MIN_WAIT=14 NO_SETTLE=1 shot mt-08-aim-slew 0 tab=aim target=dso.M31 pointing=60,20 screen=aim-slew
MIN_WAIT=20 shot mt-09-aim-goto 1 $aimed screen=aim-goto
MIN_WAIT=20 shot mt-10-aim-goto-night 1 $aimed screen=aim-goto night=1
MIN_WAIT=20 shot mt-11-aim-sync 0 $aimed screen=aim-sync
MIN_WAIT=20 shot mt-12-aim-nudge 0 $aimed screen=aim-nudge
MIN_WAIT=20 shot mt-13-aim-nudge-night 0 $aimed screen=aim-nudge night=1
MIN_WAIT=20 shot mt-14-aim-sheet 2 $aimed screen=aim-mount-sheet
MIN_WAIT=20 shot mt-15-aim-sheet-night 1 $aimed screen=aim-mount-sheet night=1

# 3. The planners of Settings > Tools.
MIN_WAIT=8 shot tl-01-star-trails 5 tab=settings screen=star-trails pointing=355,36
MIN_WAIT=8 shot tl-02-star-trails-night 3 tab=settings screen=star-trails pointing=355,36 night=1
MIN_WAIT=8 shot tl-03-time-lapse 4 tab=settings screen=time-lapse
MIN_WAIT=8 shot tl-04-time-lapse-night 2 tab=settings screen=time-lapse night=1

# 4. Skyline: the recording screen part way round (with the picture of the camera off, then on), and the skyline in use.
MIN_WAIT=12 shot hz-01-recording 1 tab=settings screen=skyline skyline=1 pointing=40,6
MIN_WAIT=12 shot hz-02-recording-night 1 tab=settings screen=skyline skyline=1 pointing=40,6 night=1
MIN_WAIT=12 shot hz-03-recording-camera 0 tab=settings screen=skyline skyline=1 pointing=40,6 camera=1
adb shell pm grant $package android.permission.CAMERA || echo "could not grant the camera"
MIN_WAIT=14 shot hz-04-recording-camera-granted 0 tab=settings screen=skyline skyline=1 pointing=40,6 camera=1
adb shell pm revoke $package android.permission.CAMERA || true
MIN_WAIT=12 shot hz-05-site-in-settings 2 tab=settings skyline=1
MIN_WAIT=10 shot hz-06-tonight 1 tab=tonight skyline=1
MIN_WAIT=20 shot hz-07-sky 0 tab=sky skyline=1 pointing=265,12
MIN_WAIT=20 shot hz-08-aim 0 tab=aim skyline=1 target=dso.M8 pointing=215,8
MIN_WAIT=20 shot hz-09-aim-night 0 tab=aim skyline=1 target=dso.M8 pointing=215,8 night=1
MIN_WAIT=12 shot hz-10-objects 0 tab=objects skyline=1
MIN_WAIT=10 shot hz-11-site-editor 3 tab=settings screen=site-editor skyline=1
MIN_WAIT=10 shot hz-12-site-editor-night 2 tab=settings screen=site-editor skyline=1 night=1
MIN_WAIT=10 shot hz-13-site-editor-open-horizon 1 tab=settings screen=site-editor

# 5. Mosaic: the planning sheet for each source, and the mosaic running on Aim and on the Sky chart.
MIN_WAIT=20 shot mo-01-sheet-target 1 $veil screen=mosaic mosaic=target
MIN_WAIT=20 shot mo-02-sheet-target-night 1 $veil screen=mosaic mosaic=target night=1
MIN_WAIT=20 shot mo-03-sheet-milkyway 1 tab=aim target=special.galacticCenter pointing=179,25 focal=24 screen=mosaic mosaic=milkyWay
MIN_WAIT=20 shot mo-04-sheet-custom 1 $veil screen=mosaic mosaic=custom focal=135
MIN_WAIT=24 shot mo-05-running-aim 0 $veil screen=mosaic-running
MIN_WAIT=24 shot mo-06-running-aim-night 0 $veil screen=mosaic-running night=1
MIN_WAIT=24 shot mo-07-running-sky 0 tab=sky target=dso.NGC6960 pointing=84,50 focal=600 screen=mosaic-running
MIN_WAIT=24 shot mo-08-running-sky-night 0 tab=sky target=dso.NGC6960 pointing=84,50 focal=600 screen=mosaic-running night=1
MIN_WAIT=24 shot mo-09-running-milkyway-aim 0 tab=aim target=special.galacticCenter pointing=179,25 focal=24 screen=mosaic-running mosaic=milkyWay
MIN_WAIT=24 shot mo-10-running-milkyway-sky 0 tab=sky target=special.galacticCenter pointing=179,25 focal=24 screen=mosaic-running mosaic=milkyWay

# 6. The Tonight widgets, at the sizes of the widget picker and resized, in the day and the night palette.
widgets wg-01-nominal-day sizes=110x110,146x146,250x110
widgets wg-02-nominal-night sizes=110x110,146x146,250x110 night=1
widgets wg-03-resized-a-day sizes=110x230,250x160
widgets wg-04-resized-a-night sizes=110x230,250x160 night=1
widgets wg-05-resized-b-day sizes=250x230,300x310
widgets wg-06-resized-b-night sizes=250x230,300x310 night=1
widgets wg-07-narrow-day sizes=100x90,120x120,160x110,200x110
widgets wg-08-nolocation-day sizes=100x90,146x146,250x110,250x230 place=none
widgets wg-09-nolocation-night sizes=100x90,146x146,250x110,250x230 place=none night=1

adb logcat -d > "$out/logcat.txt" || true
if grep -E "FATAL EXCEPTION|Fatal signal|ANR in $package|AndroidRuntime: .*$package" "$out/crashes.txt" "$out/errors.txt" >/dev/null; then
    echo "CRASH detected: see crashes.txt and errors.txt"
    grep -h -A 30 "FATAL EXCEPTION" "$out/crashes.txt" "$out/errors.txt" | head -80
    exit 1
fi
echo "no crashes"
