# Bridge mode

Bridge mode turns the app into a local HTTP server that exposes the full SMS/MMS
history of the device and can send SMS and MMS. It exists so that a Matrix bridge
(such as `mautrix-android-sms-mms`) can run on a server on the same network and
talk to the phone without adb and without root.

The HTTP contract is the agent API v1 documented in the Matrix bridge repository
(`docs/AGENT_API.md`): `GET /v1/health` is unauthenticated, every other route
requires `Authorization: Bearer <token>`.

## Enabling bridge mode

Bridge mode is off by default. It is controlled by three preferences that live in
the regular app preferences file (`shared_prefs/Prefs.xml`):

| Preference | Default | Meaning |
|---|---|---|
| `bridge_enabled` | `false` | Starts/stops the foreground service |
| `bridge_port` | `8391` | TCP port of the HTTP server |
| `bridge_token` | empty | Bearer token; generated on first service start |
| `bridge_device_id` | empty | Stable device id reported in `/v1/health`; falls back to `Build.getSerial()`/`ANDROID_ID`. Set it to the adb serial when the same phone is also used with the adb agent, so both transports produce identical portal IDs |

The bridge derives room identity from `device.serial`, so changing
`bridge_device_id` on a running deployment makes the bridge treat the phone as a
new device and create a second set of rooms. Set it once, right after installing.

There is no in-app settings screen yet, so on a debug build write the preference
with `run-as`:

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
`bridge_enabled` is true.

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

`POST /v1/send` builds a single MMS carrying every attachment and forces group
MMS for multi-recipient sends. SMS is used only for a single recipient without
attachments (long messages go out as MMS only when "send long messages as MMS"
is enabled in the app settings).

The event feed is an in-memory ring buffer (10000 events). When the client's
cursor is older than the retained window the response sets `"reset": true` and
the client must re-scan threads and messages.

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
