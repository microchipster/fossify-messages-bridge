#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 microchipster
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# install-app.sh — install the bridge app and make it the SMS app without any
# interaction on the device, optionally applying a provisioning config (see
# provision-app.sh and docs/bridge-mode.md).
#
# Usage:
#   tools/install-app.sh <adb-serial> <apk> [config-file]
#
# Steps:
#   1. `adb install -r <apk>`
#   2. grant the SMS permissions (and notifications)
#   3. hold the SMS role, set the legacy secure default app setting and keep
#      the role holder (Android 10+)
#   4. stop a local adb agent serving the same phone (avoids double bridging)
#   5. optionally run provision-app.sh <serial> <config-file-or-url>
#
# Environment:
#   APP_PACKAGE  default: org.fossify.messages.bridge
#                the debug build: org.fossify.messages.bridge.debug
#   ADB          default: adb

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
	cat >&2 <<'EOF'
Usage: tools/install-app.sh <adb-serial> <apk> [config-file-or-url]

  <adb-serial>          target device (adb -s)
  <apk>                 bridge APK to install with `adb install -r`
  [config-file-or-url]  optional config applied with control.PROVISION;
                        an http(s) URL expands to the auto-provisioning
                        defaults (bridge_enabled, control_enabled, long and
                        group MMS on, port 8391, the URL as homeserver and
                        bridge base URL), a JSON file is applied as given

The script also stops a local adb agent (android-sms-mms-adb-agent or the
sms-agent dev binary) whose configuration serves the same serial, so pairing
the app cannot leave the phone bridged twice.
EOF
}

SERIAL="${1:-}"
APK="${2:-}"
CONFIG_FILE="${3:-}"
if [ -z "$SERIAL" ] || [ -z "$APK" ]; then
	usage
	exit 2
fi
if [ ! -f "$APK" ]; then
	echo "error: APK not found: $APK" >&2
	exit 2
fi

ADB="${ADB:-adb}"
PKG="${APP_PACKAGE:-org.fossify.messages.bridge}"

# stop_adb_agents stops local adb agents (android-sms-mms-adb-agent, or the
# sms-agent dev binary) whose configuration serves <serial>, so switching the
# phone to app pairing cannot leave it bridged twice. Agents for other phones
# are left alone.
stop_adb_agents() {
	local serial="$1" pid cmd config stopped=0
	for pid in $(pgrep -f 'android-sms-mms-adb-agent|sms-agent' 2>/dev/null || true); do
		cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
		[ -z "$cmd" ] && continue
		config="$(printf '%s' "$cmd" | sed -n 's/.*-c \([^ ]*\).*/\1/p')"
		case "$cmd" in
		*"$serial"*)
			;;
		*)
			if [ -z "$config" ] || [ ! -r "$config" ] || ! grep -q "$serial" "$config" 2>/dev/null; then
				continue
			fi
			;;
		esac
		if kill "$pid" 2>/dev/null; then
			echo "stopped the adb agent (pid $pid) that serves $serial"
			stopped=1
		fi
	done
	if [ "$stopped" = "0" ]; then
		echo "no local adb agent serving $serial is running"
	else
		echo "note: if the bridge still lists an agent login for this phone, remove it with 'unpair <device_id>' in the management room"
	fi
}

"$ADB" -s "$SERIAL" install -r "$APK"

# `pm grant` needs the runtime permissions to exist; a failure is tolerated so
# the script also works on API levels the app does not grant through.
for PERMISSION in READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS POST_NOTIFICATIONS; do
	if ! "$ADB" -s "$SERIAL" shell pm grant "$PKG" "android.permission.$PERMISSION" >/dev/null 2>&1; then
		echo "warning: could not grant android.permission.$PERMISSION" >&2
	fi
done

# Android 10+: hold the SMS role (adds the app as a role holder).
if ! "$ADB" -s "$SERIAL" shell cmd role add-role-holder android.app.role.SMS "$PKG" >/dev/null 2>&1; then
	echo "warning: could not add the SMS role holder (older Android or restricted shell)" >&2
fi

# Older Android and some vendor builds read the secure default app setting.
if ! "$ADB" -s "$SERIAL" shell settings put secure sms_default_application "$PKG" >/dev/null 2>&1; then
	echo "warning: could not set sms_default_application" >&2
fi

stop_adb_agents "$SERIAL"

if [ -n "$CONFIG_FILE" ]; then
	"$SCRIPT_DIR/provision-app.sh" "$SERIAL" "$CONFIG_FILE"
fi

echo "installed $PKG on $SERIAL"
