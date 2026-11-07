package io.github.yudigaga.taplusbridge.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import static org.junit.Assert.*;

/** Existing host merge/bind entry points, with provider availability and real Android card views. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostCaptureIntegrationAndroidTest {
    private static final String URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final String TARGET = "tv.danmaku.bili";
    private Context context;
    private HostRecognitionEnhancer enhancer;
    private CapturePreviewProvider provider;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        enhancer = new HostRecognitionEnhancer(Model.class);
        provider = Robolectric.buildContentProvider(CapturePreviewProvider.class)
                .create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
    }

    @Test public void titleOnlyRecognitionPreservesHostRecommendationsWithoutManualControl() {
        enhancer.captureText("一张没有暴露网址的分享卡片标题");
        Model stock = stock("在浏览器搜索标题", null);
        ArrayList<Object> server = new ArrayList<>(List.of(stock));

        ArrayList<Object> displayed = enhancer.merge(context, server, CaptureLease.WECHAT);

        assertTrue(enhancer.capturedLinks().isEmpty());
        assertEquals(1, displayed.size());
        assertSame(stock, displayed.get(0));
        assertNull(control(displayed));
        assertEquals(1, server.size());
        assertEquals(0, provider.resolveCalls);
        assertEquals(0, provider.availableCalls);
    }

    @Test public void emptyServerAndNoExposedUrlDoesNotPublishSyntheticControl() {
        enhancer.captureText("只有标题");
        ArrayList<Object> displayed = enhancer.merge(context, new ArrayList<>(), CaptureLease.WECHAT);
        assertTrue(displayed.isEmpty());
        assertNull(control(displayed));
        assertEquals(0, provider.resolveCalls);
    }

    @Test public void destinationUsesFirstCompactCardWithOriginalRecommendationsRetained() {
        enhancer.captureText("分享链接 " + URL);
        Model firstStock = stock("原来的推荐一", "https://stock.example/one");
        Model secondStock = stock("原来的推荐二", "https://stock.example/two");
        ArrayList<Object> server = new ArrayList<>(List.of(firstStock, secondStock));

        ArrayList<Object> displayed = enhancer.merge(context, server, CaptureLease.WECHAT);

        assertEquals(3, displayed.size());
        Model target = (Model) displayed.get(0);
        assertEquals(TARGET, target.packageName);
        assertEquals(URL, target.detailUrl);
        assertEquals("在哔哩哔哩打开", target.title);
        assertFalse(HostCaptureAction.isAction(target));
        assertSame(firstStock, displayed.get(1));
        assertSame(secondStock, displayed.get(2));
        assertEquals(0, countControls(displayed));
        assertEquals(List.of(firstStock, secondStock), server);
        assertEquals(1, provider.resolveCalls);
        assertTrue(provider.lastPreviewWasLocal);
    }

    @Test public void republishingMergedHostListNeverDuplicatesControlOrApplicationCard() {
        enhancer.captureText(URL);
        Model stock = stock("已有推荐", "https://stock.example/page");
        ArrayList<Object> first = enhancer.merge(context,
                new ArrayList<>(List.of(stock)), CaptureLease.WECHAT);
        ArrayList<Object> second = enhancer.merge(context, first, CaptureLease.WECHAT);
        ArrayList<Object> third = enhancer.merge(context, second, CaptureLease.WECHAT);

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertEquals(2, third.size());
        assertEquals(0, countControls(third));
        assertEquals(1, third.stream().filter(item -> item instanceof Model model
                && TARGET.equals(model.packageName) && URL.equals(model.detailUrl)).count());
        assertSame(first.get(0), third.get(0));
        assertSame(stock, third.get(1));
    }

    @Test public void capturedProbeUrlUsesExistingDestinationCardAndLosesEphemeralIdentityOnNewSession() {
        enhancer.captureProbeUrl(URL);
        ArrayList<Object> shown = enhancer.merge(context, new ArrayList<>(), CaptureLease.WECHAT);
        Model app = (Model) shown.get(0);
        assertEquals(TARGET, app.packageName);
        assertEquals("在哔哩哔哩打开", app.title);
        assertTrue(enhancer.isProbeRecommendation(app));
        assertFalse(enhancer.isProbeRecommendation(control(shown)));
        enhancer.clear();
        assertFalse(enhancer.isProbeRecommendation(app));
        assertTrue(enhancer.capturedLinks().isEmpty());
    }

    @Test public void legacyCachedControlIsRemovedAndOriginalRecommendationsPreserved() {
        Model stock = stock("原来推荐", null);
        Model legacy = new Model(); legacy.cat=HostCaptureAction.CATEGORY;
        ArrayList<Object> enabled = new ArrayList<>(List.of(stock,legacy));
        provider.captureEnabled = false;

        ArrayList<Object> disabled = enhancer.merge(context, enabled, CaptureLease.WECHAT);

        assertEquals(List.of(stock), disabled);
        assertEquals(0, countControls(disabled));
        assertEquals(0, provider.resolveCalls);
    }

    @Test public void unknownOrClipboardSourceNeverInjectsControlOrQueriesAvailability() {
        Model stock = stock("原来推荐", null);
        for (String source : new String[] { null, "", "com.example.reader",
                BridgeContract.HOST_PACKAGE, BridgeContract.APP_PACKAGE }) {
            ArrayList<Object> displayed = enhancer.merge(context,
                    new ArrayList<>(List.of(stock)), source);
            assertEquals(List.of(stock), displayed);
            assertEquals(0, countControls(displayed));
        }
        assertEquals(0, provider.availableCalls);
        assertEquals(0, provider.resolveCalls);
    }

    @Test public void bindingStaleControlSkipsPreviewAndDropsPriorIconOverride() {
        Card card = new Card(context);
        Model app = stock("在浏览器打开", URL);
        enhancer.bindRecommendation(card, app, CaptureLease.WECHAT);
        assertEquals("在哔哩哔哩打开", card.mRecommendTitle.getText().toString());
        assertNotNull(enhancer.iconFor(card.mRecommendIcon));
        int previewsBefore = provider.resolveCalls;
        Model action = new Model(); action.cat=HostCaptureAction.CATEGORY;

        // The same card view is recycled by the host, then enters the normal after-bind hook.
        card.resetStockBinding();
        enhancer.bindRecommendation(card, action, CaptureLease.WECHAT);

        assertEquals("在浏览器打开", card.mRecommendTitle.getText().toString());
        assertEquals("浏览器", card.mRecommendAbstract.getText().toString());
        assertNotNull(card.mRecommendIcon.getDrawable());
        assertNull(enhancer.iconFor(card.mRecommendIcon));
        assertEquals(previewsBefore, provider.resolveCalls);
    }

    @Test public void unavailableCaptureProviderPreservesOriginalCardsWithoutSyntheticDestinations() {
        provider.failAvailability = true;
        Model stock = stock("原来的标题卡片", null);
        enhancer.captureText("没有网址的标题");
        ArrayList<Object> displayed = enhancer.merge(context,
                new ArrayList<>(List.of(stock)), CaptureLease.WECHAT);
        assertEquals(List.of(stock), displayed);
        assertEquals(0, provider.resolveCalls);
        assertTrue(enhancer.capturedLinks().isEmpty());
    }

    private static Model stock(String title, String url) {
        Model model = new Model(); model.title = title; model.cat = "URL"; model.detailUrl = url;
        return model;
    }
    private static Model control(List<?> cards) {
        Model found = null;
        for (Object card : cards) if (HostCaptureAction.isAction(card)) {
            assertNull("Only one capture control may be visible", found);
            found = (Model) card;
        }
        return found;
    }
    private static long countControls(List<?> cards) { return cards.stream().filter(HostCaptureAction::isAction).count(); }

    public static final class Model {
        private String title;
        private String cat;
        private String detailUrl;
        private String packageName;
        private String intent;
        private String sourceIcon;
    }
    public static class CardBase extends LinearLayout {
        protected final TextView mRecommendTitle;
        protected final TextView mRecommendAbstract;
        protected final Icon mRecommendIcon;
        CardBase(Context context) {
            super(context);
            mRecommendTitle = new TextView(context);
            mRecommendAbstract = new TextView(context);
            mRecommendIcon = new Icon(context);
            addView(mRecommendTitle); addView(mRecommendAbstract); addView(mRecommendIcon);
        }
    }
    public static final class Card extends CardBase {
        Card(Context context) { super(context); resetStockBinding(); }
        void resetStockBinding() {
            mRecommendTitle.setText("在浏览器打开");
            mRecommendAbstract.setText("浏览器");
            mRecommendIcon.setIcon(new ColorDrawable(Color.BLUE));
        }
    }
    public static final class Icon extends ImageView {
        Icon(Context context) { super(context); }
        public void setIcon(Drawable drawable) { setImageDrawable(drawable); }
    }
    public static final class CapturePreviewProvider extends ContentProvider {
        boolean captureEnabled = true;
        boolean failAvailability;
        int availableCalls, resolveCalls;
        String lastAvailabilitySource;
        boolean lastPreviewWasLocal;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            Bundle response = new Bundle();
            if ("capture_available".equals(method)) {
                availableCalls++;
                if (failAvailability) throw new IllegalStateException("Synthetic provider failure");
                lastAvailabilitySource = extras.getString("source");
                response.putBoolean("enabled", captureEnabled);
            } else if ("resolve".equals(method)) {
                resolveCalls++;
                lastPreviewWasLocal = extras.getBoolean("local_recognition");
                if (!URL.equals(extras.getString("url"))) return Bundle.EMPTY;
                response.putString("target", TARGET); response.putString("title", "哔哩哔哩");
                Bitmap icon = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
                icon.eraseColor(Color.MAGENTA); response.putParcelable("icon", icon);
            }
            return response;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
