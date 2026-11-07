package io.github.yudigaga.taplusbridge.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.github.yudigaga.taplusbridge.R;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowPackageManager;

import static org.junit.Assert.*;

/** Real Activity lifecycle and Android Intent tests; platform thaw completion is controlled. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TransitActivityAndroidTest {
    private final List<ActivityController<TransitActivity>> controllers = new ArrayList<>();
    private Context context;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().commit();
        new ConfigStore(context).setEnabled(true);
        PackageInfo target = new PackageInfo();
        target.packageName = "tv.danmaku.bili";
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = target.packageName;
        target.applicationInfo.enabled = true;
        target.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
        ShadowPackageManager pm = Shadow.extract(context.getPackageManager());
        pm.installPackage(target);
    }

    @After
    public void destroyActivities() {
        for (ActivityController<TransitActivity> controller : controllers) {
            controller.get().finish();
            controller.pause().stop().destroy();
        }
    }

    private Bundle request() {
        Bundle request = new Bundle();
        request.putString("url", "https://b23.tv/abc123");
        request.putString("source", "com.example.source");
        request.putParcelable("original", new Intent(Intent.ACTION_VIEW,
                Uri.parse("mibrowser://home?origin=contentextension&web_url=https%3A%2F%2Fb23.tv%2Fabc123")));
        return request;
    }

    private ActivityController<TransitActivity> activity(TransitActivity.Job job) {
        ActivityController<TransitActivity> controller = Robolectric.buildActivity(TransitActivity.class);
        TransitActivity activity = controller.get();
        activity.setTheme(R.style.TransitTheme);
        ShadowActivity shadow = Shadow.extract(activity);
        shadow.setLastNonConfigurationInstance(job);
        controllers.add(controller);
        return controller.create().start();
    }

    private ShadowActivity shadow(TransitActivity activity) { return Shadow.extract(activity); }

    @Test
    public void pendingAndSuccessfulHandoffDrawNoPageSpinnerOrDialog() {
        TransitActivity.Job job = new TransitActivity.Job(request());
        ActivityController<TransitActivity> controller = activity(job).resume();
        TransitActivity activity = controller.get();
        ViewGroup content = activity.findViewById(android.R.id.content);
        assertNotNull(content);
        assertEquals(0, content.getChildCount());
        assertNoPageWidgets(activity.getWindow().getDecorView());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertEquals(Color.TRANSPARENT, ((ColorDrawable) activity.getWindow().getDecorView().getBackground()).getColor());

        TypedArray window = activity.obtainStyledAttributes(new int[] {
                android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating,
                android.R.attr.windowDisablePreview, android.R.attr.windowNoTitle });
        assertTrue(window.getBoolean(0, false));
        assertFalse(window.getBoolean(1, true));
        assertTrue(window.getBoolean(2, false));
        assertTrue(window.getBoolean(3, false));
        window.recycle();

        job.complete(LaunchPlanner.plan(context, job.request), "目标应用已解冻", true);
        assertEquals("tv.danmaku.bili", shadow(activity).getNextStartedActivity().getPackage());
        assertNull(shadow(activity).getNextStartedActivity());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(activity.isFinishing());
        assertEquals(0, content.getChildCount());
    }

    @Test
    public void successfulCompletionWaitsForResumeAndOnlyOpensOnce() {
        TransitActivity.Job job = new TransitActivity.Job(request());
        ActivityController<TransitActivity> controller = activity(job);
        job.complete(LaunchPlanner.plan(context, job.request), "目标应用已启用", true);
        assertNull(shadow(controller.get()).getNextStartedActivity());
        controller.resume();
        Intent launched = shadow(controller.get()).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals("tv.danmaku.bili", launched.getPackage());
        controller.get().onResume();
        assertNull(shadow(controller.get()).getNextStartedActivity());
    }

    @Test
    public void rotationRetainsPendingJobAndReplacementActivityOpensExactlyOnce() {
        TransitActivity.Job job = new TransitActivity.Job(request());
        ActivityController<TransitActivity> controller = activity(job).resume();
        TransitActivity old = controller.get();
        controller.recreate();
        TransitActivity replacement = controller.get();
        assertNotSame(old, replacement);
        assertFalse(job.cancelled);
        assertEquals(0, ((ViewGroup) replacement.findViewById(android.R.id.content)).getChildCount());
        job.complete(LaunchPlanner.plan(context, job.request), "目标应用已解冻", true);
        Intent launched = shadow(replacement).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals("tv.danmaku.bili", launched.getPackage());
        assertNull(shadow(replacement).getNextStartedActivity());
    }

    @Test
    public void failureShowsMeaningfulDialogAndDoubleTapFallbackOnlyLaunchesOnce() {
        TransitActivity.Job job = new TransitActivity.Job(request());
        ActivityController<TransitActivity> controller = activity(job).resume();
        job.complete(LaunchPlanner.plan(context, job.request), "Root 解冻失败，请检查超级用户授权", false);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        TextView message = dialog.findViewById(android.R.id.message);
        assertTrue(message.getText().toString().contains("Root 解冻失败"));
        View fallback = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        fallback.performClick();
        fallback.performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Intent launched = shadow(controller.get()).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals("mibrowser", launched.getData().getScheme());
        assertNull(shadow(controller.get()).getNextStartedActivity());
        assertTrue(job.cancelled);
        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void dialogCancelPreventsLateSuccessFromLaunching() {
        TransitActivity.Job job = new TransitActivity.Job(request());
        ActivityController<TransitActivity> controller = activity(job).resume();
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, job.request);
        job.onTimeout();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        job.complete(plan, "解冻迟到成功", true);
        assertTrue(job.cancelled);
        assertNull(shadow(controller.get()).getNextStartedActivity());
        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void directBackOrFinishCancelsPendingJobBeforeAnyLateOpen() {
        TransitActivity.Job job = new TransitActivity.Job(request());
        ActivityController<TransitActivity> controller = activity(job).resume();
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, job.request);
        controller.get().finish();
        job.complete(plan, "解冻迟到成功", true);
        assertTrue(job.cancelled);
        assertNull(shadow(controller.get()).getNextStartedActivity());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    private void assertNoPageWidgets(View view) {
        assertFalse(view instanceof TextView);
        assertFalse(view instanceof ProgressBar);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) assertNoPageWidgets(group.getChildAt(i));
        }
    }
}
