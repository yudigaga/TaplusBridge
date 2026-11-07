package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import android.widget.TextView;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

/** Manual control models are obsolete; legacy cached entries are identified for removal only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class HostCaptureActionAndroidTest {
    @Test public void noSourceCanCreateManualControl() {
        Context context=RuntimeEnvironment.getApplication();
        for(String source:new String[]{CaptureLease.WECHAT,CaptureLease.QQ,null,"",BridgeContract.HOST_PACKAGE,"com.example.reader"})
            assertNull(HostCaptureAction.create(context,Model.class,source));
    }
    @Test public void automaticSourceEligibilityRemainsExplicit() {
        assertTrue(HostCaptureAction.eligible(CaptureLease.WECHAT)); assertTrue(HostCaptureAction.eligible(CaptureLease.QQ));
        for(String source:new String[]{null,"",BridgeContract.HOST_PACKAGE,BridgeContract.APP_PACKAGE,"com.example.reader"})
            assertFalse(HostCaptureAction.eligible(source));
    }
    @Test public void onlyExactLegacyCategoryIsRecognizedIncludingInheritedField() {
        Model legacy=new Model(); legacy.cat=HostCaptureAction.CATEGORY; assertTrue(HostCaptureAction.isAction(legacy));
        assertTrue(HostCaptureAction.isAction(new ChildModel()));
        legacy.cat="URL"; assertFalse(HostCaptureAction.isAction(legacy));
        assertFalse(HostCaptureAction.isAction(null)); assertFalse(HostCaptureAction.isAction(new Object()));
    }
    @Test public void oldArmAndBindEntryPointsStaySilentAndCannotExecuteUserAction() {
        Context context=RuntimeEnvironment.getApplication(); AtomicInteger closed=new AtomicInteger();
        TextView card=new TextView(context); card.setText("宿主推荐");
        HostCaptureAction.bind(card); HostCaptureAction.arm(context,CaptureLease.WECHAT,closed::incrementAndGet);
        assertEquals("宿主推荐",card.getText().toString()); assertEquals(0,closed.get());
        assertEquals(0,ShadowToast.shownToastCount());
    }
    public static class Model { String cat; }
    public static class BaseModel { private String cat=HostCaptureAction.CATEGORY; }
    public static final class ChildModel extends BaseModel { }
}
