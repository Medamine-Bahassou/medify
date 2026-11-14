@file:OptIn(ExperimentalTextApi::class, ExperimentalFoundationApi::class)

package app.musike.android

import android.graphics.Color as AndroidColor
import android.os.Build
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Medify's design system, in one place: colours, type, shapes, motion and the components every screen is built from.
// Screens use these instead of styling Material components themselves, so the whole app reads as one surface language:
// grouped rows that form a single rounded surface, pill buttons, bold headline headers, springy press feedback.

// ---- colour

val ACCENTS = listOf("#ff4757", "#c3ec52", "#34d399", "#22d3ee", "#60a5fa", "#a78bfa", "#f472b6", "#fb7c5c", "#fbbf24")

fun hex(s: String) = runCatching { Color(AndroidColor.parseColor(s)) }.getOrDefault(Color(0xFFFF4757))

private fun schemeFor(dark: Boolean, accentHex: String, ctx: android.content.Context): ColorScheme {
    // dark is true black (AMOLED: black pixels are off), the wallpaper's colours too; cards and bars stay a shade above it
    if (accentHex == "dynamic" && Musike.dynamicAvailable) return if (dark) dynamicDarkColorScheme(ctx).copy(background = Color.Black, surface = Color.Black,
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0B), surfaceContainer = Color(0xFF111113)) else dynamicLightColorScheme(ctx)
    val accent = hex(accentHex)
    val ink = if (accent.luminance() > 0.4f) Color(0xFF0B0C0F) else Color.White
    return if (dark) darkColorScheme(
        primary = accent, onPrimary = ink, primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color(0xFF111113)), onPrimaryContainer = lerp(accent, Color.White, 0.35f),
        secondary = accent, onSecondary = ink, secondaryContainer = Color(0xFF262830), onSecondaryContainer = Color(0xFFECEEF2), tertiary = accent,
        background = Color.Black, onBackground = Color(0xFFECEEF2), surface = Color.Black, onSurface = Color(0xFFECEEF2),
        surfaceVariant = Color(0xFF1C1D22), onSurfaceVariant = Color(0xFF9BA1AD), outline = Color(0xFF6B717D), outlineVariant = Color(0xFF26282F),
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0B), surfaceContainer = Color(0xFF111113),
        surfaceContainerHigh = Color(0xFF17181B), surfaceContainerHighest = Color(0xFF212227), inverseSurface = Color(0xFFECEEF2), inverseOnSurface = Color(0xFF12141A),
    ) else {
        // a light accent can't be read on white: text and icons get a deeper shade of it
        val deep = if (accent.luminance() > 0.4f) lerp(accent, Color.Black, 0.55f) else accent
        lightColorScheme(
            primary = deep, onPrimary = Color.White, primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color.White), onPrimaryContainer = lerp(deep, Color.Black, 0.3f),
            secondary = deep, onSecondary = Color.White, secondaryContainer = Color(0xFFE4E7EC), onSecondaryContainer = Color(0xFF12141A), tertiary = deep,
            background = Color(0xFFF6F7F9), onBackground = Color(0xFF12141A), surface = Color(0xFFF6F7F9), onSurface = Color(0xFF12141A),
            surfaceVariant = Color(0xFFE4E7EC), onSurfaceVariant = Color(0xFF555B67), outline = Color(0xFF7D8390), outlineVariant = Color(0xFFD6DAE1),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F2F5), surfaceContainer = Color(0xFFECEEF2),
            surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE6E8EC),
        )
    }
}

/** The theme glides to a new accent or mode instead of jumping. */
@Composable
private fun animated(s: ColorScheme): ColorScheme {
    @Composable fun a(c: Color) = animateColorAsState(c, tween(450), label = "theme").value
    return s.copy(
        primary = a(s.primary), onPrimary = a(s.onPrimary), primaryContainer = a(s.primaryContainer), onPrimaryContainer = a(s.onPrimaryContainer),
        secondary = a(s.secondary), secondaryContainer = a(s.secondaryContainer), onSecondaryContainer = a(s.onSecondaryContainer),
        background = a(s.background), onBackground = a(s.onBackground), surface = a(s.surface), onSurface = a(s.onSurface), onSurfaceVariant = a(s.onSurfaceVariant),
        surfaceContainerLow = a(s.surfaceContainerLow), surfaceContainer = a(s.surfaceContainer), surfaceContainerHigh = a(s.surfaceContainerHigh),
        surfaceContainerHighest = a(s.surfaceContainerHighest), outlineVariant = a(s.outlineVariant),
    )
}

