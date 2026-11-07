package io.github.yudigaga.taplusbridge.capture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CaptureLeaseTest {
    private static final String WECHAT = "com.tencent.mm";
    private static final String QQ = "com.tencent.mobileqq";
    private static final String WECHAT_WEB = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI";
    private static final String WECHAT_MM_WEB = "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI";
    private static final String QQ_WEB = "com.tencent.mobileqq.activity.QQBrowserActivity";

    @Test public void firstSupportedObservationConsumesEvenWithoutUrlPayload() {
        CaptureLease lease = new CaptureLease();
        long generation = lease.arm(WECHAT, 10123, 0, 1000);
        CaptureLease.Observation observed = lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1100);
        assertNotNull(observed);
        assertEquals(generation, observed.generation());
        assertEquals(WECHAT, observed.sourcePackage());
        assertEquals(10123, observed.sourceUid());
        assertEquals(0, observed.userId());
        assertEquals(WECHAT_WEB, observed.destinationClass());
        assertEquals(1000, observed.armedAt());
        assertEquals(1100, observed.observedAt());
        assertFalse(lease.isArmed(1100));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1200));
    }

    @Test public void wrongSourceBinderIdentityAndUserNeverConsumeLease() {
        CaptureLease lease = new CaptureLease();
        lease.arm(WECHAT, 10123, 0, 1000);
        assertNull(lease.observe(QQ, 10123, 0, WECHAT, WECHAT_WEB, 1100));
        assertNull(lease.observe(WECHAT, 10124, 0, WECHAT, WECHAT_WEB, 1100));
        assertNull(lease.observe(WECHAT, 110123, 1, WECHAT, WECHAT_WEB, 1100));
        assertNull(lease.observe(WECHAT, 10123, 1, WECHAT, WECHAT_WEB, 1100));
        assertNull(lease.observe(null, 10123, 0, WECHAT, WECHAT_WEB, 1100));
        assertTrue(lease.isArmed(1200));
        assertNotNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1201));
    }

    @Test public void destinationPackageAndClassMustBothMatchExactly() {
        CaptureLease lease = new CaptureLease();
        lease.arm(WECHAT, 10123, 0, 1000);
        assertNull(lease.observe(WECHAT, 10123, 0, QQ, WECHAT_WEB, 1100));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, QQ_WEB, 1100));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB + "Malicious", 1100));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, "com.tencent.mm.ui.LauncherUI", 1100));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, null, 1100));
        assertNotNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_MM_WEB, 1200));
    }

    @Test public void qqUsesItsOwnIdentityAndOnlyItsBrowserClass() {
        CaptureLease lease = new CaptureLease();
        lease.arm(QQ, 110123, 1, 1000);
        assertNull(lease.observe(QQ, 110123, 1, WECHAT, WECHAT_WEB, 1100));
        assertNotNull(lease.observe(QQ, 110123, 1, QQ, QQ_WEB, 1200));
    }

    @Test public void exactFifteenSecondBoundaryExpiresObservation() {
        CaptureLease lease = new CaptureLease();
        lease.arm(WECHAT, 10123, 0, 1000);
        assertTrue(lease.isArmed(15999));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 16000));
        assertFalse(lease.isArmed(16000));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1001));
    }

    @Test public void replacingLeaseChangesGenerationAndRejectsStaleCancellation() {
        CaptureLease lease = new CaptureLease();
        long old = lease.arm(WECHAT, 10123, 0, 1000);
        long latest = lease.arm(QQ, 10234, 0, 1200);
        assertTrue(latest > old);
        assertFalse(lease.cancel(old));
        assertFalse(lease.cancelOwner(WECHAT, 10123, 0));
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1250));
        CaptureLease.Observation observed = lease.observe(QQ, 10234, 0, QQ, QQ_WEB, 1251);
        assertNotNull(observed);
        assertEquals(latest, observed.generation());
    }

    @Test public void staleObservationTimestampCannotDestroyNewerArm() {
        CaptureLease lease = new CaptureLease();
        lease.arm(WECHAT, 10123, 0, 2000);
        assertNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1999));
        assertNotNull(lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 2001));
    }

    @Test public void consumedReportKeepsItsIdentityAcrossRearm() {
        CaptureLease lease = new CaptureLease();
        long first = lease.arm(WECHAT, 10123, 0, 1000);
        CaptureLease.Observation pendingReport = lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1100);
        long second = lease.arm(WECHAT, 10123, 0, 1200);
        assertFalse(lease.cancel(pendingReport.generation()));
        assertEquals(first, pendingReport.generation());
        assertEquals(1000, pendingReport.armedAt());
        assertEquals(second, lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1300).generation());
    }

    @Test public void invalidScopeAndUidProfileMismatchCannotReplaceExistingLease() {
        CaptureLease lease = new CaptureLease();
        long generation = lease.arm(WECHAT, 10123, 0, 1000);
        assertThrows(IllegalArgumentException.class, () -> lease.arm("com.example.reader", 10123, 0, 1100));
        assertThrows(IllegalArgumentException.class, () -> lease.arm("com.tencent.mm.extra", 10123, 0, 1100));
        assertThrows(IllegalArgumentException.class, () -> lease.arm(WECHAT, 110123, 0, 1100));
        assertThrows(IllegalArgumentException.class, () -> lease.arm(WECHAT, 10123, 1, 1100));
        assertThrows(IllegalArgumentException.class, () -> lease.arm(WECHAT, -1, 0, 1100));
        assertThrows(IllegalArgumentException.class, () -> lease.arm(WECHAT, 10123, 0, -1));
        assertEquals(generation, lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1200).generation());
    }

    @Test public void ownerCancellationNeedsAllIdentityFieldsAndClearWorks() {
        CaptureLease lease = new CaptureLease();
        lease.arm(WECHAT, 10123, 0, 1000);
        assertFalse(lease.cancelOwner(WECHAT, 10124, 0));
        assertFalse(lease.cancelOwner(WECHAT, 10123, 1));
        assertFalse(lease.cancelOwner(QQ, 10123, 0));
        assertTrue(lease.cancelOwner(WECHAT, 10123, 0));
        assertFalse(lease.isArmed(1100));
        long next = lease.arm(WECHAT, 10123, 0, 1200);
        assertTrue(lease.cancel(next));
        assertFalse(lease.cancel(next));
        lease.arm(QQ, 10234, 0, 1300);
        lease.clear();
        assertFalse(lease.isArmed(1400));
    }

    @Test public void simultaneousMatchingObservationsConsumeOnlyOnce() throws Exception {
        CaptureLease lease = new CaptureLease();
        lease.arm(WECHAT, 10123, 0, 1000);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<CaptureLease.Observation> first = workers.submit(() -> {
                start.await(); return lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1100);
            });
            Future<CaptureLease.Observation> second = workers.submit(() -> {
                start.await(); return lease.observe(WECHAT, 10123, 0, WECHAT, WECHAT_WEB, 1100);
            });
            start.countDown();
            int count = (first.get() == null ? 0 : 1) + (second.get() == null ? 0 : 1);
            assertEquals(1, count);
        } finally { workers.shutdownNow(); }
    }
}
