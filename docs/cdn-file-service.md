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
- **Maximum upload size** — 10 / 25 / 50 / 100 / **250 MB** *(default)*. An
  app-side pre-check against the bridge's own `max_file_bytes`, which allows up
  to 512 MiB. A stored value keeps working when the option list changes.
- **Link expiry** and **Ask for the link expiry on every upload** are
  unchanged.

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

## Albums (several media items, one link)

Selecting more than one photo or video (the gallery pickers allow multiple
selection; sharing several media items into a conversation behaves the same)
offers the same size UX as one link, with the whole selection as the unit:

- **Send as encrypted album** appears in the choice dialog when more than one
  media item is selected and the selection as a whole is above the threshold,
  or right away in the **Encrypted link** mode. Choosing it uploads every item
  and the manifest in one `POST /f/albums` request and puts a single
  `<cdn_base_url>/f/<id>#<key>` link into the message instead of the
  attachments; **Add as separate attachments** keeps the ordinary MMS path.
- The item order is the selection order; images, videos and audio can be
  mixed. The app pre-checks the bridge defaults (at most 20 items, at most
  500 MB of ciphertext, every item within **Maximum upload size**) and shows
  the bridge's own error when a deployment lowered a cap.
- One random fragment key per album; every item and the manifest are sealed
  under their own HKDF-SHA256 subkey, derived on the device exactly like the
  gallery page derives them. The key is never stored, logged or sent.

Received album links render in place like single links: the app fetches the
ciphertext route, falls back to the manifest route when the id is not a single
file, and shows a 2–4 column grid (images as thumbnails, videos with a poster
and play outline, audio with the compact player, everything else as a file
card). Each cell loads independently, so one broken item shows **Couldn't
load** without breaking the grid; taps open the existing viewer per item. The
decrypted items share the bounded disk/memory caches of single links and are
pruned the same way.

## Related

- Bridge-side file-service contract: `docs/cdn-file-service.md` in the bridge
  repository (upload, raw download, wrapper page, caps).
- Rendering of received links is local to the app and needs no setting.
