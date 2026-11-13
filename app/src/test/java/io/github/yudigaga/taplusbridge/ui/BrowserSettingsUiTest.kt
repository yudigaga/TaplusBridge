package io.github.yudigaga.taplusbridge.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserSettingsUiTest {
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            RuntimeEnvironment.getApplication().getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        override fun after() { RuntimeEnvironment.setFontScale(1f) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun browserSelectionSavesExplicitlyAndCanBeClearedBackToTheOriginalChoice() {
        installBrowser()
        open()
        compose.onNodeWithTag("browser.default").assertIsSelected()
        target().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("", selected()) }
        capture("browser-selection-light")
        compose.onNodeWithTag("browser.save").performClick()
        compose.onNodeWithTag("browser.dialog.list").assertDoesNotExist()
        compose.runOnIdle { assertEquals("example.browser", selected()) }
        open()
        compose.onNodeWithTag("browser.default").performClick()
        compose.onNodeWithTag("browser.save").performClick()
        compose.runOnIdle { assertEquals("", selected()) }
    }

    @Test fun cancellingBrowserSelectionKeepsThePreviouslySavedChoice() {
        installBrowser()
        open()
        target().performClick()
        compose.onNodeWithTag("browser.cancel").performClick()
        compose.runOnIdle { assertEquals("", selected()) }
    }

    @Test fun failedBrowserSaveKeepsTheDialogOpenAndDoesNotReportTheDraftAsSaved() {
        installBrowser()
        val original = compose.activity.baseContext
        compose.runOnIdle {
            ReflectionHelpers.setField(compose.activity, "mBase", object : ContextWrapper(original) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val actual = super.getSharedPreferences(name, mode)
                    if (name != "taplus_bridge_browser") return actual
                    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                        arrayOf(SharedPreferences::class.java)) { _, method, args ->
                        if (method.name != "edit") method.invoke(actual, *(args ?: emptyArray()))
                        else {
                            val editor = actual.edit()
                            Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                                arrayOf(SharedPreferences.Editor::class.java)) { proxy, write, values ->
                                if (write.name == "commit") { editor.apply(); false }
                                else {
                                    val result = write.invoke(editor, *(values ?: emptyArray()))
                                    if (result is SharedPreferences.Editor) proxy else result
                                }
                            }
                        }
                    } as SharedPreferences
                }
            })
        }
        try {
            open()
            target().performClick()
            compose.onNodeWithTag("browser.save").performClick()
            compose.onNodeWithTag("browser.error").assertIsDisplayed()
            compose.onNodeWithTag("browser.dialog.list").assertExists()
            compose.runOnIdle { assertEquals("", selected()) }
            compose.onNodeWithTag("browser.cancel").performClick()
        } finally { compose.runOnIdle { ReflectionHelpers.setField(compose.activity, "mBase", original) } }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    fun missingBrowserCanBeClearedWithVisibleFooterInACompactLargeFontDialog() {
        compose.runOnIdle {
            RuntimeEnvironment.setFontScale(1.5f)
            compose.activity.getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE).edit()
                .putString("selected_package", "example.removed").commit()
        }
        open()
        compose.waitUntil(10_000) {
            !compose.onNodeWithTag("browser.save").fetchSemanticsNode().config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("browser.dialog.list").performScrollToNode(hasTestTag("browser.unavailable"))
        compose.onNodeWithTag("browser.unavailable").assertIsDisplayed()
        compose.onNodeWithTag("browser.dialog.list").performScrollToNode(hasTestTag("browser.default"))
        compose.onNodeWithTag("browser.default").assertIsDisplayed().performClick()
        compose.onNodeWithTag("browser.cancel").assertIsDisplayed()
        compose.onNodeWithTag("browser.save").assertIsDisplayed()
        capture("browser-selection-dark-large")
        compose.onNodeWithTag("browser.save").performClick()
        compose.runOnIdle { assertEquals("", selected()) }
    }

    private fun open() {
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("drag.list").performScrollToNode(hasTestTag("drag.group.browser"))
        compose.onNodeWithTag("drag.group.browser").performClick()
        compose.onNodeWithTag("browser.dialog.list").assertExists()
    }

    private fun target(): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("browser.target.0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browser.dialog.list").performScrollToNode(hasTestTag("browser.target.0"))
        return compose.onNodeWithTag("browser.target.0")
    }

    private fun selected() = compose.activity.getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE)
        .getString("selected_package", "")

    @Suppress("DEPRECATION") private fun installBrowser() = compose.runOnIdle {
        val manager = Shadows.shadowOf(compose.activity.packageManager)
        val application = ApplicationInfo().apply {
            packageName = "example.browser"; enabled = true; flags = ApplicationInfo.FLAG_INSTALLED
        }
        manager.installPackage(PackageInfo().apply { packageName = application.packageName; applicationInfo = application })
        val browser = ResolveInfo().apply {
            isDefault = true; nonLocalizedLabel = "示例浏览器"
            activityInfo = ActivityInfo().apply {
                name = "example.browser.Open"; packageName = application.packageName
                enabled = true; exported = true; applicationInfo = application
            }
        }
        manager.addResolveInfoForIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), browser)
    }

    private fun capture(name: String) {
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File(System.getProperty("taplus.testArtifacts"),
                "ui-" + io.github.yudigaga.taplusbridge.BuildConfig.VERSION_NAME.removeSuffix("-dev"))
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
