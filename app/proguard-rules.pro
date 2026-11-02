# libxposed loads this name from META-INF/xposed/java_init.list.
-keep class io.github.yudigaga.taplusbridge.hook.TaplusModule {
    public <init>();
    public *;
}

# Shizuku creates this non-manifest UserService through reflection in another process.
-keep class io.github.yudigaga.taplusbridge.thaw.PrivilegedThawService {
    public <init>();
    public <init>(android.content.Context);
}
