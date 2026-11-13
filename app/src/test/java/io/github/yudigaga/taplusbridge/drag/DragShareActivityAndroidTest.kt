package io.github.yudigaga.taplusbridge.drag

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.yudigaga.taplusbridge.config.ConfigStore
import io.github.yudigaga.taplusbridge.config.DragSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [DragShareActivityAndroidTest.SharePackageManager::class])
class DragShareActivityAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private var controller: ActivityController<DragActivity>? = null

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit()
        SharePackageManager.targets = emptyList()
        SharePackageManager.queryOnMain = false
        SharePackageManager.queryCount.set(0)
        SharePackageManager.entered = CountDownLatch(1)
        SharePackageManager.release = null
    }

    @After fun cleanup() {
        SharePackageManager.release?.countDown()
        controller?.pause()?.stop()?.destroy()
        ShadowLooper.idleMainLooper()
    }

    @Test fun customShareBuildsTheMenuOffMainAndReadsEachLabelOnce() {
        val targets = listOf(target("com.example.zulu", "Zulu"), target("com.example.alpha", "Alpha"),
            target("com.example.delta", "Delta"), target("com.example.bravo", "Bravo"))
        SharePackageManager.targets = targets
        show(custom = true)
        assertTrue(SharePackageManager.entered.await(5, TimeUnit.SECONDS))
        assertFalse("Enumerating share targets must not block the UI thread", SharePackageManager.queryOnMain)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("drag.menu.item.3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        targets.forEach {
            assertFalse("Share labels must be loaded off the UI thread", it.labelOnMain)
            assertEquals("A target label should be loaded once for sorting and presentation", 1, it.labelReads.get())
        }
    }

    @Test fun destroyedActivityDropsABackgroundShareMenuResult() {
        val target = target("com.example.target", "Target")
        SharePackageManager.targets = listOf(target)
        val release = CountDownLatch(1)
        SharePackageManager.release = release
        val activity = show(custom = true)
        assertTrue(SharePackageManager.entered.await(5, TimeUnit.SECONDS))
        assertFalse("Share target enumeration must first yield to the UI thread", SharePackageManager.queryOnMain)
        controller!!.pause().stop().destroy()
        controller = null
        release.countDown()
        // Both workers must reach the barrier, so the canceled query has truly returned before draining main.
        val workers = DragActivity::class.java.getDeclaredField("IO").apply { isAccessible = true }.get(null) as ExecutorService
        val barrier = CyclicBarrier(2)
        val drained = (1..2).map { workers.submit { barrier.await(5, TimeUnit.SECONDS) } }
        drained.forEach { it.get(5, TimeUnit.SECONDS) }
        ShadowLooper.idleMainLooper()
        assertTrue(activity.isDestroyed)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals(0, compose.onAllNodesWithTag("drag.menu").fetchSemanticsNodes(atLeastOneRootRequired = false).size)
    }

    @Test fun defaultSystemChooserStartsWithoutEnumeratingCustomTargets() {
        val activity = show(custom = false)
        assertEquals(Intent.ACTION_CHOOSER, Shadows.shadowOf(activity).nextStartedActivity.action)
        assertEquals(1L, SharePackageManager.entered.count)
        assertTrue(activity.isFinishing)
    }

    @Test fun staleContentCallbackAfterImageOwnershipTransferDoesNotStartAnotherShare() {
        SharePackageManager.targets = listOf(target("com.example.target", "Target"))
        val release = CountDownLatch(1)
        SharePackageManager.release = release
        val activity = show(custom = true)
        assertTrue(SharePackageManager.entered.await(5, TimeUnit.SECONDS))
        assertFalse(activity.isFinishing)
        // Image sharing has transferred its payload to the provider while the old choice callback still exists.
        DragActivity::class.java.getDeclaredField("payload").apply { isAccessible = true }.set(activity, null)
        val content = DragActivity::class.java.declaredClasses.first { it.simpleName == "Content" }
            .getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val staleRows = listOf(content.newInstance("Original", "text", null, false), content.newInstance("Image", null, null, true))
        val apply = DragActivity::class.java.getDeclaredMethod("applyChoice", String::class.java, List::class.java,
            BooleanArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        assertEquals(false, apply.invoke(activity, "share", staleRows, booleanArrayOf(true, true), -1))
        assertEquals("A second tap must not start another share", 1, SharePackageManager.queryCount.get())
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    private fun show(custom: Boolean): DragActivity {
        ConfigStore(context).setDragSettings(DragSettings(true, "share", "rules", "lens", "portal", false,
            if (custom) listOf("com.example.pin/com.example.pin.Share") else emptyList()))
        val payload = DragPayload("only text", "com.example.source", null, "text/plain", 0, 0, "")
        val pending = DragActionStore.issue(context, "share", payload)
        @Suppress("DEPRECATION")
        val taken = DragActionStore.take(context, pending)
        taken?.getParcelable<ParcelFileDescriptor>("fd")?.close()
        val result = DragActionStore.complete(context, pending, "", emptyList())
        controller = Robolectric.buildActivity(DragActivity::class.java,
            Intent(context, DragActivity::class.java).putExtra("result_token", result)).create().start().resume().visible()
        return controller!!.get()
    }

    private fun target(pkg: String, label: String) = CountingTarget(label).apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg; name = "$pkg.Share"; enabled = true; exported = true
            applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
        }
    }

    class CountingTarget(private val title: String) : ResolveInfo() {
        val labelReads = AtomicInteger()
        @Volatile var labelOnMain = false
        override fun loadLabel(pm: PackageManager): CharSequence {
            labelReads.incrementAndGet()
            labelOnMain = labelOnMain || Looper.myLooper() == Looper.getMainLooper()
            return title
        }
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    class SharePackageManager : ShadowApplicationPackageManager() {
        companion object {
            @JvmField var targets: List<ResolveInfo> = emptyList()
            @JvmField @Volatile var queryOnMain = false
            @JvmField val queryCount = AtomicInteger()
            @JvmField var entered = CountDownLatch(1)
            @JvmField var release: CountDownLatch? = null
        }

        @Implementation override fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> {
            if (intent.action != Intent.ACTION_SEND) return super.queryIntentActivities(intent, flags)
            queryCount.incrementAndGet()
            queryOnMain = Looper.myLooper() == Looper.getMainLooper()
            entered.countDown()
            if (!queryOnMain) {
                try { release?.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            return targets
        }
    }
}
