package io.github.yudigaga.taplusbridge.runtime;
public final class BridgeApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();RuntimePublisher.start(this);}
}
