<p align="center">
  <img src="app/src/main/res/drawable/pairdrop_native_logo.png" width="180" alt="PairDrop Native Android logo">
</p>

<h1 align="center">PairDrop Native Android</h1>

<p align="center">
  Send files, text, and links between devices — online through PairDrop or directly on your local network.
</p>

<p align="center">
  <a href="https://github.com/grosserknallkopf/pairdrop-native-android/actions/workflows/android.yml"><img alt="Android build" src="https://github.com/grosserknallkopf/pairdrop-native-android/actions/workflows/android.yml/badge.svg"></a>
  <a href="https://github.com/grosserknallkopf/pairdrop-native-android/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/grosserknallkopf/pairdrop-native-android?include_prereleases&label=beta"></a>
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <a href="THIRD_PARTY_PAIRDROP_LICENSE.txt"><img alt="License" src="https://img.shields.io/badge/PairDrop-MIT-blue"></a>
</p>

> [!IMPORTANT]
> This project is currently in beta. Please report reproducible problems with the Android version, device model, network type, and relevant logs.

## What it does

PairDrop Native Android packages the PairDrop web client as an Android application and adds native integration around it. It works with regular PairDrop clients through the public signaling service when internet access is available. Native Android instances can also discover each other and transfer on the same Wi-Fi network when the internet is unavailable.

### Features

- Send one or multiple files, text, and links
- Receive files directly into `Downloads/PairDrop/`
- Share from other Android apps through the system Sharesheet
- Continue transfers through a foreground service
- Accept or reject background transfer requests from notifications
- View transfer and save progress in the service notification
- Enable background availability from a Quick Settings tile
- Discover multiple native PairDrop devices over mDNS/NSD
- Fall back to local signaling when Android reports no validated internet connection
- Reconnect after returning from Android's file picker
- Keep a selected file and recipient while a reconnect completes
- Avoid blocking the UI while large Sharesheet files are imported
- Use PairDrop pairing, public rooms, room secrets, and its translated interface

## Requirements

- Android 8.0 (API 26) or newer
- Wi-Fi or another working network connection
- Notification permission on Android 13+ for background transfer requests
- Devices on the same Wi-Fi network for offline LAN discovery

Some vendors aggressively stop background apps. The onboarding screen links to the relevant battery and app settings when an exemption is needed.

## Install the beta

1. Download the APK from the [GitHub releases page](https://github.com/grosserknallkopf/pairdrop-native-android/releases).
2. Allow installation from the browser or file manager when Android asks.
3. Install the APK and complete the short onboarding flow.
4. Optionally add the PairDrop tile from Android's Quick Settings editor for background availability.

GitHub may show a warning because the APK is installed outside an app store. Verify that downloads come from this repository. Every published update is signed with the same project release key.

## Online and offline behavior

| Network state | Signaling | Discovery and transfer |
| --- | --- | --- |
| Validated internet | `pairdrop.net` | Compatible PairDrop devices appear through the normal PairDrop service |
| No validated internet | Embedded server | Native Android instances discover one another over mDNS on the local network |

The bundled client is served from the device on `127.0.0.1:53317`. The local Ktor server provides the web assets, WebSocket signaling, native file handoff, and LAN relay endpoints.

## Privacy and permissions

PairDrop transfers use a direct peer connection whenever possible. When WebRTC cannot establish that connection, PairDrop may use its supported fallback transport. Online discovery uses `pairdrop.net`; offline native discovery stays on the local network.

The app requests only the permissions needed for networking, LAN discovery, its foreground service, notifications, and saving received files. Android 10+ files are saved through `MediaStore`; Android 8 and 9 use the legacy storage permission.

## Build from source

Prerequisites:

- JDK 17
- Android SDK with API 36

```bash
git clone https://github.com/grosserknallkopf/pairdrop-native-android.git
cd pairdrop-native-android
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To exercise offline mode, install the APK on at least two Android devices connected to the same Wi-Fi network. Disable or disconnect internet access while keeping the LAN active.

## Continuous integration and releases

The [Android workflow](.github/workflows/android.yml) builds and tests every push and pull request, then uploads a debug APK as a workflow artifact.

Tags matching `v*` additionally build a signed APK and publish a GitHub pre-release. The repository must contain these Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Example:

```bash
git tag v0.1.0-beta.2
git push origin v0.1.0-beta.2
```

The release keystore must be backed up securely. Android will reject an update signed with a different key.

## Project layout

```text
app/src/main/
├── assets/pairdrop/     Bundled PairDrop web client
├── java/.../server/     Local HTTP and WebSocket signaling server
├── java/.../service/    Foreground service and headless client
├── java/.../discovery/  Android NSD/mDNS integration
├── java/.../bridge/     WebView-to-Android bridge
└── java/.../share/      Android Sharesheet import
```

## Upstream and license

This application embeds [PairDrop](https://github.com/schlagmichdoch/PairDrop) v1.11.2. PairDrop is distributed under the GPL-3.0 license; the bundled upstream license is preserved in [LICENSE](LICENSE).

The Android wrapper is an independent community project and is not affiliated with Apple or AirDrop.
