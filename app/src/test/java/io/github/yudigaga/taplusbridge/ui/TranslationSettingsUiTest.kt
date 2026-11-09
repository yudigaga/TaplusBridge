package io.github.yudigaga.taplusbridge.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.yudigaga.taplusbridge.translation.TranslationSettings
import io.github.yudigaga.taplusbridge.translation.TranslationStore
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationSettingsUiTest {
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            RuntimeEnvironment.getApplication().getSharedPreferences("translation_private", Context.MODE_PRIVATE).edit().clear().commit()
        }
        override fun after() { RuntimeEnvironment.setFontScale(1f) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun providerFieldsStayCollectedAndCancelDoesNotSaveTheDraft() {
        open()
        node("translation.mode.NATIVE").assertIsSelected()
        compose.onNodeWithTag("translation.endpoint").assertDoesNotExist()
        compose.onNodeWithTag("translation.app").assertDoesNotExist()
        node("translation.mode.AI").performClick()
        node("translation.endpoint").assertExists()
        node("translation.model").performTextReplacement("draft-model")
        compose.onNodeWithTag("translation.cancel").performClick()
        compose.onNodeWithTag("translation.dialog.list").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(TranslationSettings.Mode.NATIVE, TranslationStore(compose.activity).load().mode)
        }
    }

    @Test fun unfinishedTranslationDraftSurvivesBackgroundingAndReleasesTheProcessWhenClosed() {
        val exits = AtomicInteger()
        lateinit var lifetime: Application.ActivityLifecycleCallbacks
        compose.runOnIdle {
            val install = ModuleProcessLifecycle::class.java.getDeclaredMethod("installForTest", Handler::class.java, Runnable::class.java)
            install.isAccessible = true
            lifetime = install.invoke(null, Handler(Looper.getMainLooper()), Runnable { exits.incrementAndGet() }) as Application.ActivityLifecycleCallbacks
            lifetime.onActivityStarted(compose.activity)
        }
        try {
            open()
            node("translation.mode.AI").performClick()
            node("translation.model").performTextReplacement("unsaved-background-model")
            compose.runOnIdle {
                lifetime.onActivityStopped(compose.activity)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
                assertEquals("An unfinished private draft must keep the editor process alive", 0, exits.get())
                assertEquals(TranslationSettings.Mode.NATIVE, TranslationStore(compose.activity).load().mode)
            }
            node("translation.model").assertTextContains("unsaved-background-model")
            compose.onNodeWithTag("translation.cancel").performClick()
            compose.runOnIdle {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
                assertEquals("Closing the draft must allow the stopped process to retire", 1, exits.get())
                assertEquals(TranslationSettings.Mode.NATIVE, TranslationStore(compose.activity).load().mode)
            }
        } finally {
            compose.runOnIdle {
                val reset = ModuleProcessLifecycle::class.java.getDeclaredMethod("resetForTest")
                reset.isAccessible = true; reset.invoke(null)
            }
        }
    }

    @Test fun aiDraftSavesOnlyExplicitlyAndNeverPlacesTheKeyInVisibleText() {
        open()
        node("translation.mode.AI").performClick()
        node("translation.endpoint").performTextReplacement("https://translate.example/v1/chat/completions")
        node("translation.api_key").performTextInput("private-key-ui-sentinel")
        node("translation.model").performTextReplacement("translation-model")
        node("translation.target").performTextReplacement("简体中文")
        node("translation.advanced").performClick()
        node("translation.prompt").performTextReplacement("Only translate the given text.")
        node("translation.api_key").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.runOnIdle { assertEquals(TranslationSettings.Mode.NATIVE, TranslationStore(compose.activity).load().mode) }
        capture("translation-ai-light")
        compose.onNodeWithTag("translation.save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val saved = TranslationStore(compose.activity).load()
            assertEquals(TranslationSettings.Mode.AI, saved.mode)
            assertEquals("private-key-ui-sentinel", saved.apiKey)
            assertEquals("translation-model", saved.model)
            assertEquals("简体中文", saved.targetLanguage)
            assertEquals("Only translate the given text.", saved.systemPrompt)
        }
        compose.onNodeWithTag("translation.dialog.list").assertDoesNotExist()
    }

    @Test fun savedKeyCanBeKeptWithoutEchoingItAndClearingRequiresAValidActiveMode() {
        compose.runOnIdle {
            TranslationStore(compose.activity).save(ai("kept-secret"))
        }
        open()
        node("translation.api_key").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        node("translation.model").performTextReplacement("updated-model")
        compose.onNodeWithTag("translation.save").performClick()
        compose.runOnIdle { assertEquals("kept-secret", TranslationStore(compose.activity).load().apiKey) }
        open()
        node("translation.clear_key").performClick()
        compose.onNodeWithTag("translation.save").performClick()
        compose.onNodeWithTag("translation.error").assertIsDisplayed()
        compose.runOnIdle { assertEquals("kept-secret", TranslationStore(compose.activity).load().apiKey) }
        node("translation.mode.NATIVE").performClick()
        compose.onNodeWithTag("translation.save").performClick()
        compose.runOnIdle {
            val saved = TranslationStore(compose.activity).load()
            assertEquals(TranslationSettings.Mode.NATIVE, saved.mode)
            assertEquals("", saved.apiKey)
        }
    }

    @Test fun appSelectionUsesASeparateCandidateListAndSavesTheChosenContract() {
        installTextProcessor()
        open()
        capture("translation-methods-light")
        node("translation.mode.APP").performClick()
        node("translation.app").performClick()
        compose.onNodeWithTag("translation.app.search").performTextInput("example.translator")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("translation.app.target.0").fetchSemanticsNodes().isNotEmpty() }
        capture("translation-app-picker-light")
        compose.onNodeWithTag("translation.app.target.0").performClick()
        compose.onNodeWithTag("translation.app.search").assertDoesNotExist()
        compose.runOnIdle { assertEquals(TranslationSettings.Mode.NATIVE, TranslationStore(compose.activity).load().mode) }
        compose.onNodeWithTag("translation.save").performClick()
        compose.runOnIdle {
            val saved = TranslationStore(compose.activity).load()
            assertEquals(TranslationSettings.Mode.APP, saved.mode)
            assertEquals("example.translator/example.translator.TranslateActivity", saved.appComponent)
            assertEquals(Intent.ACTION_PROCESS_TEXT, saved.appAction)
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    fun compactDarkAiConfigurationKeepsFooterVisibleWhileFieldsScroll() {
        compose.runOnIdle { RuntimeEnvironment.setFontScale(1.5f) }
        open()
        node("translation.mode.AI").performClick()
        node("translation.model").performTextReplacement("small-window")
        node("translation.advanced").performClick()
        node("translation.prompt").assertIsDisplayed()
        compose.onNodeWithTag("translation.cancel").assertIsDisplayed()
        compose.onNodeWithTag("translation.save").assertIsDisplayed()
        capture("translation-ai-dark-large")
        compose.onNodeWithTag("translation.cancel").performClick()
    }

    private fun ai(key: String) = TranslationSettings(TranslationSettings.Mode.AI, "", "",
        "https://translate.example/v1/chat/completions", key, "model", "中文", "Translate only.")

    private fun open() {
        compose.onNodeWithTag("nav.recognition").performClick()
        compose.onNodeWithTag("drag.list").performScrollToNode(hasTestTag("drag.group.translation"))
        compose.onNodeWithTag("drag.group.translation").performClick()
        compose.onNodeWithTag("translation.dialog.list").assertExists()
    }

    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("translation.dialog.list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun installTextProcessor() {
        compose.runOnIdle {
            val manager = Shadows.shadowOf(compose.activity.packageManager)
            val application = ApplicationInfo().apply {
                packageName = "example.translator"; enabled = true; flags = ApplicationInfo.FLAG_INSTALLED
            }
            manager.installPackage(PackageInfo().apply {
                packageName = "example.translator"; applicationInfo = application
                activities = arrayOf(ActivityInfo().apply {
                    name = "example.translator.TranslateActivity"; packageName = "example.translator"; exported = true; enabled = true
                    applicationInfo = application
                })
            })
            manager.addResolveInfoForIntent(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), ResolveInfo().apply {
                isDefault = true; nonLocalizedLabel = "示例翻译"
                activityInfo = ActivityInfo().apply {
                    name = "example.translator.TranslateActivity"; packageName = "example.translator"; exported = true; enabled = true
                    applicationInfo = ApplicationInfo().apply {
                        packageName = "example.translator"; enabled = true; flags = ApplicationInfo.FLAG_INSTALLED
                    }
                }
            })
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val path = File(System.getProperty("taplus.testArtifacts"), "ui-" + io.github.yudigaga.taplusbridge.BuildConfig.VERSION_NAME.removeSuffix("-dev"))
            path.mkdirs()
            File(path, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
