package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Exercises real descriptor pipes and the lifetime of the in-memory screenshot capability. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class ScopeCaptureStoreAndroidTest {
    private static final int OWNER = 21001;
    private static final String SOURCE = "com.example.source", OTHER = "com.example.other";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==");
    private Context context;
    private ConfigStore config;
    private ScopeCaptureStore store;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(RuleRepository.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        config = new ConfigStore(context);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), true));
        store = new ScopeCaptureStore();
    }
    @After public void teardown() { store.clear(); }
    private RuntimeSnapshot snapshot() { return RuntimeSnapshot.parse(RuntimeSnapshot.from(context)); }
    private Bundle metadata() {
        Bundle data = new Bundle(); data.putString("source", SOURCE); data.putInt("source_uid", 22001);
        data.putString("activity", SOURCE + ".Main"); data.putInt("user", 0);
        data.putFloat("x", 50); data.putFloat("y", 80); return data;
    }
    private Bundle request(String nonce) { Bundle result = new Bundle(); result.putString("nonce", nonce); return result; }
    @SuppressWarnings("deprecation")
    private byte[] readPipe(Bundle capture) throws Exception {
        assertFalse(capture.isEmpty());
        ParcelFileDescriptor descriptor = capture.getParcelable("fd"); assertNotNull(descriptor);
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            // Wait for the asynchronous producer, including Robolectric's file-backed pipe
            // implementation which may otherwise report EOF before the writer starts.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (input.available() < capture.getInt("bytes") && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(capture.getInt("bytes"), input.available());
            byte[] bytes = input.readAllBytes(); assertEquals(capture.getInt("bytes"), bytes.length); return bytes;
        }
    }

    @Test public void clearErasesTheQueuedProducerPixelsBeforeItsWorkerCanStart() throws Exception {
        var writer = (java.util.concurrent.ExecutorService) org.robolectric.util.ReflectionHelpers.getField(store, "writer");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        writer.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        RuntimeSnapshot accepted = snapshot();
        String nonce = store.issue(OWNER, accepted, metadata(), PNG);
        var field = ScopeCaptureStore.class.getDeclaredField("entry"); field.setAccessible(true);
        Object entry = field.get(store);
        var png = entry.getClass().getDeclaredMethod("png"); png.setAccessible(true);
        byte[] retained = (byte[]) png.invoke(entry);
        Bundle capture = null;
        try {
            capture = store.take(OWNER, request(nonce), accepted); assertFalse(capture.isEmpty());
            store.clear();
            assertArrayEquals("cancellation must release queued pixel data, even before the writer runs",
                    new byte[retained.length], retained);
        } finally {
            release.countDown(); writer.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (capture != null && capture.get("fd") instanceof ParcelFileDescriptor fd) fd.close();
        }
    }

    @Test(timeout = 20_000) public void realDescriptorTransfersOneImmutablePngAndMetadataCopyExactlyOnce() throws Exception {
        Bundle data = metadata(); byte[] image = PNG.clone();
        RuntimeSnapshot accepted = snapshot(); String nonce = store.issue(OWNER, accepted, data, image);
        assertNotNull(nonce); assertTrue(nonce.length() >= 24);
        data.putString("source", OTHER); data.putFloat("x", 999); image[0] = 0;
        Bundle capture = store.take(OWNER, request(nonce), accepted);
        assertEquals(SOURCE, capture.getString("source")); assertEquals(50, capture.getFloat("x"), 0);
        assertArrayEquals(PNG, readPipe(capture));
        assertTrue(store.take(OWNER, request(nonce), accepted).isEmpty());
    }

    @Test public void realSourceTextHasItsOwnSingleUseContentCapability() throws Exception {
        RuntimeSnapshot initial=snapshot();
        var issue=ScopeCaptureStore.class.getDeclaredMethod("issueContent",int.class,RuntimeSnapshot.class,
                Bundle.class,String.class,String.class); issue.setAccessible(true);
        String nonce=(String)issue.invoke(store,OWNER,initial,metadata(),"页面本轮真实文字",null);
        Bundle result=store.take(OWNER,request(nonce),initial);
        assertEquals("content",result.getString("kind"));assertEquals("页面本轮真实文字",result.getString("text"));
        assertFalse(result.containsKey("url"));assertFalse(result.containsKey("fd"));
        assertTrue(store.take(OWNER,request(nonce),initial).isEmpty());
    }

    @Test public void anEmptyNativeCallbackCannotEraseAlreadyPreparedRealTextBeforeTheHostConnects() throws Exception {
        RuntimeSnapshot initial=snapshot();
        var issue=ScopeCaptureStore.class.getDeclaredMethod("issueContent",int.class,RuntimeSnapshot.class,
                Bundle.class,String.class,String.class);issue.setAccessible(true);
        String nonce=(String)issue.invoke(store,OWNER,initial,metadata(),"已准备的真实正文",null);
        var nativeResult=ScopeCaptureStore.class.getDeclaredMethod("nativeResult",String.class,boolean.class);
        nativeResult.setAccessible(true);nativeResult.invoke(store,SOURCE,false);
        assertEquals("已准备的真实正文",store.take(OWNER,request(nonce),initial).getString("text"));
    }

    @Test(timeout = 20_000) public void wrongOwnerNonceAndMissingRequestDoNotConsumeTheRightfulCapture() throws Exception {
        RuntimeSnapshot accepted = snapshot(); String nonce = store.issue(OWNER, accepted, metadata(), PNG);
        assertTrue(store.take(OWNER + 1, request(nonce), accepted).isEmpty());
        assertTrue(store.take(OWNER, request(nonce + "wrong"), accepted).isEmpty());
        assertTrue(store.take(OWNER, Bundle.EMPTY, accepted).isEmpty());
        assertTrue(store.take(OWNER, null, accepted).isEmpty());
        assertArrayEquals(PNG, readPipe(store.take(OWNER, request(nonce), accepted)));
    }

    @Test(timeout = 20_000) public void screenshotExpiresAtTenSecondsAndCannotBeReplayedAfterExpiry() throws Exception {
        RuntimeSnapshot accepted = snapshot(); String nonce = store.issue(OWNER, accepted, metadata(), PNG);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        assertTrue(store.take(OWNER, request(nonce), accepted).isEmpty());
        assertTrue(store.take(OWNER, request(nonce), accepted).isEmpty());
        nonce = store.issue(OWNER, accepted, metadata(), PNG);
        ShadowSystemClock.advanceBy(Duration.ofMillis(9999));
        assertArrayEquals(PNG, readPipe(store.take(OWNER, request(nonce), accepted)));
    }

    @Test public void changingTheModeOrSnapshotIdentityRevokesAnExistingCapability() {
        RuntimeSnapshot initial = snapshot(); String nonce = store.issue(OWNER, initial, metadata(), PNG);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of(SOURCE)));
        RuntimeSnapshot changed = snapshot(); assertNotEquals(initial.identity, changed.identity);
        assertTrue(store.take(OWNER, request(nonce), changed).isEmpty());
        assertTrue(store.take(OWNER, request(nonce), initial).isEmpty());
        nonce = store.issue(OWNER, changed, metadata(), PNG);
        config.setNativeEnabled(false);
        assertTrue(store.take(OWNER, request(nonce), snapshot()).isEmpty());
    }

    @Test public void closingTheFallbackSwitchRejectsNewImagesAndRevokesTheAlreadyIssuedNonce() {
        RuntimeSnapshot initial = snapshot(); assertTrue(initial.scopeCapture);
        String nonce = store.issue(OWNER, initial, metadata(), PNG);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), false));
        RuntimeSnapshot disabled = snapshot(); assertFalse(disabled.scopeCapture);
        assertNotEquals(initial.identity, disabled.identity);
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, disabled, metadata(), PNG));
        assertTrue(store.take(OWNER, request(nonce), disabled).isEmpty());
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST, List.of(), List.of(), true));
        assertTrue(store.take(OWNER, request(nonce), snapshot()).isEmpty());
    }

    @Test(timeout = 20_000) public void onlyANativeResultForTheSameSourceCancelsTheScreenshot() throws Exception {
        RuntimeSnapshot accepted = snapshot(); String nonce = store.issue(OWNER, accepted, metadata(), PNG);
        store.nativeResult(OTHER); store.nativeResult(null);
        assertArrayEquals(PNG, readPipe(store.take(OWNER, request(nonce), accepted)));
        nonce = store.issue(OWNER, accepted, metadata(), PNG);
        store.nativeResult(SOURCE);
        assertTrue(store.take(OWNER, request(nonce), accepted).isEmpty());
    }

    @Test public void disabledUnmanagedOrOutOfScopeConfigurationsCannotIssueOrTakeImages() {
        RuntimeSnapshot initial = snapshot(); String nonce = store.issue(OWNER, initial, metadata(), PNG);
        config.setEnabled(false); RuntimeSnapshot disabled = snapshot();
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, disabled, metadata(), PNG));
        assertTrue(store.take(OWNER, request(nonce), disabled).isEmpty());
        config.setEnabled(true);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.WHITELIST, List.of(), List.of()));
        RuntimeSnapshot denied = snapshot();
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, denied, metadata(), PNG));
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().remove(SourceScopePolicy.KEY).commit();
        RuntimeSnapshot unmanaged = snapshot(); assertFalse(unmanaged.sourceScope.managed);
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, unmanaged, metadata(), PNG));
    }

    @Test public void invalidImagesOversizedPayloadsAndNonOwnerUserIssuanceAreRejected() {
        RuntimeSnapshot accepted = snapshot();
        for (int owner : new int[]{0, 1000, 121001})
            assertThrows(IllegalArgumentException.class, () -> store.issue(owner, accepted, metadata(), PNG));
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, accepted, null, PNG));
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, accepted, metadata(), null));
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, accepted, metadata(), new byte[8]));
        byte[] tooLarge = new byte[ScopeCaptureStore.MAX_BYTES + 1]; System.arraycopy(PNG, 0, tooLarge, 0, PNG.length);
        assertThrows(IllegalArgumentException.class, () -> store.issue(OWNER, accepted, metadata(), tooLarge));
    }

    @Test(timeout = 20_000) public void theNewestCaptureReplacesThePreviousOneAndClearRevokesIt() throws Exception {
        RuntimeSnapshot accepted = snapshot(); String previous = store.issue(OWNER, accepted, metadata(), PNG);
        String current = store.issue(OWNER, accepted, metadata(), PNG); assertNotEquals(previous, current);
        assertTrue(store.take(OWNER, request(previous), accepted).isEmpty());
        assertArrayEquals(PNG, readPipe(store.take(OWNER, request(current), accepted)));
        current = store.issue(OWNER, accepted, metadata(), PNG); store.clear();
        assertTrue(store.take(OWNER, request(current), accepted).isEmpty());
    }

    @Test public void textTransferIsOneUseAndDoesNotNeedTheScreenshotSwitchOrADescriptor() {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false,true));
        RuntimeSnapshot accepted=snapshot();assertFalse(accepted.scopeCapture);assertTrue(accepted.scopeLinkProbe);
        String url="https://example.org/project?from=about";
        String nonce=store.issueText(OWNER,accepted,metadata(),url);
        assertTrue(store.take(OWNER+1,request(nonce),accepted).isEmpty());
        assertTrue(store.take(OWNER,request(nonce+"wrong"),accepted).isEmpty());
        Bundle result=store.take(OWNER,request(nonce),accepted);
        assertEquals("text",result.getString("kind"));assertEquals(url,result.getString("url"));
        assertEquals(0,result.getInt("bytes",-1));assertFalse(result.containsKey("fd"));
        assertEquals(SOURCE,result.getString("source"));assertEquals(accepted.identity,result.getString("scope_identity"));
        assertTrue(store.take(OWNER,request(nonce),accepted).isEmpty());
        assertThrows(IllegalArgumentException.class,()->store.issue(OWNER,accepted,metadata(),PNG));
    }

    @Test public void textFlagIsRequiredAndClosingItRevokesTheAlreadyIssuedTextOnly() throws Exception {
        String url="https://example.org/community";
        assertThrows(IllegalArgumentException.class,()->store.issueText(OWNER,snapshot(),metadata(),url));
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true,true));
        RuntimeSnapshot accepted=snapshot();String nonce=store.issueText(OWNER,accepted,metadata(),url);
        assertThrows(IllegalArgumentException.class,()->store.issueText(OWNER,accepted,metadata(),"https://user:secret@example.org/"));
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true,false));
        RuntimeSnapshot disabled=snapshot();assertTrue(disabled.scopeCapture);assertFalse(disabled.scopeLinkProbe);
        assertTrue(store.take(OWNER,request(nonce),disabled).isEmpty());
        assertTrue(store.take(OWNER,request(nonce),accepted).isEmpty());
        String image=store.issue(OWNER,disabled,metadata(),PNG);
        Bundle capture=store.take(OWNER,request(image),disabled);assertEquals("image",capture.getString("kind"));
        ParcelFileDescriptor descriptor=capture.getParcelable("fd");assertNotNull(descriptor);descriptor.close();
    }

    @Test public void nativeResultExpiryAndSourcePolicyChangesAlsoRevokeTextCapabilities() {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true,true));
        RuntimeSnapshot accepted=snapshot();String url="https://example.org/actual-link";
        String nativeNonce=store.issueText(OWNER,accepted,metadata(),url);store.nativeResult(SOURCE);
        assertTrue(store.take(OWNER,request(nativeNonce),accepted).isEmpty());
        String expired=store.issueText(OWNER,accepted,metadata(),url);ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        assertTrue(store.take(OWNER,request(expired),accepted).isEmpty());
        String changed=store.issueText(OWNER,accepted,metadata(),url);
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(SOURCE),List.of(),true,true));
        assertTrue(store.take(OWNER,request(changed),snapshot()).isEmpty());
        assertTrue(store.take(OWNER,request(changed),accepted).isEmpty());
    }

    @Test(timeout = 20_000) public void anImageAndItsCapturedLinkTravelTogetherThroughOneCapability() throws Exception {
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),true,true));
        RuntimeSnapshot accepted=snapshot(); String url="https://example.org/the-real-link";
        String nonce=store.issueImage(OWNER,accepted,metadata(),PNG,url);
        Bundle capture=store.take(OWNER,request(nonce),accepted);
        assertEquals("image",capture.getString("kind")); assertEquals(url,capture.getString("url"));
        assertArrayEquals(PNG,readPipe(capture));
        assertTrue(store.take(OWNER,request(nonce),accepted).isEmpty());
    }

    @Test public void resumeCompletionPreservesTheNativePickWithoutEnablingScreenshots() {
        assertThrows(IllegalArgumentException.class,()->store.issueResume(OWNER,snapshot(),metadata()));
        assertThrows(IllegalArgumentException.class,()->store.issueImage(OWNER,snapshot(),metadata(),PNG,"https://example.org/"));
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false,true));
        RuntimeSnapshot accepted=snapshot(); String nonce=store.issueResume(OWNER,accepted,metadata());
        assertTrue(store.take(OWNER+1,request(nonce),accepted).isEmpty());
        Bundle result=store.take(OWNER,request(nonce),accepted);
        assertEquals("resume",result.getString("kind")); assertEquals(0,result.getInt("bytes",-1));
        assertFalse(result.containsKey("fd")); assertFalse(result.containsKey("url"));
        assertEquals(SOURCE,result.getString("source"));
        assertTrue(store.take(OWNER,request(nonce),accepted).isEmpty());
        nonce=store.issueResume(OWNER,accepted,metadata());
        assertTrue(config.saveSourceScope(SourceScopePolicy.Mode.BLACKLIST,List.of(),List.of(),false,false));
        assertTrue(store.take(OWNER,request(nonce),snapshot()).isEmpty());
    }
}
