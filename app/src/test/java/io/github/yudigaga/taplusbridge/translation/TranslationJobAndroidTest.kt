package io.github.yudigaga.taplusbridge.translation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TranslationJobAndroidTest {
    private fun settings() = TranslationSettings(TranslationSettings.Mode.AI, "", "", TranslationSettings.DEFAULT_ENDPOINT,
        "private-job-key", "model-id", "中文", TranslationSettings.DEFAULT_PROMPT)

    @Test fun aRetryAfterAuthorizationChangesExpiresWithoutQueueingAnotherRequest() {
        val authorized = AtomicBoolean(true)
        val calls = AtomicInteger()
        val job = TranslationJob(settings(), "private text", authorized = authorized::get,
            translator = { _, _, _ -> calls.incrementAndGet(); "译文" })
        job.status = TranslationStatus.ERROR
        authorized.set(false)
        job.start()
        assertEquals(TranslationStatus.EXPIRED, job.status)
        assertEquals(0, calls.get())
        assertNull(job.future)
    }

    @Test fun aChangedProfileRejectsAnAlreadyRunningResponseAndNeverDisplaysItsBody() {
        val authorized = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = TranslationJob(settings(), "private text", authorized = authorized::get, translator = { _, _, _ ->
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            "old provider result must not be shown"
        })
        job.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val future = job.future!!
        authorized.set(false)
        release.countDown()
        future.get(2, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
        assertEquals(TranslationStatus.EXPIRED, job.status)
        assertEquals("", job.result)
    }

    @Test fun repeatedStartsWhileLoadingProduceOneRequestAndKeepTheCurrentResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val job = TranslationJob(settings(), "private text", translator = { _, _, _ ->
            calls.incrementAndGet(); entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            "唯一译文"
        })
        job.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val future = job.future!!
        job.start()
        release.countDown()
        future.get(2, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
        assertEquals(1, calls.get())
        assertEquals(TranslationStatus.SUCCESS, job.status)
        assertEquals("唯一译文", job.result)
    }
}
