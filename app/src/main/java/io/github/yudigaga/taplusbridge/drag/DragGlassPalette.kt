package io.github.yudigaga.taplusbridge.drag

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** The host overlay uses the same Miuix colors as the module's Compose screens. */
internal data class DragGlassPalette(
    val surface: Int,
    val surfaceBottom: Int,
    val foreground: Int,
    val secondary: Int,
    val accent: Int,
    val accentSoft: Int,
    val selected: Int,
    val outline: Int,
    val scrim: Int,
    val shadow: Int,
) {
    companion object {
        @JvmStatic fun from(context: Context): DragGlassPalette {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val colors = if (dark) darkColorScheme() else lightColorScheme()
            return DragGlassPalette(
                colors.surface.copy(alpha = .94f).toArgb(),
                colors.surfaceVariant.copy(alpha = .88f).toArgb(),
                colors.onSurface.toArgb(),
                colors.onSurfaceVariantSummary.toArgb(),
                colors.primary.toArgb(),
                colors.tertiaryContainer.toArgb(),
                colors.primaryContainer.copy(alpha = .97f).toArgb(),
                colors.onSurface.copy(alpha = .12f).toArgb(),
                Color.Black.copy(alpha = if (dark) .28f else .12f).toArgb(),
                Color.Black.copy(alpha = if (dark) .32f else .16f).toArgb(),
            )
        }
    }
}
