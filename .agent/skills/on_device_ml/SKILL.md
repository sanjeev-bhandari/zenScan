---
name: on_device_ml
description: Google ML Kit vision SDK configurations, parallel OCR/Face detector engines, and clean resources destruction.
---

# On-Device Machine Learning (ML Kit) Skill

This skill documents the on-device Artificial Intelligence and Machine Learning implementation guidelines in **ZenScan**. It covers configuring Google ML Kit SDKs, optimizing concurrent detection pipelines, and safely managing vision resources.

---

## 1. Google ML KitVision Configurations

ZenScan uses Google ML Kit for secure, local processing of sensitive details. It requires zero cloud network requests, maintaining 100% user privacy.

### Dependency Targets
ML Kit requires local model declarations in `app/src/main/AndroidManifest.xml` to allow the system to download OCR and Face models during installation:
```xml
<meta-data
    android:name="com.google.mlkit.vision.DEPENDENCIES"
    android:value="ocr,face" />
```

---

## 2. Text Recognition & Face Detection Engines

### A. Text Recognition Client
Wraps Google's OCR engine to identify text boundaries on visual bitmaps.

* **Key Client Class**: `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)`
* **Processing Bitmaps**: Converts standard `Bitmap` targets to ML Kit's `InputImage` container:
  ```kotlin
  val image = InputImage.fromBitmap(bitmap, 0)
  recognizer.process(image)
      .addOnSuccessListener { visionText -> /* parse results */ }
  ```

### B. Face Detection Client
Wraps Google's high-performance facial landmark and contour locator.

* **Key Client Class**: `FaceDetection.getClient(options)`
* **Performance Strategy**: Configured with `PERFORMANCE_MODE_FAST` to minimize latency, as high-fidelity landmark detection is not required to apply blurring or solid blocks over faces.
  ```kotlin
  val options = FaceDetectorOptions.Builder()
      .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
      .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
      .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
      .build()
  ```

---

## 3. The Concurrent Detection Pipeline

To reduce processing latency, OCR and face detection should run concurrently in parallel coroutines rather than sequentially.

### Implementation Pattern (from `DetectionPipeline`)
```kotlin
suspend fun detect(bitmap: Bitmap): List<DetectedRegion> = coroutineScope {
    // Run OCR and Face detection in parallel
    val textDeferred = async(Dispatchers.Default) { textClient.detect(bitmap) }
    val faceDeferred = async(Dispatchers.Default) { faceClient.detect(bitmap) }

    val textRegions = textDeferred.await()
    val faceRegions = faceDeferred.await()

    // Collate results
    textRegions + faceRegions
}
```

---

## 4. Resource Cleanup Protocol

Vision detectors are native resources (wrapping C++ libraries) and **must be closed** when the host component lifecycle finishes to avoid critical resource leaks.

* Always invoke `.close()` on both `TextRecognizer` and `FaceDetector` inside clean wrapper callbacks.
* Pipeline Reference: [DetectionPipeline.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/detection/DetectionPipeline.kt)
* Client References:
  - [TextRecognitionClient.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/detection/TextRecognitionClient.kt)
  - [FaceDetectionClient.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/detection/FaceDetectionClient.kt)
