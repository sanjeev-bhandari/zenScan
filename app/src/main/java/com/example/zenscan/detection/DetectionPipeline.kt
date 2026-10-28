package com.example.zenscan.detection

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.zenscan.model.CustomFilterRule
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegionType
import com.example.zenscan.model.TextBlockBounds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DetectionPipeline(
    textClientProvider: (() -> TextRecognitionClient)? = null,
    faceClientProvider: (() -> FaceDetectionClient)? = null
) {
    companion object {
        private const val TAG = "DetectionPipeline"
        const val FACE_LABEL = "FACE"
    }

    private var isTextClientInitialized = false
    private val textClient by lazy {
        isTextClientInitialized = true
        textClientProvider?.invoke() ?: TextRecognitionClient()
    }

    private var isFaceClientInitialized = false
    private val faceClient by lazy {
        isFaceClientInitialized = true
        faceClientProvider?.invoke() ?: FaceDetectionClient()
    }

    suspend fun detect(bitmap: Bitmap, customRules: List<CustomFilterRule> = emptyList()): List<DetectedRegion> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyList()
        }

        val textBlocks = recognizeText(bitmap)
        currentCoroutineContext().ensureActive()

        val piiRegions = detectPII(textBlocks, customRules)
        currentCoroutineContext().ensureActive()

        val faceRegions = detectFaces(bitmap)
        currentCoroutineContext().ensureActive()

        val allRegions = piiRegions + faceRegions
        return deduplicateAndMerge(allRegions)
    }

    private suspend fun recognizeText(bitmap: Bitmap): List<TextBlockBounds> =
        suspendCancellableCoroutine { continuation ->
            textClient.recognizeText(
                bitmap,
                onSuccess = { blocks -> if (continuation.isActive) continuation.resume(blocks) },
                onFailure = { if (continuation.isActive) continuation.resume(emptyList()) }
            )
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
            faceClient.detectFaces(
                bitmap,
                onSuccess = { rects ->
                    if (!continuation.isActive) return@detectFaces
                    val regions = rects.filter { !it.isEmpty && it.width() > 0 && it.height() > 0 }.map { rect ->
                        DetectedRegion(
                            boundingBox = rect,
                            label = FACE_LABEL,
                            type = RegionType.FACE
                        )
                    }
                    continuation.resume(regions)
                },
                onFailure = { if (continuation.isActive) continuation.resume(emptyList()) }
            )
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
        if (isTextClientInitialized) textClient.close()
        if (isFaceClientInitialized) faceClient.close()
    }
}

