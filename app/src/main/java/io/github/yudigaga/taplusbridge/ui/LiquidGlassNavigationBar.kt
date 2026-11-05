package io.github.yudigaga.taplusbridge.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class LiquidGlassNavigationItem(
    val label: String,
    val icon: ImageVector,
    val tag: String,
)

@Composable
internal fun LiquidGlassNavigationBar(
    selected: Int,
    items: List<LiquidGlassNavigationItem>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().height(68.dp)
                .shadow(16.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MiuixTheme.colorScheme.surface.copy(alpha = .92f),
                            MiuixTheme.colorScheme.surfaceVariant.copy(alpha = .82f),
                        )
                    )
                )
                .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = .12f), RoundedCornerShape(24.dp))
                .padding(5.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val cellWidth = maxWidth / items.size
            val dropletX by animateDpAsState(
                targetValue = cellWidth * selected,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = .78f),
                label = "liquid-droplet-x",
            )
            Box(
                Modifier.offset(x = dropletX).width(cellWidth).fillMaxHeight()
                    .padding(horizontal = 3.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MiuixTheme.colorScheme.primaryContainer.copy(alpha = .96f),
                                MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = .76f),
                            )
                        )
                    )
                    .border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = .18f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().fillMaxHeight().background(Color.White.copy(alpha = .04f)))
            }
            Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.SpaceEvenly) {
                items.forEachIndexed { index, item ->
                    val active = selected == index
                    Box(
                        Modifier.width(cellWidth).fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onSelected(index) }
                            .testTag(item.tag)
                            .semantics {
                                this.selected = active
                                contentDescription = item.label
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp),
                                tint = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(item.label, fontSize = 11.sp, color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
    }
}
