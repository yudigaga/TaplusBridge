package io.github.yudigaga.taplusbridge.lens;

/** Only the explicit Lens activity and its bounded source-app transitions may keep the panel alive. */
public final class LensInteractionLease {
    static final long LAUNCH_TIMEOUT_MILLIS = 8000;
    private final int moduleUid, sourceUid;
    private final long launchUntil;
    private long returnUntil;
    private boolean active = true, moduleForeground;

    public LensInteractionLease(int moduleUid, int sourceUid, long now) {
        if (moduleUid < 0 || sourceUid < 0 || moduleUid / 100000 != sourceUid / 100000)
            throw new IllegalArgumentException("lens_interaction_identity");
        this.moduleUid = moduleUid; this.sourceUid = sourceUid; launchUntil = now + LAUNCH_TIMEOUT_MILLIS;
    }

    public synchronized boolean foreground(int uid, boolean foreground, long now) {
        if (!active) return now <= returnUntil
                && ((uid == moduleUid && !foreground) || (uid == sourceUid && foreground));
        if (uid == moduleUid) {
            moduleForeground = foreground;
            if (!foreground) returnUntil = now + 2000;
            return true;
        }
        return uid == sourceUid && ((!foreground && now <= launchUntil) || (foreground && now <= returnUntil));
    }

    public synchronized boolean keyboard() { return active && moduleForeground; }
    public synchronized boolean active() { return active; }
    public synchronized void end(long now) {
        if (!active) return;
        active = false; moduleForeground = false; returnUntil = now + 2000;
    }
}