// ---- type: Google Sans Flex (res/font), a variable font. Each role sets its own weight,
// width, roundness and optical size: headlines heavy, wide and round; body close to the font's default; labels round,
// to match the pill shapes they sit in. (the axes need Android 8; older phones get the regular cut.)

private fun flex(weight: Int, width: Float, round: Float, size: Float) = FontFamily(
    Font(
        R.font.google_sans_flex,
        weight = FontWeight(weight.coerceIn(100, 900)),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight), FontVariation.width(width), FontVariation.Setting("ROND", round), FontVariation.Setting("opsz", size),
        ),
    ),
)

private fun style(weight: Int, width: Float, round: Float, size: Int, line: Int) =
    TextStyle(fontFamily = flex(weight, width, round, size.toFloat()), fontWeight = FontWeight(weight.coerceIn(100, 900)), fontSize = size.sp, lineHeight = line.sp)

private val MedifyType = Typography(
    displayLarge = style(760, 118f, 40f, 57, 64), displayMedium = style(740, 116f, 40f, 45, 52), displaySmall = style(740, 116f, 40f, 36, 44),
    headlineLarge = style(780, 116f, 40f, 32, 40), headlineMedium = style(760, 114f, 38f, 28, 36), headlineSmall = style(740, 112f, 36f, 24, 32),
    titleLarge = style(660, 108f, 30f, 22, 28), titleMedium = style(600, 104f, 26f, 16, 24), titleSmall = style(580, 104f, 24f, 14, 20),
    bodyLarge = style(430, 100f, 14f, 16, 24), bodyMedium = style(420, 100f, 14f, 15, 22), bodySmall = style(420, 100f, 12f, 13, 18),
    labelLarge = style(660, 106f, 44f, 14, 20), labelMedium = style(640, 106f, 44f, 12, 16), labelSmall = style(640, 106f, 44f, 11, 16),
)

// Arabic: Almarai (res/font, OFL), made for Arabic screens, with Latin letters of its own. it has four cuts, so each role
// takes the nearest one; sizes and line heights stay those of the scale above.
private val Almarai = FontFamily(
    Font(R.font.almarai_light, FontWeight.Light), Font(R.font.almarai_regular, FontWeight.Normal),
    Font(R.font.almarai_bold, FontWeight.Bold), Font(R.font.almarai_extrabold, FontWeight.ExtraBold),
)

private fun TextStyle.almarai() = copy(fontFamily = Almarai, fontWeight = when {
    (fontWeight?.weight ?: 400) >= 740 -> FontWeight.ExtraBold
    (fontWeight?.weight ?: 400) >= 560 -> FontWeight.Bold
    else -> FontWeight.Normal
})

private val ArabicType = MedifyType.run {
    Typography(
        displayLarge.almarai(), displayMedium.almarai(), displaySmall.almarai(), headlineLarge.almarai(), headlineMedium.almarai(), headlineSmall.almarai(),
        titleLarge.almarai(), titleMedium.almarai(), titleSmall.almarai(), bodyLarge.almarai(), bodyMedium.almarai(), bodySmall.almarai(),
        labelLarge.almarai(), labelMedium.almarai(), labelSmall.almarai(),
    )
}

// ---- shapes: one radius per kind of surface

private val MedifyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)
val PillShape = RoundedCornerShape(percent = 50)
val ArtShape = RoundedCornerShape(12.dp)
val TileShape = RoundedCornerShape(20.dp)

