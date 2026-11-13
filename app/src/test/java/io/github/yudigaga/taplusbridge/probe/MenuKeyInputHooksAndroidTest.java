package io.github.yudigaga.taplusbridge.probe;

import android.view.KeyEvent;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class MenuKeyInputHooksAndroidTest {
    private Class<?> type() {
        try { return Class.forName("io.github.yudigaga.taplusbridge.probe.MenuKeyInputHooks"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Menu retention needs a real system key-input barrier",missing); }
    }
    @Test public void realKeyDownRevokesMenuRetentionButKeyUpDoesNot() throws Exception {
        Method filter=type().getDeclaredMethod("isNewKeyInput",KeyEvent.class);filter.setAccessible(true);
        for(int key:new int[]{KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_HOME,KeyEvent.KEYCODE_APP_SWITCH,
                KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_VOLUME_DOWN}) {
            assertEquals(true,filter.invoke(null,new KeyEvent(KeyEvent.ACTION_DOWN,key)));
            assertEquals(false,filter.invoke(null,new KeyEvent(KeyEvent.ACTION_UP,key)));
        }
        assertEquals(false,filter.invoke(null,(Object)null));
    }
    @Test public void hookEntryIsTheSystemInputManagerBoundaryBeforeWindowPolicy() throws Exception {
        Method lookup=type().getDeclaredMethod("entry",Class.class);lookup.setAccessible(true);
        Method hooked=(Method)lookup.invoke(null,FakeInputManager.class);
        assertEquals("interceptKeyBeforeQueueing",hooked.getName());
        assertEquals(FakeInputManager.class,hooked.getDeclaringClass());
        assertEquals(int.class,hooked.getReturnType());
        assertArrayEquals(new Class<?>[]{KeyEvent.class,int.class},hooked.getParameterTypes());
    }
    public static final class FakeInputManager {
        private int interceptKeyBeforeQueueing(KeyEvent event,int policyFlags) {return 0;}
    }
}
