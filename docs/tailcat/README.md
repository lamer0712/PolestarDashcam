# Gallery+ Tailcat receiver

This static page is opened from the Gallery+ Share QR code. It creates a browser Tailcat receiver, sends the receiver address back to Gallery+ through the vehicle Tailcat control address in the `car` query parameter, then receives the selected Saved file.

The user-facing page intentionally does not show the Tailcat address. If automatic setup is not ready, it asks the user to close the page and scan the QR again.

Incoming bytes are checkpointed to the browser's origin-private file system every 4 MiB. The receiver keeps a persistent Tailcat key and re-registers the same address when the page becomes visible again. If iOS suspends the browser while the screen is off or another app is open, transfer pauses and resumes from the last committed checkpoint instead of restarting at zero. The vehicle only reports completion after the browser has committed the full file and returned an application-level acknowledgement.

Browser background execution is controlled by the phone OS. The page requests a screen wake lock while visible, but it cannot force iOS to keep JavaScript running after Safari is suspended. Resumable checkpoints cover that case when the user returns to the same page.

Hosted URL:

```text
https://lamer0712.github.io/PolestarDashcam/tailcat/?file=name.mp4&car=tc...
```
