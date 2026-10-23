#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Slavi Pantaleev
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build a signed release APK for the bridge fork.
#
# The signing keystore lives in `pass` (the same canonical flow used by the
# other Android apps built on this machine, see
# ~/src/repos/utils/android/android/android-signing-keys): the keystore is
# stored at ~/.password-store/<namespace>/android/<applicationId>/release.keystore
# and its password at ~/.password-store/<namespace>/android/<applicationId>/storepass.
#
# Unlike the utility script, this one reads the application id from
# gradle.properties (the fork uses build.gradle.kts) and passes the keystore
# to Gradle through the SIGNING_* environment variables, so no symlink or
# keystore.properties file is needed.
#
# Usage:
#   scripts/build-release.sh                 # build + verify, picks up existing keystore
#   scripts/build-release.sh --keystore-only # only make sure the keystore exists
#   scripts/build-release.sh --install       # also `adb install -r` the APK
#
# Environment overrides:
#   PASS_NAMESPACE  pass namespace (default: example)
#   FLAVOR          product flavor to build (default: foss)
#   ANDROID_HOME    SDK location (default: ~/Android/Sdk)
set -euo pipefail

PASS_NAMESPACE="${PASS_NAMESPACE:-example}"
FLAVOR="${FLAVOR:-foss}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
export ANDROID_HOME JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

APP_ID="$(sed -n 's/^APP_ID=//p' gradle.properties | head -1)"
if [ -z "$APP_ID" ]; then
	echo "error: could not read APP_ID from gradle.properties" >&2
	exit 1
fi

PASS_ENTRY="$PASS_NAMESPACE/android/$APP_ID"
KEYSTORE_DIR="$HOME/.password-store/$PASS_NAMESPACE/android/$APP_ID"
KEYSTORE="$KEYSTORE_DIR/release.keystore"
STOREPASS_FILE="$HOME/.config/android/$APP_ID/storepass"

KEYSTORE_ONLY=0
INSTALL=0
GENERATE_KEY=0
for arg in "$@"; do
	case "$arg" in
	--keystore-only) KEYSTORE_ONLY=1 ;;
	--install) INSTALL=1 ;;
	--generate-key) GENERATE_KEY=1 ;;
	*)
		echo "error: unknown argument: $arg" >&2
		exit 1
		;;
	esac
done

# The canonical flow refuses to touch the keystore while the password store
# has unrelated uncommitted changes for this app.
if git -C "$HOME/.password-store" status --porcelain 2>/dev/null | grep -q "$APP_ID"; then
	echo "error: ~/.password-store has uncommitted changes for $APP_ID; commit or restore them first" >&2
	exit 1
fi

# Reading existing secrets from pass needs an unlocked GPG agent (the signing
# key is passphrase-protected). When that is unavailable, a password can still
# be *written* to pass (encryption only needs the public key) and cached at
# $STOREPASS_FILE for local automation.
read_pass_password() {
	pass show "$PASS_ENTRY/storepass" 2>/dev/null | head -1 || true
}

if [ ! -f "$KEYSTORE" ]; then
	if [ "$GENERATE_KEY" != "1" ]; then
		cat >&2 <<-EOF
			error: no signing keystore for $APP_ID at $KEYSTORE.

			Refusing to generate a new key automatically: a different key cannot
			upgrade an installed APK in place (the installer rejects the signature
			mismatch), so a new key is only ever wanted for a brand-new app.

			To restore the existing key:
			  1. git -C ~/.password-store pull        # keys are committed there
			  2. symlink it: ln -sf "$KEYSTORE" app/release.keystore
			  3. restore the password at $STOREPASS_FILE
			     (or unlock GPG and let \`pass show $PASS_ENTRY/storepass\` work)

			To generate a key on purpose (first release only), re-run with --generate-key.
		EOF
		exit 1
	fi
	echo "==> generating a new signing keystore for $APP_ID (stored in pass)"
	STORE_PASSWORD="$(read_pass_password)"
	if [ -z "$STORE_PASSWORD" ]; then
		STORE_PASSWORD="$(openssl rand -base64 24 | tr -d '\n')"
		echo "==> pass is not readable here (locked GPG agent); generating a fresh password and writing it to pass"
		if printf '%s\n' "$STORE_PASSWORD" | pass insert --multiline --force "$PASS_ENTRY/storepass" >/dev/null 2>&1; then
			echo "==> stored the password in pass at $PASS_ENTRY/storepass"
		else
			echo "warning: could not write $PASS_ENTRY/storepass to pass; keep $STOREPASS_FILE safe" >&2
		fi
	fi
	mkdir -p "$KEYSTORE_DIR"
	keytool -genkeypair \
		-keystore "$KEYSTORE" \
		-storetype PKCS12 \
		-alias release \
		-keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 36500 \
		-storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD" \
		-dname "CN=$APP_ID, OU=Android, O=$PASS_NAMESPACE" \
		-noprompt >/dev/null
	echo "==> keystore created at $KEYSTORE"
	if git -C "$HOME/.password-store" add "$PASS_NAMESPACE" >/dev/null 2>&1 &&
		git -C "$HOME/.password-store" commit -q -m "android: add signing keys for $APP_ID" >/dev/null 2>&1; then
		echo "==> committed the new keys to the pass git repo"
	fi
	echo "==> push the pass repo to its remote (git -C ~/.password-store push) so the keys exist somewhere other than this machine"
	echo "==> consider an offline export too (see docs/SIGNING.md)"
else
	if [ -f "$STOREPASS_FILE" ]; then
		STORE_PASSWORD="$(head -1 "$STOREPASS_FILE")"
	else
		STORE_PASSWORD="$(read_pass_password)"
	fi
fi

if [ -z "${STORE_PASSWORD:-}" ]; then
	echo "error: could not read the store password from pass ($PASS_ENTRY/storepass)" >&2
	exit 1
fi

mkdir -p "$(dirname "$STOREPASS_FILE")"
printf '%s' "$STORE_PASSWORD" >"$STOREPASS_FILE"
chmod 600 "$STOREPASS_FILE"

if [ "$KEYSTORE_ONLY" = "1" ]; then
	echo "==> keystore ready; password cached at $STOREPASS_FILE"
	exit 0
fi

echo "==> building :app:assemble${FLAVOR^}Release"
SIGNING_KEY_ALIAS=release \
	SIGNING_KEY_PASSWORD="$STORE_PASSWORD" \
	SIGNING_STORE_FILE="$KEYSTORE" \
	SIGNING_STORE_PASSWORD="$STORE_PASSWORD" \
	./gradlew ":app:assemble${FLAVOR^}Release" --no-daemon

APK="$(ls -t app/build/outputs/apk/"$FLAVOR"/release/*.apk | head -1)"
echo "==> built $APK"

APKSIGNER="$(ls -t "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | head -1 || true)"
if [ -n "$APKSIGNER" ]; then
	echo "==> verifying signature"
	"$APKSIGNER" verify --print-certs "$APK" | head -6
else
	echo "warning: apksigner not found under $ANDROID_HOME/build-tools, skipping signature verification" >&2
fi

if [ "$INSTALL" = "1" ]; then
	if [ "$APP_ID" != "org.fossify.messages.bridge.debug" ]; then
		echo "note: the release package id is $APP_ID (no .debug suffix); the debug app is a separate install"
		echo "note: remember to grant it the default-SMS role: cmd role add-role-holder --user 0 android.app.role.SMS $APP_ID"
	fi
	echo "==> adb install -r"
	adb install -r "$APK"
fi

echo "==> done"
