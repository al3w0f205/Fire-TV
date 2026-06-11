package com.fireairplay.receiver.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.palette.graphics.Palette

/**
 * Custom View that renders an animated, slowly shifting gradient overlay
 * on top of the blurred album art background to create the "liquid" feel.
 *
 * Colors are extracted from the current album artwork using [Palette].
 * When no artwork is available, defaults to vibrant pink → red → purple tones.
 *
 * The animation smoothly rotates the gradient angle over time, creating a
 * fluid, cinematic motion. Kept very subtle so the album art background
 * remains the dominant visual element.
 */
class AnimatedGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Default liquid glass gradient colors (pink → red → purple)
    private var color1 = Color.parseColor("#E91E8C") // vibrant pink
    private var color2 = Color.parseColor("#B91C4F") // deep red
    private var color3 = Color.parseColor("#7B2FBE") // purple

    // Animation
    private var currentAngle = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    // Very subtle overlay — just enough to tint, not enough to obscure
    private val overlayAlpha = 55 // 0-255, ~22% opacity

    init {
        startAnimation()
    }

    /**
     * Updates the gradient colors based on the dominant colors extracted
     * from the currently playing album's artwork.
     */
    fun updateColors(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            palette?.let {
                color1 = it.getVibrantColor(color1)
                color2 = it.getDominantColor(color2)
                color3 = it.getDarkMutedColor(color3)
                invalidate()
            }
        }
    }

    /**
     * Resets to the default pink/red/purple gradient.
     */
    fun resetColors() {
        color1 = Color.parseColor("#E91E8C")
        color2 = Color.parseColor("#B91C4F")
        color3 = Color.parseColor("#7B2FBE")
        invalidate()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 30_000L // Full rotation in 30 seconds — slow and dreamy
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                currentAngle = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val radians = Math.toRadians(currentAngle.toDouble())
        val cx = width / 2f
        val cy = height / 2f
        val diagonal = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

        val startX = cx + (diagonal * Math.cos(radians)).toFloat()
        val startY = cy + (diagonal * Math.sin(radians)).toFloat()
        val endX = cx - (diagonal * Math.cos(radians)).toFloat()
        val endY = cy - (diagonal * Math.sin(radians)).toFloat()

        val shader = LinearGradient(
            startX, startY, endX, endY,
            intArrayOf(color1, color2, color3),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.MIRROR
        )

        paint.shader = shader
        paint.alpha = overlayAlpha
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}
