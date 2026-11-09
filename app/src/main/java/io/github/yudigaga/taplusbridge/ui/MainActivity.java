package io.github.yudigaga.taplusbridge.ui;

import android.os.Bundle;
import android.content.Intent;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle;
import java.util.HashMap;
import java.util.Map;

/** Settings only; host hooks and transparent launch activities do not host this UI. */
public final class MainActivity extends ComponentActivity {
    private SettingsController settings;
    private Runnable refreshListener;
    private ExternalResults externalResults;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new SettingsController(this);
        MiuixSettingsScreen.install(this, settings);
    }
    public void setRefreshListener(Runnable listener) { refreshListener = listener; }
    private ExternalResults externalResults() {
        if (externalResults == null) externalResults = new ViewModelProvider(this).get(ExternalResults.class);
        return externalResults;
    }
    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (requestCode < 0) { super.startActivityForResult(intent, requestCode, options); return; }
        externalResults().begin(requestCode);
        try { super.startActivityForResult(intent, requestCode, options); }
        catch (RuntimeException unavailable) { externalResults().finish(requestCode); throw unavailable; }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        try { super.onActivityResult(requestCode, resultCode, data); }
        finally { externalResults().finish(requestCode); }
    }
    /** A document picker can outlive a configuration replacement of this settings Activity. */
    public static final class ExternalResults extends ViewModel {
        private final Map<Integer, ModuleProcessLifecycle.Lease> pending = new HashMap<>();
        void begin(int requestCode) {
            ModuleProcessLifecycle.Lease old = pending.put(requestCode, ModuleProcessLifecycle.acquire(5 * 60_000));
            if (old != null) old.close();
        }
        void finish(int requestCode) {
            ModuleProcessLifecycle.Lease lease = pending.remove(requestCode);
            if (lease != null) lease.close();
        }
        @Override protected void onCleared() {
            for (ModuleProcessLifecycle.Lease lease : pending.values()) lease.close();
            pending.clear();
        }
    }
    @Override protected void onResume() {
        super.onResume();io.github.yudigaga.taplusbridge.runtime.RuntimePublisher.synchronize(this);
        if (refreshListener != null) refreshListener.run();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (refreshListener != null) refreshListener.run();
    }
    @Override protected void onDestroy() {
        refreshListener = null;
        if (settings != null) settings.close();
        super.onDestroy();
    }
}
