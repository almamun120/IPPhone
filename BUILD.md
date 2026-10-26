# IPPhone — build & CI

* Push to GitHub → **Actions → Android CI** builds automatically:
  * `IPPhone-debug.apk` (debug)
  * `IPPhone-release-signed.apk` (release, signed, minified)
* Download them from the run's **Artifacts**; on `main`/`master` they are also attached to a GitHub **Release**.
* Local build: `./gradlew assembleDebug assembleRelease`

## Signing
The release APK is signed with `keystore/ipphone-release.jks` (password `ipphone123`, alias `ipphone`),
so every build is installable as an update of the previous one.
For a private key instead, add repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` — the workflow picks them up automatically.

## Features worth knowing
* **File transfer** streams raw bytes over its own TCP socket (default port 8767) — no JSON/Base64.
  `MAGIC | transferId | fileId | offset | size` → receiver answers with the resume offset → bytes → CRC32 → 1/0.
  The receiver announces its data port in `FileResponseMessage.dataPort`.
* **Quick Connect (QR)**: Nearby → QR icon (or Settings). One phone starts a LocalOnlyHotspot and shows
  a standard `WIFI:` QR; the other scans it (Google code scanner) and joins via `WifiNetworkSpecifier`.
* **Diagnostics**: Settings → Diagnostics — addresses, ports, peers, TCP round-trip, live transfer speed,
  crash log and "why did the app restart" (Android exit reasons).
* **Database**: version 3 (composite index on messages). Migration 2→3 keeps all chats.
  The legacy `pairing_state` / `paired_at` columns are still in the table (unused) so no data is lost.

## Dialing, calls and Wi-Fi Direct
* **Dial pad**: a typed IP is validated first ("Invalid IP address"), then checked (online / live TCP probe /
  chat or call history). Nothing is opened for an address nobody answers on — the app only says "Not available".
  Offline contacts that have history open the chat only (audio, video and file transfer are greyed out).
  `CallViewModel` and `CallManager.startCall` run the same check, so no foreground service, microphone,
  camera or call-log entry is created for an unreachable peer.
* **Video calls** start on the loudspeaker (Android 12+ via `setCommunicationDevice`); the speaker button
  mirrors the real state and can still be switched off by hand.
* **Wi-Fi Direct** (Settings → Wi-Fi Direct): choose *Group Owner*, *Client* or *Auto*.
  Group Owner creates the group; Client joins with group-owner-intent 0 and never becomes the owner.
  Discovered phones that already own a group are marked "Group Owner" and listed first.