@Composable
fun MedifyTheme(content: @Composable () -> Unit) {
    val dark = when (Musike.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = animated(schemeFor(dark, Musike.accent, ctx))
    MaterialTheme(colorScheme = scheme, typography = if (Musike.lang == "ar") ArabicType else MedifyType, shapes = MedifyShapes) {
        // text and icons on the page default to the page's own ink
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

// ---- motion: springs that settle quickly, a little bounce on what the finger moves, none on colour and fades

object Motion {
    fun <T> press() = spring<T>(dampingRatio = 0.7f, stiffness = 1400f)
    fun <T> move() = spring<T>(dampingRatio = 0.82f, stiffness = 600f)
    fun <T> fade() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)

    // a new page grows into place while the one under it recedes; going back reverses it. the short drift keeps the
    // direction readable.
    val forwardIn: EnterTransition get() = fadeIn(fade()) + scaleIn(move(), initialScale = 0.93f) + slideInHorizontally(move()) { it / 20 }
    val forwardOut: ExitTransition get() = fadeOut(fade()) + scaleOut(fade(), targetScale = 0.96f)
    val backIn: EnterTransition get() = fadeIn(fade()) + scaleIn(move(), initialScale = 0.96f)
    val backOut: ExitTransition get() = fadeOut(fade()) + scaleOut(fade(), targetScale = 0.93f) + slideOutHorizontally(move()) { it / 20 }
}

/** Shrinks a little while held and springs back on release. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, scale: Float = 0.96f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, Motion.press(), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Tap (and optional long press, with a haptic tick) with press-scale feedback and a ripple clipped to [shape]. */
@Composable
fun Modifier.tap(shape: Shape = SquareShape, scale: Float = 0.96f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    return pressScale(source, scale).clip(shape).combinedClickable(source, ripple(), onClick = onClick,
        onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } })
}

private val SquareShape: Shape = RoundedCornerShape(0.dp)

// ---- icons Material's core set doesn't have (Material Icons paths)

private fun icon(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)).build()

object Ic {
    val pause = icon("M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val next = icon("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    val previous = icon("M6 6h2v12H6zm3.5 6l8.5 6V6z")
    val shuffle = icon("M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z")
    val repeat = icon("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z")
    val repeatOne = icon("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z")
    val library = icon("M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 1.38-1.12 2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z")
    val queue = icon("M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z")
    val playlistAdd = icon("M14 10H2v2h12v-2zm0-4H2v2h12V6zm4 8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2 16h8v-2H2v2z")
    val playNext = icon("M19 9H2v2h17V9zm0-4H2v2h17V5zM2 15h13v-2H2v2zm15-2v6l5-3-5-3z")
    val explore = icon("M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z")
    val phone = icon("M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3zm-2 20h-4v-1h4v1zm3.25-3H6.75V4h10.5v14z")
    val auto = icon("M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z")
    val selectAll = icon("M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z")
    val download = icon("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    val downloaded = icon("M5 18h14v2H5v-2zm4.6-2.7L5 10.7l2-1.9 2.6 2.6L17 4l2 2-9.4 9.3z")
    val history = icon("M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")
    val language = icon("M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2 0 .68.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2 0-.68.07-1.35.16-2h4.68c.09.65.16 1.32.16 2 0 .68-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2 0-.68-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z")
    val radio = icon("M3.24 6.15C2.51 6.43 2 7.17 2 8v12c0 1.1.89 2 2 2h16c1.11 0 2-.9 2-2V8c0-1.11-.89-2-2-2H8.3l8.26-3.34L15.88 1 3.24 6.15zM7 20c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm13-8h-2v-2h-2v2H4V8h16v4z")
    val timer = icon("M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z")
    val mic = icon("M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z")
    val info = icon("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z")
}

// ---- liquid glass: chrome that floats over content (dock, mini player, headers, player controls) bends and blurs
// what scrolls under it. content draws into a layer (Modifier.layerBackdrop) and the glass, a sibling drawn on top,
// samples that layer: never its own parent's, which would draw the glass into itself. glass is for chrome only, never
// for content. blur needs Android 12, refraction Android 13; below that the glass is a near-opaque tinted surface.

/** Draws this content into [backdrop] for the glass over it; with glass off, nothing: the layer costs every frame. */
fun Modifier.glassSource(backdrop: LayerBackdrop) = if (Musike.glass) layerBackdrop(backdrop) else this

/** What the glass in this part of the screen refracts: the content under it. */
val LocalGlass = compositionLocalOf<Backdrop?> { null }

/** A glass surface of [shape] over [backdrop]; a plain [solid] surface without one, or with glass turned off. */
@Composable
fun Modifier.glass(shape: Shape, solid: Color = MaterialTheme.colorScheme.surfaceContainerHighest, backdrop: Backdrop? = LocalGlass.current, lens: Boolean = true): Modifier {
    if (backdrop == null || !Musike.glass) return clip(shape).background(solid)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // a veil of the surface colour keeps text readable over any artwork; with no blur to soften what's under it
    // (Android 11 and older) it's nearly opaque
    val veil = solid.copy(alpha = if (Build.VERSION.SDK_INT < 31) 0.94f else if (dark) 0.45f else 0.55f)
    return drawBackdrop(
        backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1.25f)
            blur(10.dp.toPx())
            if (lens) lens(14.dp.toPx(), 26.dp.toPx(), depthEffect = true)
        },
        // a faint hairline catches the light at the edge; depth comes from a soft shadow, not a glow
        highlight = { Highlight.Ambient.copy(width = 0.75.dp, alpha = if (dark) 0.4f else 0.7f) },
        shadow = { Shadow(radius = 20.dp, color = Color.Black.copy(alpha = if (dark) 0.35f else 0.12f)) },
        onDrawSurface = { drawRect(veil) },
    )
}

