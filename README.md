# ZenScan

**ZenScan** is a privacy-first, on-device AI redaction app for Android. It helps you protect sensitive information before sharing images — all processing stays on your device, with zero network calls.

## Features

- **AI-Powered Smart Scan** — Automatically detects sensitive content using on-device ML Kit (OCR + face detection).
- **PII Detection** — Identifies credit card numbers (with Luhn validation), email addresses, and phone numbers.
- **Face Detection** — Detects human faces with bounding boxes.
- **Tap-to-Toggle Redaction** — Tap individual detections to toggle redaction on/off.
- **Redact All** — One-tap redaction of all detected regions.
- **Manual Brush Tool** — Freehand drawing to manually redact any area.
- **Undo Support** — Full undo history (up to 15 states) with OOM-safe bitmap management.
- **Multiple Redaction Styles** — Solid black, dark slate gray, or Gaussian pixel blur.
- **Secure Export** — Compressed JPEG export via Android Share Sheet (EXIF/metadata stripped).
- **Save to Gallery** — Saves redacted images to `Pictures/ZenScan`.
- **Redacted History** — Persistent local history with no database needed.
- **Camera Capture** — In-app camera support.
- **Dark Mode** — Full Material 3 day/night theme support.

## Privacy

No internet permission. No analytics. No network calls. All ML inference runs locally using Google ML Kit. Your images never leave your device.

## Roadmap

- [ ] **FLAG_SECURE** — Window protection on editing screens to prevent screenshots/recents leaks.
- [ ] **Encrypted Storage** — Jetpack EncryptedSharedPreferences for persistent redaction history.
- [ ] **Zero-Permission Photo Picker** — Use Android Photo Picker (`PickVisualMedia`) instead of storage permissions.
- [ ] **Perspective Correction** — Document edge-snapping / skew correction before OCR.
- [ ] **Command-Pattern Undo** — Store redaction commands (coordinates, strokes) instead of full bitmaps for memory-efficient undo/redo.
- [ ] **Structured PII Regex** — Support for Tax IDs, SSNs, driver's licenses, and passport numbers.
- [ ] **Fuzzy Proximity Matching** — Flag ambiguous digit sequences near credit card keywords (Visa, Mastercard, CC, Exp).
- [ ] **Share Target Integration** — Register as a share target so users can redact photos directly from other apps.
- [ ] **Vector Destructive Redaction** — Ensure redaction is flattened into pixel data on export, not just a visual overlay.
- [ ] **Temporary File Lifecycle** — Strict cache-only pipeline for unredacted originals, cleared on exit or export.
