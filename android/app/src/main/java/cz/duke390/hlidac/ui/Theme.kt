package cz.duke390.hlidac.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val KtmOrange = Color(0xFFFF6A00)

/** Barvy štítků (pozadí, text) – zvlášť pro světlý a tmavý režim. */
@Immutable
data class BadgeColors(
    val deal: Pair<Color, Color>, val exhaust: Pair<Color, Color>, val qs: Pair<Color, Color>,
    val my: Pair<Color, Color>, val warn: Pair<Color, Color>, val neutral: Pair<Color, Color>,
)

private val LightBadges = BadgeColors(
    deal = Color(0xFFDFF3E7) to Color(0xFF0B7A3E), exhaust = Color(0xFFECE4FD) to Color(0xFF7A3DF0),
    qs = Color(0xFFFDF0D2) to Color(0xFF8A5A00), my = Color(0xFFE0EDFB) to Color(0xFF0A5FB4),
    warn = Color(0xFFFDE3E3) to Color(0xFFAA1111), neutral = Color(0xFFE9E7E2) to Color(0xFF55534E),
)
private val DarkBadges = BadgeColors(
    deal = Color(0xFF173524) to Color(0xFF6FDC9D), exhaust = Color(0xFF2C2246) to Color(0xFFC4A8FF),
    qs = Color(0xFF3A2C10) to Color(0xFFFFD27A), my = Color(0xFF172A40) to Color(0xFF8CC4FF),
    warn = Color(0xFF3D1B1B) to Color(0xFFFF9A9A), neutral = Color(0xFF33312D) to Color(0xFFB8B5AD),
)
val LocalBadges = staticCompositionLocalOf { LightBadges }

@Composable
fun DukeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = KtmOrange)
        else -> lightColorScheme(primary = KtmOrange)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalBadges provides if (dark) DarkBadges else LightBadges) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
