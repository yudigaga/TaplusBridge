package io.github.yudigaga.taplusbridge.hook;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostDragReplayAndroidTest {
    @Test public void onlyTheExactAuthorizedReplayBypassesTheScopeLinkHold() throws Exception {
        String service = new String("service"), result = new String("result");
        String equalService = new String("service"), equalResult = new String("result");
        assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, result));
        HostDragHooks.deliverNative(service, result, () -> {
            assertTrue(HostDragHooks.delivering(service, result));
            assertTrue(HostScopeCaptureHooks.syntheticPick(false, service, result));
            assertFalse(HostScopeCaptureHooks.syntheticPick(false, equalService, result));
            assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, equalResult));
            assertFalse(HostScopeCaptureHooks.syntheticPick(false, equalService, equalResult));
        });
        assertFalse(HostDragHooks.delivering(service, result));
        assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, result));
        assertTrue(HostScopeCaptureHooks.syntheticPick(true, service, result));
    }

    @Test public void anotherThreadCannotBorrowAnActiveReplayAdmission() throws Exception {
        Object service = new Object(), result = new Object();
        AtomicReference<Boolean> otherThreadAdmission = new AtomicReference<>();
        AtomicReference<Throwable> otherThreadFailure = new AtomicReference<>();
        HostDragHooks.deliverNative(service, result, () -> {
            assertTrue(HostScopeCaptureHooks.syntheticPick(false, service, result));
            Thread other = new Thread(() -> {
                try { otherThreadAdmission.set(HostScopeCaptureHooks.syntheticPick(false, service, result)); }
                catch (Throwable failure) { otherThreadFailure.set(failure); }
            });
            other.start();
            try { other.join(3_000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new ReflectiveOperationException(interrupted);
            }
            assertFalse("The thread-boundary check must complete", other.isAlive());
            assertNull(otherThreadFailure.get());
            assertEquals(Boolean.FALSE, otherThreadAdmission.get());
            assertTrue(HostScopeCaptureHooks.syntheticPick(false, service, result));
        });
        assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, result));
    }

    @Test public void aFailedNativeCallbackCannotLeaveLaterPhysicalPicksAdmitted() {
        Object service = new Object(), result = new Object();
        ReflectiveOperationException expected = new ReflectiveOperationException("native callback failed");
        ReflectiveOperationException actual = assertThrows(ReflectiveOperationException.class,
                () -> HostDragHooks.deliverNative(service, result, () -> {
                    assertTrue(HostScopeCaptureHooks.syntheticPick(false, service, result));
                    throw expected;
                }));
        assertSame(expected, actual);
        assertFalse(HostDragHooks.delivering(service, result));
        assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, result));
    }

    @Test public void nestedReplayRestoresItsOuterAdmissionAfterFailure() throws Exception {
        Object service = new Object(), outer = new Object(), inner = new Object();
        HostDragHooks.deliverNative(service, outer, () -> {
            assertTrue(HostScopeCaptureHooks.syntheticPick(false, service, outer));
            assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, inner));
            assertThrows(IllegalStateException.class, () -> HostDragHooks.deliverNative(service, inner, () -> {
                assertTrue(HostScopeCaptureHooks.syntheticPick(false, service, inner));
                assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, outer));
                throw new IllegalStateException("nested callback failed");
            }));
            assertTrue(HostScopeCaptureHooks.syntheticPick(false, service, outer));
            assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, inner));
        });
        assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, outer));
        assertFalse(HostScopeCaptureHooks.syntheticPick(false, service, inner));
    }
}
