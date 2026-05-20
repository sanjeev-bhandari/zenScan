# Memory Management & OOM Safeguards Skill

This skill documents performance practices, RAM-safe bitmap allocations, and Out-of-Memory (OOM) guards implemented in **ZenScan**.

---

## 1. The Android Bitmap Memory Profile

A single high-resolution mobile camera capture can exceed $4000 \times 3000$ pixels. Loaded raw into Android memory under the standard `ARGB_8888` pixel configuration, this single bitmap consumes:

$$\text{Memory} = 4000 \times 3000 \times 4 \text{ bytes} \approx 48 \text{ Megabytes of Heap Space}$$

If an app retains multiple copies of these bitmaps (e.g. for an undo history stack), it can quickly exceed the device's heap allocation limits, resulting in instant `OutOfMemoryError` (OOM) crashes.

---

## 2. RAM-Safe Undo History Stack

To provide dynamic undo capabilities without memory depletion, ZenScan implements an undo depth cap and proactive recycling of unreferenced bitmaps:

### Implementation Pattern (from `PreviewActivity`)
* **Maximum Depth Cap**: The undo history is strictly capped at `15` states. If a new state exceeds this limit, the oldest state is removed.
* **Proactive Bitmap Recycling**: When discarding states or when the editor is destroyed, bitmaps must be explicitly recycled using `bitmap.recycle()` to release their native memory pixels immediately.
* **Heap Copy Safety**: Cloning bitmaps for undo states must wrap operations in `try-catch` blocks designed to capture `OutOfMemoryError` safely.

```kotlin
private fun saveStateToHistory(bitmap: Bitmap, regions: List<DetectedRegion>) {
    try {
        // Safe deep copy creation
        val bmpCopy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        historyStack.add(RedactionState(bmpCopy, regions.toList()))
        
        // Evict older states if stack size exceeds capacity limits
        if (historyStack.size > 15) {
            val evicted = historyStack.removeAt(0)
            evicted.bitmap.recycle() // Force native release
        }
    } catch (e: OutOfMemoryError) {
        Toast.makeText(this, "Memory full, unable to save undo state", Toast.LENGTH_SHORT).show()
    }
}
```

---

## 3. Dynamic Export Cache Cleanup

Exported redacted JPEG images are saved temporarily to the application's local `cacheDir` to facilitate secure inter-app sharing. If neglected, these files accumulate, consuming user storage space.

### Implementation Pattern
Every time a new export is triggered, ZenScan clears its shared cache sub-directory before saving the new target:

```kotlin
val sharedDir = File(cacheDir, "shared_images").apply { mkdirs() }

// Wipe previous exports to conserve local space
sharedDir.listFiles()?.forEach { it.delete() }

val file = File(sharedDir, "zenscan_redacted_${System.currentTimeMillis()}.jpg")
```

---

## 4. Visual Reference Locations
* Undo stack management, `onDestroy` recycles, and export caches: [PreviewActivity.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/PreviewActivity.kt)
