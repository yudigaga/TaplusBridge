package io.github.yudigaga.taplusbridge.links;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.bridge.RuleReadCounter;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LinkChoiceActivityAndroidTest {
    private static final String TARGET = "com.ylmf.androidclient";
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private static final String SECOND = "magnet:?xt=urn:btih:1123456789abcdef0123456789abcdef01234567";
    private Context context; private ConfigStore config;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", 0).edit().clear().commit();
        context.getSharedPreferences(RuleRepository.PREFS, 0).edit().clear().commit();
        config = new ConfigStore(context);
        PackageInfo target = new PackageInfo(); target.packageName = TARGET; target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = TARGET; target.applicationInfo.uid = Process.myUid();
        target.applicationInfo.enabled = true; target.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(target);
        new RuleRepository(context).saveManualRule(-1, "*", "magnet:", TARGET, "none", "batch");
    }
    private ActivityController<LinkChoiceActivity> open() {
        return open(List.of(MAGNET, SECOND));
    }
    private ActivityController<LinkChoiceActivity> open(List<String> links) {
        Intent request = LinkChoiceRequests.prepare(context, links, null, true); assertNotNull(request);
        return Robolectric.buildActivity(LinkChoiceActivity.class, request).setup();
    }
    private Object session(LinkChoiceActivity activity) throws Exception {
        Field field = LinkChoiceActivity.class.getDeclaredField("session"); field.setAccessible(true); return field.get(activity);
    }
    private void choose(LinkChoiceActivity activity) throws Exception {
        choose(activity, List.of(MAGNET, SECOND));
    }
    private void choose(LinkChoiceActivity activity, List<String> links) throws Exception {
        Object state = session(activity);
        Field selected = state.getClass().getDeclaredField("selected"); selected.setAccessible(true); selected.set(state, links);
        Field target = state.getClass().getDeclaredField("target"); target.setAccessible(true); target.set(state, TARGET);
    }
    private void invoke(LinkChoiceActivity activity, String method) throws Exception {
        Method action = LinkChoiceActivity.class.getDeclaredMethod(method); action.setAccessible(true); action.invoke(activity);
    }
    @Test public void openingChoiceNeverAutomaticallyStartsAReceiverOrWritesTheClipboard() throws Exception {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class); clipboard.clearPrimaryClip();
        try (ActivityController<LinkChoiceActivity> controller = open()) {
            LinkChoiceActivity activity = controller.get();
            assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
            assertNull(clipboard.getPrimaryClip()); assertFalse(activity.isFinishing());
        }
    }
    @Test public void copyingRequiresItsExplicitActionAndPreservesAllChosenLinks() throws Exception {
        try (ActivityController<LinkChoiceActivity> controller = open()) {
            LinkChoiceActivity activity = controller.get(); choose(activity); invoke(activity, "copySelected");
            assertEquals(MAGNET + "\n" + SECOND, context.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString());
            assertTrue(activity.isFinishing());
        }
    }
    @Test public void configChangesOrFiveMinuteTimeoutPreventTheBatchExecutionAction() throws Exception {
        try (ActivityController<LinkChoiceActivity> controller = open()) {
            LinkChoiceActivity activity = controller.get(); choose(activity); config.setNativeEnabled(false); invoke(activity, "openBatch");
            assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
        }
        config.setNativeEnabled(true);
        try (ActivityController<LinkChoiceActivity> controller = open()) {
            LinkChoiceActivity activity = controller.get(); choose(activity); ShadowSystemClock.advanceBy(Duration.ofMinutes(5)); invoke(activity, "openBatch");
            assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
        }
    }
    @Test public void confirmingSelectedBatchImmediatelyDispatchesOneReceiverWithoutAPreparationStep() throws Exception {
        Bundle request = new Bundle(); request.putStringArrayList("links", new ArrayList<>(List.of(MAGNET, SECOND)));
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, request); assertNotNull(plan);
        ResolveInfo handler = new ResolveInfo(); handler.activityInfo = new ActivityInfo();
        handler.activityInfo.name = TARGET + ".Receiver"; handler.activityInfo.packageName = TARGET; handler.activityInfo.enabled = true; handler.activityInfo.exported = true;
        handler.activityInfo.applicationInfo = context.getPackageManager().getApplicationInfo(TARGET, 0);
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).addResolveInfoForIntent(plan.target(), handler);
        try (ActivityController<LinkChoiceActivity> controller = open()) {
            LinkChoiceActivity activity = controller.get(); choose(activity); invoke(activity, "batchReady");
            ShadowActivity shadow = Shadow.extract(activity); Intent opened = shadow.getNextStartedActivity(); assertNotNull(opened);
            assertEquals(TARGET, opened.getPackage()); assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertTrue(plan.target().filterEquals(opened));
            assertNull(shadow.getNextStartedActivity()); assertTrue(activity.isFinishing());
        }
    }
    @Test public void caseOnlyProtocolDuplicatesDispatchTheCanonicalSingleReceiver() throws Exception {
        List<String> links = List.of(MAGNET, "MAGNET" + MAGNET.substring(6));
        Intent expected = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(MAGNET)).setPackage(TARGET);
        addViewHandler(expected, TARGET);
        try (ActivityController<LinkChoiceActivity> controller = open(links)) {
            LinkChoiceActivity activity = controller.get(); choose(activity, links); invoke(activity, "batchReady");
            ShadowActivity shadow = Shadow.extract(activity); Intent opened = shadow.getNextStartedActivity();
            assertNotNull("A batch reduced to one canonical link must still open", opened);
            assertEquals(TARGET, opened.getPackage()); assertEquals(MAGNET, opened.getDataString());
            assertNull(shadow.getNextStartedActivity()); assertTrue(activity.isFinishing());
        }
    }
    @Test public void aChangedRuleTargetCannotReuseThePreviousBatchChoice() throws Exception {
        try (ActivityController<LinkChoiceActivity> controller = open()) {
            LinkChoiceActivity activity = controller.get(); choose(activity);
            new RuleRepository(context).saveManualRule(0, "*", "magnet:", "com.example.changed", "none", "");
            invoke(activity, "openBatch");
            assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
        }
    }
    @Test public void groupingReadsOneRuleSnapshotAndOneSwitchSetForAll64Links() throws Exception {
        String other = "com.example.ed2k";
        PackageInfo target = new PackageInfo(); target.packageName = other; target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = other; target.applicationInfo.enabled = true;
        target.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).installPackage(target);
        new RuleRepository(context).saveManualRule(-1, "*", "ed2k:", other, "none", "");
        ArrayList<String> links = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            links.add(MAGNET + "&dn=Item" + i);
            links.add("ed2k://|file|Item" + i + ".iso|123|0123456789abcdef0123456789abcdef|/");
        }
        try (ActivityController<LinkChoiceActivity> controller = open(links);
             RuleReadCounter reads = new RuleReadCounter(context)) {
            Method group = LinkChoiceActivity.class.getDeclaredMethod("downloads", List.class); group.setAccessible(true);
            group.invoke(controller.get(), links);
            // One current-configuration authorization, then one shared grouping snapshot.
            assertEquals(6, reads.ruleSnapshots()); // Constructor state/validation plus effective snapshot for each authorization/grouping read.
            assertEquals(2, reads.switchReads("enabled")); assertEquals(2, reads.switchReads("native"));
            assertEquals(2, reads.switchReads(ConfigStore.CLOUD_LINK_HANDOFF_KEY));
            assertNull(((ShadowActivity) Shadow.extract(controller.get())).getNextStartedActivity());
        }
    }
    private void addViewHandler(Intent intent, String target) throws Exception {
        ResolveInfo handler = new ResolveInfo(); handler.activityInfo = new ActivityInfo();
        handler.activityInfo.name = target + ".Receiver"; handler.activityInfo.packageName = target;
        handler.activityInfo.enabled = true; handler.activityInfo.exported = true;
        handler.activityInfo.applicationInfo = context.getPackageManager().getApplicationInfo(target, 0);
        ((ShadowPackageManager) Shadow.extract(context.getPackageManager())).addResolveInfoForIntent(intent, handler);
    }
}
