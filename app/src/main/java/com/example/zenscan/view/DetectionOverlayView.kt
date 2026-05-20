package com.example.zenscan.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegionType

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var regions: List<DetectedRegion> = emptyList()
    private var bitmapWidth: Int = 0
    private var bitmapHeight: Int = 0

    // Red accents for PII (Credit card, Email, Phone)
    private val piiFillPaint = Paint().apply {
        color = Color.argb(30, 244, 63, 94) // 12% Opacity Rose-red (#F43F5E)
        style = Paint.Style.FILL
    }

    private val piiStrokePaint = Paint().apply {
        color = Color.rgb(244, 63, 94) // Rose-red (#F43F5E)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val piiLabelBgPaint = Paint().apply {
        color = Color.rgb(244, 63, 94) // Solid Rose-red
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Indigo accents for Faces
    private val faceFillPaint = Paint().apply {
        color = Color.argb(30, 99, 102, 241) // 12% Opacity Indigo (#6366F1)
        style = Paint.Style.FILL
    }

    private val faceStrokePaint = Paint().apply {
        color = Color.rgb(99, 102, 241) // Indigo (#6366F1)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val faceLabelBgPaint = Paint().apply {
        color = Color.rgb(99, 102, 241) // Solid Indigo
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Standard Label Text Paint
    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        isAntiAlias = true
        isFakeBoldText = true
    }

    fun setRegions(regions: List<DetectedRegion>, bmpWidth: Int, bmpHeight: Int) {
        this.regions = regions
        this.bitmapWidth = bmpWidth
        this.bitmapHeight = bmpHeight
        invalidate()
    }

    fun clear() {
        regions = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (regions.isEmpty() || bitmapWidth == 0 || bitmapHeight == 0) return

        val scaleX = width.toFloat() / bitmapWidth
        val scaleY = height.toFloat() / bitmapHeight
        val scale = minOf(scaleX, scaleY)
        val offsetX = (width - bitmapWidth * scale) / 2f
        val offsetY = (height - bitmapHeight * scale) / 2f

        for (region in regions) {
            val r = region.boundingBox
            val rectF = RectF(
                r.left * scale + offsetX,
                r.top * scale + offsetY,
                r.right * scale + offsetX,
                r.bottom * scale + offsetY
            )

            val isFace = region.type == RegionType.FACE

            // Choose paints
            val fillPaint = if (isFace) faceFillPaint else piiFillPaint
            val strokePaint = if (isFace) faceStrokePaint else piiStrokePaint
            val labelBgPaint = if (isFace) faceLabelBgPaint else piiLabelBgPaint

            // 1. Draw rounded box fill (16dp rounded corner radius)
            canvas.drawRoundRect(rectF, 16f, 16f, fillPaint)

            // 2. Draw rounded box stroke outline
            canvas.drawRoundRect(rectF, 16f, 16f, strokePaint)

            // 3. Draw clean label badge tab
            val rawLabel = region.label.replace("_", " ")
            val labelText = if (isFace) "FACE" else rawLabel.uppercase()
            val textWidth = labelTextPaint.measureText(labelText)
            val textHeight = labelTextPaint.textSize

            val badgePaddingX = 14f
            val badgePaddingY = 8f

            // Position badge directly above the bounding box top, but offset inward slightly or offset up
            val badgeRect = RectF(
                rectF.left,
                rectF.top - textHeight - badgePaddingY * 2f,
                rectF.left + textWidth + badgePaddingX * 2f,
                rectF.top
            )

            // If badge goes off-screen vertically, draw it inside the bounding box
            if (badgeRect.top < 0) {
                badgeRect.offsetTo(badgeRect.left, rectF.top)
            }

            // Draw badge background with 8dp rounded corner
            canvas.drawRoundRect(badgeRect, 8f, 8f, labelBgPaint)
            
            // Draw badge text centered in the badge background
            canvas.drawText(
                labelText,
                badgeRect.left + badgePaddingX,
                badgeRect.bottom - badgePaddingY - 2f,
                labelTextPaint
            )
        }
    }
}