/** Where a list on this page plays from (an album's title, "Favorites"…): the player shows it over the song. */
val LocalPlayingFrom = compositionLocalOf { "" }

/** Room the floating mini player and dock take at the bottom, and the glass header at the top: lists pad by them so
 *  nothing starts hidden, and scroll under them. */
val LocalBottomSpace = compositionLocalOf { 0.dp }
val LocalTopSpace = compositionLocalOf { 0.dp }

@Composable
fun listPadding(horizontal: Dp = 0.dp) = PaddingValues(start = horizontal, end = horizontal, top = LocalTopSpace.current + 4.dp, bottom = LocalBottomSpace.current + 16.dp)

/** A page whose content scrolls under its glass [header]. */
@Composable
fun Page(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    val glass = rememberLayerBackdrop()
    val density = LocalDensity.current
    var top by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassSource(glass).background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalTopSpace provides top, content = content)
        }
        Box(Modifier.onSizeChanged { top = with(density) { it.height.toDp() } }) {
            CompositionLocalProvider(LocalGlass provides glass, content = header)
        }
    }
}

// ---- building blocks

@Composable
fun Cover(url: String, size: Dp, shape: Shape = ArtShape, modifier: Modifier = Modifier) =
    AsyncImage(url.ifEmpty { null }, null, modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentScale = ContentScale.Crop)

private val HeaderShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)

/** The glass pane at the top of a page, under the status bar; the content scrolls under it. */
@Composable
fun HeaderPane(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().glass(HeaderShape, MaterialTheme.colorScheme.surfaceContainer, lens = false).statusBarsPadding().padding(bottom = 12.dp)) {
        CompositionLocalProvider(LocalInHeader provides true) { content() }
    }

/** Inside a page's header: its round buttons are glass too, lenses over what scrolls under it. */
val LocalInHeader = compositionLocalOf { false }

/** The top of every page: a bold headline, a round back button, round actions on the other side, and [bottom] (a
 *  choice of tabs) under them. */
