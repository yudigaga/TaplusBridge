package io.github.yudigaga.taplusbridge.probe;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Synthetic metadata and the installed Pointer Hook; no real recording or AudioManager calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = MotionEventDisplayShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SystemScopeRecordingProtectionAndroidTest {
    @Test public void hostPreRecognitionQueryUsesLiveSourceActivityOwnerAndFreshUidEvidence() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            reads.incrementAndGet(); return observation(uid, recording.get());
        })) {
            f.begin();
            ScopeCaptureWindow.Snapshot target = owner(f);
            f.onLog = logs::add;
            int before = reads.get();
            Bundle wrongActivity = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk",
                    hostRisk(target, target.activity + ".Other"));
            assertFalse(wrongActivity.getBoolean("protected"));
            assertEquals(before, reads.get());
            f.drainWorker();
            assertTrue(logs.stream().anyMatch(s -> s.contains("scope capture stage=host_recording_risk reason=owner_mismatch")));
            assertTrue(logs.stream().noneMatch(s -> s.contains(target.source) || s.contains(target.activity)));

            Bundle malformedActivity = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk",
                    hostRisk(target, "com.tencent.mm bad"));
            assertFalse(malformedActivity.getBoolean("protected"));
            assertEquals(before, reads.get());

            Bundle unknown = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk",
                    hostRisk(target, target.activity));
            assertFalse("Unknown source evidence must preserve the host presentation", unknown.getBoolean("protected"));
            assertTrue(reads.get() > before);
            f.drainWorker();
            assertTrue(logs.stream().anyMatch(s -> s.contains("scope capture stage=host_recording_risk reason=recording_unknown")));

            recording.set(true);
            Bundle exact = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk",
                    hostRisk(target, target.activity));
            assertEquals(1, exact.size());
            assertTrue(exact.getBoolean("protected"));
            assertTrue(reads.get() > before);
            f.drainWorker();
            assertTrue(logs.stream().anyMatch(s -> s.contains("scope capture stage=host_recording_risk reason=protected")));
        }
    }

    @Test public void theHostCannotBorrowAProtectedOwnerAfterThePhysicalUp() throws Exception {
        AtomicBoolean recording = new AtomicBoolean(true);
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> observation(uid, recording.get()))) {
            Bundle request = new Bundle();
            request.putString("source", f.main.mAttrs.packageName);
            request.putString("activity", f.main.mActivityRecord.mActivityComponent.getClassName());
            f.begin();
            assertTrue(f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk", request)
                    .getBoolean("protected"));
            recording.set(false);

            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput();
            assertTrue(f.physical.released());
            assertFalse("An UP already read at the hook ends host veto eligibility even before the worker drains",
                    f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk", request)
                            .getBoolean("protected"));
            f.drainWorker();
        }
    }

    @Test public void handingOffOptionalLinkWorkDoesNotLoseTheHeldRecordingOwner() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, true, uid -> observation(uid, recording.get()))) {
            f.begin();
            var target = owner(f);
            var config = f.configuration();
            assertTrue(f.capture.tryYieldLinkToHost(target.source, target.uid, target.user, f.beganElapsed));
            assertFalse(f.capture.linkProbe().ownsNative(target.source, f.beganElapsed));
            f.drainWorker();
            recording.set(true);

            Bundle result = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk",
                    hostRisk(target, target.activity));
            assertTrue("Handing off the optional link must keep the recording gate for this held touch",
                    result.getBoolean("protected"));
            assertTrue(f.capture.protectsDrag(target, config, 0, f.downUptime));
        }
    }

    @Test public void aWindowChangeDuringTheHostAudioQueryDoesNotLatchTheOldOwner() throws Exception {
        AtomicReference<Runnable> onObserve = new AtomicReference<>();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            Runnable change = onObserve.getAndSet(null);
            if (change == null) return SourceRecordingState.UNKNOWN;
            change.run();
            return observation(uid, true);
        })) {
            f.begin();
            var target = owner(f);
            Bundle request = hostRisk(target, target.activity);
            onObserve.set(() -> {
                var changed = new ScopeCaptureWindowAndroidTest.Window();
                f.atms.mWindowManager.mRoot.display.hit = changed;
                f.atms.mWindowManager.mRoot.display.mCurrentFocus = changed;
            });
            assertFalse(f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk", request)
                    .getBoolean("protected"));
            f.restoreMainWindow();
            assertFalse("A veto rejected for a changed window cannot latch an earlier owner",
                    f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk", request)
                            .getBoolean("protected"));
        }
    }

    @Test public void hostPreRecognitionQueryProtectsMatchingOwnerWithoutAContentCatcherPoint() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            reads.incrementAndGet(); return observation(uid, recording.get());
        })) {
            f.begin();
            ScopeCaptureWindow.Snapshot target = owner(f);
            recording.set(true);

            Bundle result = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk",
                    hostRisk(target, target.activity));

            assertTrue("A missing ContentCatcher coordinate must not hide this live owner", result.getBoolean("protected"));
            assertTrue(reads.get() > 0);
        }
    }

    @Test public void hostPreRecognitionQueryWithoutAnExistingOwnerDoesNotPollOrCreateOne() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            reads.incrementAndGet(); return observation(uid, true);
        })) {
            Bundle request = new Bundle();
            request.putString("source", f.main.mAttrs.packageName);
            request.putString("activity", f.main.mActivityRecord.mActivityComponent.getClassName());
            Bundle result = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk", request);
            assertFalse(result.getBoolean("protected"));
            assertEquals("The request is a lookup, never an owner-creation path", 0, reads.get());
            assertNull(ReflectionHelpers.getField(f.capture, "recordingRisk"));
        }
    }

    @Test public void hostPreRecognitionQueryCannotUseTheCallingHostUidAsTheSourceUid() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> observation(uid, recording.get()))) {
            f.begin(); ScopeCaptureWindow.Snapshot target = owner(f); recording.set(true);
            Bundle hostUidAsSource = new Bundle();
            hostUidAsSource.putString("source", target.source);
            hostUidAsSource.putString("activity", target.activity);
            hostUidAsSource.putInt("uid", SystemMenuQueueFixture.HOST_UID);
            Bundle result = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk", hostUidAsSource);
            assertFalse("Extra caller-supplied UID fields are rejected", result.getBoolean("protected"));
        }
    }

    @Test public void hostPreRecognitionQueryRequiresTheOriginalWmsWindowStillToBeLive() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            reads.incrementAndGet(); return observation(uid, recording.get());
        })) {
            f.begin(); ScopeCaptureWindow.Snapshot target = owner(f);
            int before = reads.get(); recording.set(true);
            ScopeCaptureWindowAndroidTest.Window foreign = new ScopeCaptureWindowAndroidTest.Window();
            foreign.mActivityRecord.mActivityComponent = new ComponentName("com.example.foreign", "com.example.foreign.Main");
            foreign.mAttrs.packageName = "com.example.foreign";
            f.atms.mWindowManager.mRoot.display.hit = foreign;
            f.atms.mWindowManager.mRoot.display.mCurrentFocus = foreign;
            Bundle result = f.callRuntime(SystemMenuQueueFixture.HOST_UID, "scope_recording_risk",
                    hostRisk(target, target.activity));
            assertFalse(result.getBoolean("protected"));
            assertEquals("A stale WMS target cannot turn source recording into a host veto", before, reads.get());
        }
    }

    @Test public void aSourceAppCannotInvokeTheHostOnlyRecordingRiskQuery() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            reads.incrementAndGet(); return observation(uid, recording.get());
        })) {
            f.begin(); ScopeCaptureWindow.Snapshot target = owner(f);
            recording.set(true);
            int before = reads.get(), policyReads = f.policyReads.get();
            Bundle result = f.callRuntime(22003, "scope_recording_risk",
                    hostRisk(target, target.activity));
            assertEquals("unauthorized", result.getString("reason"));
            assertEquals(before, reads.get());
            assertEquals("An unauthorized caller cannot start a policy operation", policyReads, f.policyReads.get());
        }
    }

    @Test public void matchingUidAtDownPreventsArmingAnyScopeAddition() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(true, true,
                uid -> new SourceRecordingState.Observation(uid, SystemClock.elapsedRealtime()))) {
            f.begin();
            var back = f.installMenu(101);
            assertFalse(f.visible(f.shownElapsed));
            release(f, 200); f.advanceTo(2_000); f.drainWorker();
            assertEquals(0, back.calls); assertEquals(0, f.captureCalls.get()); assertEquals(0, f.linkTaps.get());
        }
    }

    @Test public void matchingUidAtRecognitionLeavesSourceEventsAndFocusUntouched() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(true, true, uid -> observation(uid, recording.get()))) {
            f.begin(); var back = f.showMenu(101);
            Object focus = f.atms.mWindowManager.mRoot.display.mCurrentFocus;
            recording.set(true); f.advanceTo(200);
            MotionEvent up = f.queueSinglePointer(MotionEvent.ACTION_UP, f.downUptime + 200, 0, f.downUptime,
                    InputDevice.SOURCE_TOUCHSCREEN, 0, 0, 0, 50, 200);
            f.drainInput(); f.drainWorker(); f.advanceTo(2_000); f.drainWorker();
            assertEquals(2, f.proceededInputs.get()); assertEquals(1, up.getActionMasked());
            assertEquals(0, up.getFlags()); assertEquals(0, up.getPointerId(0));
            assertEquals(50f, up.getRawX(), 0); assertEquals(200f, up.getRawY(), 0);
            assertSame(focus, f.atms.mWindowManager.mRoot.display.mCurrentFocus);
            assertEquals(0, back.calls); assertEquals(0, f.captureCalls.get()); assertEquals(0, f.linkTaps.get());
        }
    }

    @Test public void recordingObservedAfterSecondWindowInspectionVetoesBackOutsideTheWmsLock() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        AtomicReference<SystemMenuQueueFixture> fixture = new AtomicReference<>();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            var current = fixture.get();
            if (current != null) assertFalse(Thread.holdsLock(current.atms.mWindowManager.mGlobalLock));
            return observation(uid, recording.get());
        })) {
            fixture.set(f); f.begin(); var back = f.showMenu(101);
            f.menu.beforeBackRead = () -> { if (f.menu.backReads == 2) recording.set(true); };
            release(f, 200);
            assertTrue(recording.get()); assertEquals(0, back.calls); assertFalse(f.retain());
        }
    }

    @Test public void stoppingRecordingCannotReviveTheSameProtectedGesture() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(true, true, uid -> observation(uid, recording.get()))) {
            f.begin(); var back = f.showMenu(101); recording.set(true); release(f, 200);
            recording.set(false); f.advanceTo(2_000); f.drainWorker();
            assertFalse(f.visible(f.shownElapsed)); assertFalse(f.retain());
            assertEquals(0, back.calls); assertEquals(0, f.captureCalls.get()); assertEquals(0, f.linkTaps.get());
        }
    }

    @Test public void protectionAfterBackRejectsRetentionAndReportsAlreadyCommitted() throws Exception {
        AtomicBoolean recording = new AtomicBoolean(); List<String> logs = new ArrayList<>();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> observation(uid, recording.get()))) {
            f.onLog = logs::add; f.begin(); var back = f.showMenu(101); release(f, 200);
            assertEquals(1, back.calls); recording.set(true);
            assertFalse(f.retain()); recording.set(false); assertFalse(f.retain());
            assertTrue(logs.stream().anyMatch(s -> s.contains("recording_protected") && s.contains("already_committed")));
            assertEquals(1, back.calls);
        }
    }

    @Test public void freshRealDownAfterProtectionCanCloseAndRetainOnce() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> observation(uid, recording.get()))) {
            f.begin(); var old = f.showMenu(101); recording.set(true); release(f, 200);
            recording.set(false); f.restoreMainWindow(); f.begin(); var next = f.showMenu(101); release(f, 200);
            assertEquals(0, old.calls); assertEquals(1, next.calls); assertTrue(f.retain()); assertFalse(f.retain());
        }
    }

    @Test public void anotherUidRemainsUnknownAndPreservesMenuBehavior() throws Exception {
        assertUnknownAllowsMenu(uid -> new SourceRecordingState.Observation(uid + 1, SystemClock.elapsedRealtime()));
    }

    @Test public void anonymousAndEmptyMetadataRemainUnknown() throws Exception {
        assertUnknownAllowsMenu(uid -> SourceRecordingState.UNKNOWN);
    }

    @Test public void providerFailureRemainsUnknown() throws Exception {
        assertUnknownAllowsMenu(uid -> { throw new IllegalStateException("metadata_unavailable"); });
    }

    @Test public void staleObservationRemainsUnknown() throws Exception {
        assertUnknownAllowsMenu(uid -> new SourceRecordingState.Observation(uid, SystemClock.elapsedRealtime() - 251));
    }

    @Test public void futureObservationRemainsUnknown() throws Exception {
        assertUnknownAllowsMenu(uid -> new SourceRecordingState.Observation(uid, SystemClock.elapsedRealtime() + 1));
    }

    @Test public void observationBeforeThisGestureRemainsUnknown() throws Exception {
        long beforeDown = SystemClock.elapsedRealtime() - 1;
        assertUnknownAllowsMenu(uid -> new SourceRecordingState.Observation(uid, beforeDown));
    }

    @Test public void exactAgeLimitStillProtectsAnAlreadyStartedGesture() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> recording.get()
                ? new SourceRecordingState.Observation(uid, SystemClock.elapsedRealtime() - 250) : SourceRecordingState.UNKNOWN)) {
            f.begin(); var back = f.showMenu(101); f.advanceTo(300); recording.set(true); release(f, 300);
            assertEquals(0, back.calls); assertFalse(f.retain());
        }
    }

    @Test public void foreignSnapshotDoesNotBorrowScopeRiskOrPollTheProvider() throws Exception {
        AtomicBoolean recording = new AtomicBoolean(); AtomicInteger reads = new AtomicInteger();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> {
            reads.incrementAndGet(); return observation(uid, recording.get());
        })) {
            f.begin(); var owner = owner(f); var windows = ReflectionHelpers.<ScopeCaptureWindow>getField(f, "windows");
            var foreign = windows.inspect(f.atms, 0, owner.x, owner.y);
            assertNotSame(owner, foreign); recording.set(true); int before = reads.get();
            f.capture.validateLinkTargetBehind(foreign, SystemMenuQueueFixture.HOST_UID);
            assertEquals(before, reads.get());
            assertThrows(IllegalStateException.class,
                    () -> f.capture.validateLinkTargetBehind(owner, SystemMenuQueueFixture.HOST_UID));
        }
    }

    @Test public void protectedSnapshotIsRetiredEvenWhenConfirmHasNoActiveRun() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, true, uid -> observation(uid, recording.get()))) {
            f.begin(); var target = owner(f); var config = f.configuration(); recording.set(true);
            assertThrows(IllegalStateException.class,
                    () -> f.capture.validateLinkTargetBehind(target, SystemMenuQueueFixture.HOST_UID));
            f.drainWorker(); release(f, 200); AtomicInteger completed = new AtomicInteger();
            f.capture.linkProbe().confirm(target, config, f.beganElapsed + 200, f.beganElapsed + 200,
                    url -> { assertNull(url); completed.incrementAndGet(); });
            f.drainWorker(); assertEquals(0, f.linkTaps.get()); assertEquals(1, completed.get());
        }
    }

    @Test public void queuedOldCleanupCannotCancelTheNextPhysicalOwner() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> observation(uid, recording.get()))) {
            f.begin(); var old = owner(f); recording.set(true);
            assertThrows(IllegalStateException.class,
                    () -> f.capture.validateLinkTargetBehind(old, SystemMenuQueueFixture.HOST_UID));
            f.advanceTo(200); f.queue(MotionEvent.ACTION_UP, 200); f.drainInput();
            recording.set(false); f.advanceTo(201); f.beginInputOnly(); f.drainWorker();
            var next = f.showMenu(101); release(f, 200);
            assertEquals(1, next.calls); assertTrue(f.retain());
        }
    }

    @Test public void protectionCannotAuthorizeADuplicateDownWithoutTheOldPhysicalUp() throws Exception {
        AtomicBoolean recording = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> observation(uid, recording.get()))) {
            f.begin(); var old = owner(f); recording.set(true);
            assertThrows(IllegalStateException.class,
                    () -> f.capture.validateLinkTargetBehind(old, SystemMenuQueueFixture.HOST_UID));
            recording.set(false); f.advanceTo(1); f.beginInputOnly(); f.drainWorker();
            assertNull("A protected old owner is not proof that its physical stream ended",
                    ReflectionHelpers.getField(f.capture, "origin"));
            var back = f.installMenu(101); assertFalse(f.visible(f.shownElapsed)); release(f, 200);
            assertEquals(0, back.calls); assertFalse(f.retain());
        }
    }

    @Test public void anOldTakeFailureCannotClearThePendingTargetOfANewPhysicalOwner() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var old = owner(f); var config = f.configuration();
            release(f, 200);
            publishPending(f, old, "old_test_nonce");
            AtomicReference<ScopeCaptureWindow.Snapshot> next = new AtomicReference<>();
            f.beforeWindowAccess = () -> {
                f.beforeWindowAccess = () -> { };
                f.begin(); next.set(owner(f));
                publishPending(f, next.get(), "new_test_nonce");
                throw new IllegalStateException("old_window_unavailable");
            };
            Bundle request = new Bundle(); request.putString("nonce", "old_test_nonce");
            assertTrue(f.capture.take(SystemMenuQueueFixture.HOST_UID, request, config).isEmpty());
            f.drainWorker(); assertNotNull(next.get());
            assertSame(next.get(), ReflectionHelpers.getField(f.capture, "pendingTarget"));
        }
    }

    @Test public void aFailedTakeCannotRetryItsNonceBeforeTheWorkerCleanupRuns() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, true, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var target = owner(f); var config = f.configuration(); release(f, 200);
            Bundle request = pendingResume(f, target, config);
            f.atms.mWindowManager.mRoot.display.mCurrentFocus = null;
            assertTrue(f.capture.take(SystemMenuQueueFixture.HOST_UID, request, config).isEmpty());
            f.atms.mWindowManager.mRoot.display.mCurrentFocus = f.main;
            assertTrue("Restoring the window must not reopen the failed owner's nonce before cleanup",
                    f.capture.take(SystemMenuQueueFixture.HOST_UID, request, config).isEmpty());
        }
    }

    @Test public void consumedTakeCleanupLeavesAReplacementPendingTargetIntact() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, true, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var target = owner(f); var config = f.configuration(); release(f, 200);
            Bundle request = pendingResume(f, target, config);
            assertEquals("resume", f.capture.take(SystemMenuQueueFixture.HOST_UID, request, config).getString("kind"));
            var windows = ReflectionHelpers.<ScopeCaptureWindow>getField(f, "windows");
            var replacement = windows.inspect(f.atms, 0, target.x, target.y);
            Bundle replacementRequest = pendingResume(f, replacement, config);
            f.drainWorker();
            assertSame(replacement, ReflectionHelpers.getField(f.capture, "pendingTarget"));
            assertTrue("Successful consume remains one-shot", f.capture.take(SystemMenuQueueFixture.HOST_UID, request, config).isEmpty());
            assertEquals("resume", f.capture.take(SystemMenuQueueFixture.HOST_UID, replacementRequest, config).getString("kind"));
        }
    }

    @Test public void capturedOldTakeContextCannotFallBackToLegacyAfterANewOwnerPublishes() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, true, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var old = owner(f); var config = f.configuration(); release(f, 200);
            Bundle oldRequest = pendingResume(f, old, config);
            Object captured = ReflectionHelpers.getField(f.capture, "pendingCapture");
            f.begin(); var next = owner(f); release(f, 200); Bundle nextRequest = pendingResume(f, next, config);
            f.beforeWindowAccess = () -> { throw new IllegalStateException("old_window_unavailable"); };
            var method = SystemScopeCapture.class.getDeclaredMethod("take", int.class, Bundle.class,
                    io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.class, captured.getClass());
            method.setAccessible(true);
            assertTrue(((Bundle) method.invoke(f.capture, SystemMenuQueueFixture.HOST_UID, oldRequest, config, captured)).isEmpty());
            f.beforeWindowAccess = () -> { }; f.drainWorker();
            assertEquals("A stale acquisition cannot clear the new owner's live nonce", "resume",
                    f.capture.take(SystemMenuQueueFixture.HOST_UID, nextRequest, config).getString("kind"));
        }
    }

    @Test public void aRequestNonceMustMatchTheCapturedOwnerBeforeAnyWindowCheck() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, true, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var target = owner(f); var config = f.configuration(); release(f, 200);
            Bundle oldRequest = pendingResume(f, target, config);
            Bundle nextRequest = pendingResume(f, target, config);
            f.beforeWindowAccess = () -> { throw new IllegalStateException("wrong_nonce_must_not_check_window"); };
            assertTrue(f.capture.take(SystemMenuQueueFixture.HOST_UID, oldRequest, config).isEmpty());
            f.beforeWindowAccess = () -> { }; f.drainWorker();
            assertEquals("A mismatched nonce must not revoke the current pending owner", "resume",
                    f.capture.take(SystemMenuQueueFixture.HOST_UID, nextRequest, config).getString("kind"));
        }
    }

    @Test public void protectionDiscardsRequestedAssistAndItsQueuedParser() throws Exception {
        AtomicBoolean recording = new AtomicBoolean(); AtomicInteger extracted = new AtomicInteger();
        AtomicReference<Consumer<Bundle>> reply = new AtomicReference<>(); ArrayDeque<Runnable> parsers = new ArrayDeque<>();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> observation(uid, recording.get()))) {
            Handler worker = ReflectionHelpers.getField(f, "worker");
            var content = new SystemAssistContent(worker, f::configuration, target -> { },
                    (target, accept) -> { reply.set(accept); return true; },
                    (structure, target) -> { extracted.incrementAndGet(); return new SystemAssistContent.Result("selected", List.of()); },
                    parsers::add, ignored -> { }, f.physical);
            f.capture.setAssistContent(content); f.begin(); var target = owner(f); var config = f.configuration();
            f.advanceTo(200); f.drainWorker(); assertNotNull(reply.get());
            AssistStructure structure = new AssistStructure();
            ReflectionHelpers.setField(structure, "mActivityComponent", new ComponentName(target.source, target.activity));
            Bundle result = new Bundle(); result.putParcelable("structure", structure); reply.get().accept(result);
            assertEquals(1, parsers.size()); recording.set(true);
            assertThrows(IllegalStateException.class,
                    () -> f.capture.validateLinkTargetBehind(target, SystemMenuQueueFixture.HOST_UID));
            f.drainWorker(); while (!parsers.isEmpty()) parsers.remove().run();
            assertEquals(0, extracted.get()); assertFalse(content.pending(target, config, f.beganElapsed, f.downUptime));
            assertEquals(SystemAssistContent.Result.EMPTY, content.read(target, config, f.beganElapsed, f.downUptime));
        }
    }

    @Test public void unavailableThenIdenticalPolicyCannotReviveAnEstablishedOwner() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var back = f.showMenu(101);
            var stored = RuntimeEnvironment.getApplication().getSharedPreferences("menu_queue_test", 0);
            String raw = stored.getString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY, null);
            assertTrue(stored.edit().remove(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY).commit());
            assertNull(f.configuration());
            assertTrue(stored.edit().putString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY, raw).commit());
            release(f, 200);
            assertEquals(0, back.calls); assertFalse(f.retain());
        }
    }

    @Test public void revocationDuringInspectIsInheritedByTheOwnerPublishedAfterward() throws Exception {
        AtomicBoolean revoked = new AtomicBoolean();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> SourceRecordingState.UNKNOWN)) {
            var stored = RuntimeEnvironment.getApplication().getSharedPreferences("menu_queue_test", 0);
            String raw = stored.getString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY, null);
            f.main.beforeBackRead = () -> {
                if (!revoked.compareAndSet(false, true)) return;
                assertTrue(stored.edit().remove(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY).commit());
                assertNull(f.configuration());
                assertTrue(stored.edit().putString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY, raw).commit());
            };
            f.begin(); assertTrue("The inspection must actually observe policy revocation", revoked.get());
            var back = f.installMenu(101); assertFalse(f.visible(f.shownElapsed)); release(f, 200);
            assertEquals(0, back.calls); assertFalse(f.retain());
        }
    }

    @Test public void aNewValidPolicyObservedByTheNewDownKeepsItsFirstGesture() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> SourceRecordingState.UNKNOWN)) {
            assertNotNull(f.configuration());
            var context = RuntimeEnvironment.getApplication();
            new io.github.yudigaga.taplusbridge.config.ConfigStore(context).setCaptureEnabled(false);
            assertTrue(context.getSharedPreferences("menu_queue_test", 0).edit()
                    .putString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY,
                            io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.from(context)).commit());
            f.begin(); var back = f.showMenu(101); release(f, 200);
            assertEquals(1, back.calls); assertTrue(f.retain()); assertFalse(f.retain());
        }
    }

    @Test public void restoredPolicyCannotStartAnAssistRequestAlreadyArmedByTheRevokedScope() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> SourceRecordingState.UNKNOWN)) {
            Handler worker = ReflectionHelpers.getField(f, "worker");
            var content = new SystemAssistContent(worker, f::configuration, target -> { },
                    (target, accept) -> { requests.incrementAndGet(); return true; },
                    (structure, target) -> SystemAssistContent.Result.EMPTY, Runnable::run, ignored -> { }, f.physical);
            f.capture.setAssistContent(content); f.begin();
            var stored = RuntimeEnvironment.getApplication().getSharedPreferences("menu_queue_test", 0);
            String raw = stored.getString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY, null);
            f.advanceTo(199);
            assertTrue(stored.edit().remove(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY).commit());
            assertNull(f.configuration());
            assertTrue(stored.edit().putString(io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot.KEY, raw).commit());
            f.advanceTo(200); f.drainWorker(); assertEquals(0, requests.get());
        }
    }

    @Test public void exactDuplicateAssistArmCannotDropTheScopeRevocationGate() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true); AtomicInteger requests = new AtomicInteger();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var target = owner(f); var config = f.configuration();
            Handler worker = ReflectionHelpers.getField(f, "worker");
            var content = new SystemAssistContent(worker, f::configuration, picked -> { },
                    (picked, accept) -> { requests.incrementAndGet(); return true; },
                    (structure, picked) -> SystemAssistContent.Result.EMPTY, Runnable::run, ignored -> { }, f.physical);
            content.arm(target, config, f.downUptime, f.beganElapsed, f.beganElapsed + 200, 0);
            content.arm(target, config, f.downUptime, f.beganElapsed, f.beganElapsed + 200, 0, allowed::get);
            content.arm(target, config, f.downUptime, f.beganElapsed, f.beganElapsed + 200, 0);
            allowed.set(false); f.advanceTo(200); f.drainWorker(); assertEquals(0, requests.get());
        }
    }

    @Test public void aForeignAssistSnapshotKeepsItsIndependentRevocationBoundary() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true); AtomicInteger requests = new AtomicInteger();
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, uid -> SourceRecordingState.UNKNOWN)) {
            f.begin(); var target = owner(f); var config = f.configuration();
            var windows = ReflectionHelpers.<ScopeCaptureWindow>getField(f, "windows");
            var foreign = windows.inspect(f.atms, 0, target.x, target.y); assertNotSame(target, foreign);
            Handler worker = ReflectionHelpers.getField(f, "worker");
            var content = new SystemAssistContent(worker, f::configuration, picked -> { },
                    (picked, accept) -> { requests.incrementAndGet(); return true; },
                    (structure, picked) -> SystemAssistContent.Result.EMPTY, Runnable::run, ignored -> { }, f.physical);
            content.arm(foreign, config, f.downUptime, f.beganElapsed, f.beganElapsed + 200, 0);
            content.arm(target, config, f.downUptime, f.beganElapsed, f.beganElapsed + 200, 0, allowed::get);
            allowed.set(false); content.cancelOwner(target, config, 0, f.downUptime);
            f.advanceTo(200); f.drainWorker(); assertEquals(1, requests.get());
            assertTrue(content.pending(foreign, config, f.beganElapsed, f.downUptime));
        }
    }

    @Test public void unknownMetadataPreservesModeTwoDoubleFingerCapture() throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(true, false, uid -> SourceRecordingState.UNKNOWN)) {
            Settings.System.putInt(RuntimeEnvironment.getApplication().getContentResolver(), "support_long_press_mode", 2);
            f.begin(); f.advanceTo(50);
            f.queue(MotionEvent.ACTION_POINTER_DOWN | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                    f.downUptime + 50, 0, f.downUptime, 2, InputDevice.SOURCE_TOUCHSCREEN); f.drainInput(); f.drainWorker();
            f.advanceTo(200);
            f.queue(MotionEvent.ACTION_POINTER_UP | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                    f.downUptime + 200, 0, f.downUptime, 2, InputDevice.SOURCE_TOUCHSCREEN); f.drainInput(); f.drainWorker();
            release(f, 201); f.advanceTo(600); f.drainWorker();
            assertEquals(1, f.captureCalls.get()); assertEquals(4, f.proceededInputs.get());
        }
    }

    private static SourceRecordingState.Observation observation(int uid, boolean active) {
        return active ? new SourceRecordingState.Observation(uid, SystemClock.elapsedRealtime()) : SourceRecordingState.UNKNOWN;
    }

    private static ScopeCaptureWindow.Snapshot owner(SystemMenuQueueFixture f) {
        var target = ReflectionHelpers.<ScopeCaptureWindow.Snapshot>getField(f.capture, "origin");
        assertNotNull(target); return target;
    }

    private static Bundle hostRisk(ScopeCaptureWindow.Snapshot target, String activity) {
        Bundle request = new Bundle();
        request.putString("source", target.source); request.putString("activity", activity);
        return request;
    }

    private static void release(SystemMenuQueueFixture f, long at) {
        f.advanceTo(at); f.queue(MotionEvent.ACTION_UP, at); f.drainInput(); f.drainWorker();
    }

    private static void assertUnknownAllowsMenu(SourceRecordingState.Source source) throws Exception {
        try (var f = SystemMenuQueueFixture.scopeProbe(false, false, source)) {
            f.begin(); var back = f.showMenu(101); release(f, 200);
            assertEquals(1, back.calls); assertTrue(f.retain()); assertFalse(f.retain());
        }
    }

    private static Bundle pendingResume(SystemMenuQueueFixture f, ScopeCaptureWindow.Snapshot target,
                                        io.github.yudigaga.taplusbridge.runtime.RuntimeSnapshot config) {
        var store = ReflectionHelpers.<io.github.yudigaga.taplusbridge.runtime.ScopeCaptureStore>getField(f.capture, "store");
        Bundle metadata = new Bundle(); metadata.putString("source", target.source);
        Bundle request = new Bundle(); request.putString("nonce", store.issueResume(SystemMenuQueueFixture.HOST_UID, config, metadata));
        publishPending(f,target,request.getString("nonce"));
        return request;
    }

    private static void publishPending(SystemMenuQueueFixture f, ScopeCaptureWindow.Snapshot target, String nonce) {
        try {
            Class<?> riskType = Class.forName(SystemScopeCapture.class.getName() + "$RecordingRisk");
            Class<?> pendingType = Class.forName(SystemScopeCapture.class.getName() + "$PendingCapture");
            var constructor = pendingType.getDeclaredConstructor(ScopeCaptureWindow.Snapshot.class, riskType, long.class, String.class);
            constructor.setAccessible(true);
            long input = ReflectionHelpers.<java.util.concurrent.atomic.AtomicLong>getField(f.capture,"inputGeneration").get();
            Object risk = target == ReflectionHelpers.getField(f.capture,"origin")
                    ? ReflectionHelpers.getField(f.capture,"recordingRisk") : null;
            ReflectionHelpers.setField(f.capture,"pendingCapture",constructor.newInstance(target,risk,input,nonce));
        } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
        ReflectionHelpers.setField(f.capture, "pendingTarget", target);
        ReflectionHelpers.setField(f.capture, "pendingInputGeneration",
                ReflectionHelpers.<java.util.concurrent.atomic.AtomicLong>getField(f.capture,"inputGeneration").get());
    }
}
