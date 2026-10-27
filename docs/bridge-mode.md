<!--
SPDX-FileCopyrightText: 2026 Slavi Pantaleev

SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Bridge mode: intent-controlled automation

> **QR pairing is the normal flow.** Users pair the app by scanning the QR code shown by
> `!sms login app-pair` in the bridge management room (**Settings → Bridge pairing**). That
> flow needs no adb, no shell access and no hardware identifiers. Everything on this page
> is optional developer tooling for scripted/automation setups; the QR flow remains the
> supported way to connect a phone.

The bridge build (`org.fossify.messages.bridge`, debug variant `org.fossify.messages.bridge.debug`)
exposes a small, token-gated control surface so the app can be driven entirely from a shell
(`adb shell` on the host, or Termux on the device) and from automation apps (Tasker, Easer,
Automate, …) without any UI interaction.

Everything is a **broadcast** with the result delivered through `setResultData(...)` as a
single-line JSON object.

## Enabling

- Preference `control_enabled` (default **off**). While off, every control command returns
  `{"error":{"code":"disabled", …}}` and nothing else happens. Enable it in the app's bridge
  settings (or with `control.CONFIG` after a token is set and the flag is already on).
- Authorization: the caller must either
  - pass `--es control_token <token>` matching the stored control token
    (set it in the app settings), or
  - hold `android.permission.DUMP` — which `adb shell` already does. This is why no root and
    no extra setup are needed for the adb/Termux path.

## Calling from a shell

```sh
# Package: org.fossify.messages.bridge (release) or org.fossify.messages.bridge.debug (debug).
# The class part must be fully qualified: the application id differs from the
# Kotlin namespace, so `$PKG/.control.ControlReceiver` would not resolve.
# Debug package: -n org.fossify.messages.bridge.debug/org.fossify.messages.control.ControlReceiver
adb shell am broadcast \
  -n org.fossify.messages.bridge/org.fossify.messages.control.ControlReceiver \
  -a org.fossify.messages.bridge.control.STATUS
# → Broadcast completed: result=0, data="{"enabled":true,…}"
```

Termux uses the same `am broadcast` invocation. Automation apps send the same intent with the
`control_token` extra.

The convenience wrapper in the bridge repository is:

```sh
tools/drive-app.sh <adb-serial> status|config|pair|send|unpair|sync [args…]
```

`<adb-serial>` only selects the target device for the adb transport; it is never read into
the app or sent to the bridge. The app's own `device_id` is a random value it generates
once (see [Privacy rules](#privacy-rules)).

## Commands

All command names are below; the extra names are shown per command. Extras marked *json* take a
compacted JSON object.

### `control.STATUS`

No extras. Result:

```json
{
  "enabled": true,
  "service_running": true,
  "paired": true,
  "device_id": "android-3f2a9c1d4b5e6f708192a3b4c5d6e7f8",
  "user_id": "@alice:example.com",
  "hs_url": "https://matrix.example.com",
  "bridge_base_url": "https://bridge.example.com",
  "bridge_port": 8391,
  "default_sms_role": true,
  "permissions": {"sms": true, "post_notifications": true, "read_sms": true},
  "last_heartbeat_ms": 1761600000000,
  "queue_cursor": 42,
  "event_cursor": 17,
  "outbox_sms": 0,
  "outbox_mms": 1,
  "version_name": "1.10.1-bridge.1",
  "version_code": 4
}
```

Never includes the bridge token or any message content.

### `control.CONFIG`

Extras (all optional; only provided keys are applied): *json* `config` with any of
`bridge_enabled`, `bridge_port`, `bridge_device_id`, `bridge_token`, `bridge_hs_url`,
`bridge_base_url`, `control_enabled`, `control_token`. Result: the applied preferences (same
shape as a subset of `control.STATUS`), with tokens reported as booleans only
(`"bridge_token_set": true`, `"control_token_set": true`).

### `control.PAIR`

Extra *json* `pair`: `{"code":"…","hs":"https://matrix.example.com","b":"https://bridge.example.com","device_id":"…"}`.
Runs the headless pairing flow (same client the QR flow uses) and returns
`{"paired":true,"device_id":"…","user_id":"…","hs_url":"…"}` or an error.

### `control.UNPAIR`

No extras. Throws away pairing state and credentials. Returns `{"paired":false}`.

### `control.SEND`

Extras: `to` (comma-separated E.164 list), `text`, optional `file` (absolute path readable by
the shell user: `/sdcard/…` or a world-readable temp file). Performs **one** group-aware send:
a single SMS for one recipient without file, a single group MMS for 2+ recipients or when a
file is attached. Result:

```json
{"message_id": 1234, "thread_id": 5, "pending": true, "mode": "mms"}
```

`pending` stays true until the platform confirms the send; pair with the bridge side
(`status` command) for delivery state.

### `control.SYNC`

No extras. Triggers one heartbeat + queue poll immediately. Returns the cursor subset of
`control.STATUS`.

## Errors

```json
{"error": {"code": "invalid_token|disabled|invalid_request|not_paired|unavailable",
           "message": "human readable"}}
```

## Privacy rules

- The control surface **never returns message bodies**, attachments or phone numbers of
  existing conversations. `control.SEND` takes content as input only.
- Logs from control commands contain command names, cursors and error codes — never content,
  and never tokens.
- The device id reported by the app (`device_id`) is a random value generated once by the
  app (`android-<uuid>`); it is never an adb serial, `ANDROID_ID` or any other hardware
  identifier. A device id configured explicitly (for example with `control.CONFIG`) is used
  as-is and never rewritten.
- The gateway is inert unless `control_enabled` is true *and* the caller is authorized.

## Implementation map (for maintainers)

- `app/src/main/kotlin/org/fossify/messages/control/ControlContract.kt` — action names, extra
  keys, JSON encoding helpers.
- `…/control/ControlReceiver.kt` — ordered `BroadcastReceiver`, authorization, dispatch,
  `setResultData`.
- `app/src/test/kotlin/org/fossify/messages/control/ControlContractTest.kt` — JSON contract
  and authorization matrix tests (`./gradlew :app:testFossDebugUnitTest`).
- Status/config come from existing preference and `BridgeService` state; `PAIR`/`UNPAIR`
  reuse `PairingClient`/`PairingStore`; `SEND` reuses `BridgeSender` (including the
  one-MMS group rule and the own-number filter); `SYNC` reuses the heartbeat/queue poller.
- Unit tests cover the JSON contracts and authorization matrix for every command.
