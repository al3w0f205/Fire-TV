package com.fireairplay.receiver.ui

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * True Liquid Glass panel — custom-drawn frosted glass with real visual effects.
 *
 * This view renders the glass effect entirely in [onDraw] using Canvas operations:
 *
 * 1. **Frosted backdrop**: A heavily blurred + tinted copy of the album art,
 *    clipped to a rounded rectangle, giving the "see-through frosted glass" look.
 *
 * 2. **Glass body tint**: Semi-transparent white overlay that gives the glass
 *    its milky, translucent quality.
 *
 * 3. **Refraction gradient**: Top-to-bottom gradient that simulates light bending
 *    through curved glass (brighter at top, fading to transparent).
 *
 * 4. **Horizontal sheen**: Left-to-right subtle gradient simulating a light
 *    source reflecting off the glass surface.
 *
 * 5. **Specular rim light**: Bright white stroked border with varying opacity
 *    (brighter at top) that simulates light catching the edges of the glass.
 *
 * 6. **Inner glow**: Soft inner shadow/glow near the top edge.
 *
 * All layers are clipped to the same rounded rectangle path for consistency.
 */
class GlassPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Rounded rectangle path (reused across all layers) ──
    private val glassPath = Path()
    private val glassRectF = RectF()
    private val cornerRadius = 28f * resources.displayMetrics.density

    // ── Layer 1: Frosted backdrop ──
    private var blurredBitmap: Bitmap? = null
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 130 // Visible but not overpowering
    }
    private val backdropMatrix = Matrix()

    // ── Layer 2: Glass body tint (milky white overlay) ──
    private val glassTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#14FFFFFF") // Very subtle white tint
        style = Paint.Style.FILL
    }

    // ── Layer 3: Top refraction gradient ──
    private val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Layer 4: Horizontal sheen ──
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Layer 5: Specular rim stroke (outer edge) ──
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    // ── Layer 5b: Inner rim stroke (secondary depth) ──
    private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.75f * resources.displayMetrics.density
        color = Color.parseColor("#15FFFFFF")
    }
    private val innerRimRectF = RectF()
    private val innerRimInset = 2f * resources.displayMetrics.density

    // ── Layer 6: Top specular highlight arc ──
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightRectF = RectF()

    init {
        // We draw ourselves, so need this
        setWillNotDraw(false)

        // Remove any XML background — we handle all drawing
        background = null

        // Clip children to rounded shape
        clipToOutline = false
        clipChildren = true
        clipToPadding = false
    }

    /**
     * Updates the frosted backdrop bitmap.
     * This should be a blurred version of the album artwork.
     */
    fun updateBackdrop(bitmap: Bitmap?) {
        if (bitmap != null) {
            // Create a more heavily blurred version for the glass
            blurredBitmap = BlurHelper.blurBitmap(context, bitmap, 200f)
        } else {
            blurredBitmap = null
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        // Update the rounded rect path
        glassRectF.set(0f, 0f, w.toFloat(), h.toFloat())
        glassPath.reset()
        glassPath.addRoundRect(glassRectF, cornerRadius, cornerRadius, Path.Direction.CW)

        // Inner rim rect (inset for depth effect)
        innerRimRectF.set(
            innerRimInset, innerRimInset,
            w - innerRimInset, h - innerRimInset
        )

        // Top highlight area (top 35% of the panel)
        highlightRectF.set(0f, 0f, w.toFloat(), h * 0.35f)

        // ── Create gradient shaders ──

        // Layer 3: Refraction — bright white at top fading down
        refractionPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.parseColor("#1AFFFFFF"),
                Color.parseColor("#08FFFFFF"),
                Color.parseColor("#03FFFFFF"),
                Color.parseColor("#00FFFFFF")
            ),
            floatArrayOf(0f, 0.15f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )

        // Layer 4: Horizontal sheen — subtle left-right light
        sheenPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#06FFFFFF"),
                Color.parseColor("#00FFFFFF"),
                Color.parseColor("#00FFFFFF"),
                Color.parseColor("#04FFFFFF")
            ),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )

        // Layer 5: Rim gradient — brighter at top, subtle at bottom
        rimPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.parseColor("#3AFFFFFF"),
                Color.parseColor("#1AFFFFFF"),
                Color.parseColor("#0DFFFFFF"),
                Color.parseColor("#15FFFFFF")
            ),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )

        // Layer 6: Top highlight — bright specular spot
        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.35f,
            intArrayOf(
                Color.parseColor("#18FFFFFF"),
                Color.parseColor("#06FFFFFF"),
                Color.parseColor("#00FFFFFF")
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val saveCount = canvas.save()
        canvas.clipPath(glassPath)

        // ── Layer 1: Frosted backdrop (the core glass effect) ──
        blurredBitmap?.let { bmp ->
            // Scale the blurred bitmap to fill the panel area
            backdropMatrix.reset()
            val scaleX = width.toFloat() / bmp.width
            val scaleY = height.toFloat() / bmp.height
            val scale = maxOf(scaleX, scaleY)
            val dx = (width - bmp.width * scale) / 2f
            val dy = (height - bmp.height * scale) / 2f
            backdropMatrix.setScale(scale, scale)
            backdropMatrix.postTranslate(dx, dy)
            canvas.drawBitmap(bmp, backdropMatrix, backdropPaint)
        }

        // ── Layer 2: Glass body tint ──
        canvas.drawRoundRect(glassRectF, cornerRadius, cornerRadius, glassTintPaint)

        // ── Layer 3: Top refraction gradient ──
        canvas.drawRoundRect(glassRectF, cornerRadius, cornerRadius, refractionPaint)

        // ── Layer 4: Horizontal sheen ──
        canvas.drawRoundRect(glassRectF, cornerRadius, cornerRadius, sheenPaint)

        // ── Layer 6: Top specular highlight ──
        canvas.save()
        canvas.clipRect(highlightRectF)
        canvas.drawRoundRect(glassRectF, cornerRadius, cornerRadius, highlightPaint)
        canvas.restore()

        canvas.restoreToCount(saveCount)

        // ── Layer 5: Rim stroke (drawn outside clip to get crisp edges) ──
        canvas.drawRoundRect(glassRectF, cornerRadius, cornerRadius, rimPaint)

        // ── Layer 5b: Inner rim ──
        canvas.drawRoundRect(
            innerRimRectF,
            cornerRadius - innerRimInset,
            cornerRadius - innerRimInset,
            innerRimPaint
        )

        // Children (text, progress bar, etc.) are drawn by super after this
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Clip children to the rounded rect
        val saveCount = canvas.save()
        canvas.clipPath(glassPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveCount)
    }
}
