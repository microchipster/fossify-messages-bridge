#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 microchipster
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# provision-app.sh — apply a zero-touch provisioning config (one JSON object)
# to the bridge app over adb using the control.PROVISION broadcast documented
# in docs/bridge-mode.md.
#
# Usage:
#   tools/provision-app.sh <adb-serial> <config-file>
#
# Environment:
#   CONTROL_TOKEN  optional control token extra. `adb shell` holds
#                  android.permission.DUMP, so this stays unset unless the
#                  app requires a token on top.
#   APP_PACKAGE    default: org.fossify.messages.bridge
#                  the debug build: org.fossify.messages.bridge.debug
#   ACTION_PREFIX  default: org.fossify.messages.bridge.control
#
# The app answers with a single-line JSON verdict in the `data=` field of the
# `Broadcast completed: ...` output line; the script exits non-zero when the
# verdict is an error.

set -euo pipefail

usage() {
	cat >&2 <<'EOF'
Usage: tools/provision-app.sh <adb-serial> <config-file>

The config file holds one JSON object with any of:
  bridge_enabled, bridge_port, bridge_device_id, bridge_hs_url,
  bridge_base_url, control_enabled, control_token

Example config file:
  {
    "bridge_enabled": true,
    "bridge_port": 8391,
    "bridge_hs_url": "https://matrix.example.com",
    "bridge_base_url": "https://bridge.example.com",
    "control_enabled": true,
    "control_token": "a-long-random-token"
  }
EOF
}

SERIAL="${1:-}"
CONFIG_FILE="${2:-}"
if [ -z "$SERIAL" ] || [ -z "$CONFIG_FILE" ]; then
	usage
	exit 2
fi

if [ ! -f "$CONFIG_FILE" ]; then
	echo "error: config file not found: $CONFIG_FILE" >&2
	exit 2
fi

CONFIG="$(cat "$CONFIG_FILE")"
case "$CONFIG" in
'{'*'}')
	;;
*)
	echo "error: config file must hold one JSON object: $CONFIG_FILE" >&2
	exit 2
	;;
esac

PKG="${APP_PACKAGE:-org.fossify.messages.bridge}"
PREFIX="${ACTION_PREFIX:-org.fossify.messages.bridge.control}"
# The application id differs from the Kotlin namespace (`org.fossify.messages`),
# so `am -n` must be given the fully qualified class after the slash.
RECEIVER="$PKG/org.fossify.messages.control.ControlReceiver"

ARGS=(shell am broadcast -n "$RECEIVER" -a "$PREFIX.PROVISION" --es config "$CONFIG")
if [ -n "${CONTROL_TOKEN:-}" ]; then
	ARGS+=(--es control_token "$CONTROL_TOKEN")
fi

OUTPUT="$(adb -s "$SERIAL" "${ARGS[@]}")"
printf '%s\n' "$OUTPUT"
if grep -q 'error' <<<"$OUTPUT"; then
	echo "error: provisioning failed, see the JSON verdict above" >&2
	exit 1
fi
