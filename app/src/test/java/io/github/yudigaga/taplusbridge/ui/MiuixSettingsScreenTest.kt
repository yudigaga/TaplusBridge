package io.github.yudigaga.taplusbridge.ui

import android.content.Context
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.yudigaga.taplusbridge.config.ConfigStore
import io.github.yudigaga.taplusbridge.config.DragSettings
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.yudigaga.taplusbridge.rules.RuleRepository
import io.github.yudigaga.taplusbridge.thaw.ThawEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Exercises the shipped Activity and Miuix controls with real preferences and package queries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixSettingsScreenTest {
    @Test fun cloudLinkHandoffIsAnIndependentImmediatelySavedBehaviorSwitch() {
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("toggle.cloud_handoff").assertDoesNotExist()
        compose.onNodeWithTag("rules.behavior").performClick()
        compose.onNodeWithTag("toggle.cloud_handoff").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(compose.activity.getSharedPreferences("config", Context.MODE_PRIVATE).getBoolean("cloud_link_handoff", true))
            assertTrue(ConfigStore(compose.activity).nativeEnabled())
        }
        compose.onNodeWithTag("rules.behavior.close").performClick()
        compose.onNodeWithTag("rules.behavior").performClick()
        compose.onNodeWithTag("toggle.cloud_handoff").assertIsOff()
        capture("cloud-handoff-off")
    }

    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            for (name in listOf("config", "status", "capture", RuleRepository.PREFS))
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            val manager = Shadows.shadowOf(context.packageManager)
            val launcher = ComponentName(context.packageName, "io.github.yudigaga.taplusbridge.ui.LauncherAlias")
            manager.addActivityIfNotPresent(launcher).apply {
                targetActivity = MainActivity::class.java.name
                applicationInfo = context.applicationInfo
                enabled = true; exported = true
            }
            context.packageManager.setComponentEnabledSetting(launcher,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            for ((pkg, label) in listOf("tv.danmaku.bili" to "哔哩哔哩", "com.coolapk.market" to "酷安", "com.tencent.mm" to "微信")) {
                manager.installPackage(PackageInfo().apply {
                    packageName = pkg
                    applicationInfo = ApplicationInfo().apply {
                        packageName = pkg; nonLocalizedLabel = label
                        flags = ApplicationInfo.FLAG_INSTALLED; enabled = pkg != "com.coolapk.market"
                    }
                })
            }
        }
        override fun after() { RuntimeEnvironment.setFontScale(1f) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun overviewRulesAndRuntimeHaveOneClearSettingsDirectory() {
        compose.onNodeWithTag("toggle.enabled").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).enabled()) }
        compose.onNodeWithTag("toggle.enabled").performClick()
        for (tag in listOf("toggle.capture", "toggle.lens", "toggle.recognition", "toggle.native", "drag.enabled"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("action.runtime").assertExists()
        compose.onNodeWithTag("action.about").assertExists()
        capture("home-light")
        compose.onNodeWithText("无感识别此卡片").assertDoesNotExist()
        compose.onNodeWithTag("action.generic_target").assertDoesNotExist()
        compose.onNodeWithTag("action.generic_open").assertDoesNotExist()
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("rules.search").assertIsDisplayed()
        compose.onNodeWithText("32 条规则").assertIsDisplayed()
        capture("rules-light")
        compose.onNodeWithTag("rules.search").performTextInput("coolapk")
        compose.onNodeWithTag("rule.row.coolapk.com.com.coolapk.market").assertExists()
        compose.onNodeWithTag("nav.home").performClick()
        openOverview("runtime")
        overviewNode("action.root").assertIsDisplayed()
        compose.onNodeWithText("尚未检查 Root 授权").assertIsDisplayed()
        compose.onNodeWithTag("action.logs").assertDoesNotExist()
        compose.onNodeWithTag("action.icebox").assertDoesNotExist()
        compose.onNodeWithTag("action.capture_status").assertDoesNotExist()
        compose.onNodeWithTag("action.cancel_capture").assertDoesNotExist()
        compose.onNodeWithTag("action.help").assertDoesNotExist()
        compose.onNodeWithText("识别诊断").assertDoesNotExist()
        capture("runtime-light")
        closeOverview()
        openOverview("about")
        overviewNode("action.logs").assertIsDisplayed()
        capture("about-light")
        closeOverview()
    }

    @Test fun fourTopLevelTabsKeepRecognitionAndApplicationsAdjacent() {
        compose.onNodeWithTag("nav.more").assertDoesNotExist()
        compose.onNodeWithTag("nav.gestures").assertDoesNotExist()
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("page.source_scope").assertIsDisplayed()
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("page.drag").assertIsDisplayed()
        compose.onNodeWithTag("page.drag").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("page.source_scope").assertIsDisplayed()
        compose.onNodeWithTag("nav.home").performClick()
        compose.onNodeWithTag("home.source_scope").assertDoesNotExist()
        compose.onNodeWithTag("home.drag").assertDoesNotExist()
    }

    @Test fun gestureDetailsStayHiddenUntilEnabledAndTargetPickerNeedsSelectedMode() {
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("drag.enabled").assertDoesNotExist()
        compose.onNodeWithTag("drag.link_probe").assertDoesNotExist()
        compose.onNodeWithTag("drag.group.recognition").assertExists()
        compose.onNodeWithTag("drag.group.timing").assertDoesNotExist()
        compose.onNodeWithTag("drag.group.directions").assertDoesNotExist()
        compose.onNodeWithTag("drag.group.share").assertDoesNotExist()
        compose.onNodeWithTag("drag.recognitionDelay.value").assertDoesNotExist()
        compose.onNodeWithTag("drag.direction.up").assertDoesNotExist()
        openDragGroup("gesture")
        dragDialogNode("drag.group.timing").assertIsDisplayed()
        compose.onNodeWithTag("drag.group.directions").assertDoesNotExist()
        compose.onNodeWithTag("drag.group.share").assertDoesNotExist()
        dragDialogNode("drag.enabled").assertIsOff().performClick()
        compose.onNodeWithTag("drag.recognitionDelay.value").assertDoesNotExist()
        compose.onNodeWithTag("drag.direction.up").assertDoesNotExist()
        openDragGroup("timing")
        dragDialogNode("drag.recognitionDelay.value").assertIsDisplayed()
        closeDragDialog()
        openDragGroup("directions")
        dragDialogNode("drag.direction.up").assertIsDisplayed()
        closeDragDialog()
        openDragGroup("share")
        compose.onNodeWithTag("drag.share.search").assertDoesNotExist()
        dragDialogNode("drag.share.selected").performClick()
        dragDialogNode("drag.share.search").assertIsDisplayed()
        closeDragDialog()
    }

    @Test fun selectedSourceAppsStayOnMainWhileUnselectedRemainInPicker() {
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.search").assertDoesNotExist()
        compose.onNodeWithTag("source_scope.app.com.coolapk.market").assertDoesNotExist()
        openSourcePicker("com.tencent.mm")
        sourceCandidate("com.tencent.mm").performClick()
        compose.onNodeWithTag("source_scope.candidate.com.tencent.mm").assertDoesNotExist()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).sourceScope().managed) }
        closeSourcePicker()
        scopeNode("source_scope.app.com.tencent.mm").assertIsOn()
        compose.onNodeWithTag("source_scope.app.com.coolapk.market").assertDoesNotExist()
        capture("source-scope-selected-light")
        openSourcePicker("com.")
        compose.onNodeWithTag("source_scope.candidate.com.tencent.mm").assertDoesNotExist()
        sourceCandidate("com.coolapk.market").assertIsOff()
        capture("source-scope-picker-light")
        closeSourcePicker()
        scopeNode("source_scope.app.com.tencent.mm").performClick()
        compose.onNodeWithTag("source_scope.app.com.tencent.mm").assertDoesNotExist()
    }

    @Test fun normalSystemSyncHasNoFrozenModuleOption() {
        openOverview("runtime")
        compose.onNodeWithTag("toggle.independent").assertDoesNotExist()
        overviewNode("action.runtime_sync").assertIsDisplayed()
        compose.onNodeWithText("系统已确认配置，可冻结本体").assertDoesNotExist()
        capture("runtime-sync-light")
        closeOverview()
    }

    @Test fun launcherIconSwitchHidesOnlyTheLauncherAliasAndRestoresIt() {
        val packages = compose.activity.packageManager
        val alias = ComponentName(compose.activity.packageName, "io.github.yudigaga.taplusbridge.ui.LauncherAlias")
        val main = ComponentName(compose.activity, MainActivity::class.java)
        val mainState = packages.getComponentEnabledSetting(main)
        val packageState = packages.getApplicationEnabledSetting(compose.activity.packageName)
        compose.onNodeWithTag("toggle.hide_launcher_icon").assertDoesNotExist()
        openOverview("runtime")
        overviewNode("toggle.hide_launcher_icon").assertIsOff()
        compose.onNodeWithText("隐藏模块应用图标").assertIsDisplayed()
        overviewNode("toggle.hide_launcher_icon").performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packages.getComponentEnabledSetting(alias))
            assertEquals("Hiding the icon must leave MainActivity available to module settings", mainState,
                packages.getComponentEnabledSetting(main))
            assertTrue(packages.getActivityInfo(main, PackageManager.MATCH_DISABLED_COMPONENTS).enabled)
            assertEquals("The module package must stay enabled", packageState,
                packages.getApplicationEnabledSetting(compose.activity.packageName))
            assertTrue(packages.getApplicationInfo(compose.activity.packageName, 0).enabled)
        }
        capture("runtime-launcher-hidden")
        closeOverview()
        openOverview("runtime")
        overviewNode("toggle.hide_launcher_icon").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertTrue(packages.getComponentEnabledSetting(alias) in setOf(
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
            assertTrue(packages.getActivityInfo(alias, 0).enabled)
            assertEquals(mainState, packages.getComponentEnabledSetting(main))
            assertEquals(packageState, packages.getApplicationEnabledSetting(compose.activity.packageName))
        }
        closeOverview()
    }

    @Test fun lensSwitchPersistsAndRemainsIndependentOfLinkRouting() {
        compose.onNodeWithTag("toggle.lens").assertDoesNotExist()
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("images")
        compose.runOnIdle { assertTrue(ConfigStore(compose.activity).lensEnabled()) }
        dragDialogNode("recognition.lens").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertFalse(ConfigStore(compose.activity).lensEnabled())
            assertTrue(ConfigStore(compose.activity).enabled())
        }
        dragDialogNode("recognition.lens").performClick()
        compose.runOnIdle { assertTrue(ConfigStore(compose.activity).lensEnabled()) }
        closeDragDialog()
    }

    @Test fun recognitionControlsHaveOneEditorAndOverviewTracksSavedValuesAcrossPages() {
        compose.onNodeWithTag("toggle.lens").assertDoesNotExist()
        compose.onNodeWithTag("toggle.recognition").assertDoesNotExist()
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("images")
        dragDialogNode("recognition.lens").assertIsOn().performClick()
        dragDialogNode("recognition.screenshot").assertIsOff().performClick()
        closeDragDialog()
        compose.onNodeWithTag("nav.home").performClick()
        overviewSummary("overview.lens").assertTextContains("已关闭", substring = true)
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("recognition")
        dragDialogNode("toggle.recognition").assertIsOn().performClick()
        dragDialogNode("recognition.channel.native").assertIsOn().performClick().assertIsOff()
        dragDialogNode("recognition.channel.assist").assertIsOn().assertIsEnabled()
        dragDialogNode("toggle.recognition").performClick()
        dragDialogNode("recognition.channel.native").assertIsOff()
        dragDialogNode("toggle.recognition").performClick()
        closeDragDialog()
        compose.onNodeWithTag("nav.home").performClick()
        overviewSummary("overview.recognition").assertTextContains("已关闭", substring = true)
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("gesture")
        dragDialogNode("drag.enabled").assertIsOff().performClick()
        closeDragDialog()
        compose.onNodeWithTag("nav.home").performClick()
        overviewSummary("overview.drag").assertTextContains("已关闭", substring = true)
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("drag.save").performClick()
        compose.onNodeWithTag("nav.home").performClick()
        overviewSummary("overview.drag").assertTextContains("已开启", substring = true)
        compose.runOnIdle {
            val config = ConfigStore(compose.activity)
            assertFalse(config.lensEnabled()); assertFalse(config.recognitionEnabled())
            assertTrue(config.scopeCaptureEnabled()); assertTrue(config.getDragSettings().enabled)
            assertFalse(config.recognitionChannels().enabled(io.github.yudigaga.taplusbridge.config.RecognitionChannels.Channel.NATIVE))
        }
    }

    @Test fun chatCardAdapterRequiresVisibleTapPermissionAndDoesNotRewriteItsDependencyChoices() {
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        compose.onNodeWithTag("toggle.capture").assertDoesNotExist()
        dragDialogNode("drag.link_probe").assertIsOff().performClick()
        dragDialogNode("toggle.capture").assertIsOn().assertIsEnabled().performClick().assertIsOff()
        dragDialogNode("drag.link_probe").performClick()
        compose.onNodeWithTag("toggle.capture").assertDoesNotExist()
        closeDragDialog()
        openDragGroup("recognition")
        dragDialogNode("toggle.recognition").performClick()
        closeDragDialog()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").performClick()
        dragDialogNode("toggle.capture").assertIsOff().assertIsNotEnabled().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width - 42f, height / 2f))
        }.assertIsOff()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).captureEnabled()) }
        closeDragDialog()
        openDragGroup("recognition")
        dragDialogNode("toggle.recognition").performClick()
        closeDragDialog()
        compose.onNodeWithTag("nav.home").performClick()
        compose.onNodeWithTag("toggle.enabled").performClick()
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("toggle.capture").assertIsOff().assertIsNotEnabled().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width - 42f, height / 2f))
        }.assertIsOff()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).captureEnabled()) }
        closeDragDialog()
        compose.runOnIdle {
            val config = ConfigStore(compose.activity)
            assertFalse(config.enabled()); assertTrue(config.recognitionEnabled())
            assertFalse(config.captureEnabled()); assertTrue(config.scopeLinkProbeEnabled())
            assertTrue(config.lensEnabled())
        }
    }

    @Test fun ruleOpeningBehaviorSavesImmediatelyAndManagementToolsStayCollected() {
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("toggle.native").assertDoesNotExist()
        compose.onNodeWithTag("rules.import").assertDoesNotExist()
        compose.onNodeWithTag("rules.behavior").performClick()
        compose.onNodeWithTag("toggle.native").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).nativeEnabled()) }
        compose.onNodeWithTag("rules.behavior.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rules.manage").performClick()
        compose.onNodeWithTag("rules.online").assertExists()
        compose.onNodeWithTag("rules.import").assertExists()
        compose.onNodeWithTag("rules.export").assertExists()
        compose.onNodeWithTag("rules.clear").assertExists()
        compose.onNodeWithTag("rules.manage.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rules.behavior").performClick()
        compose.onNodeWithTag("toggle.native").assertIsOff()
        compose.onNodeWithTag("rules.behavior.close").performClick()
    }

    @Test fun dragDraftCanBeCancelledAndSavedWithFourIndependentActions() {
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("drag.save").assertTextContains("保存手势")
        compose.onNodeWithTag("drag.back").assertTextContains("放弃手势")
        openDragGroup("gesture")
        dragDialogNode("drag.enabled").assertIsOff().performClick()
        closeDragDialog()
        openDragGroup("directions")
        dragDialogNode("drag.direction.up").performClick()
        dragDialogNode("drag.action.rules").performClick()
        closeDragDialog()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).getDragSettings().enabled) }
        compose.onNodeWithTag("drag.back").performClick()
        openDragGroup("gesture")
        dragDialogNode("drag.enabled").assertIsOff().performClick()
        closeDragDialog()
        openDragGroup("directions")
        dragDialogNode("drag.direction.up").performClick()
        dragDialogNode("drag.action.rules").performClick()
        closeDragDialog()
        openDragGroup("timing")
        dragDialogNode("drag.recognition_hint").assertTextContains("提前松手留给系统长按菜单", substring = true)
        dragDialogNode("drag.recognitionDelay.up").performClick()
        dragDialogNode("drag.dragDelay.up").performClick()
        closeDragDialog()
        capture("drag-light")
        compose.onNodeWithTag("drag.save").performClick()
        compose.runOnIdle {
            val saved = ConfigStore(compose.activity).getDragSettings()
            assertTrue(saved.enabled); assertEquals("rules", saved.up)
            assertEquals("share", saved.down); assertEquals("lens", saved.left); assertEquals("translate", saved.right)
            assertEquals(2_050, saved.recognitionDelayMillis); assertEquals(2_050, saved.dragDelayMillis)
        }
    }

    @Test fun dragShareSelectionCanBeReorderedAndAnEmptySelectedMenuIsExplicit() {
        compose.runOnIdle {
            ConfigStore(compose.activity).setDragSettings(DragSettings(true, "portal", "share", "lens", "translate", true,
                listOf("com.example.first/com.example.first.Share", "com.example.second/com.example.second.Share")))
        }
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("share")
        dragDialogNode("drag.share.down.com.example.first/com.example.first.Share").performClick()
        capture("drag-share-light")
        closeDragDialog()
        compose.onNodeWithTag("drag.save").performClick()
        compose.runOnIdle {
            val targets = ConfigStore(compose.activity).getDragSettings().shareTargets
            assertEquals(listOf("com.example.second/com.example.second.Share", "com.example.first/com.example.first.Share"), targets)
        }
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("share")
        for (component in listOf("com.example.first/com.example.first.Share", "com.example.second/com.example.second.Share")) {
            dragDialogNode("drag.share.target.$component").performClick()
        }
        dragDialogNode("drag.share.empty").assertIsDisplayed()
        closeDragDialog()
        compose.onNodeWithTag("drag.save").performClick()
        compose.runOnIdle {
            assertTrue(ConfigStore(compose.activity).getDragSettings().shareOnlySelected)
            assertTrue(ConfigStore(compose.activity).getDragSettings().shareTargets.isEmpty())
        }
    }

    @Test fun groupedGestureDialogsKeepDraftsAndReturnToDirections() {
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("drag.enabled").assertDoesNotExist()
        compose.onNodeWithTag("drag.link_probe").assertDoesNotExist()
        for (group in listOf("recognition", "images", "probe", "gesture"))
            compose.onNodeWithTag("drag.list").performScrollToNode(hasTestTag("drag.group.$group"))
        capture("main-light")
        openDragGroup("recognition")
        dragDialogNode("recognition.channel.native").assertIsOn()
        capture("dialog-recognition")
        closeDragDialog()
        openDragGroup("images")
        dragDialogNode("recognition.lens").assertIsOn()
        capture("dialog-images")
        closeDragDialog()
        openDragGroup("gesture")
        dragDialogNode("drag.group.timing").assertIsDisplayed()
        dragDialogNode("drag.enabled").assertIsOff().performClick()
        closeDragDialog()
        openDragGroup("timing")
        dragDialogNode("drag.recognitionDelay.up").performClick()
        capture("dialog-timing")
        closeDragDialog()
        compose.runOnIdle {
            assertFalse(ConfigStore(compose.activity).getDragSettings().enabled)
            assertEquals(2_000, ConfigStore(compose.activity).getDragSettings().recognitionDelayMillis)
        }
        openDragGroup("timing")
        dragDialogNode("drag.recognitionDelay.value").assertTextContains("2050", substring = true)
        closeDragDialog()
        openDragGroup("directions")
        dragDialogNode("drag.direction.up").performClick()
        dragDialogNode("drag.action.rules").assertExists()
        compose.onNodeWithTag("drag.direction.back").assertIsDisplayed().performClick()
        dragDialogNode("drag.direction.up").performClick()
        dragDialogNode("drag.action.rules").performClick()
        dragDialogNode("drag.direction.up").assertTextContains("按模块规则", substring = true)
        compose.onAllNodesWithTag("drag.dialog.list").assertCountEquals(1)
        capture("dialog-directions")
        closeDragDialog()
        openDragGroup("share")
        dragDialogNode("drag.share.selected").performClick()
        dragDialogNode("drag.share.empty").assertIsDisplayed()
        capture("dialog-share")
        closeDragDialog()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).getDragSettings().shareOnlySelected) }
        openDragGroup("share")
        dragDialogNode("drag.share.selected").assertIsSelected()
        closeDragDialog()
        compose.onNodeWithTag("drag.back").performClick()
        openDragGroup("gesture")
        dragDialogNode("drag.enabled").assertIsOff().performClick()
        closeDragDialog()
        openDragGroup("directions")
        dragDialogNode("drag.direction.up").performClick()
        pressBack()
        dragDialogNode("drag.direction.up").assertIsDisplayed()
        compose.onNodeWithTag("drag.action.rules").assertDoesNotExist()
        pressBack()
        dragDialogNode("drag.enabled").assertIsOn()
        compose.onNodeWithTag("drag.group.directions").assertExists()
        pressBack()
        compose.onNodeWithTag("drag.dialog.list").assertDoesNotExist()
        compose.onNodeWithTag("page.drag").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("page.home").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    fun compactDarkLargeGestureDialogsKeepScrollingAndFooterUsable() {
        compose.runOnIdle { RuntimeEnvironment.setFontScale(1.5f) }
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("gesture")
        dragDialogNode("drag.enabled").performClick()
        closeDragDialog()
        openDragGroup("timing")
        dragDialogNode("drag.dragDelay.up").assertIsDisplayed().performClick()
        dragDialogNode("drag.recognition_hint").assertIsDisplayed()
        compose.onNodeWithTag("drag.dialog.close").assertIsDisplayed()
        closeDragDialog()

        openDragGroup("directions")
        dragDialogNode("drag.direction.right").performClick()
        dragDialogNode("drag.action.none").assertIsDisplayed()
        compose.onNodeWithTag("drag.direction.back").assertIsDisplayed().performClick()
        dragDialogNode("drag.direction.right").assertIsDisplayed()
        closeDragDialog()

        openDragGroup("images")
        dragDialogNode("recognition.lens").assertIsDisplayed()
        compose.onNodeWithTag("drag.dialog.close").assertIsDisplayed()
        capture("dark-large")
        closeDragDialog()
        openDragGroup("recognition")
        dragDialogNode("recognition.channel.native").assertIsDisplayed()
        closeDragDialog()

        openDragGroup("share")
        dragDialogNode("drag.share.selected").performClick()
        dragDialogNode("drag.share.search").assertIsDisplayed()
        compose.onNodeWithTag("drag.dialog.close").assertIsDisplayed()
        closeDragDialog()
        compose.onNodeWithTag("drag.save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val saved = ConfigStore(compose.activity).getDragSettings()
            assertTrue(saved.enabled); assertEquals(2_050, saved.dragDelayMillis)
        }
    }

    @Test fun sourceScopeDraftCanBeCancelledWithoutTakingOverTheHostSettings() {
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.hint").assertTextContains("保存后接管传送门的应用范围", substring = true)
        scopeNode("source_scope.mode.WHITELIST").performClick()
        openSourcePicker("微信")
        sourceCandidate("com.tencent.mm").performClick()
        closeSourcePicker()
        scopeNode("source_scope.app.com.tencent.mm").assertIsOn()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).sourceScope().managed) }
        compose.onNodeWithTag("source_scope.back").performClick()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).sourceScope().managed) }
        compose.onNodeWithTag("nav.apps").performClick()
        scopeNode("source_scope.mode.BLACKLIST").assertIsSelected()
        scopeNode("source_scope.selection").assertTextContains("已选 0 个应用", substring = true)
    }

    @Test fun sourceScopeModesKeepSeparateSearchableDraftsAndSaveBothLists() {
        compose.onNodeWithTag("nav.apps").performClick()
        openSourcePicker("com.tencent.mm")
        sourceCandidate("com.tencent.mm").performClick()
        closeSourcePicker()
        scopeNode("source_scope.mode.WHITELIST").performClick()
        openSourcePicker("哔哩")
        sourceCandidate("tv.danmaku.bili").performClick()
        closeSourcePicker()
        scopeNode("source_scope.mode.BLACKLIST").performClick()
        scopeNode("source_scope.app.com.tencent.mm").assertIsOn()
        compose.onNodeWithTag("source_scope.app.tv.danmaku.bili").assertDoesNotExist()
        scopeNode("source_scope.mode.WHITELIST").performClick()
        scopeNode("source_scope.app.tv.danmaku.bili").assertIsOn()
        capture("source-scope-light")
        compose.onNodeWithTag("source_scope.save").performClick()
        scopeNode("source_scope.hint").assertTextContains("修改暂存", substring = true)
        compose.runOnIdle {
            val scope = ConfigStore(compose.activity).sourceScope()
            assertTrue(scope.managed); assertEquals(SourceScopePolicy.Mode.WHITELIST, scope.mode)
            assertEquals(setOf("com.tencent.mm"), scope.blacklist)
            assertEquals(setOf("tv.danmaku.bili"), scope.whitelist)
        }
        compose.onNodeWithTag("nav.home").performClick()
        compose.onNodeWithText("白名单 · 仅 1 个应用生效").assertExists()
        compose.onNodeWithTag("nav.apps").performClick()
        scopeNode("source_scope.mode.WHITELIST").assertIsSelected()
        scopeNode("source_scope.app.tv.danmaku.bili").assertIsOn()
    }

    @Test fun sourceScopeSuccessfulSaveRefreshesManagedHintAndClearsRepairedConfigurationError() {
        compose.runOnIdle {
            compose.activity.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString(SourceScopePolicy.KEY, "broken scope configuration").commit()
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav.apps").performClick()
        scopeNode("source_scope.configuration_error").assertTextContains("配置损坏", substring = true)
        compose.onNodeWithTag("source_scope.save").performClick()
        compose.onNodeWithTag("source_scope.configuration_error").assertDoesNotExist()
        scopeNode("source_scope.hint").assertTextContains("修改暂存", substring = true)
        compose.onNodeWithTag("source_scope.save").assertTextContains("保存名单")
        compose.onNodeWithTag("source_scope.back").assertTextContains("放弃名单")
        compose.runOnIdle { assertTrue(ConfigStore(compose.activity).sourceScope().managed) }
    }

    @Test fun allRecognitionSourcesAreIndependentImmediateSwitchesEvenWithDragDisabled() {
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).getDragSettings().enabled) }
        openDragGroup("recognition")
        for (channel in listOf("native", "assist", "ocr", "server", "segment", "clipboard")) {
            dragDialogNode("recognition.channel.$channel").assertIsOn().performClick().assertIsOff()
            compose.runOnIdle {
                val raw = ConfigStore(compose.activity).let { compose.activity.getSharedPreferences("config", 0).getString("recognition_channels", null) }
                assertFalse(org.json.JSONObject(raw!!).getBoolean(channel))
            }
        }
        capture("recognition-channels-light")
        closeDragDialog()
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.capture_fallback").assertDoesNotExist()
        compose.onNodeWithTag("source_scope.save").performClick()
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("drag.back").performClick()
        compose.onNodeWithTag("drag.save").performClick()
        compose.runOnIdle {
            val raw = compose.activity.getSharedPreferences("config", 0).getString("recognition_channels", null)
            assertTrue(org.json.JSONObject(raw!!).keys().asSequence().all { !org.json.JSONObject(raw).getBoolean(it) })
        }
    }

    @Test fun screenshotFallbackSavesImmediatelyAndAppDraftCannotOverwriteIt() {
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.capture_fallback").assertDoesNotExist()
        compose.onNodeWithTag("source_scope.mode.WHITELIST").performClick()
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("images")
        dragDialogNode("recognition.screenshot").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(ConfigStore(compose.activity).scopeCaptureEnabled()) }
        closeDragDialog()
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.save").performClick()
        compose.runOnIdle {
            assertTrue(ConfigStore(compose.activity).scopeCaptureEnabled())
            assertEquals(SourceScopePolicy.Mode.WHITELIST, ConfigStore(compose.activity).sourceScope().mode)
        }
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("images")
        dragDialogNode("recognition.screenshot").assertIsOn().performClick().assertIsOff()
        closeDragDialog()
        compose.onNodeWithTag("drag.back").performClick()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).scopeCaptureEnabled()) }
    }

    @Test fun savedSupplementalTapShowsWhetherTheSystemHasConfirmedItsConfiguration() {
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").performClick()
        compose.runOnIdle { assertTrue(ConfigStore(compose.activity).scopeLinkProbeEnabled()) }
        closeDragDialog()
        compose.onNodeWithTag("drag.list").performScrollToNode(hasTestTag("drag.runtime_status"))
        compose.onNodeWithTag("drag.runtime_status").assertTextContains("系统", substring = true)
        compose.onNodeWithTag("drag.save").performClick()
    }

    @Test fun supplementalTapSaveErrorsRemainVisibleWhenDraggingIsDisabled() {
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").performClick()
        closeDragDialog()
        compose.runOnIdle {
            org.robolectric.util.ReflectionHelpers.getField<SettingsController>(compose.activity, "settings").close()
        }
        compose.onNodeWithTag("drag.save").performClick()
        compose.onNodeWithTag("drag.error").assertIsDisplayed().assertTextEquals("页面已关闭")
        compose.runOnIdle {
            assertFalse(ConfigStore(compose.activity).getDragSettings().enabled)
            assertTrue(ConfigStore(compose.activity).scopeLinkProbeEnabled())
        }
    }

    @Test fun turningTapOffPersistsImmediatelyWithoutSaveAndSurvivesNavigationAndRecreation() {
        compose.runOnIdle { ConfigStore(compose.activity).setScopeLinkProbeEnabled(true) }
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse("The displayed OFF state must already be durable", ConfigStore(compose.activity).scopeLinkProbeEnabled()) }
        closeDragDialog()
        compose.onNodeWithTag("drag.back").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").assertIsOff()
        closeDragDialog()
        compose.onNodeWithTag("drag.save").performClick()
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.save").performClick()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).scopeLinkProbeEnabled()) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").assertIsOff()
        closeDragDialog()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).scopeLinkProbeEnabled()) }
    }

    @Test fun tappingTheActualSwitchThumbCommitsExactlyOneToggle() {
        compose.runOnIdle { ConfigStore(compose.activity).setScopeLinkProbeEnabled(true) }
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width - 40f, height / 2f))
        }
        compose.onNodeWithTag("drag.link_probe").assertIsOff()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).scopeLinkProbeEnabled()) }
        closeDragDialog()
    }

    @Test fun supplementalTapLivesInRecognitionAndApplicationDraftCannotOverwriteIt() {
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.link_probe").assertDoesNotExist()
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(ConfigStore(compose.activity).scopeLinkProbeEnabled()) }
        closeDragDialog()
        compose.onNodeWithTag("drag.back").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").assertIsOn()
        closeDragDialog()
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.save").performClick()
        compose.onNodeWithTag("nav.recognition").performClick()
        openDragGroup("probe")
        dragDialogNode("drag.link_probe").assertIsOn().performClick().assertIsOff()
        closeDragDialog()
        compose.onNodeWithTag("drag.save").performClick()
        compose.runOnIdle { assertFalse(ConfigStore(compose.activity).scopeLinkProbeEnabled()) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    fun sourceScopeRetainsMissingSelectionsAndLetsUsersRemoveThemAtLargeText() {
        compose.runOnIdle {
            RuntimeEnvironment.setFontScale(1.5f)
            ConfigStore(compose.activity).saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,
                listOf("com.example.missing", "com.example.keep"), listOf("com.tencent.mm"))
        }
        compose.onNodeWithTag("nav.apps").performClick()
        compose.onNodeWithTag("source_scope.save").assertIsDisplayed()
        assertActionsBelowTitle("生效应用", "source_scope.save")
        compose.onNodeWithTag("source_scope.search").assertDoesNotExist()
        scopeNode("source_scope.app.com.example.missing").assertIsOn().performClick()
        compose.onNodeWithTag("source_scope.app.com.example.missing").assertDoesNotExist()
        scopeNode("source_scope.app.com.example.keep").assertIsOn()
        capture("source-scope-dark-large")
        openSourcePicker("微信")
        sourceCandidate("com.tencent.mm").assertIsOff()
        compose.onNodeWithTag("source_scope.picker.close").assertIsDisplayed()
        capture("source-scope-picker-dark-large")
        closeSourcePicker()
        compose.onNodeWithTag("source_scope.save").performClick()
        compose.runOnIdle {
            val scope = ConfigStore(compose.activity).sourceScope()
            assertEquals(setOf("com.example.keep"), scope.blacklist)
            assertEquals(setOf("com.tencent.mm"), scope.whitelist)
        }
    }

    @Test fun ruleEditorSavesSourceAndUsesSameMatcherAsOpening() {
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("rules.add").performClick()
        compose.onNodeWithTag("editor.pick_target").performClick()
        compose.onNodeWithTag("picker.search").performTextInput("coolapk")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("picker.com.coolapk.market").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("picker.com.coolapk.market").performClick()
        compose.onNodeWithTag("editor.host").performTextInput("https://example.com/share/1")
        compose.onNodeWithTag("editor.advanced").performScrollTo().performClick()
        compose.onNodeWithTag("editor.source").performScrollTo().performTextReplacement("com.tencent.mm")
        compose.onNodeWithTag("editor.save").performClick()
        compose.onNodeWithTag("page.rules").assertExists()
        compose.runOnIdle {
            val entry = RuleRepository(compose.activity).manualRules().single()
            assertEquals("example.com", entry.host)
            assertEquals("com.tencent.mm", entry.sourcePackage); assertEquals("none", entry.urlConversion)
        }
        compose.onNodeWithTag("rules.preview").performClick()
        compose.onNodeWithTag("preview.url").performTextInput("https://example.com/share/2")
        compose.onNodeWithTag("preview.source").performTextInput("com.tencent.mm")
        compose.onNodeWithTag("preview.check").performScrollTo().performClick()
        compose.onNodeWithTag("preview.result").assertTextContains("酷安", substring = true)
        capture("preview-light")
    }

    @Test fun unfinishedRuleHostSurvivesActivityStateRestorationWithoutSavingARule() {
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("rules.add").performClick()
        compose.onNodeWithTag("editor.host").performTextInput("https://unsaved.example/private-draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("page.rule_editor").assertExists()
        compose.onNodeWithTag("editor.host").assertTextContains("https://unsaved.example/private-draft")
        compose.runOnIdle { assertTrue(RuleRepository(compose.activity).manualRules().isEmpty()) }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    fun narrowDarkLargeTextRetainsEditorAndLogActions() {
        compose.runOnIdle { RuntimeEnvironment.setFontScale(1.5f) }
        compose.waitForIdle()
        capture("home-dark-large")
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("rules.add").performClick()
        compose.onNodeWithTag("editor.save").assertIsDisplayed()
        compose.onNodeWithTag("editor.host").assertIsDisplayed()
        capture("editor-dark-large")
        compose.onNodeWithTag("editor.pick_target").performScrollTo().performClick()
        compose.onNodeWithTag("picker.search").assertIsDisplayed().performTextInput("coolapk")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("picker.com.coolapk.market").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("picker.com.coolapk.market").performClick()
        compose.onNodeWithText("酷安").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("nav.home").performClick()
        openOverview("about")
        overviewNode("action.logs").performClick()
        compose.onNodeWithTag("message.close").assertIsDisplayed()
        compose.onNodeWithTag("message.body").assertTextContains("LSPosed", substring = true)
        capture("logs-dark-large")
        compose.onNodeWithTag("message.close").performClick()
        overviewNode("action.logs").assertIsDisplayed()
        closeOverview()
    }

    @Test fun thawChannelsContainOnlyRootAndShizukuAndRefreshTheAuthorizationPage() {
        openOverview("runtime")
        overviewNode("mode.ROOT").assertIsDisplayed()
        overviewNode("mode.SHIZUKU").assertIsDisplayed()
        compose.onNodeWithTag("mode.NONE").assertDoesNotExist()
        compose.onNodeWithTag("mode.ICEBOX").assertDoesNotExist()
        overviewNode("mode.SHIZUKU").performClick()
        compose.runOnIdle { assertEquals(ThawEngine.Mode.SHIZUKU, ConfigStore(compose.activity).mode()) }
        overviewNode("action.shizuku").assertIsDisplayed()
        compose.onNodeWithTag("action.root").assertDoesNotExist()
        compose.onNodeWithTag("action.logs").assertDoesNotExist()
        capture("shizuku-light")
        closeOverview()
    }

    @Test fun editingAWebsiteKeepsExistingSourceAndNoteWithoutAConversionOption() {
        compose.runOnIdle {
            RuleRepository(compose.activity).saveManualRule(-1, "com.tencent.mm", "example.com", "com.coolapk.market", "fixed_deep_link", "保留备注")
        }
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("rules.search").performTextInput("example.com")
        compose.onNodeWithTag("rule.row.example.com.com.coolapk.market").performScrollTo().performClick()
        compose.onNodeWithTag("editor.conversion").assertDoesNotExist()
        compose.onNodeWithTag("editor.host").performTextReplacement("https://example.com/new-path?share=1")
        compose.onNodeWithTag("editor.save").performClick()
        compose.runOnIdle {
            val entry = RuleRepository(compose.activity).manualRules().single()
            assertEquals("com.tencent.mm", entry.sourcePackage); assertEquals("保留备注", entry.note)
            assertEquals("none", entry.urlConversion); assertEquals("example.com", entry.host)
        }
    }

    @Test fun onlineManagerKeepsCachedRevisionAndCountAfterDisablingUpdatesAndReopening() {
        compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("rules.manage").performClick()
        compose.onNodeWithTag("rules.online").performClick()
        compose.onNodeWithTag("online.cache").assertTextContains("缓存修订：r1", substring = true)
        compose.onNodeWithTag("online.cache").assertTextContains("缓存规则：32 条", substring = true)
        compose.onNodeWithTag("online.enabled").performScrollTo().performClick()
        compose.onNodeWithTag("online.enabled").performClick()
        compose.onNodeWithTag("online.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rules.manage.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav.home").performClick(); compose.onNodeWithTag("nav.rules").performClick()
        compose.onNodeWithTag("rules.manage").performClick()
        compose.onNodeWithTag("rules.online").performClick()
        compose.onNodeWithTag("online.cache").assertTextContains("缓存修订：r1", substring = true)
        compose.onNodeWithTag("online.cache").assertTextContains("缓存规则：32 条", substring = true)
        compose.runOnIdle {
            val repository = RuleRepository(compose.activity)
            assertFalse(repository.onlineState().enabled); assertEquals(32, repository.onlineRules().size)
        }
        capture("online-cache-light")
        compose.onNodeWithTag("online.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rules.manage.close").assertIsDisplayed().performClick()
    }

    private var dragGroup: String? = null

    private fun openDragGroup(group: String) {
        if (group in listOf("timing", "directions", "share")) {
            if (compose.onAllNodesWithTag("drag.dialog.list").fetchSemanticsNodes().isEmpty()) openDragGroup("gesture")
            dragDialogNode("drag.group.$group").performClick()
        } else {
            compose.onNodeWithTag("drag.list").performScrollToNode(hasTestTag("drag.group.$group"))
            compose.onNodeWithTag("drag.group.$group").performClick()
        }
        dragGroup = group
        compose.onAllNodesWithTag("drag.dialog.list").assertCountEquals(1)
        compose.onNodeWithTag("drag.dialog.close").assertIsDisplayed()
    }

    private fun dragDialogNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("drag.dialog.list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun closeDragDialog() {
        compose.onNodeWithTag("drag.dialog.close").assertIsDisplayed().performClick()
        if (dragGroup in listOf("timing", "directions", "share")) {
            dragDialogNode("drag.enabled").assertExists()
            compose.onNodeWithTag("drag.dialog.close").assertIsDisplayed().performClick()
        }
        compose.onNodeWithTag("drag.dialog.list").assertDoesNotExist()
        dragGroup = null
    }

    private fun openOverview(section: String) {
        compose.onNodeWithTag("nav.home").performClick()
        compose.onNodeWithTag("overview.list").performScrollToNode(hasTestTag("action.$section"))
        compose.onNodeWithTag("action.$section").performClick()
        compose.onNodeWithTag("overview.dialog.close").assertIsDisplayed()
    }

    private fun overviewNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("overview.dialog.list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun overviewSummary(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("overview.list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun closeOverview() {
        compose.onNodeWithTag("overview.dialog.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("overview.dialog.list").assertDoesNotExist()
    }

    private fun pressBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun openSourcePicker(query: String) {
        scopeNode("source_scope.add").performClick()
        compose.onNodeWithTag("source_scope.search").assertIsDisplayed().performTextReplacement(query)
        compose.onNodeWithTag("source_scope.picker.close").assertIsDisplayed()
    }

    private fun sourceCandidate(pkg: String): SemanticsNodeInteraction {
        // Package enumeration runs outside Compose's idling resources.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("source_scope.picker.empty").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("source_scope.picker.list").performScrollToNode(hasTestTag("source_scope.candidate.$pkg"))
        return compose.onNodeWithTag("source_scope.candidate.$pkg")
    }

    private fun closeSourcePicker() {
        compose.onNodeWithTag("source_scope.picker.close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("source_scope.picker.list").assertDoesNotExist()
        compose.onNodeWithTag("source_scope.search").assertDoesNotExist()
    }

    private fun scopeNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("source_scope.list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun assertActionsBelowTitle(title: String, actionTag: String) {
        val titleBottom = compose.onAllNodesWithText(title).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        val actionTop = compose.onNodeWithTag(actionTag).fetchSemanticsNode().boundsInRoot.top
        assertTrue("$actionTag should sit below $title", actionTop >= titleBottom)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) colors.add(bitmap.getPixel(x, y))
            assertTrue("Expected actual Miuix pixels", colors.size > 8)
            val directory = File(System.getProperty("taplus.testArtifacts"), "ui-" + io.github.yudigaga.taplusbridge.BuildConfig.VERSION_NAME.removeSuffix("-dev")).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
