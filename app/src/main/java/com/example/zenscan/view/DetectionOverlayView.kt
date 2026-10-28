package com.example.zenscan.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withMatrix
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

    // Image bitmap to draw and its matrix state
    var bitmap: Bitmap? = null
        set(value) {
            val dimensionsChanged = field == null || value == null ||
                    field?.width != value.width || field?.height != value.height
            field = value
            if (value != null) {
                bitmapWidth = value.width
                bitmapHeight = value.height
            }
            if (dimensionsChanged) {
                resetMatrix()
            }
            invalidate()
        }

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private var minScale = 1f
    private var maxScale = 8f
    private val matrixValues = FloatArray(9)

    // Manual Brush Drawing Engine
    private val brushPath = Path()
    private var lastBrushX = 0f
    private var lastBrushY = 0f
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

    private var isDragging = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isZoomingOrPanning = false

    private val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val currentScale = getScale()

            var targetScale = currentScale * scaleFactor
            if (targetScale < minScale) {
                targetScale = minScale
            } else if (targetScale > maxScale) {
                targetScale = maxScale
            }

            val factor = targetScale / currentScale
            imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)

            constrainTranslation()
            updateInverseMatrix()
            invalidate()
            return true
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, scaleGestureListener)

    // Semantic crimson accents for PII (Credit card, Email, Phone, SSN)
    private val piiFillPaint = Paint().apply {
        color = Color.argb(30, 217, 48, 37) // #D93025
        style = Paint.Style.FILL
    }

    private val piiStrokePaint = Paint().apply {
        color = Color.rgb(217, 48, 37) // #D93025
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val piiLabelBgPaint = Paint().apply {
        color = Color.rgb(217, 48, 37) // #D93025
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Cobalt accents for Faces
    private val faceFillPaint = Paint().apply {
        color = Color.argb(30, 27, 110, 243) // #1B6EF3
        style = Paint.Style.FILL
    }

    private val faceStrokePaint = Paint().apply {
        color = Color.rgb(27, 110, 243) // #1B6EF3
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val faceLabelBgPaint = Paint().apply {
        color = Color.rgb(27, 110, 243) // #1B6EF3
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Neutral styling for bypassed (unselected) regions
    private val bypassedFillPaint = Paint().apply {
        color = Color.argb(14, 110, 117, 124)
        style = Paint.Style.FILL
    }

    private val bypassedStrokePaint = Paint().apply {
        color = Color.rgb(110, 117, 124)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val bypassedLabelBgPaint = Paint().apply {
        color = Color.rgb(110, 117, 124)
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
    private var cachedDashScale = -1f
    private var cachedDashPathEffect: DashPathEffect? = null

    private var animationProgress = 1f
    private var animator: android.animation.ValueAnimator? = null

    private fun formatRegionBadgeLabel(region: DetectedRegion): String {
        val isFace = region.type == RegionType.FACE
        val rawLabel = region.label.replace("_", " ")
        val baseLabel = if (isFace) com.example.zenscan.detection.DetectionPipeline.FACE_LABEL else rawLabel.uppercase()
        return if (region.isRedacted) "✓ $baseLabel" else "○ $baseLabel"
    }

    private fun getScale(): Float {
        imageMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    fun resetMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return

        val scaleX = width.toFloat() / bmp.width
        val scaleY = height.toFloat() / bmp.height
        val scale = minOf(scaleX, scaleY)
        val offsetX = (width - bmp.width * scale) / 2f
        val offsetY = (height - bmp.height * scale) / 2f

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(offsetX, offsetY)

        minScale = scale
        maxScale = scale * 8f

        updateInverseMatrix()
    }

    private fun constrainTranslation() {
        val bmp = bitmap ?: return

        imageMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scale = matrixValues[Matrix.MSCALE_X]

        val widthLimit = bmp.width * scale
        val heightLimit = bmp.height * scale

        var newTransX = transX
        var newTransY = transY

        if (widthLimit <= width) {
            newTransX = (width - widthLimit) / 2f
        } else {
            val minX = width - widthLimit
            val maxX = 0f
            newTransX = transX.coerceIn(minX, maxX)
        }

        if (heightLimit <= height) {
            newTransY = (height - heightLimit) / 2f
        } else {
            val minY = height - heightLimit
            val maxY = 0f
            newTransY = transY.coerceIn(minY, maxY)
        }

        matrixValues[Matrix.MTRANS_X] = newTransX
        matrixValues[Matrix.MTRANS_Y] = newTransY
        imageMatrix.setValues(matrixValues)
    }

    private fun updateInverseMatrix() {
        imageMatrix.invert(inverseMatrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    fun setRegions(regions: List<DetectedRegion>, bmpWidth: Int, bmpHeight: Int, animate: Boolean = false) {
        val countChanged = this.regions.size != regions.size
        this.regions = regions
        this.bitmapWidth = bmpWidth
        this.bitmapHeight = bmpHeight

        if (animate || countChanged) {
            animator?.cancel()
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { valueAnimator ->
                    animationProgress = valueAnimator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animationProgress = 1f
            invalidate()
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
        scaleDetector.onTouchEvent(event)

        val action = event.actionMasked
        val pointerCount = event.pointerCount

        // If we transition to multiple fingers, abort any active brush drawing
        if (pointerCount > 1) {
            if (!isZoomingOrPanning) {
                isZoomingOrPanning = true
                brushPath.reset()
                invalidate()
            }
        }

        if (pointerCount > 1) {
            isDragging = false
            if (action == MotionEvent.ACTION_MOVE) {
                var sumX = 0f
                var sumY = 0f
                for (i in 0 until pointerCount) {
                    sumX += event.getX(i)
                    sumY += event.getY(i)
                }
                val avgX = sumX / pointerCount
                val avgY = sumY / pointerCount

                if (lastPanX != 0f || lastPanY != 0f) {
                    val dx = avgX - lastPanX
                    val dy = avgY - lastPanY
                    imageMatrix.postTranslate(dx, dy)
                    constrainTranslation()
                    updateInverseMatrix()
                    invalidate()
                }
                lastPanX = avgX
                lastPanY = avgY
            } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                var sumX = 0f
                var sumY = 0f
                for (i in 0 until pointerCount) {
                    sumX += event.getX(i)
                    sumY += event.getY(i)
                }
                lastPanX = sumX / pointerCount
                lastPanY = sumY / pointerCount
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                val upIndex = event.actionIndex
                var sumX = 0f
                var sumY = 0f
                var count = 0
                for (i in 0 until pointerCount) {
                    if (i != upIndex) {
                        sumX += event.getX(i)
                        sumY += event.getY(i)
                        count++
                    }
                }
                if (count > 0) {
                    lastPanX = sumX / count
                    lastPanY = sumY / count
                }
            }
            return true
        }

        val pts = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(pts)
        val bitmapX = pts[0]
        val bitmapY = pts[1]

        if (isBrushMode) {
            if (isZoomingOrPanning) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    isZoomingOrPanning = false
                    performClick()
                }
                return true
            }

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    brushPath.reset()
                    brushPath.moveTo(bitmapX, bitmapY)
                    lastBrushX = bitmapX
                    lastBrushY = bitmapY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val midX = (lastBrushX + bitmapX) / 2f
                    val midY = (lastBrushY + bitmapY) / 2f
                    brushPath.quadTo(lastBrushX, lastBrushY, midX, midY)
                    lastBrushX = bitmapX
                    lastBrushY = bitmapY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    brushPath.lineTo(bitmapX, bitmapY)
                    val bitmapPath = Path(brushPath)
                    val strokeWidthOnBitmap = 48f / getScale()
                    onBrushStrokeCompleted?.invoke(bitmapPath, strokeWidthOnBitmap)
                    brushPath.reset()
                    invalidate()
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    brushPath.reset()
                    invalidate()
                    return true
                }
            }
            return false
        } else {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y

                    lastPanX = event.x
                    lastPanY = event.y

                    isDragging = getScale() > minScale
                    isZoomingOrPanning = false
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val upIndex = event.actionIndex
                    val remainingIndex = if (upIndex == 0) 1 else 0
                    lastPanX = event.getX(remainingIndex)
                    lastPanY = event.getY(remainingIndex)
                    isDragging = getScale() > minScale
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isZoomingOrPanning || isDragging) {
                        val dx = event.x - lastPanX
                        val dy = event.y - lastPanY
                        imageMatrix.postTranslate(dx, dy)
                        constrainTranslation()
                        updateInverseMatrix()
                        invalidate()
                    }
                    lastPanX = event.x
                    lastPanY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isZoomingOrPanning) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (distance < touchSlop) {
                            val currentScale = getScale()
                            val slopOnBitmap = 20f / currentScale

                            // Find all regions whose bounding box or badge contains the tapped coordinates
                            val candidates = regions.filter { region ->
                                val r = region.boundingBox
                                val inBox = bitmapX >= (r.left - slopOnBitmap) &&
                                        bitmapX <= (r.right + slopOnBitmap) &&
                                        bitmapY >= (r.top - slopOnBitmap) &&
                                        bitmapY <= (r.bottom + slopOnBitmap)

                                val badgeHeight = (26f + 16f) / currentScale
                                val badgeLabel = formatRegionBadgeLabel(region)
                                val badgeWidth = ( badgeLabel.length * 16f + 28f) / currentScale
                                val inBadge = bitmapX >= (r.left - slopOnBitmap) &&
                                        bitmapX <= (r.left + badgeWidth + slopOnBitmap) &&
                                        bitmapY >= (r.top - badgeHeight - slopOnBitmap) &&
                                        bitmapY <= (r.top + slopOnBitmap)

                                inBox || inBadge
                            }

                            if (candidates.isNotEmpty()) {
                                // Select the most specific (smallest area) region
                                val tapped = candidates.minByOrNull {
                                    it.boundingBox.width() * it.boundingBox.height()
                                }
                                if (tapped != null) {
                                    onRegionToggled?.invoke(tapped)
                                }
                            }
                        }
                    }
                    isDragging = false
                    isZoomingOrPanning = false
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    isZoomingOrPanning = false
                    return true
                }
            }
            return false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bmp = bitmap ?: return

        canvas.withMatrix(imageMatrix) {
            drawBitmap(bmp, 0f, 0f, null)

            if (isBrushMode && !brushPath.isEmpty) {
                val currentScale = getScale()
                brushPaint.strokeWidth = 48f / currentScale
                drawPath(brushPath, brushPaint)
            }

            if (regions.isNotEmpty()) {
                val currentScale = getScale()

                piiStrokePaint.strokeWidth = 4f / currentScale
                faceStrokePaint.strokeWidth = 4f / currentScale
                bypassedStrokePaint.strokeWidth = 3f / currentScale

                if (cachedDashScale != currentScale || cachedDashPathEffect == null) {
                    cachedDashScale = currentScale
                    cachedDashPathEffect = DashPathEffect(
                        floatArrayOf(12f / currentScale, 12f / currentScale),
                        0f
                    )
                }
                bypassedStrokePaint.pathEffect = cachedDashPathEffect

                labelTextPaint.textSize = 26f / currentScale

                val badgePaddingX = 14f / currentScale
                val badgePaddingY = 8f / currentScale
                val badgeCornerRadius = 8f / currentScale
                val boxCornerRadius = 16f / currentScale

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

                for (region in regions) {
                    val r = region.boundingBox
                    reuseRect.set(
                        r.left.toFloat(),
                        r.top.toFloat(),
                        r.right.toFloat(),
                        r.bottom.toFloat()
                    )
                    val rectF = reuseRect
                    val isFace = region.type == RegionType.FACE

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

                    drawRoundRect(rectF, boxCornerRadius, boxCornerRadius, fillPaint)
                    drawRoundRect(rectF, boxCornerRadius, boxCornerRadius, strokePaint)

                    val labelText = formatRegionBadgeLabel(region)
                    val textWidth = labelTextPaint.measureText(labelText)
                    val textHeight = labelTextPaint.textSize

                    reuseBadgeRect.set(
                        rectF.left,
                        rectF.top - textHeight - badgePaddingY * 2f,
                        rectF.left + textWidth + badgePaddingX * 2f,
                        rectF.top
                    )
                    val badgeRect = reuseBadgeRect

                    if (badgeRect.top < 0f) {
                        badgeRect.offsetTo(badgeRect.left, rectF.top)
                    }

                    drawRoundRect(badgeRect, badgeCornerRadius, badgeCornerRadius, labelBgPaint)

                    drawText(
                        labelText,
                        badgeRect.left + badgePaddingX,
                        badgeRect.bottom - badgePaddingY - (2f / currentScale),
                        labelTextPaint
                    )
                }
            }
        }
    }
}
