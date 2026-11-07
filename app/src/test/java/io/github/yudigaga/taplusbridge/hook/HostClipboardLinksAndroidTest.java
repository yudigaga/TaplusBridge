package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostClipboardLinksAndroidTest {
    private static final String WEB = "https://example.com/share/One";
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private final Object service = new Object(), view = new Object();
    private final AtomicReference<HostClipboardLinks.Policy> policy = new AtomicReference<>();
    private HostClipboardLinks helper;
    private FakeHost host;
    @Before public void setup() throws Exception {
        policy.set(new HostClipboardLinks.Policy("configuration-one", true, true));
        host = new FakeHost(); host.service = service; host.content = WEB; host.current = view;
        helper = new HostClipboardLinks(new HostRecognitionEnhancer(Model.class), host, policy::get);
        PreviewProvider provider = Robolectric.buildContentProvider(PreviewProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
    }
    @Test public void clipboardTextAndProtocolCaptureAreIndependentAndReplaceThePreviousClipboardSession() {
        helper.begin(service, WEB + "\n" + MAGNET);
        assertEquals(List.of(WEB, MAGNET), helper.links());
        helper.begin(service, "plain next clipboard");
        assertTrue(helper.links().isEmpty());
    }
    @Test public void disabledMissingAndMalformedPolicyNeverCaptureClipboardContent() {
        policy.set(new HostClipboardLinks.Policy("configuration-one", false, true));
        helper.begin(service, MAGNET); assertTrue(helper.links().isEmpty());
        policy.set(null); helper.begin(service, WEB); assertTrue(helper.links().isEmpty());
        policy.set(new HostClipboardLinks.Policy("", true, true));
        helper.begin(service, WEB); assertTrue(helper.links().isEmpty());
    }
    @Test public void changedConfigurationAndAClosedViewReleaseTheCurrentClipboardLinks() {
        helper.begin(service, WEB); assertEquals(List.of(WEB), helper.links());
        policy.set(new HostClipboardLinks.Policy("configuration-two", true, true));
        helper.shown(view); assertTrue(helper.links().isEmpty());
        helper.begin(service, MAGNET); host.content = MAGNET;
        helper.shown(view); helper.destroyed(view);
        assertTrue(helper.links().isEmpty());
    }
    @Test public void oldCommerceTaskCannotSupplyCardsToANewerClipboardSessionEvenWhenTextIsEqual() {
        Object old = new Object(), fresh = new Object(), card = new Object();
        helper.begin(service, WEB); helper.ownTask(old);
        helper.begin(service, WEB); helper.ownTask(fresh);
        assertTrue(helper.server(old, List.of(card)).isEmpty());
        assertEquals(List.of(card), helper.server(fresh, List.of(card)));
    }
    @Test public void clipboardSessionDestructionDoesNotLeaveHistoryOrAllowLateServerCards() {
        Object task = new Object();
        helper.begin(service, MAGNET); helper.ownTask(task);
        helper.destroyed(service);
        assertTrue(helper.links().isEmpty());
        assertTrue(helper.server(task, List.of(new Object())).isEmpty());
    }
    @Test public void localCardsWaitForTheirOwnInflatedViewAndDoNotNeedTheServerChannel() {
        policy.set(new HostClipboardLinks.Policy("configuration-one", true, false));
        helper.begin(service, WEB);
        assertTrue(host.replacements.isEmpty());
        helper.shown(view);
        assertEquals(1, host.replacements.size());
        assertEquals(1, host.replacements.get(0).size());
        Model local = (Model) host.replacements.get(0).get(0);
        assertEquals(WEB, local.detailUrl); assertEquals("com.example.reader", local.packageName);
        helper.shown(view);
        assertEquals("One clipboard view must not append the same cards again", 1, host.replacements.size());
    }
    @Test public void validLateServerReplacesTheSameViewRatherThanAppendingAndOldTasksDoNothing() {
        Object task = new Object(), wrongTask = new Object();
        helper.begin(service, WEB); helper.ownTask(task); helper.shown(view);
        assertEquals(1, host.replacements.size());
        Model server = new Model(); server.detailUrl = "https://server.example/page";
        helper.server(wrongTask, List.of(server));
        assertEquals(1, host.replacements.size());
        helper.server(task, List.of(server));
        assertEquals(2, host.replacements.size());
        assertTrue(host.replacements.get(1).contains(server));
        helper.server(task, List.of(server));
        assertEquals(2, host.replacements.size());
    }
    @Test public void wrongSourceServiceTextOrFinishingViewCannotReceiveCurrentClipboardCards() {
        helper.begin(service, WEB);
        host.service = new Object(); helper.shown(view); assertTrue(host.replacements.isEmpty());
        host.service = service; host.content = MAGNET; helper.shown(view); assertTrue(host.replacements.isEmpty());
        host.content = WEB; host.finishing = true; helper.shown(view); assertTrue(host.replacements.isEmpty());
    }
    @Test public void resolvedLocalRuleReplacesOnlyTheWholeClipboardUrlNerFallback() {
        Object stock = new Object();
        helper.begin(service, WEB);
        assertTrue(helper.ner(RuntimeEnvironment.getApplication(), WEB, 2, List.of(stock)).isEmpty());
        assertEquals(List.of(stock), helper.ner(RuntimeEnvironment.getApplication(), WEB, 3, List.of(stock)));
        assertEquals(List.of(stock), helper.ner(RuntimeEnvironment.getApplication(), MAGNET, 2, List.of(stock)));
    }
    @Test public void unconfiguredLinksInMixedClipboardKeepTheStockNerFallback() {
        String mixed = WEB + "\nhttps://unmatched.example/link";
        Object stock = new Object();
        host.content = mixed; helper.begin(service, mixed);
        assertEquals(List.of(stock), helper.ner(RuntimeEnvironment.getApplication(), mixed, 2, List.of(stock)));
        host.service = new Object();
        helper.begin(service, WEB); host.content = WEB;
        assertEquals(List.of(stock), helper.ner(RuntimeEnvironment.getApplication(), WEB, 2, List.of(stock)));
    }
    @Test public void newCopyCanUpdateTheSameAlreadyInflatedClipboardViewWithoutWaitingForAnotherShowCallback() {
        helper.begin(service, WEB); helper.shown(view);
        assertEquals(1, host.replacements.size());
        helper.begin(service, MAGNET); host.content = MAGNET;
        helper.afterStart(service);
        assertEquals(3, host.replacements.size());
        Model latest = (Model) host.replacements.get(2).get(0);
        assertEquals(MAGNET, latest.detailUrl);
        assertEquals("com.example.reader", latest.packageName);
    }
    @Test public void afterStartNeverPublishesIntoAnUninflatedOrFinishingView() {
        helper.begin(service, WEB); host.ready = false;
        helper.afterStart(service); assertTrue(host.replacements.isEmpty());
        host.ready = true; host.finishing = true;
        helper.afterStart(service); assertTrue(host.replacements.isEmpty());
        host.finishing = false;
        helper.afterStart(new Object()); assertTrue(host.replacements.isEmpty());
    }
    @Test public void disablingClipboardOrChangingConfigurationRemovesVisibleCardsAndReleasesTheirLinks() {
        helper.begin(service, WEB); helper.shown(view);
        policy.set(new HostClipboardLinks.Policy("configuration-two", false, false));
        helper.changed();
        assertTrue(helper.links().isEmpty());
        assertTrue(host.replacements.get(host.replacements.size() - 1).isEmpty());
        assertTrue(helper.server(new Object(), List.of(new Model())).isEmpty());
        helper.begin(service, MAGNET);
        assertTrue(helper.links().isEmpty());
    }
    public static final class Model { public String title, cat, detailUrl, packageName, intent; }
    private static final class FakeHost implements HostClipboardLinks.Host {
        Object service, current; String content; boolean finishing, ready = true;
        final List<List<?>> replacements = new ArrayList<>();
        public Context context(Object view) { return RuntimeEnvironment.getApplication(); }
        public Object service(Object view) { return service; }
        public String content() { return content; }
        public boolean clipboardView(Object view) { return true; }
        public boolean finishing(Object view) { return finishing; }
        public boolean ready(Object view) { return ready; }
        public Object currentView(Object service) { return current; }
        public void replace(Object view, List<?> models) { replacements.add(new ArrayList<>(models)); }
    }
    public static final class PreviewProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle input) {
            if (!"resolve".equals(method) || input == null) return null;
            assertNull("Clipboard must not inherit a long-press source", input.getString("source"));
            if (input.getString("url", "").contains("unmatched.example")) return null;
            Bundle result = new Bundle(); result.putString("target", "com.example.reader"); result.putString("title", "阅读器");
            return result;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
