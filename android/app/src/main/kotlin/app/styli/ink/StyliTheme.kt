package app.styli.ink

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** The colours of `design/tokens.json` that screens use beyond Material's own slots. Mirror the tokens, never invent. */
class StyColors(
    val bg: Color, val surface: Color, val sidebar: Color, val border: Color, val borderStrong: Color,
    val ink: Color, val inkMuted: Color, val honey: Color, val onHoney: Color, val honeyText: Color, val honeyTint: Color,
    val danger: Color,
)

private val light = StyColors(
    Color(0xFFF6F1E7), Color(0xFFFFFDF8), Color(0xFFEDE5D5), Color(0xFFE2D9C6), Color(0xFF857760),
    Color(0xFF1E1B17), Color(0xFF5C5346), Color(0xFFC8902E), Color(0xFF1E1B17), Color(0xFF8A5A12), Color(0x33C8902E),
    Color(0xFFB3261E),
)
private val dark = StyColors(
    Color(0xFF1A1713), Color(0xFF24201B), Color(0xFF201C17), Color(0xFF3A332A), Color(0xFF7A6E5C),
    Color(0xFFF3ECDF), Color(0xFFB8AD99), Color(0xFFE0AE57), Color(0xFF1E1B17), Color(0xFFE0AE57), Color(0x33E0AE57),
    Color(0xFFF2B8B5),
)

val LocalSty = staticCompositionLocalOf { light }

/** Spacing and radius tokens (space-1..7, radius-md, radius-lg). */
object StySize {
    val space1 = 4.dp; val space2 = 8.dp; val space3 = 12.dp; val space4 = 16.dp; val space5 = 24.dp; val space6 = 32.dp
    val radiusMd = 10.dp; val radiusLg = 16.dp
    val touch = 44.dp
}

/** Marcellus is not bundled yet, so headings use the token's fallback (a serif). */
val DisplayFont: FontFamily = FontFamily.Serif

/** Follows the system light or dark setting. */
@Composable
fun StyliTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val c = if (isDark) dark else light
    val scheme = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.honey, onPrimary = c.onHoney, primaryContainer = c.honeyTint, onPrimaryContainer = c.ink,
        secondary = c.honey, onSecondary = c.onHoney, secondaryContainer = c.honeyTint, onSecondaryContainer = c.ink,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.sidebar, onSurfaceVariant = c.inkMuted,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.sidebar,
        error = c.danger, outline = c.borderStrong, outlineVariant = c.border,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalSty provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
