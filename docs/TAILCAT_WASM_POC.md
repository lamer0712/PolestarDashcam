# Tailcat WASM transfer PoC

This branch adds an experimental Tailcat path for moving one Saved file from Gallery+ to an iPhone without joining a Tailscale tailnet.

## Flow

1. Open **Saved** in Gallery+.
2. Enter selection mode and choose one or more files.
3. Tap **Tailcat** next to **Copy to USB** in the bottom action bar.
4. Scan the QR on the iPhone and keep the page open.
5. Gallery+ creates a vehicle-side Tailcat control listener and puts that `tc...` address in the QR URL.
6. The phone browser creates its own receive listener, then sends that receive `tc...` address back to the vehicle over the vehicle Tailcat control listener.
7. Gallery+ automatically sends the selected file. If multiple files are selected, Gallery+ sends one ZIP archive.

Gallery+ currently serves the receiver page from `/tailcat/` on the local phone-share server, but the address exchange itself no longer needs `/tailcat-register` when the QR contains `car=...`. The local `/tailcat-register` path and manual `tc...` address entry remain available as fallbacks. Selected Saved media is sent through the embedded Go Tailcat bridge (`tailcatbridge.aar`).

## Current limitations

- This is still a PoC, but it now sends the selected Saved file set. Multiple selections are zipped before transfer.
- The QR receiver page is still served from the vehicle app in this APK, so the phone must be able to open that page at least once. To remove the same-network dependency completely, host the same `tailcat/` web assets on public HTTPS and point the QR there with the vehicle `car=...` Tailcat address.
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
