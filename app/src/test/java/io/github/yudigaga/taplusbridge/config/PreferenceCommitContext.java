package io.github.yudigaga.taplusbridge.config;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real Android memory/listener semantics, with a controlled outcome after memory changes. */
public final class PreferenceCommitContext extends ContextWrapper {
    private final SharedPreferences preferences;
    public boolean reject, throwAfterMemory, failRestore;
    public CountDownLatch memoryChanged, releaseCommit;
    public int broadcasts;

    public PreferenceCommitContext(Context context) {
        super(context);
        SharedPreferences actual = context.getSharedPreferences("config", 0);
        preferences = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!"edit".equals(method.getName())) return invoke(method, actual, args);
                    SharedPreferences.Editor editor = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, operation, values) -> {
                                if ("apply".equals(operation.getName()) && failRestore)
                                    throw new IllegalStateException("memory restoration unavailable");
                                if ("commit".equals(operation.getName())) {
                                    boolean committed = editor.commit();
                                    if (memoryChanged != null) memoryChanged.countDown();
                                    if (releaseCommit != null && !releaseCommit.await(5, TimeUnit.SECONDS))
                                        throw new AssertionError("Blocked preference commit timed out");
                                    if (throwAfterMemory) throw new IllegalStateException("disk unavailable");
                                    return committed && !reject;
                                }
                                Object result = invoke(operation, editor, values);
                                return result instanceof SharedPreferences.Editor ? editProxy : result;
                            });
                });
    }

    @Override public Context getApplicationContext() { return this; }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return "config".equals(name) ? preferences : super.getSharedPreferences(name, mode);
    }
    @Override public void sendBroadcast(Intent intent) { broadcasts++; }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
