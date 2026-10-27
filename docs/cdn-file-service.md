<!--
SPDX-FileCopyrightText: 2026 microchipster

SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Encrypted file links (bridge file service)

The bridge can host oversized attachments as AES-256-GCM encrypted files and
put a `<cdn_base_url>/f/<id>#<key>` link into the message instead of pushing
the bytes through MMS. The app side is configured in **Settings → Bridge**; the
send path only ever runs when the bridge advertised a `cdn_base_url` over a
paired device token, so plain SMS/MMS sends are untouched without one.

## Sender-side size settings

- **Send files larger than** — the attachment size above which the send mode
  applies.
  - **Auto (carrier MMS limit)** *(default)* — the threshold is the MMS file
    size limit configured in the app's MMS settings. This matches the behavior
    the send path had before the threshold existed.
  - **1 / 5 / 10 MB / custom** — a fixed size. A custom value of `0 MB`
    applies the mode to any attachment, so with **Encrypted link** even small
    files become links.
- **For files above the limit** — what happens above the threshold.
  - **Always ask** *(default)* — the choice dialog: encrypted link, compress
    first (images only), send as MMS when the carrier limit still carries it,
    or cancel.
  - **Encrypted link** — uploads right away, without the dialog. When the link
    path is unavailable (no advertised base URL, file above **Maximum upload
    size**, upload failure), the app falls back to the ordinary compress/MMS
    path.
  - **Never** — the CDN never appears in the send path; oversized images are
    compressed (asking first while **Ask before compressing large
    attachments** is on) and everything else keeps the old behavior.
- **Maximum upload size**, **Link expiry** and **Ask for the link expiry on
  every upload** are unchanged. The upload limit is an app-side pre-check
  against the bridge's own `max_file_bytes`.

## Migration from the old boolean setting

Installs that still carry the previous `cdn_allow_links` preference migrate on
the next read, without a visible step:

| old value | new mode |
| --- | --- |
| checked ("Send large files as encrypted CDN links") | **Always ask** — the same dialog as before; the link leaves the device only when tapped |
| unchecked (default) | **Never** — no CDN offer, compress/MMS exactly as before |

The threshold starts at **Auto** (the carrier MMS limit the old send path
used), and the **Ask before compressing** and **Maximum upload size**
preferences carry over unchanged. Nothing is uploaded until the user taps the
link option in the dialog or picks **Encrypted link**.

## Related

- Bridge-side file-service contract: `docs/cdn-file-service.md` in the bridge
  repository (upload, raw download, wrapper page, caps).
- Rendering of received links is local to the app and needs no setting.
