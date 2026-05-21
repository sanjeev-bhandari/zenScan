---
name: security_privacy
description: EXIF metadata scrubbing, FileProvider sandbox configurations, and secure inter-app sharing Intent creation.
---

# Security, Sandboxing & Privacy Skill

This skill documents safety parameters, EXIF metadata stripping, local sandbox configurations, and secure Android inter-app sharing integrations in **ZenScan**.

---

## 1. EXIF Metadata Stripping Mechanics

Camera images carry deep EXIF (Exchangeable Image File Format) headers detailing exact GPS capture coordinates, phone serial identifiers, exact capture timestamps, and software parameters.

* **The Privacy Risk**: Simply coloring black boxes on screen does not wipe hidden metadata. Sharing a redacted file still leaks the original metadata parameters.
* **The ZenScan Solution**: Instead of editing the original file, ZenScan renders visual redaction layers directly into a brand new `Bitmap` array in memory. It then decompresses this in-memory bitmap into a completely fresh JPEG file using Android's native graphics compression. This native compression target inherits none of the original capture EXIF headers, effectively scrubbing GPS coordinates and device details:

```kotlin
// Example from PreviewActivity: Compression strips all original metadata
val file = File(sharedDir, "zenscan_redacted_${System.currentTimeMillis()}.jpg")
FileOutputStream(file).use { out ->
    // Native JPEG compression yields a completely metadata-clean image
    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
}
```

---

## 2. Secure Sharing Sandbox (`FileProvider`)

Android applications operate inside private sandbox directories. To share our redacted image with external applications (e.g. messaging apps, mail clients) without compromising directory security, ZenScan uses the modern `FileProvider` API.

### A. Manifest Provider Registration (from `AndroidManifest.xml`)
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="com.example.zenscan.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

### B. Shared Paths Configuration (`res/xml/file_paths.xml`)
Defines the specific sub-folders exposed to inter-app sharing. External apps can only access files placed inside these specified folders:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Expose ONLY the cache/shared_images folder via com.example.zenscan.fileprovider -->
    <cache-path name="shared_images" path="shared_images/" />
</paths>
```

---

## 3. Secure Inter-App Intent Creation

When launching the Android Share Sheet, the system URI is compiled safely using `FileProvider`, and the intent is explicitly passed temporary read-only permissions:

```kotlin
val contentUri = FileProvider.getUriForFile(
    this@PreviewActivity,
    "com.example.zenscan.fileprovider",
    sharedFile
)

val shareIntent = Intent(Intent.ACTION_SEND).apply {
    type = "image/jpeg"
    putExtra(Intent.EXTRA_STREAM, contentUri)
    
    // CRITICAL: Grant external target app permission to read our secure URI
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

startActivity(Intent.createChooser(shareIntent, "Share Redacted Image"))
```

---

## 4. Visual Reference Locations
* Sandbox provider details: [AndroidManifest.xml](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/AndroidManifest.xml)
* Metadata compression and share launcher: [PreviewActivity.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/PreviewActivity.kt)
