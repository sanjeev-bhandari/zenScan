# ZenScan Project Plan

**ZenScan** is a privacy-first utility app designed to help users redact sensitive information from documents and photos using on-device AI. 

## Project Overview
The app aims to solve the problem of accidentally sharing sensitive data (like credit card numbers, IDs, or faces) in screenshots or photos. By performing all AI operations locally, ZenScan ensures that user data never leaves the device, providing a high level of trust and security.

## Core Features
- **Automatic PII Detection:** Finds credit cards, emails, and phone numbers using OCR and Regex.
- **Face Masking:** Automatically detects faces to allow quick blurring/redaction.
- **Metadata Stripping:** Removes EXIF data (location, device info) from images before sharing.
- **One-Tap Redaction:** Simple interactive UI to hide sensitive areas.

---

## Implementation Phases

### Phase 1: Project Setup & Infrastructure (Completed)
- **Environment:** Configured `build.gradle.kts` with Google ML Kit (Text & Face) and Coil dependencies.
- **Permissions:** Implemented modern Android permission handling (`READ_MEDIA_IMAGES`, `CAMERA`).
- **Dashboard:** Created a clean landing page with "Scan Gallery" and "Scan Document" actions.
- **Image Picking:** Integrated the modern Photo Picker API for secure file access.

### Phase 2: The "Smart Scan" Engine (AI Detection)
- **ML Kit Integration:** Setting up `TextRecognition` and `FaceDetection` clients.
- **Pattern Matching:** Implementing Regex-based filters for:
    - Credit Cards (Luhn algorithm check where possible).
    - Email addresses.
    - Phone numbers.
- **Detection Pipeline:** Creating a background service/manager to process Bitmaps locally.

### Phase 3: Interactive Redaction UI
- **Preview Screen:** A dedicated Activity/Fragment to show the selected image.
- **Overlay Layer:** Drawing semi-transparent boxes over detected sensitive areas.
- **User Interaction:** 
    - Tap to toggle redaction (masking).
    - Manual brush tool for custom areas.
- **Visuals:** Implementing solid black blocks or blur effects for redacted regions.

### Phase 4: Secure Export & Sharing
- **Final Rendering:** Generating a new Bitmap with all redactions applied.
- **Privacy Wipe:** Custom logic to strip all EXIF metadata from the final JPEG/PNG.
- **Share Integration:** Using `FileProvider` and the Android Share Sheet for secure export to other apps.

---

## Tech Stack
- **Language:** Kotlin
- **AI Libraries:** Google ML Kit
- **Image Loading:** Coil
- **UI:** XML ViewBinding / Material Design 3
- **Processing:** Android Graphics (Canvas, Bitmap)
