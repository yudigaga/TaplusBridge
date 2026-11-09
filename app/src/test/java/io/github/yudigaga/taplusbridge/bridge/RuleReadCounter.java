package io.github.yudigaga.taplusbridge.bridge;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Looper;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.robolectric.util.ReflectionHelpers;

/** Counts UI-thread preference reads without replacing storage or its listener behavior. */
public final class RuleReadCounter implements AutoCloseable {
    private final Application application;
    private final Context original;
    private final Map<String, Integer> reads = new HashMap<>();

    public RuleReadCounter(Context context) {
        application = (Application) context.getApplicationContext();
        original = application.getBaseContext();
        Context counted = new ContextWrapper(original) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                SharedPreferences actual = super.getSharedPreferences(name, mode);
                return (SharedPreferences) Proxy.newProxyInstance(RuleReadCounter.class.getClassLoader(),
                        new Class<?>[]{SharedPreferences.class}, (proxy, method, arguments) -> {
                            if (Looper.myLooper() == Looper.getMainLooper() && method.getName().equals("getAll"))
                                reads.merge(name + ":snapshot", 1, Integer::sum);
                            if (Looper.myLooper() == Looper.getMainLooper()
                                    && (method.getName().equals("getString") || method.getName().equals("getBoolean"))
                                    && arguments != null && arguments.length > 0) {
                                String key = name + ":" + arguments[0];
                                reads.merge(key, 1, Integer::sum);
                            }
                            try { return method.invoke(actual, arguments); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
            }
        };
        ReflectionHelpers.setField(application, "mBase", counted);
    }

    public int ruleSnapshots() { return reads.getOrDefault(RuleRepository.PREFS + ":snapshot", 0); }
    public int switchReads(String key) { return reads.getOrDefault("config:" + key, 0); }
    @Override public void close() { ReflectionHelpers.setField(application, "mBase", original); }
}
