package io.github.yudigaga.taplusbridge.hook;

import android.content.Context;
import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import java.lang.reflect.Field;

/** Recognizes legacy cached control models so automatic mode can remove them. */
public final class HostCaptureAction {
    public static final String CATEGORY="TAPLUS_BRIDGE_CAPTURE";
    private HostCaptureAction() { }
    public static boolean eligible(String source) { return CaptureLease.allowedSource(source); }
    public static Object create(Context context,Class<?> model,String source) { return null; }
    public static boolean isAction(Object model) {
        if(model==null)return false;
        for(Class<?> type=model.getClass();type!=null;type=type.getSuperclass())try {
            Field category=type.getDeclaredField("cat");category.setAccessible(true);
            return category.getType()==String.class && CATEGORY.equals(category.get(model));
        } catch(NoSuchFieldException missing) { }
        catch(ReflectiveOperationException|RuntimeException unavailable) { return false; }
        return false;
    }
    /** Legacy entry points are inert: no extra control, toast, dialog, or manual click workflow. */
    public static void bind(Object ignored) { }
    public static void arm(Context context,String source,Runnable ignoredClose) { }
}