@Composable
fun Header(title: String, subtitle: String? = null, back: Boolean = true, bottom: (@Composable ColumnScope.() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) = HeaderPane {
    Row(
        Modifier.fillMaxWidth().padding(start = if (back) 12.dp else 20.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, str.back, size = 44.dp) { Nav.back() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
    if (bottom != null) Column(Modifier.padding(top = 12.dp), content = bottom)
}

/** A round tonal icon button: the header's back and actions, and the companions of a [PillButton]. */
@Composable
fun RoundButton(icon: ImageVector, description: String?, size: Dp = 52.dp, on: Boolean = false, tint: Color = Color.Unspecified, glass: Boolean = LocalInHeader.current, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, Motion.fade(), label = "round")
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.onSurface
    val surface = if (glass && !on) Modifier.glass(CircleShape) else Modifier.background(bg)
    Box(Modifier.size(size).tap(CircleShape, 0.9f, onClick = onClick).then(surface), Alignment.Center) {
        Icon(icon, description, tint = fg, modifier = Modifier.size(size * 0.46f))
    }
}

/** The main action of a page (Play) as a filled pill. */
@Composable
fun PillButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, height: Dp = 52.dp, onClick: () -> Unit) {
    Row(
        modifier.height(height).tap(PillShape, onClick = onClick).background(MaterialTheme.colorScheme.primary).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Play as a pill, then Shuffle (and Download all, when asked) as round buttons, then [extra]. */
@Composable
fun PlayRow(tracks: List<Track>, download: Boolean = false, extra: @Composable RowScope.() -> Unit = {}) {
    if (tracks.isEmpty()) return
    val from = LocalPlayingFrom.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(str.play, Icons.Default.PlayArrow) { Playback.play(tracks, from = from) }
        RoundButton(Ic.shuffle, str.shuffle) { Playback.play(tracks, shuffled = true, from = from) }
        if (download) {
            val done by Downloads.done.collectAsState()
            val active by Downloads.active.collectAsState()
            val have = done.map { it.id }.toSet()
            when {
                tracks.any { it.id in active } -> Box(Modifier.size(52.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) }
                tracks.all { it.id in have } -> RoundButton(Ic.downloaded, null, tint = MaterialTheme.colorScheme.primary) {}
                else -> RoundButton(Ic.download, str.downloadAll) { Downloads.add(tracks) }
            }
        }
        extra()
    }
}

/** A row of choices joined into one control; the chosen one turns into a full pill. */
@Composable
fun ConnectedButtons(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val outer = 22.dp
            val inner by animateDpAsState(if (on) outer else 6.dp, Motion.move(), label = "corner")
            val shape = RoundedCornerShape(
                topStart = if (i == 0) outer else inner, bottomStart = if (i == 0) outer else inner,
                topEnd = if (i == labels.lastIndex) outer else inner, bottomEnd = if (i == labels.lastIndex) outer else inner,
            )
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.fade(), label = "seg")
            Box(
                Modifier.weight(1f).fillMaxHeight().tap(shape) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(i) }.background(bg),
                Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

/** A pill to pick or open something: a filter, a genre. The chosen one fills with the accent's tint. */
@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.fade(), label = "chip")
    Box(
        Modifier.height(40.dp).tap(PillShape, 0.94f, onClick = onClick).background(bg)
            .border(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, PillShape).padding(horizontal = 18.dp),
        Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onAction) { Text(action, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun Nothing(text: String) = Text(text, Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Loads [load] off the main thread, with a spinner meanwhile and a retry button when it fails. */
@Composable
fun <T> Loader(key: Any, load: () -> T, content: @Composable (T) -> Unit) {
    var attempt by remember(key) { mutableIntStateOf(0) }
    val result by produceState<Result<T>?>(null, key, attempt) {
        value = null
        value = runCatching { withContext(Dispatchers.IO) { load() } }
    }
    val r = result
    when {
        r == null -> Box(Modifier.fillMaxWidth().padding(top = LocalTopSpace.current).padding(48.dp), Alignment.Center) { CircularProgressIndicator(strokeWidth = 4.dp) }
        r.isSuccess -> content(r.getOrThrow())
        else -> Column(Modifier.fillMaxWidth().padding(top = LocalTopSpace.current).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val e = r.exceptionOrNull()
            Nothing(if (e is UnknownHostException) str.offline else e?.message ?: str.playbackFailed)
            PillButton(str.retry, Icons.Default.Refresh) { attempt++ }
        }
    }
}

// ---- grouped rows: related rows read as one rounded surface. the first is rounded on top, the last at the bottom,
// the ones between nearly square, and a thin gap between them stands in for a divider.

enum class Pos { Single, Top, Middle, Bottom }

fun posOf(i: Int, count: Int) = when { count <= 1 -> Pos.Single; i == 0 -> Pos.Top; i == count - 1 -> Pos.Bottom; else -> Pos.Middle }

private val GroupOuter = 24.dp
private val GroupInner = 6.dp
private val GroupGap = 3.dp

fun groupShape(p: Pos) = when (p) {
    Pos.Single -> RoundedCornerShape(GroupOuter)
    Pos.Top -> RoundedCornerShape(topStart = GroupOuter, topEnd = GroupOuter, bottomStart = GroupInner, bottomEnd = GroupInner)
    Pos.Middle -> RoundedCornerShape(GroupInner)
    Pos.Bottom -> RoundedCornerShape(topStart = GroupInner, topEnd = GroupInner, bottomStart = GroupOuter, bottomEnd = GroupOuter)
}

/** Side margins and the gap under a row, by its place in the group. */
fun Modifier.inGroup(p: Pos) = padding(start = 16.dp, end = 16.dp, bottom = if (p == Pos.Top || p == Pos.Middle) GroupGap else 0.dp)

/** One row of a group: the shared surface, shape and feedback. [highlight] tints it (the playing track, the chosen option). */
@Composable
fun GroupCard(
    p: Pos, modifier: Modifier = Modifier, highlight: Boolean = false,
    onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit,
) {
    val bg by animateColorAsState(if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.fade(), label = "row")
    val shape = groupShape(p)
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.tap(shape, 0.98f, onLongClick, onClick) else Modifier.clip(shape))
            .background(bg).heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

@Composable
fun IconBadge(icon: ImageVector, on: Boolean = false, size: Dp = 44.dp) {
    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, Motion.fade(), label = "badge")
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(bg), Alignment.Center) {
        Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size / 2))
    }
}

