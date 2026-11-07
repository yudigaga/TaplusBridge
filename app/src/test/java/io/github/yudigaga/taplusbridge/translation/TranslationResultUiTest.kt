package io.github.yudigaga.taplusbridge.translation

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.yudigaga.taplusbridge.ui.MainActivity
import io.github.yudigaga.taplusbridge.ui.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h640dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationResultUiTest {
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() { RuntimeEnvironment.getApplication().getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit() }
        override fun after() { RuntimeEnvironment.setFontScale(1f) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun resultIsPlainSelectableTextWithCopyAndCloseAtLargeFont() {
        var copied = 0
        var closed = 0
        compose.runOnIdle {
            RuntimeEnvironment.setFontScale(1.5f)
            compose.activity.setContent {
                SettingsTheme {
                    Scaffold(Modifier.fillMaxSize()) {
                    TranslationResultDialog(TranslationStatus.SUCCESS, "<b>这段内容仍是纯文本</b>\n" + "译文内容\n".repeat(80), "",
                        { closed++ }, {}, { copied++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("translation.result.text").assertTextContains("<b>这段内容仍是纯文本</b>", substring = true)
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val directory = java.io.File(System.getProperty("taplus.testArtifacts"), "ui-" + io.github.yudigaga.taplusbridge.BuildConfig.VERSION_NAME.removeSuffix("-dev")).apply { mkdirs() }
            java.io.File(directory, "translation-result-dark-large.png").outputStream().use {
                org.junit.Assert.assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
        compose.onNodeWithTag("translation.result.body").performTouchInput { swipeUp() }
        compose.onNodeWithTag("translation.result.copy").assertIsDisplayed().performClick()
        compose.onNodeWithTag("translation.result.close").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, copied); assertEquals(1, closed) }
    }

    @Test fun onlyFailedAiRequestsOfferAnExplicitRetry() {
        var retries = 0
        var status by mutableStateOf(TranslationStatus.ERROR)
        compose.runOnIdle {
            compose.activity.setContent {
                SettingsTheme {
                    Scaffold(Modifier.fillMaxSize()) {
                    TranslationResultDialog(status, "", "无法连接翻译服务，请检查网络后重试", {},
                        { retries++; status = TranslationStatus.LOADING }, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("translation.result.error").assertIsDisplayed()
        compose.onNodeWithTag("translation.result.retry").performClick()
        compose.onNodeWithTag("translation.result.retry").assertDoesNotExist()
        compose.onNodeWithTag("translation.result.loading").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, retries); status = TranslationStatus.EXPIRED }
        compose.onNodeWithTag("translation.result.expired").assertIsDisplayed()
        compose.onNodeWithTag("translation.result.retry").assertDoesNotExist()
        compose.onNodeWithTag("translation.result.copy").assertDoesNotExist()
    }
}
