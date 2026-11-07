package io.github.yudigaga.taplusbridge.translation;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class TranslationActivityAndroidTest {
    @SuppressWarnings("unchecked") private static Class<? extends Activity> activity() {
        try { return (Class<? extends Activity>)Class.forName("io.github.yudigaga.taplusbridge.translation.TranslationActivity"); }
        catch(ClassNotFoundException absent){throw new AssertionError("translation result activity is missing",absent);}
    }
    @Test public void missingOrInvalidTokenNeverCreatesAnActiveTranslationRequest() throws Exception {
        for(String token:new String[]{null,"invalid-ticket"}) {
            Intent intent=new Intent();if(token!=null)intent.putExtra("token",token);
            var controller=Robolectric.buildActivity(activity(),intent).create().start().resume().visible();
            Object owner=controller.get();
            Method retained=owner.getClass().getDeclaredMethod("onRetainCustomNonConfigurationInstance");retained.setAccessible(true);
            Object job=retained.invoke(owner);
            Method status=job.getClass().getDeclaredMethod("getStatus");status.setAccessible(true);
            assertEquals("EXPIRED",status.invoke(job).toString());
            var text=job.getClass().getDeclaredField("text");text.setAccessible(true);
            assertEquals("",text.get(job));
            controller.pause().stop().destroy();
        }
    }

    @Test public void rotationRetainsTheSameJobInsteadOfConsumingOrStartingAnotherRequest() throws Exception {
        var controller=Robolectric.buildActivity(activity(),new Intent()).create().start().resume().visible();
        Activity original=controller.get();
        Method retained=original.getClass().getDeclaredMethod("onRetainCustomNonConfigurationInstance");retained.setAccessible(true);
        Object job=retained.invoke(original);
        android.content.res.Configuration rotated=new android.content.res.Configuration(original.getResources().getConfiguration());
        rotated.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(rotated);
        Object replacement=controller.get();
        Method replacementRetained=replacement.getClass().getDeclaredMethod("onRetainCustomNonConfigurationInstance");replacementRetained.setAccessible(true);
        assertSame(job,replacementRetained.invoke(replacement));
        controller.pause().stop().destroy();
    }

    @Test public void destructionCancelsTheJobAndItsQueuedWork() throws Exception {
        var controller=Robolectric.buildActivity(activity(),new Intent()).create().start().resume().visible();
        Activity owner=controller.get();
        Method retained=owner.getClass().getDeclaredMethod("onRetainCustomNonConfigurationInstance");retained.setAccessible(true);
        Object job=retained.invoke(owner);
        var cancellation=job.getClass().getDeclaredField("cancellation");cancellation.setAccessible(true);
        android.os.CancellationSignal signal=new android.os.CancellationSignal();cancellation.set(job,signal);
        var future=job.getClass().getDeclaredField("future");future.setAccessible(true);
        java.util.concurrent.FutureTask<Void> queued=new java.util.concurrent.FutureTask<>(()->null);future.set(job,queued);
        controller.pause().stop().destroy();
        assertTrue(signal.isCanceled());assertTrue(queued.isCancelled());
    }
}
