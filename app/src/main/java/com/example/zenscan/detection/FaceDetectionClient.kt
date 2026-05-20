package com.example.zenscan.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceDetectionClient {

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        detector = FaceDetection.getClient(options)
    }

    fun detectFaces(bitmap: Bitmap, onSuccess: (List<Rect>) -> Unit, onFailure: (Exception) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)

        detector.process(image)
            .addOnSuccessListener { faces ->
                val rects = faces.mapNotNull { face ->
                    face.boundingBox
                }
                onSuccess(rects)
            }
            .addOnFailureListener { e ->
                onFailure(e)
            }
    }

    fun close() {
        detector.close()
    }
}
