package com.cleansweep.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Writes the whole screen to `app/build/screenshots/<name>.png`.
 *
 * The tests check what a screen says and where it is laid out; the picture is for looking at what a device
 * would draw, which no assertion can replace.
 */
fun ComposeContentTestRule.saveScreen(
    name: String,
) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val target = File("build/screenshots/$name.png")
    target.parentFile?.mkdirs()
    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    assertTrue("The screen $name was drawn", bitmap.width > 0 && bitmap.height > 0)
}
