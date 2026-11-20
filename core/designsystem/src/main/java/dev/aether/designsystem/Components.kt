package dev.aether.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aether.model.theme.SurfaceStyle

/** One quiet glass-like surface treatment; no blur or gradient overlays. */
@Composable
fun AetherSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalAetherTokens.current
    val alpha = if (tokens.surface.style == SurfaceStyle.LIQUID_GLASS) tokens.surface.alpha else 1f
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = alpha),
                shape = RoundedCornerShape(tokens.shapes.mediumDp),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(tokens.shapes.mediumDp),
            )
            .padding(tokens.dimensions.spacingDp.dp),
        content = content,
    )
}
