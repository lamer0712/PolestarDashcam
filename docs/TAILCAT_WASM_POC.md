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

Gallery+ opens the hosted receiver page at `https://lamer0712.github.io/PolestarDashcam/tailcat/` for Saved Tailcat transfers. When a vehicle-side `car=...` Tailcat control address is available, the phone sends its receive address back automatically over Tailcat. If the Android Tailcat control listener is not ready or fails, the phone page still shows a `tc...` receive address that can be typed into the Gallery+ manual fallback field. The app-local `/tailcat/` page and local `/tailcat-register` path remain as older fallbacks. Selected Saved media is sent through the embedded Go Tailcat bridge (`tailcatbridge.aar`).

## Current limitations

- This is still a PoC, but it now sends the selected Saved file set. Multiple selections are zipped before transfer.
- The hosted receiver assets live in `docs/tailcat/` for GitHub Pages. Enable GitHub Pages from the repository's `main` branch `/docs` folder.
- If the GitHub account or repository name changes, update `HOSTED_TAILCAT_RECEIVER_URL` in `MainActivity.kt`.
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
