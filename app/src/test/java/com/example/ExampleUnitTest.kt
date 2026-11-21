package com.example

import androidx.compose.ui.geometry.Offset
import com.example.ui.components.formatFileSize
import com.example.ui.reader.PdfZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `pinch zoom scales within bounds and clamps offsets`() {
        val zoomed = PdfZoomMath.computePinchTransform(
            currentScale = 1f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
            zoomFactor = 2f,
            pan = Offset.Zero,
            centroid = Offset(500f, 1000f),
            viewportWidth = 1000f,
            viewportHeight = 2000f
        )
        assertEquals(2f, zoomed.scale, 0.001f)
        assertEquals(0f, zoomed.offsetX, 0.001f)
        assertEquals(0f, zoomed.offsetY, 0.001f)

        val overZoomed = PdfZoomMath.computePinchTransform(
            currentScale = 4f,
            currentOffsetX = 0f,
            currentOffsetY = 0f,
            zoomFactor = 2f,
            pan = Offset.Zero,
            centroid = Offset(500f, 1000f),
            viewportWidth = 1000f,
            viewportHeight = 2000f
        )
        assertEquals(PdfZoomMath.MAX_SCALE, overZoomed.scale, 0.001f)
    }

    @Test
    fun `render bucket width increases with zoom scale and respects cap`() {
        val base = PdfZoomMath.computeRenderBucketWidth(1000, 1.0f)
        val medium = PdfZoomMath.computeRenderBucketWidth(1000, 1.8f)
        val high = PdfZoomMath.computeRenderBucketWidth(1000, 3.8f)

        assertTrue(medium > base)
        assertTrue(high > medium)
        assertTrue(high <= 2200)
    }

    @Test
    fun `file size formatter formats KB MB GB cleanly`() {
        assertEquals("", formatFileSize(0L))
        assertEquals("512 KB", formatFileSize(512L * 1024L))
        assertEquals("4.5 MB", formatFileSize((4.5 * 1024 * 1024).toLong()))
        assertEquals("1.25 GB", formatFileSize((1.25 * 1024 * 1024 * 1024).toLong()))
    }
}
