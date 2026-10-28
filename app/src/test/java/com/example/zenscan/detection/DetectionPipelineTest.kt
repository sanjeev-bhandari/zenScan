package com.example.zenscan.detection

import android.graphics.Rect
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionPipelineTest {

    private val pipeline = DetectionPipeline()

    private fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    @Test
    fun deduplicateAndMerge_emptyList() {
        val result = pipeline.deduplicateAndMerge(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun deduplicateAndMerge_singleRegion() {
        val region = DetectedRegion(
            boundingBox = createRect(10, 10, 100, 50),
            label = "EMAIL",
            type = RegionType.PII_TEXT
        )
        val result = pipeline.deduplicateAndMerge(listOf(region))
        assertEquals(1, result.size)
        assertEquals(region, result[0])
    }

    @Test
    fun deduplicateAndMerge_preservesAdjacentTouchingRegions() {
        // Two regions directly touching vertically (common with lines of text)
        val region1 = DetectedRegion(
            boundingBox = createRect(10, 10, 100, 50),
            label = "EMAIL",
            type = RegionType.PII_TEXT
        )
        val region2 = DetectedRegion(
            boundingBox = createRect(10, 50, 100, 90),
            label = "PHONE",
            type = RegionType.PII_TEXT
        )

        val result = pipeline.deduplicateAndMerge(listOf(region1, region2))
        assertEquals("Adjacent/touching regions must both be preserved", 2, result.size)
    }

    @Test
    fun deduplicateAndMerge_preservesDisjointRegions() {
        val region1 = DetectedRegion(
            boundingBox = createRect(10, 10, 50, 50),
            label = "EMAIL",
            type = RegionType.PII_TEXT
        )
        val region2 = DetectedRegion(
            boundingBox = createRect(100, 100, 150, 150),
            label = "CREDIT_CARD",
            type = RegionType.PII_TEXT
        )

        val result = pipeline.deduplicateAndMerge(listOf(region1, region2))
        assertEquals(2, result.size)
    }

    @Test
    fun deduplicateAndMerge_mergesSignificantlyOverlappingRegions() {
        // Region 2 is almost entirely inside Region 1
        val region1 = DetectedRegion(
            boundingBox = createRect(10, 10, 120, 60),
            label = "EMAIL",
            type = RegionType.PII_TEXT
        )
        val region2 = DetectedRegion(
            boundingBox = createRect(15, 15, 115, 55),
            label = "CUSTOM",
            type = RegionType.PII_TEXT
        )

        val result = pipeline.deduplicateAndMerge(listOf(region1, region2))
        assertEquals(1, result.size)
        // Bounding box should cover the union
        assertEquals(10, result[0].boundingBox.left)
        assertEquals(10, result[0].boundingBox.top)
        assertEquals(120, result[0].boundingBox.right)
        assertEquals(60, result[0].boundingBox.bottom)
    }

    @Test
    fun scaleRect_identityReturnsSameRect() {
        val rect = createRect(10, 20, 50, 80)
        val scaled = pipeline.scaleRect(rect, 1.0f, 1.0f, 1000, 1000)
        assertEquals(rect.left, scaled.left)
        assertEquals(rect.top, scaled.top)
        assertEquals(rect.right, scaled.right)
        assertEquals(rect.bottom, scaled.bottom)
    }

    @Test
    fun scaleRect_emptyRectReturnsImmediately() {
        val rect = createRect(0, 0, 0, 0)
        val scaled = pipeline.scaleRect(rect, 2.0f, 2.0f, 1000, 1000)
        assertTrue(scaled.right <= scaled.left || scaled.bottom <= scaled.top)
    }

    @Test
    fun scaleRect_scalesUpCoordinatesAccurately() {
        val rect = createRect(10, 20, 50, 60)
        val scaled = pipeline.scaleRect(rect, 2.0f, 3.0f, 1000, 1000)
        assertEquals(20, scaled.left)
        assertEquals(60, scaled.top)
        assertEquals(100, scaled.right)
        assertEquals(180, scaled.bottom)
    }

    @Test
    fun scaleRect_clampsToMaxDimensions() {
        val rect = createRect(80, 80, 120, 120)
        val scaled = pipeline.scaleRect(rect, 2.0f, 2.0f, 200, 200)
        assertEquals(160, scaled.left)
        assertEquals(160, scaled.top)
        assertEquals(200, scaled.right)
        assertEquals(200, scaled.bottom)
    }
}
