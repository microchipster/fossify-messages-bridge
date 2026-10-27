<!--
SPDX-FileCopyrightText: 2026 microchipster

SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Long messages and group threads as MMS: advice

**Short answer: yes.** For a deployment whose contacts are mostly on iMessage or Google
Messages, enable both `send_long_message_mms` and `send_group_message_mms`. Group MMS is the
bigger win: it keeps one conversation for every participant instead of 1:1 SMS threads the
recipients answer individually. Long-message MMS is the smaller win: it avoids multipart-SMS
reassembly quirks. Neither is free, so check the caveats below before turning them on for a
metered or MMS-less plan.

## How the app decides today

- `send_long_message_mms` ("Send long messages as MMS", default off) — a text that needs more
  than one SMS segment (`getSendMessageSettings()` sets `sendLongAsMmsAfter = 1`, so anything
  over one page) is sent as a single MMS instead of a concatenated multipart SMS.
- `send_group_message_mms` ("Send group messages as MMS", default off) — a thread with more
  than one recipient is sent as one group MMS. If the preference was never set, the thread UI
  asks once on the first group send (`GroupMessageSendDialog`).
- Attachments always go out as MMS regardless of these preferences.
- Code paths: `messaging/Messaging.kt` (`getSendMessageSettings()`, `isLongMmsMessage()`,
  `sendMessageCompat()`), the thread UI (`ThreadActivity.isMmsMessage()`), and the
  bridge/control sends (`bridge/BridgeSender.kt`, `control/ControlReceiver.kt`). The
  bridge/control path forces group MMS for 2+ recipients and only follows the long-message
  preference while the app holds the default SMS role, because MMS requires it.

## Interop trade-offs

| | Concatenated SMS | MMS |
| --- | --- | --- |
| Long message on the other side | reassembled from UDH parts; usually correct, but parts can arrive out of order or be dropped by carrier/aggregator paths | one message with one body, nothing to reassemble |
| Group thread | recipients see separate 1:1 threads and reply to the sender only | one group thread for every MMS-capable client (iMessage, Google Messages, most Android apps) |
| Transport | pure SMS signaling, no data needed | needs mobile data + a working MMSC/APN |
| Cost | billed per segment | billed per message (usually more than an SMS) |
| Size | 160 chars per segment, practically unlimited count | carrier limit (~300 KB–1 MB); the app's "Resize sent MMS images" setting also applies |
| Failure mode | silent part loss | send error, message stays pending and is retried |

With iMessage contacts, both formats are green bubbles; the difference is the thread shape.
With Google Messages contacts, RCS covers both cases when both ends support it and the
fallback is exactly this decision.

Caveats seen in the field:

- MMS fails on some prepaid/roaming plans, some carriers cap size aggressively, and MMS to
  short codes or email gateways is often not supported.
- Group MMS replies can still arrive as individual SMS if a recipient's client or carrier
  downgrades the group; that is out of the phone's control.
- A phone without data connectivity can send neither MMS nor receive attachments; SMS still
  works, which is why the defaults stay conservative.

## Recommendation

- Contacts mostly on iMessage/Google Messages, SIM has data + MMS: **enable both**. Group
  MMS first — it is the single biggest interop improvement.
- Cost-sensitive plan: leave long-message MMS off and prefer short texts; keep group MMS on
  if group chats matter, since separate 1:1 replies are worse than one MMS charge.
- Carrier that caps or drops MMS: keep `send_long_message_mms` off. Multipart SMS is the
  fallback and most clients reassemble it fine.
- Headless/bridge deployments: `control.PROVISION` covers the bridge/control preferences
  only, so the two message-format preferences still have to be set once in
  **Settings → Outgoing messages**. Adding them (or a general message-format block) to the
  provisioning config is the obvious follow-up if automation should own the send format too.
