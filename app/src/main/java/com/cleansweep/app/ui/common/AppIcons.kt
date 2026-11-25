package com.cleansweep.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icons that the standard set does not have. Each is one Material glyph, drawn from its outline data so that no
 * icon library has to be shipped for the sake of twenty shapes.
 */
object AppIcons {

    /** Scan controls. */
    val Pause: ImageVector by lazy { glyph("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }
    val Stop: ImageVector by lazy { glyph("Stop", "M6,6h12v12H6z") }
    val Restore: ImageVector by lazy {
        glyph(
            "Restore",
            "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7" +
                "c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z" +
                "m-1,5v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8L12,8z",
        )
    }
    val Bookmark: ImageVector by lazy {
        glyph("Keep", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z")
    }
    val Visibility: ImageVector by lazy {
        glyph(
            "Preview",
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17" +
                "c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zm0,-8c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 " +
                "3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    /** Kinds of files and places. */
    val Folder: ImageVector by lazy {
        glyph(
            "Folder",
            "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
        )
    }
    val FolderOpen: ImageVector by lazy {
        glyph(
            "FolderOpen",
            "M20,6h-8l-2,-2H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2z" +
                "m0,12H4V8h16v10z",
        )
    }
    val File: ImageVector by lazy {
        glyph("File", "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2L18,22c1.1,0 2,-0.9 2,-2V8l-6,-6H6zm7,7V3.5L18.5,9H13z")
    }
    val Document: ImageVector by lazy {
        glyph(
            "Document",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zm2,16H8v-2h8v2z" +
                "m0,-4H8v-2h8v2zm-3,-5V3.5L18.5,9H13z",
        )
    }
    val Image: ImageVector by lazy {
        glyph(
            "Image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5" +
                "l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        )
    }
    val Video: ImageVector by lazy {
        glyph(
            "Video",
            "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16" +
                "c1.1,0 2,-0.9 2,-2V4h-4z",
        )
    }
    val Audio: ImageVector by lazy {
        glyph(
            "Audio",
            "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
        )
    }
    val Installer: ImageVector by lazy {
        glyph(
            "Installer",
            "M6,18c0,0.55 0.45,1 1,1h1v3.5c0,0.83 0.67,1.5 1.5,1.5s1.5,-0.67 1.5,-1.5V19h2v3.5c0,0.83 0.67,1.5 1.5,1.5" +
                "s1.5,-0.67 1.5,-1.5V19h1c0.55,0 1,-0.45 1,-1V8H6v10zM3.5,8C2.67,8 2,8.67 2,9.5v7c0,0.83 0.67,1.5 1.5,1.5" +
                "S5,17.33 5,16.5v-7C5,8.67 4.33,8 3.5,8zm17,0c-0.83,0 -1.5,0.67 -1.5,1.5v7c0,0.83 0.67,1.5 1.5,1.5" +
                "s1.5,-0.67 1.5,-1.5v-7c0,-0.83 -0.67,-1.5 -1.5,-1.5zm-4.97,-5.84l1.3,-1.3c0.2,0.2 0.2,0.51 0,0.71" +
                " -0.2,0.2 -0.51,0.2 -0.71,0l-1.48,1.48C13.85,1.23 12.95,1 12,1c-0.96,0 -1.86,0.23 -2.66,0.63" +
                "L7.85,0.15c-0.2,-0.2 -0.51,-0.2 -0.71,0 -0.2,0.2 -0.2,0.51 0,0.71l1.31,1.31C6.97,3.26 6,5.01 6,7h12" +
                "c0,-1.99 -0.97,-3.75 -2.47,-4.84zM10,5H9V4h1v1zm5,0h-1V4h1v1z",
        )
    }
    val Archive: ImageVector by lazy {
        glyph(
            "Archive",
            "M20.54,5.23l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.16,0.55L3.46,5.23" +
                "C3.17,5.57 3,6.02 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.48 -0.17,-0.93 -0.46,-1.27z" +
                "M12,17.5L6.5,12H10v-2h4v2h3.5L12,17.5zM5.12,5l0.81,-1h12l0.94,1H5.12z",
        )
    }
    val Storage: ImageVector by lazy {
        glyph("Storage", "M2,20h20v-4H2v4zm2,-3h2v2H4v-2zM2,4v4h20V4H2zm4,3H4V5h2v2zm-4,7h20v-4H2v4zm2,-3h2v2H4v-2z")
    }

    /** Privacy and safety. */
    val Shield: ImageVector by lazy {
        glyph(
            "Shield",
            "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zm-2,16l-4,-4 1.41,-1.41L10,14.17" +
                "l6.59,-6.59L18,9l-8,8z",
        )
    }
    val Block: ImageVector by lazy {
        glyph(
            "Block",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 " +
                "1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zm8,8c-1.85,0 -3.55,-0.63 -4.9,-1.69" +
                "L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z",
        )
    }

    /** The app's screens. */
    val Suggestions: ImageVector by lazy {
        glyph(
            "Suggestions",
            "M9,21c0,0.55 0.45,1 1,1h4c0.55,0 1,-0.45 1,-1v-1H9v1zm3,-19C8.14,2 5,5.14 5,9c0,2.38 1.19,4.47 3,5.74" +
                "V17c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1v-2.26c1.81,-1.27 3,-3.36 3,-5.74 0,-3.86 -3.14,-7 -7,-7z",
        )
    }

    /** Selecting and sorting. */
    val SelectAll: ImageVector by lazy {
        glyph(
            "SelectAll",
            "M3,5h2V3c-1.1,0 -2,0.9 -2,2zm0,8h2v-2H3v2zm4,8h2v-2H7v2zM3,9h2V7H3v2zm10,-6h-2v2h2V3zm6,0v2h2" +
                "c0,-1.1 -0.9,-2 -2,-2zM5,21v-2H3c0,1.1 0.9,2 2,2zm-2,-4h2v-2H3v2zM9,3H7v2h2V3zm2,18h2v-2h-2v2z" +
                "m8,-8h2v-2h-2v2zm0,8c1.1,0 2,-0.9 2,-2h-2v2zm0,-12h2V7h-2v2zm0,8h2v-2h-2v2zm-4,4h2v-2h-2v2z" +
                "m0,-16h2V3h-2v2zM7,17h10V7H7v10zm2,-8h6v6H9V9z",
        )
    }
    val ArrowUpward: ImageVector by lazy {
        glyph("ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    }
    val Copy: ImageVector by lazy {
        glyph(
            "Copy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zm3,4H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7" +
                "c0,-1.1 -0.9,-2 -2,-2zm0,16H8V7h11v14z",
        )
    }
    val OpenInNew: ImageVector by lazy {
        glyph(
            "OpenInNew",
            "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59" +
                "l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
        )
    }
    val Clock: ImageVector by lazy {
        glyph(
            "Clock",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20" +
                "c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23" +
                " -4.5,-2.67V7z",
        )
    }

    /**
     * A 24 by 24 glyph from its outline, in the notation Material icons are written in. The shape is filled with
     * black; `Icon` recolours it with the tint it is given, as it does for the standard icons.
     */
    private fun glyph(
        name: String,
        pathData: String,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        pathBuilder = { traceOutline(pathData) },
    ).build()
}

/** One command letter, or one number, of an outline. */
private val OUTLINE_TOKEN = Regex("[MmLlHhVvCcSsZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)")

/**
 * Follows an outline written the way Material icons are: moves, straight lines, Bézier curves and closing lines.
 * A command stays in force for every set of numbers that follows it, as in the notation. Arcs are not needed
 * for any of these glyphs and are refused, so that a wrong outline is noticed here rather than on screen.
 */
internal fun PathBuilder.traceOutline(
    pathData: String,
) {
    val tokens = OUTLINE_TOKEN.findAll(pathData).map { it.value }.toList()
    var index = 0

    fun next(): Float = tokens[index++].toFloat()

    // Where the pen is, where the line it is drawing began, and where the last curve left its last control point.
    var x = 0f
    var y = 0f
    var startX = 0f
    var startY = 0f
    var lastControlX = 0f
    var lastControlY = 0f
    var hasLastControl = false
    var command = 'M'

    while (index < tokens.size) {
        val leading = tokens[index].first()
        if (leading.isLetter()) {
            command = leading
            index++
        }
        when (command) {
            'M' -> {
                x = next(); y = next()
                moveTo(x, y)
                startX = x; startY = y
                command = 'L'
                hasLastControl = false
            }

            'm' -> {
                val dx = next(); val dy = next()
                moveToRelative(dx, dy)
                x += dx; y += dy
                startX = x; startY = y
                command = 'l'
                hasLastControl = false
            }

            'L' -> {
                x = next(); y = next()
                lineTo(x, y)
                hasLastControl = false
            }

            'l' -> {
                val dx = next(); val dy = next()
                lineToRelative(dx, dy)
                x += dx; y += dy
                hasLastControl = false
            }

            'H' -> {
                x = next()
                horizontalLineTo(x)
                hasLastControl = false
            }

            'h' -> {
                val dx = next()
                horizontalLineToRelative(dx)
                x += dx
                hasLastControl = false
            }

            'V' -> {
                y = next()
                verticalLineTo(y)
                hasLastControl = false
            }

            'v' -> {
                val dy = next()
                verticalLineToRelative(dy)
                y += dy
                hasLastControl = false
            }

            'C' -> {
                val firstX = next(); val firstY = next()
                val secondX = next(); val secondY = next()
                x = next(); y = next()
                curveTo(firstX, firstY, secondX, secondY, x, y)
                lastControlX = secondX; lastControlY = secondY
                hasLastControl = true
            }

            'c' -> {
                val firstDx = next(); val firstDy = next()
                val secondDx = next(); val secondDy = next()
                val endDx = next(); val endDy = next()
                curveToRelative(firstDx, firstDy, secondDx, secondDy, endDx, endDy)
                lastControlX = x + secondDx; lastControlY = y + secondDy
                x += endDx; y += endDy
                hasLastControl = true
            }

            'S', 's' -> {
                // The first control point mirrors the previous one; after anything but a curve it is the pen itself.
                val (firstX, firstY) = if (hasLastControl) {
                    (2 * x - lastControlX) to (2 * y - lastControlY)
                } else {
                    x to y
                }
                val secondX = if (command == 'S') next() else x + next()
                val secondY = if (command == 'S') next() else y + next()
                x = if (command == 'S') next() else x + next()
                y = if (command == 'S') next() else y + next()
                curveTo(firstX, firstY, secondX, secondY, x, y)
                lastControlX = secondX; lastControlY = secondY
                hasLastControl = true
            }

            'Z', 'z' -> {
                close()
                x = startX; y = startY
                hasLastControl = false
            }

            else -> throw IllegalArgumentException("Unsupported command '$command' in the icon outline")
        }
    }
}
