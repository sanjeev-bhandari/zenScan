package com.example.zenscan.detection

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.zenscan.model.CustomFilterRule
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegionType
import com.example.zenscan.model.TextBlockBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class DetectionPipeline {
    companion object {
        const val FACE_LABEL = "FACE"
        const val MAX_INFERENCE_DIMENSION = 1280
    }

    private var isTextRecognizerInitialized = false
    private val textRecognizer by lazy {
        isTextRecognizerInitialized = true
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var isFaceDetectorInitialized = false
    private val faceDetector by lazy {
        isFaceDetectorInitialized = true
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    suspend fun detect(bitmap: Bitmap, customRules: List<CustomFilterRule> = emptyList()): List<DetectedRegion> = coroutineScope {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@coroutineScope emptyList()
        }

        val maxDim = maxOf(bitmap.width, bitmap.height)
        val needsDownscale = maxDim > MAX_INFERENCE_DIMENSION

        val (inferenceBitmap, scaleX, scaleY) = if (needsDownscale) {
            val scale = MAX_INFERENCE_DIMENSION.toFloat() / maxDim
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            Triple(scaled, bitmap.width.toFloat() / targetW, bitmap.height.toFloat() / targetH)
        } else {
            Triple(bitmap, 1f, 1f)
        }

        try {
            // Run text recognition and face detection concurrently on Dispatchers.Default
            val textDeferred = async(Dispatchers.Default) {
                recognizeText(inferenceBitmap)
            }
            val faceDeferred = async(Dispatchers.Default) {
                detectFaces(inferenceBitmap)
            }

            val rawTextBlocks = textDeferred.await()
            ensureActive()

            // Scale text bounding boxes back to original bitmap dimensions
            val textBlocks = rawTextBlocks.map { block ->
                block.copy(boundingBox = scaleRect(block.boundingBox, scaleX, scaleY, bitmap.width, bitmap.height))
            }

            val piiRegions = detectPII(textBlocks, customRules)
            ensureActive()

            val rawFaceRegions = faceDeferred.await()
            ensureActive()

            val faceRegions = rawFaceRegions.map { region ->
                region.copy(boundingBox = scaleRect(region.boundingBox, scaleX, scaleY, bitmap.width, bitmap.height))
            }

            val allRegions = piiRegions + faceRegions
            deduplicateAndMerge(allRegions)
        } finally {
            if (inferenceBitmap !== bitmap && !inferenceBitmap.isRecycled) {
                inferenceBitmap.recycle()
            }
        }
    }

    internal fun scaleRect(rect: Rect, scaleX: Float, scaleY: Float, maxWidth: Int, maxHeight: Int): Rect {
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if ((scaleX == 1f && scaleY == 1f) || width <= 0 || height <= 0) return rect
        val left = (rect.left * scaleX).toInt().coerceIn(0, maxWidth)
        val top = (rect.top * scaleY).toInt().coerceIn(0, maxHeight)
        val right = maxOf(left + 1, (rect.right * scaleX).toInt()).coerceIn(left, maxWidth)
        val bottom = maxOf(top + 1, (rect.bottom * scaleY).toInt()).coerceIn(top, maxHeight)
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): List<TextBlockBounds> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val blocks = visionText.textBlocks.flatMap { block ->
                        block.lines.flatMap { line ->
                            line.elements.map { element ->
                                TextBlockBounds(
                                    text = element.text,
                                    boundingBox = element.boundingBox ?: Rect(0, 0, 0, 0)
                                )
                            }
                        }
                    }
                    continuation.resume(blocks)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }

    private data class IndexedBlock(
        val block: TextBlockBounds,
        val start: Int,
        val end: Int
    )

    private fun detectPII(textBlocks: List<TextBlockBounds>, customRules: List<CustomFilterRule>): List<DetectedRegion> {
        if (textBlocks.isEmpty()) return emptyList()

        val builder = StringBuilder()
        val indexedBlocks = ArrayList<IndexedBlock>(textBlocks.size)

        for (block in textBlocks) {
            val start = builder.length
            builder.append(block.text)
            val end = builder.length
            indexedBlocks.add(IndexedBlock(block, start, end))
            builder.append(' ')
        }
        val fullText = builder.toString()

        val matches = PiiPatternMatcher.findPII(fullText, customRules)
        if (matches.isEmpty()) return emptyList()

        val detectedRegions = mutableListOf<DetectedRegion>()

        for ((match, label) in matches) {
            val matchStart = match.startIndex
            val matchEnd = match.endIndex

            // Find all blocks that intersect this match range: [start, end)
            val matchedBlocks = indexedBlocks.filter { it.start < matchEnd && it.end > matchStart }
            if (matchedBlocks.isEmpty()) continue

            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE
            var validBlockFound = false

            for (indexed in matchedBlocks) {
                val box = indexed.block.boundingBox
                if (box.isEmpty || box.width() <= 0 || box.height() <= 0) continue
                left = minOf(left, box.left)
                top = minOf(top, box.top)
                right = maxOf(right, box.right)
                bottom = maxOf(bottom, box.bottom)
                validBlockFound = true
            }

            if (validBlockFound && right > left && bottom > top) {
                detectedRegions.add(
                    DetectedRegion(
                        boundingBox = Rect(left, top, right, bottom),
                        label = label,
                        type = RegionType.PII_TEXT
                    )
                )
            }
        }

        return detectedRegions
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<DetectedRegion> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val regions = faces.mapNotNull { face ->
                        face.boundingBox?.takeIf { !it.isEmpty && it.width() > 0 && it.height() > 0 }?.let { rect ->
                            DetectedRegion(
                                boundingBox = rect,
                                label = FACE_LABEL,
                                type = RegionType.FACE
                            )
                        }
                    }
                    continuation.resume(regions)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }

    /**
     * Deduplicates and merges overlapping regions safely.
     * Unlike naive deduplication, this does NOT discard adjacent or lightly touching regions.
     * If two regions have a high overlap (IoU >= 0.5) or one is mostly contained inside another (>= 75%),
     * they are merged into their union bounding box to ensure NO sensitive data is exposed.
     */
    fun deduplicateAndMerge(regions: List<DetectedRegion>): List<DetectedRegion> {
        if (regions.size <= 1) return regions

        val workingList = regions.toMutableList()
        val result = mutableListOf<DetectedRegion>()

        while (workingList.isNotEmpty()) {
            var current = workingList.removeAt(0)
            var mergedSomething: Boolean
            do {
                mergedSomething = false
                val iterator = workingList.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (shouldMerge(current, candidate)) {
                        // Merge current and candidate into a bounding box covering both
                        val unionBox = Rect().apply {
                            left = minOf(current.boundingBox.left, candidate.boundingBox.left)
                            top = minOf(current.boundingBox.top, candidate.boundingBox.top)
                            right = maxOf(current.boundingBox.right, candidate.boundingBox.right)
                            bottom = maxOf(current.boundingBox.bottom, candidate.boundingBox.bottom)
                        }
                        // If one is Face, prefer Face type, otherwise PII
                        val mergedType = if (current.type == RegionType.FACE || candidate.type == RegionType.FACE) {
                            RegionType.FACE
                        } else {
                            RegionType.PII_TEXT
                        }
                        val mergedLabel = if (current.label == candidate.label) current.label else "${current.label}/${candidate.label}"
                        current = current.copy(
                            boundingBox = unionBox,
                            type = mergedType,
                            label = mergedLabel
                        )
                        iterator.remove()
                        mergedSomething = true
                    }
                }
            } while (mergedSomething)

            result.add(current)
        }

        return result
    }

    private fun shouldMerge(a: DetectedRegion, b: DetectedRegion): Boolean {
        val rA = a.boundingBox
        val rB = b.boundingBox

        val overlapLeft = maxOf(rA.left, rB.left)
        val overlapTop = maxOf(rA.top, rB.top)
        val overlapRight = minOf(rA.right, rB.right)
        val overlapBottom = minOf(rA.bottom, rB.bottom)

        if (overlapLeft >= overlapRight || overlapTop >= overlapBottom) {
            return false // No intersection at all
        }

        val intersectionArea = (overlapRight - overlapLeft).toLong() * (overlapBottom - overlapTop).toLong()
        val widthA = (rA.right - rA.left).toLong()
        val heightA = (rA.bottom - rA.top).toLong()
        val widthB = (rB.right - rB.left).toLong()
        val heightB = (rB.bottom - rB.top).toLong()

        val areaA = widthA * heightA
        val areaB = widthB * heightB

        if (areaA <= 0L || areaB <= 0L) return false

        val unionArea = areaA + areaB - intersectionArea
        val iou = intersectionArea.toDouble() / unionArea.toDouble()

        // High Intersection-over-Union -> definitely same region
        if (iou >= 0.45) return true

        // One region is mostly contained inside the other (e.g. credit card word inside full line, or face)
        val containmentA = intersectionArea.toDouble() / areaA.toDouble()
        val containmentB = intersectionArea.toDouble() / areaB.toDouble()
        return containmentA >= 0.70 || containmentB >= 0.70
    }

    fun close() {
        if (isTextRecognizerInitialized) textRecognizer.close()
        if (isFaceDetectorInitialized) faceDetector.close()
    }
}

