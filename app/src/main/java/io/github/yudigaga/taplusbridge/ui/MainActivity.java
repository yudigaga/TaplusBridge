package io.github.yudigaga.taplusbridge.ui;

import android.os.Bundle;
import androidx.activity.ComponentActivity;

/** Settings only; host hooks and transparent launch activities do not host this UI. */
public final class MainActivity extends ComponentActivity {
    private SettingsController settings;
    private Runnable refreshListener;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new SettingsController(this);
        MiuixSettingsScreen.install(this, settings);
    }
    public void setRefreshListener(Runnable listener) { refreshListener = listener; }
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
