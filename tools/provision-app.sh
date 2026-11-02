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
#   tools/provision-app.sh <adb-serial> <config-file-or-url>
#
# The config can be a JSON file (used as given) or an http(s) URL. A URL is
# expanded to the auto-provisioning defaults: bridge_enabled, control_enabled,
# long/group MMS on, bridge_port 8391, and the URL as both the homeserver and
# the bridge base URL.
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
Usage: tools/provision-app.sh <adb-serial> <config-file-or-url>

The config can be a JSON file or an http(s) URL.

A URL expands to the auto-provisioning defaults:
  {
    "bridge_enabled": true,
    "bridge_port": 8391,
    "bridge_hs_url": "<url>",
    "bridge_base_url": "<url>",
    "control_enabled": true,
    "send_long_message_mms": true,
    "send_group_message_mms": true
  }

A JSON file is applied as given and can override any of the defaults. It holds
one JSON object with any of:
  bridge_enabled, bridge_port, bridge_device_id, bridge_hs_url,
  bridge_base_url, control_enabled, control_token,
  send_long_message_mms, send_group_message_mms

Example config file:
  {
    "bridge_enabled": true,
    "bridge_port": 8391,
    "bridge_hs_url": "https://matrix.example.com",
    "bridge_base_url": "https://bridge.example.com",
    "control_enabled": true,
    "control_token": "a-long-random-token",
    "send_group_message_mms": true
  }
EOF
}

SERIAL="${1:-}"
CONFIG_SOURCE="${2:-}"
if [ -z "$SERIAL" ] || [ -z "$CONFIG_SOURCE" ]; then
	usage
	exit 2
fi

case "$CONFIG_SOURCE" in
http://*|https://*)
	# A URL expands to the auto-provisioning defaults; a JSON file can
	# override any of them. The URL must be a plain URL without quotes.
	CONFIG="$(printf '{"bridge_enabled": true, "bridge_port": 8391, "bridge_hs_url": "%s", "bridge_base_url": "%s", "control_enabled": true, "send_long_message_mms": true, "send_group_message_mms": true}' "$CONFIG_SOURCE" "$CONFIG_SOURCE")"
	;;
*)
	if [ ! -f "$CONFIG_SOURCE" ]; then
		echo "error: config file not found: $CONFIG_SOURCE" >&2
		exit 2
	fi
	CONFIG="$(cat "$CONFIG_SOURCE")"
	case "$CONFIG" in
	'{'*'}')
		;;
	*)
		echo "error: config file must hold one JSON object: $CONFIG_SOURCE" >&2
		exit 2
		;;
	esac
	;;
esac

PKG="${APP_PACKAGE:-org.fossify.messages.bridge}"
PREFIX="${ACTION_PREFIX:-org.fossify.messages.bridge.control}"
# The application id differs from the Kotlin namespace (`org.fossify.messages`),
# so `am -n` must be given the fully qualified class after the slash.
RECEIVER="$PKG/org.fossify.messages.control.ControlReceiver"

# sh_quote single-quotes its argument for the device shell: `adb shell` joins
# its arguments into one command string, so values with spaces, quotes or
# newlines (JSON configs, message texts) must be quoted for the shell that
# parses it.
sh_quote() {
	printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

ARGS=(shell am broadcast -n "$RECEIVER" -a "$PREFIX.PROVISION" --es config "$(sh_quote "$CONFIG")")
if [ -n "${CONTROL_TOKEN:-}" ]; then
	ARGS+=(--es control_token "$(sh_quote "$CONTROL_TOKEN")")
fi

OUTPUT="$(adb -s "$SERIAL" "${ARGS[@]}")"
printf '%s\n' "$OUTPUT"
if grep -q 'error' <<<"$OUTPUT"; then
	echo "error: provisioning failed, see the JSON verdict above" >&2
	exit 1
fi
