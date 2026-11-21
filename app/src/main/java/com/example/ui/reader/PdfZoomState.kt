package com.example.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import kotlin.math.roundToInt

enum class ReadingMode {
    PAPER,
    SEPIA,
    NIGHT
}

object PdfZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4.5f
    const val DOUBLE_TAP_SCALE = 2.5f
    const val ZOOM_EPSILON = 1.01f

    fun maxOffset(viewportExtent: Float, scale: Float): Float {
        if (viewportExtent <= 0f || scale <= 1f) return 0f
        return ((viewportExtent * (scale - 1f)) / 2f).coerceAtLeast(0f)
    }

    fun clampOffset(rawOffset: Float, viewportExtent: Float, scale: Float): Float {
        val bound = maxOffset(viewportExtent, scale)
        return rawOffset.coerceIn(-bound, bound)
    }

    data class TransformUpdate(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val verticalOverflowPx: Float
    )

    fun computePinchTransform(
        currentScale: Float,
        currentOffsetX: Float,
        currentOffsetY: Float,
        zoomFactor: Float,
        pan: Offset,
        centroid: Offset,
        viewportWidth: Float,
        viewportHeight: Float
    ): TransformUpdate {
        val newScale = (currentScale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (newScale <= ZOOM_EPSILON && zoomFactor < 1f) {
            return TransformUpdate(
                scale = MIN_SCALE,
                offsetX = 0f,
                offsetY = 0f,
                verticalOverflowPx = 0f
            )
        }

        val effectiveRatio = newScale / currentScale
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f

        val rawX = (centroid.x - centerX) * (1f - effectiveRatio) +
            currentOffsetX * effectiveRatio + pan.x
        val rawY = (centroid.y - centerY) * (1f - effectiveRatio) +
            currentOffsetY * effectiveRatio + pan.y

        val clampedX = clampOffset(rawX, viewportWidth, newScale)
        val clampedY = clampOffset(rawY, viewportHeight, newScale)
        val overflowY = rawY - clampedY

        return TransformUpdate(
            scale = newScale,
            offsetX = clampedX,
            offsetY = clampedY,
            verticalOverflowPx = overflowY
        )
    }

    fun computeDoubleTapTarget(
        currentScale: Float,
        currentOffsetX: Float,
        currentOffsetY: Float,
        tapOffset: Offset,
        viewportWidth: Float,
        viewportHeight: Float
    ): TransformUpdate {
        if (currentScale > ZOOM_EPSILON) {
            return TransformUpdate(
                scale = MIN_SCALE,
                offsetX = 0f,
                offsetY = 0f,
                verticalOverflowPx = 0f
            )
        }

        val targetScale = DOUBLE_TAP_SCALE
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        val ratio = targetScale / currentScale

        val rawX = (tapOffset.x - centerX) * (1f - ratio) + currentOffsetX * ratio
        val rawY = (tapOffset.y - centerY) * (1f - ratio) + currentOffsetY * ratio

        return TransformUpdate(
            scale = targetScale,
            offsetX = clampOffset(rawX, viewportWidth, targetScale),
            offsetY = clampOffset(rawY, viewportHeight, targetScale),
            verticalOverflowPx = 0f
        )
    }

    /**
     * Quantizes continuous pinch scale into discrete rendering width buckets so high-DPI
     * re-rendering only triggers at meaningful thresholds instead of every frame.
     */
    fun computeRenderBucketWidth(baseWidthPx: Int, scale: Float): Int {
        val safeBase = baseWidthPx.coerceIn(480, 1440)
        val bucketMultiplier = when {
            scale <= 1.25f -> 1.0f
            scale <= 2.15f -> 1.65f
            scale <= 3.2f -> 2.3f
            else -> 2.8f
        }
        return (safeBase * bucketMultiplier).roundToInt().coerceIn(480, 2200)
    }
}

fun ReadingMode.toColorFilter(): ColorFilter? {
    return when (this) {
        ReadingMode.PAPER -> null
        ReadingMode.SEPIA -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.96f, 0f, 0f, 0f, 0f,
                    0f, 0.90f, 0f, 0f, 0f,
                    0f, 0f, 0.78f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        ReadingMode.NIGHT -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.88f, 0f, 0f, 0f, 232f,
                    0f, -0.88f, 0f, 0f, 228f,
                    0f, 0f, -0.88f, 0f, 220f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}
