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

## File transfer protocol (data channel)
Files are streamed over a dedicated TCP socket (default port 8767) as raw bytes:
`MAGIC | transferId | fileId | offset | size` → receiver replies resume offset → raw bytes → CRC32 → 1/0.
The receiver announces its data port in `FileResponseMessage.dataPort`.
