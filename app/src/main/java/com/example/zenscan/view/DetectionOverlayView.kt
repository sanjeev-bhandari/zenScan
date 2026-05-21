package com.example.zenscan.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
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

    // Callback variables for host Activity interaction
    var onRegionToggled: ((DetectedRegion) -> Unit)? = null
    var onBrushStrokeCompleted: ((Path, Float) -> Unit)? = null
    var isBrushMode: Boolean = false

    // Manual Brush Drawing Engine
    private val brushPath = Path()
    private val brushPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 48f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    // Touch gesture helpers
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val touchSlop = 10f

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

    // Modern styling for bypassed (unselected) regions
    private val bypassedFillPaint = Paint().apply {
        color = Color.argb(12, 100, 116, 139) // Ultra light Slate translucent fill
        style = Paint.Style.FILL
    }

    private val bypassedStrokePaint = Paint().apply {
        color = Color.rgb(100, 116, 139) // Neutral Slate outline
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
        isAntiAlias = true
    }

    private val bypassedLabelBgPaint = Paint().apply {
        color = Color.rgb(100, 116, 139) // Neutral Slate
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

    private val reuseRect = RectF()
    private val reuseBadgeRect = RectF()

    private var animationProgress = 0f
    private var animator: android.animation.ValueAnimator? = null

    fun setRegions(regions: List<DetectedRegion>, bmpWidth: Int, bmpHeight: Int) {
        this.regions = regions
        this.bitmapWidth = bmpWidth
        this.bitmapHeight = bmpHeight
        
        animator?.cancel()
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                animationProgress = valueAnimator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun clear() {
        animator?.cancel()
        regions = emptyList()
        animationProgress = 0f
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isBrushMode) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    brushPath.reset()
                    brushPath.moveTo(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    brushPath.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    brushPath.lineTo(event.x, event.y)
                    
                    // Translate screen coordinates to Bitmap coordinates using Matrix transformation
                    if (bitmapWidth > 0 && bitmapHeight > 0) {
                        val scaleX = width.toFloat() / bitmapWidth
                        val scaleY = height.toFloat() / bitmapHeight
                        val scale = minOf(scaleX, scaleY)
                        val offsetX = (width - bitmapWidth * scale) / 2f
                        val offsetY = (height - bitmapHeight * scale) / 2f

                        val matrix = Matrix()
                        matrix.postTranslate(-offsetX, -offsetY)
                        matrix.postScale(1f / scale, 1f / scale)

                        val bitmapPath = Path()
                        brushPath.transform(matrix, bitmapPath)

                        val strokeWidthOnBitmap = brushPaint.strokeWidth / scale
                        onBrushStrokeCompleted?.invoke(bitmapPath, strokeWidthOnBitmap)
                    }

                    brushPath.reset()
                    invalidate()
                    performClick()
                    return true
                }
            }
            return false
        } else {
            // Tap-to-toggle logic
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                    if (distance < touchSlop) {
                        // Clean tap gesture verified, map coordinates back to raw bitmap
                        if (bitmapWidth > 0 && bitmapHeight > 0) {
                            val scaleX = width.toFloat() / bitmapWidth
                            val scaleY = height.toFloat() / bitmapHeight
                            val scale = minOf(scaleX, scaleY)
                            val offsetX = (width - bitmapWidth * scale) / 2f
                            val offsetY = (height - bitmapHeight * scale) / 2f

                            val bitmapX = ((event.x - offsetX) / scale).toInt()
                            val bitmapY = ((event.y - offsetY) / scale).toInt()

                            // Find and toggle the region containing this touch point
                            val tappedRegion = regions.find { it.boundingBox.contains(bitmapX, bitmapY) }
                            if (tappedRegion != null) {
                                onRegionToggled?.invoke(tappedRegion)
                            }
                        }
                    }
                    performClick()
                    return true
                }
            }
            return false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw active brush path in real-time if drawing
        if (isBrushMode && !brushPath.isEmpty) {
            canvas.drawPath(brushPath, brushPaint)
        }

        if (regions.isEmpty() || bitmapWidth == 0 || bitmapHeight == 0) return

        // Dynamic fade-in animation alpha adjustments
        val currentFillAlpha = (30 * animationProgress).toInt()
        val currentBypassedFillAlpha = (12 * animationProgress).toInt()
        val currentStrokeAlpha = (255 * animationProgress).toInt()

        piiFillPaint.alpha = currentFillAlpha
        piiStrokePaint.alpha = currentStrokeAlpha
        piiLabelBgPaint.alpha = currentStrokeAlpha

        faceFillPaint.alpha = currentFillAlpha
        faceStrokePaint.alpha = currentStrokeAlpha
        faceLabelBgPaint.alpha = currentStrokeAlpha

        bypassedFillPaint.alpha = currentBypassedFillAlpha
        bypassedStrokePaint.alpha = currentStrokeAlpha
        bypassedLabelBgPaint.alpha = currentStrokeAlpha

        labelTextPaint.alpha = currentStrokeAlpha

        val scaleX = width.toFloat() / bitmapWidth
        val scaleY = height.toFloat() / bitmapHeight
        val scale = minOf(scaleX, scaleY)
        val offsetX = (width - bitmapWidth * scale) / 2f
        val offsetY = (height - bitmapHeight * scale) / 2f

        for (region in regions) {
            val r = region.boundingBox
            reuseRect.set(
                r.left * scale + offsetX,
                r.top * scale + offsetY,
                r.right * scale + offsetX,
                r.bottom * scale + offsetY
            )
            val rectF = reuseRect

            val isFace = region.type == RegionType.FACE

            // Choose paints based on selection status (isRedacted) and type
            val fillPaint: Paint
            val strokePaint: Paint
            val labelBgPaint: Paint

            if (!region.isRedacted) {
                fillPaint = bypassedFillPaint
                strokePaint = bypassedStrokePaint
                labelBgPaint = bypassedLabelBgPaint
            } else {
                fillPaint = if (isFace) faceFillPaint else piiFillPaint
                strokePaint = if (isFace) faceStrokePaint else piiStrokePaint
                labelBgPaint = if (isFace) faceLabelBgPaint else piiLabelBgPaint
            }

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
            reuseBadgeRect.set(
                rectF.left,
                rectF.top - textHeight - badgePaddingY * 2f,
                rectF.left + textWidth + badgePaddingX * 2f,
                rectF.top
            )
            val badgeRect = reuseBadgeRect

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
