package org.fetchx.app.ui.theme

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fetchx.app.data.Wallpaper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.fetchx.app.data.AppSettings
import org.fetchx.app.data.ThemeMode

private val flatShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF0C0C0C),
    surfaceContainerHighest = Color(0xFF141414),
    surfaceVariant = Color(0xFF141414),
    outlineVariant = Color(0xFF2A2A2A),
)

/** True while a custom wallpaper is showing, so screens leave their backgrounds transparent. */
val LocalWallpaperActive = compositionLocalOf { false }

@Composable
fun FetchXTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    if (dark && settings.amoled) scheme = scheme.pureBlack()
    MaterialTheme(colorScheme = scheme, shapes = flatShapes) {
        val base = MaterialTheme.colorScheme.background
        val wallpaper by produceState<ImageBitmap?>(null, settings.wallpaperVersion) {
            value = if (settings.wallpaperVersion == 0L) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(Wallpaper.file(context).absolutePath)?.asImageBitmap() }.getOrNull()
                }
            }
        }
        CompositionLocalProvider(LocalWallpaperActive provides (wallpaper != null)) {
            Box(Modifier.fillMaxSize().background(base)) {
                wallpaper?.let { bmp ->
                    val alpha = settings.wallpaperAlpha.coerceIn(0, 100) / 100f
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = alpha,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Light scrim that grows with the opacity, so text stays readable on busy images.
                    Box(Modifier.fillMaxSize().background(base.copy(alpha = 0.35f * alpha)))
                }
                content()
            }
        }
    }
}
