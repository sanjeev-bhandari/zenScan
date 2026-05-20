package com.example.zenscan.model

import android.graphics.Rect

data class DetectedRegion(
    val boundingBox: Rect,
    val label: String,
    val type: RegionType,
    val confidence: Float = 1.0f
)

enum class RegionType {
    PII_TEXT,
    FACE
}

data class TextBlockBounds(
    val text: String,
    val boundingBox: Rect
)

data class RegexMatch(
    val text: String,
    val startIndex: Int,
    val endIndex: Int
)
