package dev.aether.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.aether.model.theme.ThemeTokens
import dev.aether.theme.AetherThemeCatalog
import dev.aether.theme.ColorKey
import dev.aether.theme.ResolvedTheme
import dev.aether.theme.ThemeResolver

val LocalAetherTokens = compositionLocalOf<ThemeTokens> {
    error("AetherTheme is required")
}

private val resolver = ThemeResolver(AetherThemeCatalog.AETHER_BLACK.tokens)

@Composable
fun AetherTheme(
    resolvedTheme: ResolvedTheme? = null,
    content: @Composable () -> Unit,
) {
    val resolved = resolvedTheme ?: resolver.resolve(
        AetherThemeCatalog.default,
        AetherThemeCatalog.definitions(),
    )
    val tokens = resolved.tokens
    val colors = darkColorScheme(
        primary = Color(resolver.color(resolved, ColorKey.PRIMARY)),
        onPrimary = Color(resolver.color(resolved, ColorKey.ON_PRIMARY)),
        secondary = Color(resolver.color(resolved, ColorKey.MUTED_FOREGROUND)),
        onSecondary = Color(resolver.color(resolved, ColorKey.BACKGROUND)),
        background = Color(resolver.color(resolved, ColorKey.BACKGROUND)),
        onBackground = Color(resolver.color(resolved, ColorKey.FOREGROUND)),
        surface = Color(resolver.color(resolved, ColorKey.SURFACE)),
        onSurface = Color(resolver.color(resolved, ColorKey.FOREGROUND)),
        outline = Color(resolver.color(resolved, ColorKey.OUTLINE)),
    )
    val typography = Typography(
        displayLarge = TextStyle(
            fontSize = tokens.typography.displaySizeSp.sp,
            letterSpacing = tokens.typography.letterSpacingEm.em,
        ),
        titleLarge = TextStyle(
            fontSize = tokens.typography.titleSizeSp.sp,
            letterSpacing = tokens.typography.letterSpacingEm.em,
        ),
        bodyMedium = TextStyle(
            fontSize = tokens.typography.bodySizeSp.sp,
            letterSpacing = tokens.typography.letterSpacingEm.em,
        ),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(tokens.shapes.smallDp),
        small = RoundedCornerShape(tokens.shapes.smallDp),
        medium = RoundedCornerShape(tokens.shapes.mediumDp),
        large = RoundedCornerShape(tokens.shapes.largeDp),
        extraLarge = RoundedCornerShape(tokens.shapes.largeDp),
    )

    CompositionLocalProvider(LocalAetherTokens provides tokens) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
