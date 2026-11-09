package io.github.yudigaga.taplusbridge.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.graphics.createBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object SettingsIcons {
    private fun line(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { draw() }
    }.build()
    val Home = line("Home") { moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f); moveTo(5f, 9f); verticalLineTo(21f); horizontalLineTo(10f); verticalLineTo(15f); horizontalLineTo(14f); verticalLineTo(21f); horizontalLineTo(19f); verticalLineTo(9f) }
    val Rules = line("Rules") { moveTo(8f, 5f); horizontalLineTo(21f); moveTo(8f, 12f); horizontalLineTo(21f); moveTo(8f, 19f); horizontalLineTo(21f); moveTo(3f, 5f); horizontalLineTo(3.1f); moveTo(3f, 12f); horizontalLineTo(3.1f); moveTo(3f, 19f); horizontalLineTo(3.1f) }
    val More = line("More") { moveTo(4f, 4f); horizontalLineTo(9f); verticalLineTo(9f); horizontalLineTo(4f); close(); moveTo(15f, 4f); horizontalLineTo(20f); verticalLineTo(9f); horizontalLineTo(15f); close(); moveTo(4f, 15f); horizontalLineTo(9f); verticalLineTo(20f); horizontalLineTo(4f); close(); moveTo(15f, 15f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(15f); close() }
    val Apps = line("Apps") { moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close(); moveTo(8f, 9f); horizontalLineTo(16f); moveTo(8f, 13f); horizontalLineTo(16f) }
    val Gesture = line("Gesture") { moveTo(8f, 20f); curveTo(6f, 18f, 6f, 15f, 7f, 12f); lineTo(8f, 7f); curveTo(8f, 5f, 11f, 5f, 11f, 7f); lineTo(11f, 12f); lineTo(12f, 4f); curveTo(12f, 2f, 15f, 2f, 15f, 4f); lineTo(15f, 12f); lineTo(16f, 6f); curveTo(16f, 4f, 19f, 4f, 19f, 6f); lineTo(18f, 14f); curveTo(18f, 18f, 15f, 21f, 12f, 21f); close() }
    val Back = line("Back") { moveTo(14f, 5f); lineTo(7f, 12f); lineTo(14f, 19f) }
    val Plus = line("Plus") { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) }
    val Link = line("Link") { moveTo(10f, 7f); lineTo(12f, 5f); curveTo(16f, 1f, 23f, 8f, 19f, 12f); lineTo(16f, 15f); moveTo(14f, 17f); lineTo(12f, 19f); curveTo(8f, 23f, 1f, 16f, 5f, 12f); lineTo(8f, 9f); moveTo(8f, 16f); lineTo(16f, 8f) }
}

@Composable internal fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), cornerRadius = 8.dp, content = content)
}

@Composable internal fun SettingsPageHeader(
    title: String,
    subtitle: String,
    discardTag: String,
    saveTag: String,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    discardText: String = "放弃",
    saveText: String = "保存",
) {
    Column {
        TopAppBar(title = title, subtitle = subtitle)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TextButton(discardText, onDiscard, Modifier.testTag(discardTag))
            TextButton(saveText, onSave, Modifier.testTag(saveTag))
        }
    }
}

@Composable internal fun SectionLabel(title: String) {
    Text(title, Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable internal fun QuietText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 13.sp, lineHeight = 19.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable internal fun Tag(text: String, accent: Boolean = false) {
    Text(text, Modifier.clip(RoundedCornerShape(7.dp))
        .background(if (accent) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.secondaryVariant)
        .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp,
        color = if (accent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontWeight = FontWeight.Medium)
}

@Composable internal fun AppIcon(drawable: Drawable?, modifier: Modifier = Modifier) {
    val bitmap = remember(drawable) {
        drawable?.let {
            val copy = it.constantState?.newDrawable()?.mutate() ?: it.mutate()
            createBitmap(64, 64).also { image ->
                copy.setBounds(0, 0, 64, 64); copy.draw(Canvas(image))
            }.asImageBitmap()
        }
    }
    if (bitmap != null) Image(bitmap, null, modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    else Box(modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
        .background(MiuixTheme.colorScheme.tertiaryContainer).padding(9.dp)) {
        Icon(SettingsIcons.More, null, tint = MiuixTheme.colorScheme.primary)
    }
}

@Composable internal fun AppIcon(controller: SettingsController, app: SettingsController.AppEntry?, modifier: Modifier = Modifier) {
    var drawable by remember(controller, app) { mutableStateOf<Drawable?>(null) }
    DisposableEffect(controller, app) {
        var active = true
        val request = if (app != null) controller.loadAppIcon(app.packageName) { if (active) drawable = it } else null
        onDispose { active = false; request?.cancel(true) }
    }
    AppIcon(drawable, modifier)
}

@Composable internal fun MessageDialog(title: String, message: String, dismiss: () -> Unit) {
    val bodyHeight = LocalWindowInfo.current.containerDpSize.height * .48f
    OverlayDialog(show = true, title = title, onDismissRequest = dismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(message, Modifier.heightIn(max = bodyHeight).verticalScroll(rememberScrollState()).testTag("message.body"), fontSize = 15.sp, lineHeight = 23.sp)
            TextButton("知道了", dismiss, Modifier.fillMaxWidth().testTag("message.close"), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable internal fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    OverlayDialog(show = true, title = title, summary = message, onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", dismiss, Modifier.weight(1f))
            TextButton("确定", confirm, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}
