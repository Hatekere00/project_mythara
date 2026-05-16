#!/usr/bin/env bash
#
# install-cluster.sh — build + install Mythara across the device cluster.
#
#   Phones  -> :app        (com.mythara.debug)            the Lumi assistant
#   Watches -> :wear       (com.mythara.debug)            the PTT app
#              :watchface  (com.mythara.watchface.debug)  Mythara Tactical WFF face
#
# Why a script instead of auto-delivery: embedding the watch APKs into the
# phone app (the `wearApp` Gradle dependency) only auto-pushes to a paired
# watch when the apps are RELEASE-signed and distributed through Play. For
# sideloaded debug builds that path is a no-op, so this is the one-command
# install. Bundling/release-signing is a later step.
#
# Usage:   ./install-cluster.sh
# Env:     ADB=...        path to adb        (default: adb on PATH)
#          JAVA_HOME=...  JDK 17 for Gradle  (default: /opt/homebrew/opt/openjdk@17)

set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-adb}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

APP_APK="app/build/outputs/apk/debug/app-debug.apk"
WEAR_APK="wear/build/outputs/apk/debug/wear-debug.apk"
WF_APK="watchface/build/outputs/apk/debug/watchface-debug.apk"
WF_ID="com.mythara.watchface.debug"

echo "==> Building debug APKs (:app :wear :watchface)"
./gradlew :app:assembleDebug :wear:assembleDebug :watchface:assembleDebug

found=0
for dev in $("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}'); do
    found=1
    kind=$("$ADB" -s "$dev" shell getprop ro.build.characteristics | tr -d '\r')
    if [[ "$kind" == *watch* ]]; then
        echo "==> $dev (watch): installing :wear + :watchface"
        "$ADB" -s "$dev" install -r "$WEAR_APK"
        "$ADB" -s "$dev" install -r "$WF_APK"
        # set Mythara Tactical as the active face (debug surface)
        "$ADB" -s "$dev" shell am broadcast \
            -a com.google.android.wearable.app.DEBUG_SURFACE \
            --es operation set-watchface --es watchFaceId "$WF_ID"
    else
        echo "==> $dev (phone): installing :app"
        "$ADB" -s "$dev" install -r "$APP_APK"
        # Auto-apply the Mythara wallpaper to home + lock. Uses the
        # in-app WallpaperApplyReceiver's `target=static` mode — it
        # renders one frame of the live wallpaper (posture-adaptive)
        # to a bitmap and applies it via WallpaperManager.setBitmap
        # for both FLAG_SYSTEM and FLAG_LOCK. No user interaction
        # required. The renderer auto-detects fold-inner displays and
        # picks the FoldInner static-layer bake.
        echo "==> $dev (phone): applying Mythara wallpaper (home + lock)"
        "$ADB" -s "$dev" shell am broadcast \
            -a com.mythara.action.APPLY_WALLPAPER \
            -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \
            --es target static >/dev/null
    fi
done

[[ "$found" -eq 1 ]] || { echo "!! no adb devices connected"; exit 1; }
echo "==> Done."
echo
echo "Tip: to upgrade home from static → animated live wallpaper, run:"
echo "  adb -s <serial> shell am broadcast \\"
echo "    -a com.mythara.action.APPLY_WALLPAPER \\"
echo "    -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \\"
echo "    --es target live"
echo "and tap 'Set wallpaper' once in the picker that opens."
