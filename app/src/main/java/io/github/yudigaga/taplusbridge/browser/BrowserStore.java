package io.github.yudigaga.taplusbridge.browser;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;

/** Browser selection is private to the module and independent of gesture drafts. */
public final class BrowserStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;

    public BrowserStore(Context context) {
        preferences = context.getSharedPreferences("taplus_bridge_browser", Context.MODE_PRIVATE);
    }

    public String selectedPackage() {
        synchronized (LOCK) {
            try {
                String selected = preferences.getString("selected_package", "");
                return HostPolicy.validTarget(selected) ? selected : "";
            } catch (RuntimeException malformed) { return ""; }
        }
    }

    public boolean save(String packageName) {
        synchronized (LOCK) {
            if (packageName == null || (!packageName.isEmpty() && !HostPolicy.validTarget(packageName))) return false;
            String previous = selectedPackage();
            try {
                SharedPreferences.Editor edit = preferences.edit();
                if (packageName.isEmpty()) edit.remove("selected_package");
                else edit.putString("selected_package", packageName);
                if (edit.commit()) return true;
            } catch (RuntimeException ignored) { }
            // A failed disk commit may already have replaced SharedPreferences' in-memory value.
            try {
                SharedPreferences.Editor restore = preferences.edit();
                if (previous.isEmpty()) restore.remove("selected_package");
                else restore.putString("selected_package", previous);
                restore.commit();
            } catch (RuntimeException ignored) { }
            return false;
        }
    }
}
