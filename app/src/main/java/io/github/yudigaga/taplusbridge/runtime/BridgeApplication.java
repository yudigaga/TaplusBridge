package io.github.yudigaga.taplusbridge.runtime;
public final class BridgeApplication extends android.app.Application {
    @Override public void onCreate(){
        super.onCreate();
        ModuleProcessLifecycle.install(this);
        String process = android.app.Application.getProcessName();
        if ((getPackageName() + ":lens").equals(process)) android.webkit.WebView.setDataDirectorySuffix("lens");
        else if (getPackageName().equals(process)) RuntimePublisher.start(this);
    }
}
