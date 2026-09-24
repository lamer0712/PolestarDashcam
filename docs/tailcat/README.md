# Gallery+ Tailcat receiver

This static page is opened from the Gallery+ Share QR code. It creates a browser Tailcat receiver, sends the receiver address back to Gallery+ through the vehicle Tailcat control address in the `car` query parameter, then receives the selected Saved file.

The user-facing page intentionally does not show the Tailcat address. If automatic setup is not ready, it asks the user to close the page and scan the QR again.

Hosted URL:

```text
https://lamer0712.github.io/PolestarDashcam/tailcat/?file=name.mp4&car=tc...
```
