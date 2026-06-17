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

class DetectionPipeline {
    companion object {
        private const val TAG = "DetectionPipeline"
        const val FACE_LABEL = "FACE"
    }

    private val textClient = TextRecognitionClient()
    private val faceClient = FaceDetectionClient()

    suspend fun detect(bitmap: Bitmap, customRules: List<CustomFilterRule> = emptyList()): List<DetectedRegion> {
        Log.d(TAG, "detect() started, bitmap=${bitmap.width}x${bitmap.height}")
        val textBlocks = recognizeText(bitmap)
        Log.d(TAG, "recognizeText() returned ${textBlocks.size} blocks")
        currentCoroutineContext().ensureActive()
        val piiRegions = detectPII(textBlocks, customRules)
        Log.d(TAG, "detectPII() found ${piiRegions.size} regions")
        currentCoroutineContext().ensureActive()
        val faceRegions = detectFaces(bitmap)
        Log.d(TAG, "detectFaces() found ${faceRegions.size} regions")
        currentCoroutineContext().ensureActive()
        val allRegions = piiRegions + faceRegions
        val result = deduplicate(allRegions)
        Log.d(TAG, "detect() complete, ${result.size} regions after dedup")
        return result
    }

    private suspend fun recognizeText(bitmap: Bitmap): List<TextBlockBounds> =
        suspendCancellableCoroutine { continuation ->
            textClient.recognizeText(
                bitmap,
                onSuccess = { blocks -> if (continuation.isActive) continuation.resume(blocks) },
                onFailure = { e -> if (continuation.isActive) continuation.resume(emptyList()) }
            )
        }

    private fun detectPII(textBlocks: List<TextBlockBounds>, customRules: List<CustomFilterRule>): List<DetectedRegion> {
        val builder = StringBuilder()
        val blockIndex = mutableListOf<Pair<Int, TextBlockBounds>>()
        for (block in textBlocks) {
            val start = builder.length
            builder.append(block.text)
            val end = builder.length
            for (i in start until end) {
                blockIndex.add(i to block)
            }
            builder.append(' ')
            blockIndex.add(builder.length - 1 to block)
        }
        val fullText = builder.toString()

        val matches = PiiPatternMatcher.findPII(fullText, customRules)
        if (matches.isEmpty()) return emptyList()

        return matches.mapNotNull { (match, label) ->
            val matchedBlocks = (match.startIndex until match.endIndex)
                .mapNotNull { i -> blockIndex.firstOrNull { it.first == i }?.second }
                .distinct()

            if (matchedBlocks.isEmpty()) return@mapNotNull null

            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE
            for (block in matchedBlocks) {
                val r = block.boundingBox
                left = minOf(left, r.left)
                top = minOf(top, r.top)
                right = maxOf(right, r.right)
                bottom = maxOf(bottom, r.bottom)
            }

            DetectedRegion(
                boundingBox = Rect(left, top, right, bottom),
                label = label,
                type = RegionType.PII_TEXT
            )
        }
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<DetectedRegion> =
        suspendCancellableCoroutine { continuation ->
            faceClient.detectFaces(
                bitmap,
                onSuccess = { rects ->
                    if (!continuation.isActive) return@detectFaces
                    val regions = rects.map { rect ->
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

    private fun deduplicate(regions: List<DetectedRegion>): List<DetectedRegion> {
        if (regions.size <= 1) return regions
        val result = mutableListOf<DetectedRegion>()
        for (region in regions) {
            val overlap = result.any { existing ->
                val overlapLeft = maxOf(existing.boundingBox.left, region.boundingBox.left)
                val overlapTop = maxOf(existing.boundingBox.top, region.boundingBox.top)
                val overlapRight = minOf(existing.boundingBox.right, region.boundingBox.right)
                val overlapBottom = minOf(existing.boundingBox.bottom, region.boundingBox.bottom)
                overlapLeft < overlapRight && overlapTop < overlapBottom
            }
            if (!overlap) {
                result.add(region)
            }
        }
        return result
    }

    fun close() {
        textClient.close()
        faceClient.close()
    }
}
