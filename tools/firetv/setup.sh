#!/usr/bin/env bash
# Grants Curbox the permissions Fire TV gives no screen for. Fire OS points the overlay and
# accessibility settings intents at empty placeholder activities, so ADB is the only way in.
#
# Usage: tools/firetv/setup.sh <device> [package]
#   device   adb serial, such as 192.168.0.13:5555
#   package  defaults to neth.iecal.curbox; use neth.iecal.curbox.debug for debug builds
set -euo pipefail

DEVICE="${1:?usage: $0 <device> [package]}"
PKG="${2:-neth.iecal.curbox}"
SERVICE="$PKG/neth.iecal.curbox.services.AppBlockerService"

run() { adb -s "$DEVICE" shell "$@"; }

run pm path "$PKG" > /dev/null || { echo "$PKG is not installed on $DEVICE" >&2; exit 1; }

run appops set "$PKG" SYSTEM_ALERT_WINDOW allow
run pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
run dumpsys deviceidle whitelist "+$PKG" > /dev/null

# Keep any service already enabled, such as VoiceView.
current="$(run settings get secure enabled_accessibility_services | tr -d '\r')"
case ":$current:" in
    *":$SERVICE:"*) services="$current" ;;
    ":null:" | "::") services="$SERVICE" ;;
    *) services="$current:$SERVICE" ;;
esac
run settings put secure enabled_accessibility_services "$services"
run settings put secure accessibility_enabled 1

echo "Overlay:        $(run appops get "$PKG" SYSTEM_ALERT_WINDOW | tr -d '\r')"
echo "Secure settings: $(run dumpsys package "$PKG" | grep -c 'WRITE_SECURE_SETTINGS: granted=true') grant(s)"
echo "Accessibility:  $(run settings get secure enabled_accessibility_services | tr -d '\r')"
