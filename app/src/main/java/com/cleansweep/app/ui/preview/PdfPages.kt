package com.cleansweep.app.ui.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File

/** Draws the first pages of a PDF into pictures. The platform's own renderer: nothing is sent anywhere. */
class PdfPages {

    /** How many pages are shown. Enough to recognise a document; the rest is what a PDF app is for. */
    private val maxPages = 3

    /**
     * The first pages of the PDF at [path], [targetWidth] pixels wide. Each page is about four times its width in
     * height in memory, so three pages of a phone-wide picture stay well below ten megabytes.
     *
     * @throws Exception when the file is not a PDF that can be opened, or is damaged.
     */
    fun firstPagesOf(
        path: String,
        targetWidth: Int,
    ): List<Bitmap> {
        val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(descriptor).use { renderer ->
                val count = minOf(renderer.pageCount, maxPages)
                return (0 until count).map { index -> render(renderer, index, targetWidth) }
            }
        } finally {
            descriptor.close()
        }
    }

    private fun render(
        renderer: PdfRenderer,
        index: Int,
        targetWidth: Int,
    ): Bitmap = renderer.openPage(index).use { page ->
        val scale = targetWidth.toFloat() / page.width
        val bitmap = createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        // Pages are transparent where nothing is printed; white is what paper looks like.
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }
}
