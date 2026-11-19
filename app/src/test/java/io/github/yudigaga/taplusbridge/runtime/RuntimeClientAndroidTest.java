package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ClipData;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import io.github.yudigaga.taplusbridge.drag.DragActionStore;
import io.github.yudigaga.taplusbridge.drag.DragPayload;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, shadows = RuntimeClientAndroidTest.FrameworkResultReceiverShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RuntimeClientAndroidTest {
    @Implements(value = ResultReceiver.class, callThroughByDefault = true)
    public static final class FrameworkResultReceiverShadow { }

    private BindContext context;
    private String readyToken;
    private DragActionStore.Choice consumed;
    private final List<AsyncScopeCall> scopeCalls = new ArrayList<>();
    private final List<BlockingCall> blockingCalls = new ArrayList<>();

    @Before public void setup() {
        RuntimeClient.testBridge(null);
        context = new BindContext(RuntimeEnvironment.getApplication());
    }

    @After public void cleanup() throws InterruptedException, ReflectiveOperationException {
        for (BlockingCall call : blockingCalls) call.release.countDown();
        RuntimeClient.testBridge(null);
        for (BlockingCall call : blockingCalls) assertTrue(call.exited.await(1, TimeUnit.SECONDS));
        for (AsyncScopeCall call : scopeCalls) assertTrue(call.returned.await(1, TimeUnit.SECONDS));
        awaitRiskCallsIdle();
        if (readyToken != null) DragActionStore.cancel(readyToken);
        if (consumed != null) consumed.payload().close();
    }

    @Test public void anUnconnectedCallIsNotSentOrReplayedWhenTheBinderArrives() {
        RuntimeClient.CallResult result = RuntimeClient.callDetailed(context, "drag_choose", choice());
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED, result.state());
        assertNull(result.response());
        Bridge bridge = new Bridge((method, request) -> accepted());
        replyConnected(bridge);
        assertEquals(0, bridge.calls);
        assertEquals(RuntimeClient.CallState.REPLIED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(1, bridge.calls);
    }

    @Test public void anExplicitDeclineRemainsAReplyAndKeepsItsReason() {
        Bundle declined = new Bundle();
        declined.putBoolean("accepted", false); declined.putString("reason", "device_locked");
        Bridge bridge = new Bridge((method, request) -> declined);
        RuntimeClient.testBridge(bridge);
        RuntimeClient.CallResult result = RuntimeClient.callDetailed(context, "drag_choose", choice());
        assertEquals(RuntimeClient.CallState.REPLIED, result.state());
        assertSame(declined, result.response());
        assertFalse(result.response().getBoolean("accepted"));
        assertEquals("device_locked", result.response().getString("reason"));
        assertEquals(1, bridge.calls); assertTrue(context.commands.isEmpty());
    }

    @Test public void anAcceptedReplyPreservesTheLegacyCallContract() {
        Bundle reply = accepted(), request = choice();
        Bridge bridge = new Bridge((method, sent) -> {
            assertEquals("drag_choose", method); assertSame(request, sent); return reply;
        });
        RuntimeClient.testBridge(bridge);
        assertSame(reply, RuntimeClient.call(context, "drag_choose", request));
        assertEquals(1, bridge.calls);
    }

    @Test public void remoteFailureBeforeAnObservableEffectIsStillUnknownAndNeverReplayed() {
        Bridge failed = new Bridge((method, request) -> { throw new RemoteException("transport failed"); });
        RuntimeClient.testBridge(failed);
        RuntimeClient.CallResult result = RuntimeClient.callDetailed(context, "drag_choose", choice());
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, result.state()); assertNull(result.response());
        Bridge reconnected = new Bridge((method, request) -> accepted());
        replyConnected(reconnected);
        assertEquals(1, failed.calls); assertEquals(0, reconnected.calls);
    }

    @Test public void replyLossAfterAnEffectIsUnknownAndDoesNotRepeatTheEffect() {
        AtomicInteger launches = new AtomicInteger();
        Bridge failed = new Bridge((method, request) -> {
            launches.incrementAndGet(); throw new RemoteException("reply lost after launch");
        });
        RuntimeClient.testBridge(failed);
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        Bridge reconnected = new Bridge((method, request) -> { launches.incrementAndGet(); return accepted(); });
        replyConnected(reconnected);
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, launches.get()); assertEquals(0, reconnected.calls);
    }

    @Test public void aRuntimeFailureIsUnknownAndLegacyCallStillReturnsNull() {
        Bridge failed = new Bridge((method, request) -> { throw new IllegalStateException("dispatch failed"); });
        RuntimeClient.testBridge(failed);
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        RuntimeClient.testBridge(failed);
        assertNull(RuntimeClient.call(context, "drag_choose", choice()));
        assertEquals(2, failed.calls);
    }

    @Test public void aNullReplyCannotBeReportedAsAnExplicitDecline() {
        Bridge bridge = new Bridge((method, request) -> null);
        RuntimeClient.testBridge(bridge);
        RuntimeClient.CallResult result = RuntimeClient.callDetailed(context, "drag_choose", choice());
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, result.state()); assertNull(result.response());
        assertEquals(1, bridge.calls);
    }

    @Test public void replyLossBeforeChoiceConsumptionDoesNotDiscardOrReplayTheSingleUseResult() throws Exception {
        readyToken = readyChoice();
        Bridge failed = new Bridge((method, request) -> { throw new RemoteException("reply lost before consumption"); });
        RuntimeClient.testBridge(failed);
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN,
                RuntimeClient.callDetailed(context, "drag_choose", choice(readyToken)).state());
        Bridge reconnected = new Bridge((method, request) -> accepted()); replyConnected(reconnected);
        assertEquals(0, reconnected.calls);
        consumed = DragActionStore.takeChoice(readyToken);
        assertNotNull(consumed); assertEquals("original", consumed.originalText());
        assertNull(DragActionStore.takeChoice(readyToken));
    }

    @Test public void replyLossAfterChoiceConsumptionCannotConsumeTheResultAgain() throws Exception {
        readyToken = readyChoice();
        Bridge failed = new Bridge((method, request) -> {
            consumed = DragActionStore.takeChoice(request.getString("token"));
            assertNotNull(consumed); throw new RemoteException("reply lost after consumption");
        });
        RuntimeClient.testBridge(failed);
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN,
                RuntimeClient.callDetailed(context, "drag_choose", choice(readyToken)).state());
        Bridge reconnected = new Bridge((method, request) -> accepted()); replyConnected(reconnected);
        assertEquals(0, reconnected.calls); assertNull(DragActionStore.takeChoice(readyToken));
    }

    @Test @Config(sdk = 28, manifest = Config.NONE)
    public void androidNineDoesNotAttemptTheAndroidThirteenRuntimeConnection() {
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertTrue(context.commands.isEmpty());
    }

    @Test public void anAlreadyDeadCachedBinderIsNotInvoked() {
        Bridge dead = new Bridge((method, request) -> { fail("A known dead Binder cannot receive a request"); return null; });
        dead.alive = false; RuntimeClient.testBridge(dead);
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(0, dead.calls); assertEquals(1, context.commands.size());
    }

    @Test public void aNullCachedBinderIsNotConnectedOrInvoked() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.nullBinder = true;
        assertCachedBridgeUnavailable(bridge);
    }

    @Test public void aCachedAsBinderFailureIsNotConnectedOrInvoked() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failAsBinder = true;
        assertCachedBridgeUnavailable(bridge);
    }

    @Test public void aCachedLivenessFailureIsNotConnectedOrInvoked() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failLiveness = true;
        assertCachedBridgeUnavailable(bridge);
    }

    @Test public void connectCanReplaceACachedInterfaceWithoutABinder() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.nullBinder = true;
        assertConnectReplacesUnavailableBridge(bridge);
    }

    @Test public void connectCanReplaceACachedAsBinderFailure() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failAsBinder = true;
        assertConnectReplacesUnavailableBridge(bridge);
    }

    @Test public void connectCanReplaceACachedLivenessFailure() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failLiveness = true;
        assertConnectReplacesUnavailableBridge(bridge);
    }

    @Test public void anOldLivenessFailureCannotClearItsReplacement() {
        Bridge replacement = new Bridge((method, request) -> accepted());
        Bridge old = new Bridge((method, request) -> accepted()); old.failLiveness = true;
        old.onLiveness = () -> RuntimeClient.testBridge(replacement);
        RuntimeClient.testBridge(old);
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(0, old.calls); assertEquals(0, replacement.calls);
        assertEquals(RuntimeClient.CallState.REPLIED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(1, replacement.calls);
    }

    @Test public void discoveryFailureCannotEscapeAnUnconnectedCallOrLeaveDiscoveryStuck() {
        context.failBroadcast = true;
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        context.failBroadcast = false;
        Bridge bridge = new Bridge((method, request) -> accepted());
        RuntimeClient.connect(context, null); replyConnected(bridge);
        assertEquals(2, context.broadcasts);
    }

    @Test public void discoveryFailureCannotChangeAnAttemptedCallsUnknownOutcome() {
        Bridge failed = new Bridge((method, request) -> { throw new RemoteException("reply lost"); });
        RuntimeClient.testBridge(failed); context.failBroadcast = true;
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(1, failed.calls); assertEquals(1, context.broadcasts);
    }

    @Test public void anUnusableReplacementDuringReconnectDoesNotEscapeAnUnknownOutcome() {
        Bridge replacement = new Bridge((method, request) -> accepted()); replacement.failAsBinder = true;
        Bridge failed = new Bridge((method, request) -> {
            RuntimeClient.testBridge(replacement); throw new RemoteException("old reply lost");
        });
        RuntimeClient.testBridge(failed);
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(1, failed.calls); assertEquals(0, replacement.calls);
    }

    @Test public void aNullIncomingBinderCannotAnnounceAConnection() {
        assertIncomingBridgeUnavailable(new Bundle(), null);
    }

    @Test public void aWrongTypedIncomingBinderCannotAnnounceAConnection() {
        Bundle data = new Bundle(); data.putString("bridge", "invalid");
        assertIncomingBridgeUnavailable(data, null);
    }

    @Test public void anIncomingLivenessFailureCannotAnnounceAConnection() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failLiveness = true;
        assertIncomingBridgeUnavailable(binderData(bridge.handle), bridge);
        assertEquals(0, bridge.deathRegistrations);
    }

    @Test public void anIncomingQueryLocalInterfaceFailureCannotAnnounceAConnection() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failQuery = true;
        assertIncomingBridgeUnavailable(binderData(bridge.handle), bridge);
        assertEquals(1, bridge.queries); assertEquals(0, bridge.deathRegistrations);
    }

    @Test public void anIncomingInterfaceWithoutABinderCannotAnnounceAConnection() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.nullBinder = true;
        assertIncomingBridgeUnavailable(binderData(bridge.handle), bridge);
        assertEquals(0, bridge.deathRegistrations);
    }

    @Test public void anIncomingAsBinderFailureCannotAnnounceAConnection() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failAsBinder = true;
        assertIncomingBridgeUnavailable(binderData(bridge.handle), bridge);
        assertEquals(0, bridge.deathRegistrations);
    }

    @Test public void binderDeathRevokesTheConnectionWithoutReplayingThePreviousCall() {
        Bridge bridge = new Bridge((method, request) -> accepted());
        RuntimeClient.connect(context, null); replyConnected(bridge);
        bridge.die();
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(0, bridge.calls);
    }

    @Test public void aLateDeathFromTheOldBinderCannotClearItsReplacement() {
        Bridge old = new Bridge((method, request) -> accepted());
        RuntimeClient.connect(context, null); replyConnected(old);
        Bridge replacement = new Bridge((method, request) -> accepted());
        RuntimeClient.testBridge(replacement); old.die();
        assertEquals(RuntimeClient.CallState.REPLIED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(1, replacement.calls);
    }

    @Test public void failureFromAnOldCallCannotClearAConnectionInstalledWhileItWasRunning() {
        Bridge replacement = new Bridge((method, request) -> accepted());
        Bridge old = new Bridge((method, request) -> {
            RuntimeClient.testBridge(replacement); throw new RemoteException("old call failed");
        });
        RuntimeClient.testBridge(old);
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(RuntimeClient.CallState.REPLIED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(1, replacement.calls);
    }

    @Test public void deathRegistrationFailureDoesNotAnnounceAUsableConnection() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failDeathRegistration = true;
        AtomicInteger connected = new AtomicInteger();
        RuntimeClient.connect(context, connected::incrementAndGet); context.reply(bridge);
        assertEquals(1, bridge.deathRegistrations);
        assertEquals(0, connected.get());
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(0, bridge.calls);
    }

    @Test public void aRuntimeDeathRegistrationFailureDoesNotAnnounceAUsableConnection() {
        Bridge bridge = new Bridge((method, request) -> accepted()); bridge.failDeathRegistrationRuntime = true;
        assertIncomingBridgeUnavailable(binderData(bridge.handle), bridge);
        assertEquals(1, bridge.deathRegistrations);
    }

    @Test public void aPostRegistrationLivenessFailureRevokesTheCandidate() {
        Bridge bridge = new Bridge((method, request) -> accepted());
        bridge.onDeathRegistration = () -> bridge.failLiveness = true;
        AtomicInteger connected = new AtomicInteger();
        RuntimeClient.connect(context, connected::incrementAndGet); context.reply(bridge);
        assertEquals(1, bridge.deathRegistrations); assertEquals(1, bridge.deaths.size());
        assertEquals(0, connected.get());
        bridge.failLiveness = false;
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(null, "drag_choose", choice()).state());
        assertEquals(0, bridge.calls);
    }

    @Test public void aPostRegistrationBinderDeathRevokesTheCandidate() {
        Bridge bridge = new Bridge((method, request) -> accepted());
        bridge.onDeathRegistration = () -> bridge.alive = false;
        AtomicInteger connected = new AtomicInteger();
        RuntimeClient.connect(context, connected::incrementAndGet); context.reply(bridge);
        assertEquals(1, bridge.deathRegistrations); assertEquals(1, bridge.deaths.size());
        assertEquals(0, connected.get());
        bridge.alive = true;
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(null, "drag_choose", choice()).state());
        assertEquals(0, bridge.calls);
    }

    @Test public void anOldDeathRegistrationFailureCannotClearItsReplacement() {
        Bridge replacement = new Bridge((method, request) -> accepted());
        Bridge old = new Bridge((method, request) -> accepted()); old.failDeathRegistrationRuntime = true;
        old.onDeathRegistration = () -> RuntimeClient.testBridge(replacement);
        AtomicInteger connected = new AtomicInteger();
        RuntimeClient.connect(context, connected::incrementAndGet); context.reply(old);
        assertEquals(1, old.deathRegistrations); assertEquals(0, connected.get());
        assertEquals(RuntimeClient.CallState.REPLIED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(0, old.calls); assertEquals(1, replacement.calls);
    }

    @Test public void aThrowingConnectionObserverDoesNotRevokeAUsableBinder() {
        Bridge bridge = new Bridge((method, request) -> accepted());
        RuntimeClient.connect(context, () -> { throw new IllegalStateException("observer failed"); });
        replyConnected(bridge);
        RuntimeClient.connect(context, () -> { throw new IllegalStateException("cached observer failed"); });
        assertEquals(RuntimeClient.CallState.REPLIED,
                RuntimeClient.callDetailed(context, "drag_choose", choice()).state());
        assertEquals(1, bridge.calls);
    }

    @Test public void scopeRecordingRiskWithoutABridgeIsNotConnectedAndNeverReplayed() throws Exception {
        AsyncScopeCall call = callScopeAsync(new Bundle());
        assertTrue(call.returned.await(1, TimeUnit.SECONDS));
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED, call.result.get().state());
        assertNull(call.result.get().response());

        Bridge bridge = new Bridge((method, request) -> accepted());
        replyConnected(bridge);
        assertEquals(0, bridge.calls);
    }

    @Test public void scopeRecordingRiskKeepsAnExplicitPositiveReply() {
        Bundle request = new Bundle();
        request.putString("source", "com.example.source");
        Bundle reply = new Bundle();
        reply.putBoolean("protected", true);
        AtomicReference<String> methodSeen = new AtomicReference<>();
        AtomicReference<Bundle> requestSeen = new AtomicReference<>();
        Bridge bridge = new Bridge((method, sent) -> {
            methodSeen.set(method);
            requestSeen.set(sent);
            return reply;
        });
        RuntimeClient.testBridge(bridge);

        RuntimeClient.CallResult result = RuntimeClient.callScopeRecordingRisk(context, request, 1_000);

        assertEquals(RuntimeClient.CallState.REPLIED, result.state());
        assertSame(reply, result.response());
        assertTrue(result.response().getBoolean("protected"));
        assertEquals("scope_recording_risk", methodSeen.get());
        assertSame(request, requestSeen.get());
        assertEquals(1, bridge.calls);
    }

    @Test public void scopeRecordingRiskTreatsANullReplyAsUnknown() {
        Bridge bridge = new Bridge((method, request) -> null);
        RuntimeClient.testBridge(bridge);

        RuntimeClient.CallResult result = RuntimeClient.callScopeRecordingRisk(context, new Bundle(), 1_000);

        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, result.state());
        assertNull(result.response());
        assertEquals(1, bridge.calls);
    }

    @Test public void scopeRecordingRiskRemoteFailureIsUnknownAndNeverReplayed() {
        Bridge failed = new Bridge((method, request) -> { throw new RemoteException("reply lost"); });
        RuntimeClient.testBridge(failed);

        RuntimeClient.CallResult result = RuntimeClient.callScopeRecordingRisk(context, new Bundle(), 1_000);
        Bridge replacement = new Bridge((method, request) -> accepted());
        replyConnected(replacement);

        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, result.state());
        assertNull(result.response());
        assertEquals(1, failed.calls);
        assertEquals(0, replacement.calls);
    }

    @Test public void twoBlockedScopeQueriesRejectAThirdWithoutQueueing() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(2);
        BlockingCall blocked = new BlockingCall(release, exited);
        blockingCalls.add(blocked);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicReference<String> methodSeen = new AtomicReference<>();
        Bridge bridge = new Bridge((method, request) -> {
            methodSeen.set(method);
            dispatches.incrementAndGet();
            entered.countDown();
            awaitIgnoringInterrupts(release);
            exited.countDown();
            return protectedReply();
        });
        RuntimeClient.testBridge(bridge);

        AsyncScopeCall first = callScopeAsync(new Bundle());
        AsyncScopeCall second = callScopeAsync(new Bundle());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals("scope_recording_risk", methodSeen.get());
        assertTrue(first.returned.await(1, TimeUnit.SECONDS));
        assertTrue(second.returned.await(1, TimeUnit.SECONDS));
        assertTrue("16 ms caller budget plus scheduling margin must stay under 80 ms",
                first.elapsedNanos < TimeUnit.MILLISECONDS.toNanos(80));
        assertTrue("16 ms caller budget plus scheduling margin must stay under 80 ms",
                second.elapsedNanos < TimeUnit.MILLISECONDS.toNanos(80));
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, first.result.get().state());
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, second.result.get().state());

        RuntimeClient.CallResult third = RuntimeClient.callScopeRecordingRisk(context, new Bundle());
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, third.state());
        assertNull(third.response());
        assertEquals(2, dispatches.get());

        release.countDown();
        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertEquals(2, dispatches.get());
    }

    @Test public void releasingATimedOutScopeQueryDoesNotReplayIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        BlockingCall blocked = new BlockingCall(release, exited);
        blockingCalls.add(blocked);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicBoolean queryCompleted = new AtomicBoolean();
        AtomicReference<String> methodSeen = new AtomicReference<>();
        Bridge bridge = new Bridge((method, request) -> {
            methodSeen.set(method);
            dispatches.incrementAndGet();
            entered.countDown();
            awaitIgnoringInterrupts(release);
            queryCompleted.set(true);
            exited.countDown();
            return protectedReply();
        });
        RuntimeClient.testBridge(bridge);

        AsyncScopeCall call = callScopeAsync(new Bundle());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals("scope_recording_risk", methodSeen.get());
        assertTrue(call.returned.await(1, TimeUnit.SECONDS));
        assertTrue("16 ms caller budget plus scheduling margin must stay under 80 ms",
                call.elapsedNanos < TimeUnit.MILLISECONDS.toNanos(80));
        RuntimeClient.CallResult timedOut = call.result.get();
        assertEquals(RuntimeClient.CallState.OUTCOME_UNKNOWN, timedOut.state());
        assertFalse(queryCompleted.get());

        release.countDown();
        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertTrue(queryCompleted.get());
        assertEquals(1, dispatches.get());
        assertSame(timedOut, call.result.get());
    }

    private void assertCachedBridgeUnavailable(Bridge bridge) {
        RuntimeClient.testBridge(bridge);
        RuntimeClient.CallResult result = RuntimeClient.callDetailed(context, "drag_choose", choice());
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED, result.state()); assertNull(result.response());
        assertEquals(0, bridge.calls); assertEquals(1, context.commands.size());
    }

    private void assertConnectReplacesUnavailableBridge(Bridge bridge) {
        RuntimeClient.testBridge(bridge);
        AtomicInteger connected = new AtomicInteger();
        RuntimeClient.connect(context, connected::incrementAndGet);
        assertEquals(0, connected.get()); assertEquals(0, bridge.calls);
        Bridge replacement = new Bridge((method, request) -> accepted()); replyConnected(replacement);
        assertEquals(1, connected.get());
    }

    private void assertIncomingBridgeUnavailable(Bundle data, Bridge bridge) {
        AtomicInteger connected = new AtomicInteger();
        RuntimeClient.connect(context, connected::incrementAndGet); context.replyData(data);
        assertEquals(0, connected.get());
        if (bridge != null) assertEquals(0, bridge.calls);
        assertEquals(RuntimeClient.CallState.NOT_CONNECTED,
                RuntimeClient.callDetailed(null, "drag_choose", choice()).state());
        Bridge replacement = new Bridge((method, request) -> accepted()); replyConnected(replacement);
        assertEquals(1, connected.get());
    }

    private void replyConnected(Bridge bridge) {
        context.reply(bridge);
        assertEquals(1, bridge.deathRegistrations); assertEquals(1, bridge.deaths.size());
        AtomicInteger connected = new AtomicInteger(); RuntimeClient.connect(context, connected::incrementAndGet);
        assertEquals(1, connected.get()); assertEquals(0, bridge.calls);
    }

    private static Bundle binderData(IBinder binder) {
        Bundle data = new Bundle(); data.putBinder("bridge", binder); return data;
    }

    private static Bundle choice() {
        return choice("A".repeat(43));
    }

    private static Bundle choice(String token) {
        Bundle request = new Bundle(); request.putString("token", token); return request;
    }

    private String readyChoice() throws Exception {
        DragPayload payload = DragPayload.read(context, ClipData.newPlainText("", "original"),
                "com.example.source", new CancellationSignal());
        String token = DragActionStore.issue(context, "copy", payload);
        assertNotNull(DragActionStore.take(context, token));
        String completed = DragActionStore.complete(context, token, "recognized");
        assertNotNull(completed); return completed;
    }

    private static Bundle accepted() {
        Bundle response = new Bundle(); response.putBoolean("accepted", true); return response;
    }

    private static Bundle protectedReply() {
        Bundle response = new Bundle(); response.putBoolean("protected", true); return response;
    }

    private AsyncScopeCall callScopeAsync(Bundle request) {
        AsyncScopeCall call = new AsyncScopeCall(request);
        scopeCalls.add(call);
        return call;
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release) {
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitRiskCallsIdle() throws ReflectiveOperationException, InterruptedException {
        Field field = RuntimeClient.class.getDeclaredField("SCOPE_RECORDING_RISK_CALLS");
        field.setAccessible(true);
        ThreadPoolExecutor calls = (ThreadPoolExecutor) field.get(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (calls.getActiveCount() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals("A previous query must finish before this test class is reused", 0, calls.getActiveCount());
    }

    private final class AsyncScopeCall {
        final AtomicReference<RuntimeClient.CallResult> result = new AtomicReference<>();
        final CountDownLatch returned = new CountDownLatch(1);
        volatile long elapsedNanos;

        AsyncScopeCall(Bundle request) {
            Thread caller = new Thread(() -> {
                long started = System.nanoTime();
                try {
                    result.set(RuntimeClient.callScopeRecordingRisk(context, request));
                } finally {
                    elapsedNanos = System.nanoTime() - started;
                    returned.countDown();
                }
            }, "runtime-client-scope-caller");
            caller.setDaemon(true);
            caller.start();
        }
    }

    private static final class BlockingCall {
        final CountDownLatch release;
        final CountDownLatch exited;

        BlockingCall(CountDownLatch release, CountDownLatch exited) {
            this.release = release;
            this.exited = exited;
        }
    }

    @FunctionalInterface private interface Call {
        Bundle run(String method, Bundle request) throws RemoteException;
    }

    private static final class Bridge extends IRuntimeBridge.Stub {
        final Call call;
        final List<IBinder.DeathRecipient> deaths = new ArrayList<>();
        final IBinder handle;
        int calls, queries, deathRegistrations;
        boolean alive = true, nullBinder, failAsBinder, failLiveness, failQuery;
        boolean failDeathRegistration, failDeathRegistrationRuntime;
        Runnable onLiveness, onDeathRegistration;
        Bridge(Call call) {
            this.call = call;
            handle = (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(), new Class<?>[]{IBinder.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isBinderAlive", "pingBinder" -> {
                            if (onLiveness != null) onLiveness.run();
                            if (failLiveness) throw new IllegalStateException("liveness unavailable");
                            yield alive;
                        }
                        case "queryLocalInterface" -> {
                            queries++;
                            if (failQuery) throw new IllegalStateException("interface unavailable");
                            yield this;
                        }
                        case "linkToDeath" -> {
                            deathRegistrations++;
                            if (onDeathRegistration != null) onDeathRegistration.run();
                            if (failDeathRegistration) throw new RemoteException("already dead");
                            if (failDeathRegistrationRuntime) throw new IllegalStateException("registration unavailable");
                            deaths.add((IBinder.DeathRecipient) args[0]); yield null;
                        }
                        case "unlinkToDeath" -> deaths.remove(args[0]);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Runtime test Binder";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        @Override public IBinder asBinder() {
            if (failAsBinder) throw new IllegalStateException("handle unavailable");
            return nullBinder ? null : handle;
        }
        @Override public Bundle call(String method, Bundle request) throws RemoteException {
            calls++; return call.run(method, request);
        }
        void die() { alive = false; for (var death : List.copyOf(deaths)) death.binderDied(); }
    }

    private static final class BindContext extends ContextWrapper {
        final List<Intent> commands = new ArrayList<>();
        int broadcasts;
        boolean failBroadcast;
        BindContext(Context context) { super(context); }
        @Override public void sendBroadcast(Intent intent) {
            broadcasts++;
            if (failBroadcast) throw new SecurityException("discovery unavailable");
            commands.add(new Intent(intent));
        }
        void reply(Bridge bridge) { replyData(binderData(bridge.asBinder())); }
        @SuppressWarnings("deprecation") void replyData(Bundle data) {
            assertFalse(commands.isEmpty());
            ResultReceiver receiver = commands.get(commands.size() - 1).getParcelableExtra("reply");
            assertNotNull(receiver);
            receiver.send(1, data);
            ShadowLooper.idleMainLooper();
        }
    }
}