@Composable
private fun RowScope.Texts(title: String, subtitle: String?, highlight: Boolean = false) = Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A row that opens something or acts: icon badge, title and subtitle, a chevron (or [trailing]). */
@Composable
fun GroupRow(icon: ImageVector, title: String, p: Pos, subtitle: String? = null, modifier: Modifier = Modifier.inGroup(p), trailing: (@Composable () -> Unit)? = {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}, onClick: () -> Unit) = GroupCard(p, modifier, onClick = onClick) {
    IconBadge(icon)
    Texts(title, subtitle)
    trailing?.invoke()
}

/** A row the whole of which flips a switch. */
@Composable
fun GroupToggle(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, p: Pos, onChange: (Boolean) -> Unit) =
    GroupRow(icon, title, p, subtitle, trailing = {
        Switch(checked, onChange, thumbContent = if (checked) { { Icon(Icons.Default.Check, null, Modifier.size(SwitchDefaults.IconSize)) } } else null)
    }) { onChange(!checked) }

/** One of several choices: the chosen one is tinted, with a check. */
@Composable
fun GroupSelect(icon: ImageVector, title: String, selected: Boolean, p: Pos, subtitle: String? = null, onClick: () -> Unit) =
    GroupCard(p, Modifier.inGroup(p), highlight = selected, onClick = onClick) {
        IconBadge(icon, on = selected)
        Texts(title, subtitle)
        if (selected) Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }

/** A track as a group row: artwork (or its number), title and artist with its download state, and its menu. */
@Composable
fun TrackRow(t: Track, list: List<Track>, index: Int, p: Pos, playlistId: String? = null, number: Int? = null) {
    val now = Playback.current?.id == t.id
    val from = LocalPlayingFrom.current
    // a long-press starts picking songs; while picking, a tap picks or drops one
    val picking = Ui.picked.isNotEmpty()
    val picked = t.id in Ui.picked
    GroupCard(p, Modifier.inGroup(p), highlight = now || picked, onLongClick = { Ui.pick(t, list, playlistId) },
        onClick = { if (picking) Ui.pick(t, list, playlistId) else Playback.play(list, index, from = from) }) {
        if (number != null) Text("$number", Modifier.width(32.dp), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
            color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        else Cover(t.thumb, 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (now) FontWeight.Bold else FontWeight.Medium,
                color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val progress = Downloads.active.collectAsState().value[t.id]
                if (progress != null) CircularProgressIndicator({ progress }, Modifier.padding(end = 6.dp).size(12.dp), strokeWidth = 2.dp)
                else if (Downloads.done.collectAsState().value.any { it.id == t.id }) Icon(Ic.downloaded, null, Modifier.padding(end = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(listOfNotNull(t.artist.ifEmpty { null }, t.duration.takeIf { it > 0 }?.let(::clock)).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (picking) Box(Modifier.padding(10.dp).size(28.dp).clip(CircleShape)
            .then(if (picked) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)), Alignment.Center) {
            if (picked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
        else IconButton({ Ui.menu = t to playlistId }) { Icon(Icons.Default.MoreVert, str.more, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

fun LazyListScope.trackRows(tracks: List<Track>, playlistId: String? = null, numbered: Boolean = false, limit: Int = tracks.size) {
    val shown = tracks.take(limit)
    itemsIndexed(shown, key = { i, t -> "$i/${t.id}" }) { i, t ->
        TrackRow(t, tracks, i, posOf(i, shown.size), playlistId, if (numbered) i + 1 else null)
    }
}
