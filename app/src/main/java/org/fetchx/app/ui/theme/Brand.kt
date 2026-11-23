package org.fetchx.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fetchx.app.R

/** Outfit (SIL Open Font License), a rounded geometric sans used for the FetchX wordmark. */
val OutfitFamily = FontFamily(
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

/** "FetchX" with a subtle accent colour on the X. */
@Composable
fun Wordmark(
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append("Fetch") }
            withStyle(SpanStyle(color = accent)) { append("X") }
        },
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
        letterSpacing = (-0.5).sp,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * Launch reveal: the wordmark scales and fades in over the same background colour as the system splash screen,
 * then the whole overlay fades out to show the main screen, which is already composed underneath.
 * It only draws, so it never delays startup.
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val scale = remember { Animatable(0.82f) }
    val reveal = remember { Animatable(0f) }
    val overlay = remember { Animatable(1f) }
    val bg = colorResource(R.color.splash_bg)
    val fg = colorResource(R.color.splash_fg)

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { scale.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing)) }
            launch { reveal.animateTo(1f, tween(durationMillis = 420)) }
        }
        delay(200)
        overlay.animateTo(0f, tween(durationMillis = 300))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlay.value }
            .background(bg)
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = reveal.value
                scaleX = scale.value
                scaleY = scale.value
            },
        ) {
            Wordmark(fontSize = 52.sp, color = fg, accent = MaterialTheme.colorScheme.primary)
            Text(
                text = "by Emtrix",
                fontFamily = OutfitFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 1.6.sp,
                color = fg.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
