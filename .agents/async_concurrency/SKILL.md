# Advanced Asynchronous Concurrency Skill

This skill documents asynchronous thread handling and coroutine execution patterns in **ZenScan**. It covers Kotlin Coroutine Dispatchers, lifecycle-bound thread safety, and concurrent parallel jobs.

---

## 1. Kotlin Coroutines & Thread Management

To prevent blocking the Android UI main thread, heavy computations and operations are isolated using Kotlin Coroutines on specific thread pools.

### The Dispatcher Decision Matrix
* **`Dispatchers.Default` (CPU Bound)**:
  * **Use Cases**: OCR detections, face contour mappings, PII pattern regex matching, Luhn algorithm card checksums, drawing redaction pixels on bitmap canvases.
  * **Why**: Uses a shared background pool scaled to the device CPU core count, preventing UI frame drops.
* **`Dispatchers.IO` (Disk/Input-Output Bound)**:
  * **Use Cases**: Reading bitmaps from URI input streams, exporting JPEG arrays, cleaning files from cache folders.
  * **Why**: Optimized to scale with blocking file system calls.
* **`Dispatchers.Main` (UI Bound)**:
  * **Use Cases**: Displaying scanning dialogs, updating the bounding box overlay, showing summary badges, throwing toasts.
  * **Why**: Coordinates interactions directly with Android's Single Thread UI toolkit.

---

## 2. Implementing Thread Swaps (`withContext`)

Use `withContext` to seamlessly offload execution blocks from the main thread, returning the output when completed:

```kotlin
// Example from PreviewActivity: Load bitmap off the main thread
private fun loadImageAndDetect(uri: Uri) {
    binding.loadingIndicator.visibility = View.VISIBLE
    lifecycleScope.launch {
        // SWAP THREAD: Shift to Dispatchers.IO to load file bytes
        val bitmap = withContext(Dispatchers.IO) {
            loadBitmapFromUri(uri)
        }
        
        // SWAP THREAD: Back on Main thread automatically
        if (bitmap == null) {
            binding.loadingIndicator.visibility = View.GONE
            Toast.makeText(this@PreviewActivity, "Failed to load", Toast.LENGTH_SHORT).show()
            return@launch
        }
        
        originalBitmap = bitmap
        binding.imagePreview.setImageBitmap(bitmap)
        
        // SWAP THREAD: Shift to Dispatchers.Default for CPU-heavy AI
        runDetection(bitmap)
    }
}
```

---

## 3. Parallel Processing (`async` / `await`)

When operations do not rely on each other's outputs, execute them concurrently using `async` to reduce user wait times:

```kotlin
suspend fun executeParallelPipelines(bitmap: Bitmap) = coroutineScope {
    val task1 = async(Dispatchers.Default) { runOcr(bitmap) }
    val task2 = async(Dispatchers.Default) { runFaceDetection(bitmap) }

    // Execute concurrently, suspend until both are resolved
    val ocrResult = task1.await()
    val faceResult = task2.await()

    collateResults(ocrResult, faceResult)
}
```

---

## 4. Asynchronous Reference Locations
* Parallel AI Engine orchestration: [DetectionPipeline.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/detection/DetectionPipeline.kt)
* Thread swaps and lifecycles: [PreviewActivity.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/PreviewActivity.kt)
