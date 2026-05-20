package com.example.zenscan.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegionType
import com.example.zenscan.model.TextBlockBounds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DetectionPipeline {

    private val textClient = TextRecognitionClient()
    private val faceClient = FaceDetectionClient()

    suspend fun detect(bitmap: Bitmap): List<DetectedRegion> {
        val textBlocks = recognizeText(bitmap)
        val piiRegions = detectPII(textBlocks)
        val faceRegions = detectFaces(bitmap)
        val allRegions = piiRegions + faceRegions
        return deduplicate(allRegions)
    }

    private suspend fun recognizeText(bitmap: Bitmap): List<TextBlockBounds> =
        suspendCancellableCoroutine { continuation ->
            textClient.recognizeText(
                bitmap,
                onSuccess = { blocks -> continuation.resume(blocks) },
                onFailure = { e -> continuation.resume(emptyList()) }
            )
        }

    private fun detectPII(textBlocks: List<TextBlockBounds>): List<DetectedRegion> {
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

        val matches = PiiPatternMatcher.findPII(fullText)
        if (matches.isEmpty()) return emptyList()

        return matches.map { (match, label) ->
            val matchedBlocks = (match.startIndex until match.endIndex)
                .mapNotNull { i -> blockIndex.firstOrNull { it.first == i }?.second }
                .distinct()

            val combinedRect = if (matchedBlocks.isEmpty()) {
                Rect(0, 0, 0, 0)
            } else {
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
                Rect(left, top, right, bottom)
            }

            DetectedRegion(
                boundingBox = combinedRect,
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
                    val regions = rects.map { rect ->
                        DetectedRegion(
                            boundingBox = rect,
                            label = "FACE",
                            type = RegionType.FACE
                        )
                    }
                    continuation.resume(regions)
                },
                onFailure = { continuation.resume(emptyList()) }
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
