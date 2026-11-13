package io.github.yudigaga.taplusbridge.drag

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.yudigaga.taplusbridge.config.ConfigStore
import io.github.yudigaga.taplusbridge.config.DragSettings
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy
import io.github.yudigaga.taplusbridge.rules.RuleRepository
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner
import io.github.yudigaga.taplusbridge.bridge.RuleReadCounter
import android.content.pm.PackageInfo
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/** Exercises the actual result-token Activity, selection controls and action side effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragChoiceActivityAndroidTest {
    private val magnetOne = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=First"
    private val magnetTwo = "magnet:?xt=urn:btih:1123456789abcdef0123456789abcdef01234567&dn=Second"

    @Test fun severalMagnetsCanBeSelectedTogetherWithoutOpeningOrCopyingBeforeExecution() {
        val activity = show(result("rules", "", "", listOf(magnetOne, magnetTwo)))
        compose.onNodeWithTag("drag.menu.all").assertIsOn()
        compose.onNodeWithTag("drag.menu.item.0").assertIsOn()
        compose.onNodeWithTag("drag.menu.item.1").assertIsOn()
        compose.onNodeWithTag("drag.menu.item.0").performClick().assertIsOff()
        compose.onNodeWithTag("drag.menu.item.1").assertIsOn()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(clipboard.primaryClip)
        assertFalse(activity.isFinishing)
        capture("batch-selection-0.15.38")
    }

    @Test fun unsupportedBatchOffersExplicitCopyOfEverySelectedMagnetWithoutStartingAnything() {
        installTarget("com.example.downloader")
        RuleRepository(context).saveManualRule(-1, "*", "magnet:", "com.example.downloader", "none", "")
        val activity = show(result("rules", "", "", listOf(magnetOne, magnetTwo)))
        compose.onNodeWithTag("drag.menu.execute").performClick()
        compose.onNodeWithText("复制已选链接").assertIsDisplayed().performClick()
        assertEquals("$magnetOne\n$magnetTwo", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test fun browserActionSeparatesDownloadBatchFromOrdinaryWebLinks() {
        val activity = show(result("browser", "", "", listOf(magnetOne, "https://example.com/page", magnetTwo)))
        compose.onNodeWithText("磁力与 ed2k（2 条）").assertIsDisplayed().performClick()
        compose.onNodeWithTag("drag.menu.all").assertIsOn()
        compose.onNodeWithTag("drag.menu.item.1").assertIsOn()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(clipboard.primaryClip)
    }

    @Test fun choosingABrowserStartsTheSingleRecognizedWebLinkOnce() {
        val url = "https://article.example/read/123"
        val targets = listOf("com.example.firstbrowser", "com.example.secondbrowser")
        configureBrowserChoices(url, targets)
        val activity = show(result("browser", "卡片标题", "", listOf(url)))
        compose.onNodeWithText("选择浏览器").assertIsDisplayed()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        compose.onNodeWithTag("drag.menu.item.1").performClick()
        val opened = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(url, opened.dataString)
        assertEquals(targets[1], opened.`package`)
        assertTrue(opened.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test fun aBrowserThatDisappearsWhileChoosingShowsFailureWithoutLaunching() {
        val url = "https://article.example/read/123"
        val targets = listOf("com.example.firstbrowser", "com.example.secondbrowser")
        configureBrowserChoices(url, targets)
        val activity = show(result("browser", "", "", listOf(url)))
        Shadows.shadowOf(context.packageManager).setResolveInfosForIntent(
            browserIntent(url, targets[1]), emptyList(),
        )
        compose.onNodeWithTag("drag.menu.item.1").performClick()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals("浏览器无法打开该链接", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test fun savedBrowserOpensTheRecognizedUrlWithoutAskingAgain() {
        val url = "https://article.example/read/123"
        val targets = listOf("com.example.firstbrowser", "com.example.secondbrowser")
        configureBrowserChoices(url, targets)
        context.getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE).edit()
            .putString("selected_package", targets[1]).commit()
        val activity = show(result("browser", "", "", listOf(url)))
        val opened = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull("The saved browser must run directly", opened)
        assertEquals(targets[1], opened.`package`)
        assertEquals(url, opened.dataString)
        assertTrue(opened.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test fun unavailableSavedBrowserShowsRecoveryWithoutSilentlyLaunchingTheRemainingBrowser() {
        val url = "https://article.example/read/123"
        configureBrowserChoices(url, listOf("com.example.remainingbrowser"))
        context.getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE).edit()
            .putString("selected_package", "com.example.uninstalledbrowser").commit()
        val activity = show(result("browser", "", "", listOf(url)))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals("所选浏览器当前不可用，请在模块内重新选择", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test fun savedBrowserThatCannotHandleTheRecognizedUrlDoesNotUseAnotherBrowser() {
        val url = "https://article.example/read/123"
        val targets = listOf("com.example.firstbrowser", "com.example.secondbrowser")
        configureBrowserChoices(url, targets)
        context.getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE).edit()
            .putString("selected_package", targets[1]).commit()
        Shadows.shadowOf(context.packageManager).setResolveInfosForIntent(browserIntent(url, targets[1]), emptyList())
        val activity = show(result("browser", "", "", listOf(url)))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals("所选浏览器当前不可用，请在模块内重新选择", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test fun choosingOneMagnetOpensItWithoutShowingAnotherLinkPicker() {
        val target = "com.example.downloader"
        installTarget(target)
        RuleRepository(context).saveManualRule(-1, "*", "magnet:", target, "none", "")
        val request = Bundle().apply { putString("url", magnetOne); putString("source", "com.example.source") }
        addViewHandler(LaunchPlanner.plan(context, request)!!.target(), target)
        val activity = show(result("rules", "", "", listOf(magnetOne, magnetTwo)))
        compose.onNodeWithTag("drag.menu.item.1").performClick()
        compose.onNodeWithTag("drag.menu.execute").performClick()
        val opened = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(opened)
        assertEquals(magnetOne, opened.dataString)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test fun confirmingBatchSelectionDispatchesOnceWithoutPreparationOrLoginPrompt() {
        val target = "com.ylmf.androidclient"
        installTarget(target)
        RuleRepository(context).saveManualRule(-1, "*", "magnet:", target, "none", "")
        val request = Bundle().apply {
            putStringArrayList("links", arrayListOf(magnetOne, magnetTwo))
            putString("source", "com.example.source")
        }
        val planned = LaunchPlanner.plan(context, request)!!
        addViewHandler(planned.target(), target)
        val activity = show(result("rules", "", "", listOf(magnetOne, magnetTwo)))
        compose.onNodeWithTag("drag.menu.execute").performClick()
        val opened = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(opened)
        assertEquals(target, opened.`package`)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertTrue(planned.target().filterEquals(opened))
        compose.onNodeWithText("已打开，批量添加 2 条").assertDoesNotExist()
        compose.onNodeWithText("打开${target}并登录").assertDoesNotExist()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(clipboard.primaryClip)
        assertTrue(activity.isFinishing)
    }

    @Test fun batchDispatchNeedsOnlyTheReceiverAndDoesNotProbeOrOpenTheApplicationLauncher() {
        val target = "com.ylmf.androidclient"
        installTarget(target)
        RuleRepository(context).saveManualRule(-1, "*", "magnet:", target, "none", "")
        val request = Bundle().apply {
            putStringArrayList("links", arrayListOf(magnetOne, magnetTwo))
            putString("source", "com.example.source")
        }
        val planned = LaunchPlanner.plan(context, request)!!
        addViewHandler(planned.target(), target)
        assertNull(context.packageManager.getLaunchIntentForPackage(target))
        val activity = show(result("rules", "", "", listOf(magnetOne, magnetTwo)))
        compose.onNodeWithTag("drag.menu.execute").performClick()
        val opened = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(opened)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertTrue(planned.target().filterEquals(opened))
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(clipboard.primaryClip)
        assertTrue(activity.isFinishing)
    }

    @Test fun mixedRuleTargetsAreGroupedInsteadOfSilentlySendingEveryLinkToOneApplication() {
        val first = "com.example.first"
        val second = "com.example.second"
        installTarget(first); installTarget(second)
        val ed2k = "ed2k://|file|Second.iso|123|0123456789abcdef0123456789abcdef|/"
        RuleRepository(context).saveManualRule(-1, "*", "magnet:", first, "none", "")
        RuleRepository(context).saveManualRule(-1, "*", "ed2k:", second, "none", "")
        val activity = show(result("rules", "", "", listOf(magnetOne, magnetTwo, ed2k)))
        compose.onNodeWithTag("drag.menu.execute").performClick()
        compose.onNodeWithText("按规则选择目标组").assertIsDisplayed()
        compose.onNodeWithText("$first（2 条）").assertIsDisplayed()
        compose.onNodeWithText("$second（1 条）").assertIsDisplayed()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(clipboard.primaryClip)
    }

    @Test fun batchGroupingReadsOneRuleSnapshotAndOneSwitchSetForAll64Links() {
        val first = "com.example.first"
        val second = "com.example.second"
        installTarget(first); installTarget(second)
        RuleRepository(context).saveManualRule(-1, "*", "magnet:", first, "none", "")
        RuleRepository(context).saveManualRule(-1, "*", "ed2k:", second, "none", "")
        val links = (0 until 32).flatMap { index -> listOf(
            "$magnetOne&dn=Item$index",
            "ed2k://|file|Item$index.iso|123|0123456789abcdef0123456789abcdef|/",
        ) }
        val activity = show(result("rules", "", "", links))
        RuleReadCounter(context).use { reads ->
            compose.onNodeWithTag("drag.menu.execute").performClick()
            assertEquals(3, reads.ruleSnapshots()) // Constructor state/validation and one shared grouping snapshot.
            assertEquals(2, reads.switchReads("enabled")) // Action authorization plus the grouping snapshot.
            assertEquals(1, reads.switchReads("native"))
            assertEquals(1, reads.switchReads(ConfigStore.CLOUD_LINK_HANDOFF_KEY))
        }
        compose.onNodeWithText("按规则选择目标组").assertIsDisplayed()
        compose.onNodeWithText("$first（32 条）").assertIsDisplayed()
        compose.onNodeWithText("$second（32 条）").assertIsDisplayed()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(clipboard.primaryClip)
    }

    @Test fun changedRulesAreReplannedBeforeAChosenTargetGroupRuns() {
        val first = "com.example.first"
        val second = "com.ylmf.androidclient"
        installTarget(first); installTarget(second)
        val repository = RuleRepository(context)
        repository.saveManualRule(-1, "*", "magnet:", first, "none", "")
        repository.saveManualRule(-1, "*", "ed2k:", second, "none", "")
        val ed2k = "ed2k://|file|Second.iso|123|0123456789abcdef0123456789abcdef|/"
        val activity = show(result("rules", "", "", listOf(magnetOne, magnetTwo, ed2k)))
        compose.onNodeWithTag("drag.menu.execute").performClick()
        repository.saveManualRule(0, "*", "magnet:", second, "none", "")
        compose.onNodeWithText("$first（2 条）").performClick()
        compose.onNodeWithText("无法批量添加").assertIsDisplayed()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertNull(clipboard.primaryClip)
    }

    @Suppress("DEPRECATION") private fun addViewHandler(intent: Intent, target: String) {
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = target; name = "$target.Open"
                enabled = true; exported = true
                applicationInfo = context.packageManager.getApplicationInfo(target, 0)
            }
        })
    }

    @Suppress("DEPRECATION") private fun configureBrowserChoices(url: String, targets: List<String>) {
        val manager = Shadows.shadowOf(context.packageManager)
        val browsers = targets.map { target ->
            installTarget(target)
            ResolveInfo().apply {
                isDefault = true
                activityInfo = ActivityInfo().apply {
                    packageName = target; name = "$target.Browser"
                    enabled = true; exported = true
                    applicationInfo = context.packageManager.getApplicationInfo(target, 0)
                }
            }.also { manager.setResolveInfosForIntent(browserIntent(url, target), listOf(it)) }
        }
        manager.setResolveInfosForIntent(
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), browsers,
        )
    }

    private fun browserIntent(url: String, target: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(target)

    @Suppress("DEPRECATION") private fun installTarget(target: String) {
        val manager = Shadows.shadowOf(context.packageManager)
        manager.installPackage(PackageInfo().apply {
            packageName = target
            applicationInfo = ApplicationInfo().apply {
                packageName = target; enabled = true; flags = ApplicationInfo.FLAG_INSTALLED
                uid = android.os.Process.myUid()
            }
        })
    }

    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private lateinit var config: ConfigStore
    private var controller: ActivityController<DragActivity>? = null
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE).edit().clear().commit()
        config = ConfigStore(context)
        config.setDragSettings(DragSettings(true, "share", "rules", "lens", "portal", false, emptyList()))
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun result(
        action: String,
        original: String = "原文",
        recognized: String = "图片识别文字",
        links: List<String> = listOf("https://example.com/real"),
    ): String {
        val payload = DragPayload(original, "com.example.source", null, "text/plain", 0, 0, "")
        val token = DragActionStore.issue(context, action, payload)
        DragActionStore.take(context, token)
        return DragActionStore.complete(context, token, recognized, links)
    }

    private fun show(token: String): DragActivity {
        controller = Robolectric.buildActivity(DragActivity::class.java,
            Intent(context, DragActivity::class.java).putExtra("result_token", token))
            .create().start().resume().visible()
        ShadowLooper.idleMainLooper()
        return controller!!.get()
    }

    @Test fun recognitionShowsContentChoicesWithoutExecutingTheFixedAction() {
        val activity = show(result("copy"))
        compose.onNodeWithText("选择用于复制文字的内容").assertIsDisplayed()
        compose.onNodeWithTag("drag.menu.all").assertIsOn()
        compose.onNodeWithTag("drag.menu.item.0").assertIsOn()
        compose.onNodeWithTag("drag.menu.item.1").assertIsOn()
        assertNull(clipboard.primaryClip)
        capture("content-light")
        compose.onNodeWithTag("drag.menu.cancel").performClick()
        assertTrue(activity.isFinishing)
        assertNull(clipboard.primaryClip)
    }

    @Test fun oneContentKindRunsWithoutAChooser() {
        val activity = show(result("copy", "只有原文", "", emptyList()))
        assertEquals("只有原文", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(activity.isFinishing)
    }

    @Test fun onlyLinksShareEveryLinkWithoutAContentChooser() {
        val activity = show(result("share", "", "", listOf("https://one.example/a", "https://two.example/b")))
        val chooser = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("https://one.example/a\nhttps://two.example/b", send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(activity.isFinishing)
    }

    @Test fun severalLinksStillAskWhichOneABrowserOrRuleCanOpen() {
        val activity = show(result("browser", "", "", listOf("https://one.example/a", "https://two.example/b")))
        compose.onNodeWithTag("drag.menu.item.0").assertIsSelected()
        compose.onNodeWithTag("drag.menu.item.1").assertIsNotSelected().performClick()
        compose.onNodeWithTag("drag.menu.item.0").assertIsNotSelected()
        compose.onNodeWithTag("drag.menu.item.1").assertIsSelected()
        compose.onNodeWithTag("drag.menu.all").assertDoesNotExist()
        compose.onNodeWithTag("drag.menu.execute").assertIsEnabled()
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test fun selectingOnlyOcrTextExecutesThePreviouslyFixedCopyAction() {
        show(result("copy"))
        compose.onNodeWithTag("drag.menu.item.0").performClick()
        compose.onNodeWithTag("drag.menu.all").assertIsOff()
        compose.onNodeWithTag("drag.menu.execute").performClick()
        assertEquals("图片识别文字", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(controller!!.get().isFinishing)
    }

    @Test fun clearingEveryContentDisablesExecutionUntilAContentIsSelectedAgain() {
        val activity = show(result("copy"))
        compose.onNodeWithTag("drag.menu.all").performClick()
        compose.onNodeWithTag("drag.menu.item.0").assertIsOff()
        compose.onNodeWithTag("drag.menu.item.1").assertIsOff()
        compose.onNodeWithTag("drag.menu.execute").assertIsNotEnabled()
        assertFalse(activity.isFinishing)
        assertNull(clipboard.primaryClip)
        compose.onNodeWithTag("drag.menu.item.1").performClick()
        compose.onNodeWithTag("drag.menu.execute").assertIsEnabled().performClick()
        assertEquals("图片识别文字", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun disablingTheModuleWhileChoosingPreventsALateAction() {
        show(result("copy"))
        config.setEnabled(false)
        compose.onNodeWithTag("drag.menu.execute").performClick()
        assertTrue(controller!!.get().isFinishing)
        assertNull(clipboard.primaryClip)
    }

    @Test fun newSourceBlacklistAndActivityRecreationInvalidatePendingChoices() {
        val blocked = result("share")
        config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, listOf("com.example.source"), emptyList())
        assertTrue(show(blocked).isFinishing)
        controller!!.pause().stop().destroy()
        controller = null
        config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, emptyList(), emptyList())
        val recreated = result("share")
        controller = Robolectric.buildActivity(DragActivity::class.java,
            Intent(context, DragActivity::class.java).putExtra("result_token", recreated)).create(Bundle())
        assertTrue(controller!!.get().isFinishing)
        assertNull(DragActionStore.takeChoice(recreated))
    }

    @Test fun shareTargetRowsStillExecuteInOneTapWithTheOriginalPayload() {
        val components = listOf("com.example.first/com.example.first.Share", "com.example.second/com.example.second.Share")
        config.setDragSettings(DragSettings(true, "share", "rules", "lens", "portal", true, components))
        val manager = Shadows.shadowOf(context.packageManager)
        components.forEachIndexed { index, component ->
            val packageName = component.substringBefore('/')
            manager.addResolveInfoForIntent(Intent(Intent.ACTION_SEND).setType("text/plain"), ResolveInfo().apply {
                nonLocalizedLabel = "目标 ${index + 1}"
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = component.substringAfter('/')
                    exported = true; enabled = true
                    applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
                }
            })
        }
        val activity = show(result("share", "", "", listOf("https://one.example/a")))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("drag.menu.item.1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("drag.menu.execute").assertDoesNotExist()
        compose.onNodeWithTag("drag.menu.item.1").performClick()
        val send = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals("com.example.second", send.component!!.packageName)
        assertEquals("https://one.example/a", send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(activity.isFinishing)
    }

    @Test fun backCancelsWithoutExecutingPendingContent() {
        val activity = show(result("copy"))
        compose.onNodeWithTag("drag.menu.execute").assertIsEnabled()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(activity.isFinishing)
        assertNull(clipboard.primaryClip)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun longContentScrollsAtLargeFontWithoutLosingCancelOrExecute() {
        RuntimeEnvironment.setFontScale(1.3f)
        show(result("share", "带有较长标题的原文".repeat(25), "图片识别文字".repeat(30),
            (1..8).map { "https://example.com/very-long-link-$it/" + "long-path/".repeat(12) }))
        compose.onNodeWithTag("drag.menu.item.9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drag.menu.execute").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("drag.menu.cancel").assertIsDisplayed()
        capture("content-compact-large-font")
        compose.onNodeWithTag("drag.menu.cancel").performClick()
        assertNull(Shadows.shadowOf(controller!!.get()).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-night-mdpi")
    fun nightContentKeepsTheSameSelectionAndCancellationControls() {
        show(result("copy"))
        compose.onNodeWithTag("drag.menu.all").assertIsOn()
        compose.onNodeWithTag("drag.menu.execute").assertIsDisplayed()
        capture("content-night")
        compose.onNodeWithTag("drag.menu.cancel").performClick()
        assertNull(clipboard.primaryClip)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            val view = controller!!.get().window.decorView
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) colors.add(bitmap.getPixel(x, y))
            assertTrue("Expected rendered Miuix content", colors.size > 8)
            val directory = File(System.getProperty("taplus.testArtifacts"), "drag-menu-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
