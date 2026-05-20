# High-Performance Graphics & Custom Canvas Skill

This skill documents custom view rendering, canvas painting techniques, and layout-to-bitmap scaling math used in the **ZenScan** editor.

---

## 1. Custom View Lifecycle & `onDraw`

To support high-performance overlay rendering without inflating heavy view hierarchies for every bounding box, ZenScan uses a single custom view: `DetectionOverlayView`.

* **View Setup**: Inherits directly from `android.view.View` and uses the `@JvmOverloads` constructor.
* **Invalidation Loop**: When new detection regions are scanned, the activity calls `.setRegions(regions, width, height)`. This stores the local state and calls `invalidate()`, which schedules a redraw, invoking `onDraw(canvas)` on the UI thread.

---

## 2. Bounding Box Scaling Mathematics

ML Kit returns coordinates relative to the raw, uncompressed `Bitmap` dimensions. However, the bitmap is scaled to fit inside the visual boundary of the device's `ImageView`. We must perform coordinates translation so bounding boxes align perfectly with visual pixels.

### The Scaling Translation Formulas
```kotlin
// Calculate scale factors for both horizontal and vertical axes
val scaleX = width.toFloat() / bitmapWidth
val scaleY = height.toFloat() / bitmapHeight

// Capture the aspect fit scale ratio (maintaining uniform bounds)
val scale = minOf(scaleX, scaleY)

// Calculate padding offsets if the image has letterboxes/pillarboxes
val offsetX = (width - bitmapWidth * scale) / 2f
val offsetY = (height - bitmapHeight * scale) / 2f

// Map original Bitmap bounds (r) to display Screen bounds (rectF)
val rectF = RectF(
    r.left * scale + offsetX,
    r.top * scale + offsetY,
    r.right * scale + offsetX,
    r.bottom * scale + offsetY
)
```

---

## 3. Painting Configurations

We use specific hex values and alpha levels to provide a highly polished, premium feeling:
* **PII Highlighting (Rose-red)**:
  * Fill color: `#F43F5E` at 12% opacity (`Color.argb(30, 244, 63, 94)`).
  * Stroke outline: `#F43F5E` solid (`Color.rgb(244, 63, 94)`), stroke width of `4f`, anti-aliasing active.
* **Face Highlighting (Indigo)**:
  * Fill color: `#6366F1` at 12% opacity (`Color.argb(30, 99, 102, 241)`).
  * Stroke outline: `#6366F1` solid (`Color.rgb(99, 102, 241)`), stroke width of `4f`, anti-aliasing active.

---

## 4. Dynamic Badge Layout Translation

Each bounding box includes a tab badge centered above it. The layout system must dynamically shift the badge inside the box if the box resides at the absolute top of the screen to prevent clipping:

```kotlin
// Position badge directly above the bounding box
val badgeRect = RectF(
    rectF.left,
    rectF.top - textHeight - badgePaddingY * 2f,
    rectF.left + textWidth + badgePaddingX * 2f,
    rectF.top
)

// Bounds check: If badge is clipped above the top edge (y < 0), draw it inside the box top
if (badgeRect.top < 0) {
    badgeRect.offsetTo(badgeRect.left, rectF.top)
}
```

---

## 5. Visual Reference Locations
* Custom Overlay Canvas: [DetectionOverlayView.kt](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/java/com/example/zenscan/view/DetectionOverlayView.kt)
* Activity Layout Container: [activity_preview.xml](file:///home/sanjeev/AndroidStudioProjects/Realsanjeev/app/src/main/res/layout/activity_preview.xml)
