package io.github.yudigaga.taplusbridge.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import java.util.function.Consumer
import java.util.function.IntConsumer
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

/** The module's post-drop choices share the settings theme without changing drag input. */
object DragMenuUi {
    @JvmStatic fun showActions(
        activity: ComponentActivity,
        title: String,
        labels: List<String>,
        onSelected: IntConsumer,
    ) = install(activity, title, "选择后立即执行", labels, false, null) { checked ->
        onSelected.accept(checked.indexOfFirst { it })
    }

    @JvmStatic fun showSelection(
        activity: ComponentActivity,
        title: String,
        summary: String,
        labels: List<String>,
        multiple: Boolean,
        executeLabel: String,
        onExecute: Consumer<BooleanArray>,
    ) = install(activity, title, summary, labels, multiple, executeLabel, onExecute)

    private fun install(
        activity: ComponentActivity,
        title: String,
        summary: String,
        labels: List<String>,
        multiple: Boolean,
        executeLabel: String?,
        onExecute: Consumer<BooleanArray>,
    ) {
        activity.setContent {
            SettingsTheme {
                BackHandler { activity.finish() }
                Scaffold(
                    modifier = Modifier.fillMaxSize().testTag("drag.menu"),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    ChoiceDialog(title, summary, labels, multiple, executeLabel, activity::finish, onExecute)
                }
            }
        }
    }
}

@Composable private fun ChoiceDialog(
    title: String,
    summary: String,
    labels: List<String>,
    multiple: Boolean,
    executeLabel: String?,
    cancel: () -> Unit,
    execute: Consumer<BooleanArray>,
) {
    var checked by remember(labels, multiple, executeLabel) {
        mutableStateOf(BooleanArray(labels.size) { multiple || (executeLabel != null && it == 0) })
    }
    val maximumHeight = LocalWindowInfo.current.containerDpSize.height * .45f
    OverlayDialog(show = true, title = title, summary = summary, onDismissRequest = cancel) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsCard {
                if (multiple && labels.size > 1) {
                    CheckboxPreference(
                        title = "全部内容", summary = "已选择 ${checked.count { it }} / ${labels.size} 项",
                        checked = checked.all { it },
                        onCheckedChange = { value -> checked = BooleanArray(labels.size) { value } },
                        modifier = Modifier.testTag("drag.menu.all").semantics {
                            toggleableState = if (checked.all { it }) ToggleableState.On else ToggleableState.Off
                        },
                    )
                }
                Column(Modifier.heightIn(max = maximumHeight).verticalScroll(rememberScrollState()).testTag("drag.menu.list")) {
                    labels.forEachIndexed { index, label ->
                        val itemTitle = label.substringBefore('\n')
                        val itemSummary = label.substringAfter('\n', "")
                        val modifier = Modifier.testTag("drag.menu.item.$index")
                        when {
                            executeLabel == null -> ArrowPreference(
                                title = itemTitle, summary = itemSummary,
                                onClick = { execute.accept(BooleanArray(labels.size) { it == index }) },
                                modifier = modifier,
                            )
                            multiple -> CheckboxPreference(
                                title = itemTitle, summary = itemSummary, checked = checked[index],
                                onCheckedChange = { value -> checked = checked.copyOf().also { it[index] = value } },
                                modifier = modifier.semantics {
                                    toggleableState = if (checked[index]) ToggleableState.On else ToggleableState.Off
                                },
                            )
                            else -> RadioButtonPreference(
                                title = itemTitle, summary = itemSummary, selected = checked[index],
                                onClick = { checked = BooleanArray(labels.size) { it == index } },
                                modifier = modifier.semantics { selected = checked[index] },
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", cancel, Modifier.weight(1f).testTag("drag.menu.cancel"))
                if (executeLabel != null) TextButton(
                    executeLabel, { execute.accept(checked.copyOf()) },
                    Modifier.weight(1f).testTag("drag.menu.execute"), enabled = checked.any { it },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
