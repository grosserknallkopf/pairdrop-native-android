# PairDrop Native Android

Native Android wrapper for PairDrop v1.11.2 with:

- embedded PairDrop web client assets in `app/src/main/assets/pairdrop`
- local Ktor HTTP/WebSocket signaling server on `127.0.0.1:53317`
- offline LAN discovery through Android `NsdManager` (`_pairdrop._tcp.`)
- Quick Settings tile to start/stop the foreground service
- Android Sharesheet support for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
- direct saving of received files to `Downloads/PairDrop/` through `MediaStore`

The app serves the bundled PairDrop client locally. When validated internet is available, `/config` points the PairDrop client at `pairdrop.net` cloud signaling. Without internet, the same client falls back to the embedded signaling server, while the service advertises and discovers other native Android instances over mDNS.

## Build

```bash
./gradlew :app:assembleDebug
```

Every push and pull request is built by GitHub Actions. Debug APKs are attached to
the workflow run as artifacts.

## Beta release

Tags matching `v*` build a signed APK and publish it as a GitHub pre-release. Configure
these repository Actions secrets once before creating the first tag:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded Java/Android keystore
- `ANDROID_KEYSTORE_PASSWORD`: Keystore password
- `ANDROID_KEY_ALIAS`: Signing-key alias
- `ANDROID_KEY_PASSWORD`: Signing-key password

Create the first beta after merging the changes:

```bash
git tag v0.1.0-beta.1
git push origin v0.1.0-beta.1
```

Keep the keystore and its passwords safe. Every later update must use the same signing key.

Install the debug APK on at least two Android devices on the same Wi-Fi network to test offline LAN transfer. Add the PairDrop Quick Settings tile from Android's tile editor, then tap it to start or stop the service.

PairDrop is MIT licensed. The copied upstream license is stored in `THIRD_PARTY_PAIRDROP_LICENSE.txt`.
