package com.fireairplay.receiver.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.graphics.RenderEffect
import android.graphics.Shader
import android.widget.ImageView

/**
 * Cross-API blur utility for the liquid glass aesthetic.
 *
 * - API 31+: Uses [RenderEffect.createBlurEffect] (GPU-accelerated, applied directly to the View)
 * - API 17–30: Uses [RenderScript] to produce a software-blurred bitmap
 *   (downscale → blur → upscale for performance on Fire TV hardware)
 *
 * Also provides saturation boost to make blurred backgrounds more vibrant.
 */
@Suppress("DEPRECATION") // RenderScript is deprecated but needed for API < 31
object BlurHelper {

    /** Scale factor: the image is downscaled before blur for performance */
    private const val DOWNSCALE_FACTOR = 0.25f

    /** Maximum RenderScript blur radius (hardcoded Android limit) */
    private const val MAX_RS_RADIUS = 25f

    /**
     * Applies a heavy blur to the given [ImageView].
     * Also boosts saturation to make the blurred background more vibrant.
     *
     * @param context   Activity context (needed for RenderScript on older APIs)
     * @param imageView Target ImageView
     * @param bitmap    Source bitmap (album artwork)
     * @param radius    Desired blur radius in pixels
     */
    fun applyBlur(context: Context, imageView: ImageView, bitmap: Bitmap, radius: Float = 150f) {
        // Boost saturation first so the blurred background is vibrant
        val saturated = boostSaturation(bitmap, 1.4f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: GPU-accelerated RenderEffect blur
            imageView.setImageBitmap(saturated)
            imageView.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)
            )
        } else {
            // API 17–30: Software blur via RenderScript
            val blurred = blurBitmap(context, saturated, radius)
            imageView.setImageBitmap(blurred)
        }
    }

    /**
     * Clears any blur effect from the ImageView (API 31+).
     */
    fun clearBlur(imageView: ImageView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            imageView.setRenderEffect(null)
        }
    }

    /**
     * Creates a blurred copy of the given bitmap using RenderScript.
     * Strategy: downscale → multi-pass blur → return.
     */
    fun blurBitmap(context: Context, source: Bitmap, radius: Float = 150f): Bitmap {
        val scaledWidth = (source.width * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * DOWNSCALE_FACTOR).toInt().coerceAtLeast(1)
        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        val output = scaledBitmap.copy(Bitmap.Config.ARGB_8888, true)

        val rs = RenderScript.create(context)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

        val passes = (radius / (MAX_RS_RADIUS * (1f / DOWNSCALE_FACTOR))).toInt().coerceAtLeast(2)

        var currentBitmap = output
        repeat(passes) {
            val allocationIn = Allocation.createFromBitmap(rs, currentBitmap)
            val allocationOut = Allocation.createFromBitmap(rs, currentBitmap)
            script.setRadius(MAX_RS_RADIUS)
            script.setInput(allocationIn)
            script.forEach(allocationOut)
            allocationOut.copyTo(currentBitmap)
            allocationIn.destroy()
            allocationOut.destroy()
        }

        script.destroy()
        rs.destroy()

        return currentBitmap
    }

    /**
     * Boosts the saturation of a bitmap to make colors more vibrant.
     * This is applied before blurring so the background has rich, flowing colors.
     *
     * @param source     Source bitmap
     * @param saturation Saturation multiplier (1.0 = unchanged, >1.0 = more saturated)
     * @return A new bitmap with boosted saturation
     */
    private fun boostSaturation(source: Bitmap, saturation: Float): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(saturation)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }
}
