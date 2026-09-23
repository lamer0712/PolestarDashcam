# Tailcat WASM transfer PoC

This branch adds an experimental Tailcat path for moving one Saved file from Gallery+ to an iPhone without joining a Tailscale tailnet.

## Flow

1. Open **Saved** in Gallery+.
2. Enter selection mode and choose one or more files.
3. Tap **Tailcat** next to **Copy to USB** in the bottom action bar.
4. Scan the QR on the iPhone and keep the page open.
5. The custom Gallery+ Tailcat receiver page registers its `tc...` listener address with the vehicle app.
6. Gallery+ automatically sends the selected file. If multiple files are selected, Gallery+ sends one ZIP archive.

Gallery+ serves the receiver page from `/tailcat/` on the local phone-share server and sends the selected Saved media through the embedded Go Tailcat bridge (`tailcatbridge.aar`). A manual `tc...` address fallback remains available in the dialog.

## Current limitations

- This is still a PoC, but it now sends the selected Saved file set. Multiple selections are zipped before transfer.
- The QR receiver page is served from the vehicle app, so the phone must be able to open the local Gallery+ share URL at least once. If the hotspot blocks phone-to-vehicle HTTP, use Tailscale or a hosted receiver page.
- Browser Tailcat currently uses DERP relay only, so large videos can be slow.
- The official Tailcat web demo uses the browser's save-file picker. iPhone Safari compatibility must be validated in the vehicle/phone environment.
- The embedded Go bridge is arm64-only for now.

## Build notes

The bridge source is in `tailcatbridge/` and the generated AAR is `app/libs/tailcatbridge.aar`.

To rebuild it:

```sh
cd tailcatbridge
export PATH="$PATH:$(go env GOPATH)/bin"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK_HOME=/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264
gomobile bind -target=android/arm64 -androidapi=30 -javapkg=com.polestar.tailcat -o ../app/libs/tailcatbridge.aar .
```
