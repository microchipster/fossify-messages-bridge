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
#   4. optionally run provision-app.sh <serial> <config-file>
#
# Environment:
#   APP_PACKAGE  default: org.fossify.messages.bridge
#                the debug build: org.fossify.messages.bridge.debug
#   ADB          default: adb

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
	cat >&2 <<'EOF'
Usage: tools/install-app.sh <adb-serial> <apk> [config-file]

  <adb-serial>   target device (adb -s)
  <apk>          bridge APK to install with `adb install -r`
  [config-file]  optional JSON config applied with control.PROVISION
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

if [ -n "$CONFIG_FILE" ]; then
	"$SCRIPT_DIR/provision-app.sh" "$SERIAL" "$CONFIG_FILE"
fi

echo "installed $PKG on $SERIAL"
