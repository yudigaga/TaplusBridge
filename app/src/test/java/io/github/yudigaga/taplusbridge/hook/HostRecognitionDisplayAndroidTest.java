package io.github.yudigaga.taplusbridge.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import static org.junit.Assert.*;

/** Exercises the Android card's visible caption and icon, including fields inherited by host cards. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class HostRecognitionDisplayAndroidTest {
    private static final String BILI_URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final String BILI_TARGET = "tv.danmaku.bili";
    private Context context;
    private HostRecognitionEnhancer enhancer;
    private PreviewProvider provider;

    @Before public void attachPreviewProvider() throws Exception {
        context = RuntimeEnvironment.getApplication();
        enhancer = new HostRecognitionEnhancer(FakeModel.class);
        provider = Robolectric.buildContentProvider(PreviewProvider.class)
                .create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider);
    }

    @Test public void browserCaptionAndIconAreReplacedWithActualTargetApplication() {
        Card card = new Card(context);
        Drawable browserIcon = card.mRecommendIcon.getDrawable();
        enhancer.bindRecommendation(card, model(BILI_URL), "com.example.reader");

        assertEquals("在哔哩哔哩打开", card.mRecommendTitle.getText().toString());
        assertEquals("哔哩哔哩", card.mRecommendAbstract.getText().toString());
        assertEquals(View.VISIBLE, card.mRecommendAbstract.getVisibility());
        Drawable actual = card.mRecommendIcon.getDrawable();
        assertNotSame(browserIcon, actual);
        assertTrue(actual instanceof BitmapDrawable);
        assertEquals(Color.MAGENTA, ((BitmapDrawable) actual).getBitmap().getPixel(0, 0));
        assertSame(actual, enhancer.iconFor(card.mRecommendIcon));
        assertEquals("com.example.reader", provider.lastSource);
        assertEquals(1, provider.resolveCalls);
    }

    @Test public void clipboardUrlCardShowsSameActualAppInsteadOfBrowserLabel() {
        Card card = new Card(context);
        enhancer.bindNer(card, new FakeEntity("URL", BILI_URL), "com.example.reader");

        assertEquals("在哔哩哔哩打开", card.mRecommendTitle.getText().toString());
        assertEquals("哔哩哔哩", card.mRecommendAbstract.getText().toString());
        assertTrue(card.mRecommendIcon.getDrawable() instanceof BitmapDrawable);
        assertSame(card.mRecommendIcon.getDrawable(), enhancer.iconFor(card.mRecommendIcon));
        assertNull("Clipboard does not inherit a stale source application", provider.lastSource);
    }

    @Test public void unresolvedRecommendationPreservesStockBrowserPresentation() {
        Card card = new Card(context);
        Drawable stockIcon = card.mRecommendIcon.getDrawable();
        enhancer.bindRecommendation(card, model("https://unknown.example.org/article"), null);
        assertStockCard(card, stockIcon);
        assertNull(enhancer.iconFor(card.mRecommendIcon));
        assertEquals(1, provider.resolveCalls);
    }

    @Test public void nonViewNativeIntentDoesNotRewriteCaptionEvenWhenUrlMatches() {
        Card card = new Card(context);
        Drawable stockIcon = card.mRecommendIcon.getDrawable();
        FakeModel info = model(BILI_URL);
        info.intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, BILI_URL).toUri(0);
        enhancer.bindRecommendation(card, info, null);
        assertStockCard(card, stockIcon);
        assertEquals(0, provider.resolveCalls);
    }

    @Test public void translationAndNonUrlEntitiesKeepHostPresentation() {
        Card card = new Card(context);
        Drawable stockIcon = card.mRecommendIcon.getDrawable();
        FakeModel translation = model(BILI_URL);
        translation.cat = "EN_2_CH_PARA";
        enhancer.bindRecommendation(card, translation, null);
        assertStockCard(card, stockIcon);
        enhancer.bindNer(card, new FakeEntity("PHONE", "13800138000"), null);
        assertStockCard(card, stockIcon);
        assertEquals(0, provider.resolveCalls);
    }

    @Test public void beforeBindRemovesIconOverrideBeforeAStockOrRecycledBinding() {
        Card card = new Card(context);
        enhancer.bindRecommendation(card, model(BILI_URL), null);
        assertNotNull(enhancer.iconFor(card.mRecommendIcon));
        enhancer.beforeBind(card);
        assertNull(enhancer.iconFor(card.mRecommendIcon));
        // The host binds its new icon after beforeBind(); no previous override may restore Bili.
        ColorDrawable nextStock = new ColorDrawable(Color.GREEN);
        card.mRecommendIcon.setIcon(nextStock);
        assertSame(nextStock, card.mRecommendIcon.getDrawable());
        assertNull(enhancer.iconFor(card.mRecommendIcon));
    }

    @Test public void unresolvedRebindAlsoDropsOldAsyncIconOverride() {
        Card card = new Card(context);
        enhancer.bindRecommendation(card, model(BILI_URL), null);
        assertNotNull(enhancer.iconFor(card.mRecommendIcon));
        // Reproduce normal host binding before the module's after-bind callback.
        card.resetToBrowser();
        Drawable stockIcon = card.mRecommendIcon.getDrawable();
        enhancer.bindRecommendation(card, model("https://unknown.example.org/article"), null);
        assertStockCard(card, stockIcon);
        assertNull(enhancer.iconFor(card.mRecommendIcon));
    }

    @Test public void urlSpanDestinationSurvivesEvenWhenVisibleTextContainsNoUrl() {
        SpannableString titleOnly = new SpannableString("这是一条分享卡片标题");
        titleOnly.setSpan(new URLSpan(BILI_URL), 0, titleOnly.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertFalse(titleOnly.toString().contains("https://"));
        Bundle payload = new Bundle(); payload.putCharSequence("viewContent", titleOnly);
        enhancer.capturePayload(payload);

        assertEquals(Collections.singletonList(BILI_URL), enhancer.capturedLinks());
        ArrayList<Object> merged = enhancer.merge(context, new ArrayList<>(), "com.example.reader");
        assertEquals(1, merged.size());
        FakeModel actual = (FakeModel) merged.get(0);
        assertEquals(BILI_TARGET, actual.packageName);
        assertEquals(BILI_URL, actual.detailUrl);
        assertEquals("在哔哩哔哩打开", actual.title);
        assertTrue(provider.lastLocalRecognition);
    }

    @Test public void foldedViewCanContributeItsFullUnderlyingTextUrl() {
        Bundle payload = new Bundle();
        payload.putString("viewContent", "介绍内容…展开");
        payload.putString("fullText", "介绍内容以及折叠后完整内容：\n" + BILI_URL + "\n原文结束");
        enhancer.capturePayload(payload);

        assertEquals(Collections.singletonList(BILI_URL), enhancer.capturedLinks());
        ArrayList<Object> merged = enhancer.merge(context, new ArrayList<>(), "com.example.reader");
        assertEquals(1, merged.size());
        Card card = new Card(context);
        enhancer.bindRecommendation(card, merged.get(0), "com.example.reader");
        assertEquals("在哔哩哔哩打开", card.mRecommendTitle.getText().toString());
    }

    @Test public void imageOnlyMetadataNeverBecomesTheLandingPage() {
        Bundle payload = new Bundle();
        payload.putString("imgUrl", BILI_URL);
        payload.putString("thumbnailUrl", "https://images.example.org/card.png");
        payload.putString("iconUrl", "https://images.example.org/icon.png");
        enhancer.capturePayload(payload);
        assertTrue(enhancer.capturedLinks().isEmpty());
        assertTrue(enhancer.merge(context, new ArrayList<>(), null).isEmpty());
        assertEquals(0, provider.resolveCalls);
    }

    @Test public void nativeLauncherCaptionMatchesClickedDouyinTargetWithoutDomainRule() {
        FakeModel info = model("https://no-matching-domain.example/article");
        info.packageName = "com.ss.android.ugc.aweme"; info.intent = "";
        Card card = new Card(context);
        enhancer.bindRecommendation(card, info, "com.example.reader");
        assertEquals("在抖音打开", card.mRecommendTitle.getText().toString());
        assertNotNull(provider.lastCandidate);
        assertEquals(Intent.ACTION_MAIN, provider.lastCandidate.getAction());
        assertEquals("com.ss.android.ugc.aweme", provider.lastCandidate.getPackage());
    }

    private static FakeModel model(String url) {
        FakeModel info = new FakeModel();
        info.title = "在浏览器打开"; info.cat = "URL";
        info.detailUrl = url; info.packageName = "com.mi.globalbrowser";
        return info;
    }

    private static void assertStockCard(Card card, Drawable icon) {
        assertEquals("在浏览器打开", card.mRecommendTitle.getText().toString());
        assertEquals("浏览器", card.mRecommendAbstract.getText().toString());
        assertSame(icon, card.mRecommendIcon.getDrawable());
    }

    public static final class FakeModel {
        private String title;
        private String cat;
        private String detailUrl;
        private String packageName;
        private String intent;
    }

    public static final class FakeEntity {
        private final String type;
        private final String text;
        FakeEntity(String type, String text) { this.type = type; this.text = text; }
        public String getEntityClass() { return type; }
        public String getEntityStr() { return text; }
    }

    public static class CardBase extends LinearLayout {
        final TextView mRecommendTitle;
        final TextView mRecommendAbstract;
        final Icon mRecommendIcon;
        CardBase(Context context) {
            super(context);
            mRecommendTitle = new TextView(context);
            mRecommendAbstract = new TextView(context);
            mRecommendIcon = new Icon(context);
            addView(mRecommendTitle); addView(mRecommendAbstract); addView(mRecommendIcon);
        }
    }

    public static final class Card extends CardBase {
        Card(Context context) { super(context); resetToBrowser(); }
        void resetToBrowser() {
            mRecommendTitle.setText("在浏览器打开");
            mRecommendAbstract.setText("浏览器");
            mRecommendAbstract.setVisibility(View.GONE);
            mRecommendIcon.setIcon(new ColorDrawable(Color.BLUE));
        }
    }

    public static final class Icon extends ImageView {
        Icon(Context context) { super(context); }
        public void setIcon(Drawable icon) { setImageDrawable(icon); }
    }

    public static final class PreviewProvider extends ContentProvider {
        int resolveCalls;
        String lastSource;
        boolean lastLocalRecognition;
        Intent lastCandidate;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (!"resolve".equals(method) || extras == null) return null;
            resolveCalls++;
            lastSource = extras.getString("source");
            lastLocalRecognition = extras.getBoolean("local_recognition");
            lastCandidate = extras.getParcelable("candidate");
            boolean douyin = lastCandidate != null && Intent.ACTION_MAIN.equals(lastCandidate.getAction())
                    && "com.ss.android.ugc.aweme".equals(lastCandidate.getPackage());
            if (!douyin && !BILI_URL.equals(extras.getString("url"))) return null;
            Bundle preview = new Bundle();
            preview.putString("target", douyin ? "com.ss.android.ugc.aweme" : BILI_TARGET);
            preview.putString("title", douyin ? "抖音" : "哔哩哔哩");
            Bitmap icon = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            icon.eraseColor(Color.MAGENTA);
            preview.putParcelable("icon", icon);
            return preview;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
