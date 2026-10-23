<!--
SPDX-FileCopyrightText: 2026 microchipster

SPDX-License-Identifier: GPL-3.0-or-later
-->

# Bridge mode

Bridge mode turns the app into a local HTTP server that exposes the full SMS/MMS
history of the device and can send SMS and MMS. It exists so that a Matrix bridge
(such as `mautrix-android-sms-mms`) can run on a server on the same network and
talk to the phone without adb and without root.

The HTTP contract is the agent API v1 documented in the Matrix bridge repository
(`docs/AGENT_API.md`): `GET /v1/health` is unauthenticated, every other route
requires `Authorization: Bearer <token>`.

There are two independent transports:

- the static-token HTTP server described on this page (`bridge_enabled`), for a
  bridge or agent that can reach the phone on the network, and
- the app-initiated pairing transport, where the phone polls the Matrix bridge
  over HTTPS (see [Pairing with a Matrix bridge](#pairing-with-a-matrix-bridge-app-initiated-transport)).

Both use the same telephony code and can run at the same time; they share the
foreground service.

## Enabling bridge mode

Bridge mode is off by default. It is controlled by three preferences that live in
the regular app preferences file (`shared_prefs/Prefs.xml`):

| Preference | Default | Meaning |
|---|---|---|
| `bridge_enabled` | `false` | Starts/stops the local HTTP server inside the foreground service (the service also runs while a paired device token is stored) |
| `bridge_port` | `8391` | TCP port of the HTTP server |
| `bridge_token` | empty | Bearer token; generated on first service start |
| `bridge_device_id` | empty | Stable device id reported in `/v1/health`; falls back to `Build.getSerial()`/`ANDROID_ID`. Set it to the adb serial when the same phone is also used with the adb agent, so both transports produce identical portal IDs |

The bridge derives room identity from `device.serial`, so changing
`bridge_device_id` on a running deployment makes the bridge treat the phone as a
new device and create a second set of rooms. Set it once, right after installing.

There is no in-app settings screen for these static agent-mode preferences yet
(the pairing screen is a separate screen), so on a debug build write the
preference with `run-as`:

```sh
# 1. stop the app so it cannot overwrite the file from memory
adb shell am force-stop org.fossify.messages.bridge.debug

# 2. pull, enable, push back
adb exec-out run-as org.fossify.messages.bridge.debug cat shared_prefs/Prefs.xml > Prefs.xml
# edit Prefs.xml so it contains:
#   <boolean name="bridge_enabled" value="true" />
#   <int name="bridge_port" value="8391" />
adb push Prefs.xml /data/local/tmp/Prefs.xml
adb shell run-as org.fossify.messages.bridge.debug cp /data/local/tmp/Prefs.xml shared_prefs/Prefs.xml

# 3. start the app once; the service auto-starts and keeps running
adb shell monkey -p org.fossify.messages.bridge.debug -c android.intent.category.LAUNCHER 1
```

On a rooted device the same file can be edited directly under
`/data/data/<applicationId>/shared_prefs/Prefs.xml` (the debug application id is
`org.fossify.messages.bridge.debug`, the release one is
`org.fossify.messages.bridge`). The service also auto-starts after a reboot when
`bridge_enabled` is true or a paired device token is stored.

A small "Messages Bridge" foreground notification (channel `fossify_messages`)
indicates that the server is running.

## Token and port

The bearer token is generated once (32 hex characters) and persisted as
`bridge_token`. It is also written to logcat when the service starts:

```sh
adb logcat -s BridgeService
# I BridgeService: bridge HTTP API listening on 0.0.0.0:8391, bearer token: 0123abcd...
```

Delete the `bridge_token` preference to have a new token generated on the next
service start. Changing `bridge_port` requires restarting the app (or the
service).

Quick check from the bridge host:

```sh
curl http://<phone-ip>:8391/v1/health
curl -H 'Authorization: Bearer <token>' http://<phone-ip>:8391/v1/threads
```

## Pairing with a Matrix bridge (app-initiated transport)

The pairing transport inverts the direction: the phone polls the bridge over
HTTPS, so no LAN reachability, port forwarding or `adb` is needed. The wire
protocol is `docs/PAIRING_PROTOCOL.md` in `mautrix-android-sms-mms`. It is
independent of `bridge_enabled`: the static-token API and the pairing poller
can be enabled together or separately, both run inside the same foreground
service.

### Pairing preferences

| Preference | Meaning |
|---|---|
| `bridge_hs_url` | Homeserver URL of the paired Matrix account |
| `bridge_base_url` | Public base URL of the bridge API (`/_smsmms/v1/...`) |
| `bridge_paired_device_id` | Device id the bridge knows: `bridge_device_id` when set, else a generated `android-<random>` |
| `bridge_paired_user` | MXID that confirmed the pairing |
| `bridge_token_wrapped` | Device token, AES-GCM wrapped with the Android Keystore key `smsmms_bridge_token_key` (never stored in clear) |
| `bridge_queue_cursor`, `bridge_events_cursor`, `bridge_last_event_pushed_ms` | Direction cursors and the gap-fill watermark |
| `bridge_pending_events`, `bridge_recent_send_results` | Persisted unsent events and recent send results |

The device signing key lives in the Android Keystore under the alias
`smsmms_pair_key` (ECDSA P-256, non-exportable) and is created on first use.

### Pairing a phone

Run `!sms login` in the bridge's management room. The bot renders a QR code and
the same payload as text:

```
smsmms://pair?hs=https%3A%2F%2Fmatrix.example.com&b=https%3A%2F%2Fmatrix.example.com&code=ABCD1234&v=1
```

Open **Settings → Bridge pairing** in the app and either

- scan the QR code (camera permission; optional, the payload can be pasted
  instead),
- paste the payload and tap *Use payload* (it fills the fields and pairs
  immediately), or
- type the homeserver URL, the optional bridge URL and the 8 character code,
  then tap *Pair*.

When the bridge URL is left empty the app reads `{hs}/.well-known/matrix/client`
and uses the `chat.<namespace>.bridge.sms_mms` key (the playbook injects it). A
manually entered bridge URL always wins.

On success the app stores the device token, starts (or keeps) the foreground
service and shows `Paired as @user:example.com`. *Unpair this device* clears
the local pairing state; the bridge side can revoke a token with
`!sms unpair`, and a rejected token clears the local pairing automatically.

The activity is not exported. The supported entry point is the settings row; on
a debuggable build it can also be started from adb:

```sh
# debug build
adb shell run-as org.fossify.messages.bridge.debug am start -n \
  org.fossify.messages.bridge.debug/org.fossify.messages.bridge.PairingActivity
# release build
adb shell run-as org.fossify.messages.bridge am start -n \
  org.fossify.messages.bridge/org.fossify.messages.bridge.PairingActivity
```

`run-as` executes the command as the app's own user, which is required because
the activity is not exported (`am start` from the shell would be rejected on
recent Android versions).

### Runtime behaviour

- Long-polls `GET /_smsmms/v1/queue?since=<cursor>&wait_ms=30000` with the
  bearer token. `send_sms` / `send_mms` go through the same send path as
  `POST /v1/send`, `mark_read` through the read-state writer and `ping` is
  answered directly; failures are retried with exponential backoff
  (5 s … 5 min).
- Answers sends with `send.result` (with `pending: true` until the provider row
  can be resolved) and `mark_read` / `ping` with `ack`; received messages are
  pushed as `message.new` / `thread.updated` events by the event-push thread.
- Sends a heartbeat (`battery_pct`, `default_sms_app`, permissions,
  `app_version`) every 5 minutes.
- Recovers state on `reset: true`, on an overflowing local event buffer and on
  startup after downtime by re-sending a bounded snapshot (newest 50 threads,
  200 messages, up to 24 h back).
- Unsent events and the last 100 send results survive restarts, so a command
  that is re-delivered after a crash is answered from the cache instead of
  being sent twice.

### Security notes

- All pairing traffic is TLS. Plain HTTP is refused except for loopback URLs
  (development only).
- The device token is the only bearer credential; it is wrapped with a
  non-exportable Keystore key, which also means it does not survive a device
  restore (the phone pairs again).
- The pairing code is one-time, short-lived (10 minutes) and bound to the
  Matrix user that started the flow; the app proves device possession by
  signing the server nonce with the Keystore key.
- Revocation is bridge-side (`!sms unpair <device_id|all>`); the app clears
  its stored token as soon as a request is rejected.

## API summary

| Route | Purpose |
|---|---|
| `GET /v1/health` | Device/backend info; no auth |
| `GET /v1/subscriptions` | SIM subscriptions |
| `GET /v1/threads` | All conversations |
| `GET /v1/threads/{id}` | One conversation + recipients |
| `GET /v1/threads/{id}/messages?before_ms=&after_ms=&limit=` | Thread history, oldest first |
| `GET /v1/messages/{id}` | One message (`sms:<sub>:<row>` / `mms:<sub>:<row>`) |
| `GET /v1/mms/{id}/parts/{part_id}` | Raw MMS part bytes |
| `GET /v1/events?since=&timeout_ms=` | Long-poll event feed (max 30 s) |
| `POST /v1/read` | Mark a thread (`thread_id`/`up_to_ms`) or messages (`message_ids`) read |
| `POST /v1/send` | Send SMS or MMS (base64 attachments, multi-recipient group MMS) |
| `GET /v1/contacts` | Not implemented (returns `501 unsupported`) |
| `OPTIONS *` | Preflight helper (returns `204`, no auth) |

`POST /v1/send` builds a single MMS carrying every attachment and forces group
MMS for multi-recipient sends. SMS is used only for a single recipient without
attachments (long messages go out as MMS only when "send long messages as MMS"
is enabled in the app settings). Error responses use the agent API codes:
`unauthorized`, `not_found`, `unsupported` (malformed request, too many
recipients, group SMS without the default role), `too_long` (more than 100 SMS
parts), `too_large` (>5 MiB attachment), `mms_send_unsupported` (attachments
without the default SMS role) and `send_failed`.

The event feed is an in-memory ring buffer (10000 events). When the client's
cursor is older than the retained window the response sets `"reset": true` and
the client must re-scan threads and messages. Receive hooks and the send path
currently emit `message.new` events; `message.updated`, `mms.downloaded` and
`thread.updated` are not generated yet, so clients reconcile those changes
through history fetches and read-state polling.

## Permissions

Grant the runtime permissions over adb (they cannot be granted at install time):

```sh
PKG=org.fossify.messages.bridge.debug
adb shell pm grant $PKG android.permission.READ_SMS
adb shell pm grant $PKG android.permission.SEND_SMS
adb shell pm grant $PKG android.permission.RECEIVE_SMS
adb shell pm grant $PKG android.permission.RECEIVE_MMS
adb shell pm grant $PKG android.permission.WRITE_SMS
adb shell pm grant $PKG android.permission.READ_PHONE_STATE
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell pm grant $PKG android.permission.READ_CONTACTS   # optional, for names
```

Sending SMS works without the default-SMS role as long as `SEND_SMS` is granted,
but receiving (`SMS_DELIVER`, `WAP_PUSH_DELIVER`) and writing to the telephony
provider (MMS outbox persistence, read state) only work for the **default SMS
app**. Set the role in Settings → Apps → Default apps → SMS app, or from a shell:

```sh
adb shell cmd role add-role-holder android.app.role.SMS org.fossify.messages.bridge
```

The `cmd role` route may require root or an unlocked/debuggable device; the
Settings UI always works. The health endpoint reports the current default SMS
app in `default_sms_app`.

MMS additionally needs an MMS APN and mobile data (or carrier Wi-Fi calling
support), same as any other SMS app.

## Limitations and caveats

- The default-SMS role is required for MMS send and for read-state writeback;
  `/v1/health` reports `backend.send_mms`, `backend.can_mark_read` and the
  `default_sms_app` accordingly. Without the role, `POST /v1/send` with
  attachments fails with `501 mms_send_unsupported` and `POST /v1/read` may
  update no rows.
- MMS download (and therefore `mms.downloaded`-style receive) only happens for
  the default SMS app: WAP push broadcasts (`WAP_PUSH_DELIVER`) are delivered
  to the default app only.
- Read state is written to the telephony provider (`read` + `seen`); some
  vendor ROMs ignore `seen`. The bridge treats `read` as authoritative.
- Battery optimizations can kill the foreground service on aggressive ROMs;
  exempt the app, and keep `POST_NOTIFICATIONS` granted so the FGS notification
  is visible.
- Contact names are best-effort (`READ_CONTACTS`): `/v1/threads` returns raw
  addresses, `/v1/contacts` is not implemented (`501`).

## Building

The app depends on the local `fossify-mmslib` fork instead of the JitPack
artifact:

```sh
JAVA_HOME=... ANDROID_HOME=... ./gradlew :app:assembleFossDebug
# mmslib is expected at ../fossify-mmslib/library, override with:
MMSLIB_DIR=/path/to/fossify-mmslib/library ./gradlew :app:assembleFossDebug
```

The debug build installs side-by-side with the stock app
(`org.fossify.messages.bridge.debug`, label "Messages Bridge debug").

## Security notes

- The server listens on all interfaces (`0.0.0.0:<port>`) because the Matrix
  bridge usually runs on another machine. Use it on a trusted LAN only; do not
  expose the port to the internet or an untrusted guest network.
- Traffic is plain HTTP. The bearer token is the only protection; anyone on the
  same network segment who has the token can read all messages and send SMS/MMS.
- Keep `POST_NOTIFICATIONS` granted and the app out of battery optimizations so
  the foreground service (and therefore push receive events) survives.
- The service is not exported, so other apps on the phone cannot start it, and
  no other app can bind to it.
