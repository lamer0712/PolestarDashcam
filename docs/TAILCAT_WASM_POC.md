# Tailcat WASM transfer PoC

This branch adds an experimental Tailcat path for moving one Saved file from Gallery+ to an iPhone without joining a Tailscale tailnet.

## Flow

1. Open **Share** in Gallery+.
2. Scan the **Tailcat experiment** QR on the iPhone.
3. The QR opens `https://tailscale.github.io/tailcat/?mode=listen`, which starts the browser Tailcat receiver.
4. Copy the `tc...` listener address shown on the iPhone.
5. Enter that address in Gallery+.
6. Tap **Send latest Saved file**.

Gallery+ sends the newest Saved file through the embedded Go Tailcat bridge (`tailcatbridge.aar`).

## Current limitations

- This is a one-file PoC. It sends only the latest Saved file.
- The iPhone address must be typed into the vehicle UI. A smoother handshake needs a custom hosted Tailcat web page, similar to tailcatchat's invite exchange.
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
