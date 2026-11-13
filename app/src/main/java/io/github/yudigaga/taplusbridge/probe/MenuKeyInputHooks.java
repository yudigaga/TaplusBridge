package io.github.yudigaga.taplusbridge.probe;

import android.view.KeyEvent;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/** Revokes an expected menu-close echo before a new key reaches application window policy. */
public final class MenuKeyInputHooks {
    private MenuKeyInputHooks() { }

    public static boolean install(XposedModule module,ClassLoader loader,Supplier<SystemScopeCapture> capture) {
        try {
            Method entry=entry(loader.loadClass("com.android.server.input.InputManagerService"));
            module.hook(entry).setId("taplusbridge:menu-key-input")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        if(chain.getArg(0) instanceof KeyEvent event && isNewKeyInput(event)) {
                            SystemScopeCapture active=capture.get();
                            if(active!=null)active.newKeyInput();
                        }
                        return chain.proceed();
                    });
            module.log(4,"TaplusBridge","Recognition menu key-input guard installed");
            return true;
        } catch(ReflectiveOperationException|RuntimeException unavailable) {
            module.log(5,"TaplusBridge","Recognition menu key-input guard unavailable");
            return false;
        }
    }

    static Method entry(Class<?> type) throws ReflectiveOperationException {
        Method entry=type.getDeclaredMethod("interceptKeyBeforeQueueing",KeyEvent.class,int.class);
        if(entry.getReturnType()!=int.class || Modifier.isStatic(entry.getModifiers()))
            throw new NoSuchMethodException("menu_key_input_contract");
        entry.setAccessible(true);return entry;
    }

    static boolean isNewKeyInput(KeyEvent event) {return event!=null && event.getAction()==KeyEvent.ACTION_DOWN;}
}
