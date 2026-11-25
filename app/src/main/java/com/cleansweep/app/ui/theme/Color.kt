package com.cleansweep.app.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette. Used as-is on Android 10/11; Android 12+ takes colours from the user's wallpaper instead.
// Each `on` role is at least 4.5:1 against the role it is paired with, and `primary` and `error` clear the same
// bar when used as text on `surface`/`background` (WCAG AA for body text).

/**
 * Colours of the storage breakdown. They do not come from the wallpaper palette, because a chart needs hues
 * that stay apart; they are the brand green and five colours kept at a distance from it and from each other.
 * They only fill shapes: the legend is written in the theme's text colours, so contrast is not their job.
 */
data class ChartColors(
    val document: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val installer: Color,
    val other: Color,
)

val LightChartColors = ChartColors(
    document = Color(0xFF3E6373),
    image = Color(0xFF0B6E56),
    video = Color(0xFF7A4E9E),
    audio = Color(0xFFA85B12),
    installer = Color(0xFFA63A6B),
    other = Color(0xFF707973),
)

val DarkChartColors = ChartColors(
    document = Color(0xFFA6CCDF),
    image = Color(0xFF7AD6B8),
    video = Color(0xFFCBA6E8),
    audio = Color(0xFFF0B878),
    installer = Color(0xFFF0A0C0),
    other = Color(0xFF8A938C),
)

val PrimaryLight = Color(0xFF0B6E56)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFA8F2D6)
val OnPrimaryContainerLight = Color(0xFF002016)
val SecondaryLight = Color(0xFF4A635B)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFCCE9DD)
val OnSecondaryContainerLight = Color(0xFF072019)
val TertiaryLight = Color(0xFF3E6373)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFC2E8FB)
val OnTertiaryContainerLight = Color(0xFF001F2A)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)
val BackgroundLight = Color(0xFFF5FBF6)
val OnBackgroundLight = Color(0xFF171D1A)
val SurfaceLight = Color(0xFFF5FBF6)
val OnSurfaceLight = Color(0xFF171D1A)
val SurfaceVariantLight = Color(0xFFDBE5DE)
val OnSurfaceVariantLight = Color(0xFF404944)
val OutlineLight = Color(0xFF707973)

val PrimaryDark = Color(0xFF7AD6B8)
val OnPrimaryDark = Color(0xFF00382A)
val PrimaryContainerDark = Color(0xFF00513E)
val OnPrimaryContainerDark = Color(0xFFA8F2D6)
val SecondaryDark = Color(0xFFB3CCC0)
val OnSecondaryDark = Color(0xFF1F352C)
val SecondaryContainerDark = Color(0xFF354C42)
val OnSecondaryContainerDark = Color(0xFFCCE9DD)
val TertiaryDark = Color(0xFFA6CCDF)
val OnTertiaryDark = Color(0xFF093140)
val TertiaryContainerDark = Color(0xFF274857)
val OnTertiaryContainerDark = Color(0xFFC2E8FB)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
val BackgroundDark = Color(0xFF0F1512)
val OnBackgroundDark = Color(0xFFDFE4DF)
val SurfaceDark = Color(0xFF0F1512)
val OnSurfaceDark = Color(0xFFDFE4DF)
val SurfaceVariantDark = Color(0xFF404944)
val OnSurfaceVariantDark = Color(0xFFBFC9C1)
val OutlineDark = Color(0xFF8A938C)

// The levels of surface. App bars, cards and sheets each take one of them, a step away from the background, so
// that what lies on top can be told apart from what lies beneath. Without them Material 3 would fall back to
// its own neutral greys, which stand out against the brand colours.
val SurfaceDimLight = Color(0xFFD6DED9)
val SurfaceBrightLight = Color(0xFFF5FBF6)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF0F6F2)
val SurfaceContainerLight = Color(0xFFEAEFEB)
val SurfaceContainerHighLight = Color(0xFFE4EAE6)
val SurfaceContainerHighestLight = Color(0xFFDFE4E0)
val OutlineVariantLight = Color(0xFFC0C9C3)
val InverseSurfaceLight = Color(0xFF2B322E)
val InverseOnSurfaceLight = Color(0xFFEDF3EF)
val InversePrimaryLight = Color(0xFF7AD6B8)

val SurfaceDimDark = Color(0xFF0F1512)
val SurfaceBrightDark = Color(0xFF353B38)
val SurfaceContainerLowestDark = Color(0xFF0A100D)
val SurfaceContainerLowDark = Color(0xFF171D1A)
val SurfaceContainerDark = Color(0xFF1B211E)
val SurfaceContainerHighDark = Color(0xFF252B28)
val SurfaceContainerHighestDark = Color(0xFF303633)
val OutlineVariantDark = Color(0xFF3A423E)
val InverseSurfaceDark = Color(0xFFDFE4DF)
val InverseOnSurfaceDark = Color(0xFF2F3532)
val InversePrimaryDark = Color(0xFF0B6E56)
