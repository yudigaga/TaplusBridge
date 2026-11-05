package io.github.yudigaga.taplusbridge.probe;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pointer lifecycle only. Never retains coordinates or application contents. */
public final class PhysicalTouchState {
    public static final int DOWN=0, UP=1, MOVE=2, CANCEL=3, POINTER_DOWN=5, POINTER_UP=6;
    private record Stream(int display, int device, long downAt) { }
    private final Map<Integer,Long> active=new HashMap<>();
    // Only a new DOWN observed after this local time can begin recovery for this device.
    private final Map<Integer,Long> uncertain=new HashMap<>();
    private final LinkedHashMap<Stream,Long> injected=new LinkedHashMap<>();
    private boolean observedUp, overflow;
    private long releasedAt=-1, releasedElapsedAt=-1;
    public synchronized void injected(int display,int device,long downAt,long now) {
        prune(now); injected.put(new Stream(display,device,downAt),now+30000);
        while(injected.size()>32) injected.remove(injected.keySet().iterator().next());
    }
    public synchronized void event(int display,int device,long downAt,long at,int action,long now) {
        prune(now);
        if(!isTouchAction(action) || display!=0 || injected.containsKey(new Stream(display,device,downAt))) return;
        if(overflow) return; // Stop retaining device IDs once physical state can no longer be proven.
        if(at<0 || at>now || downAt<0 || downAt>at) { uncertainDevice(device,now); return; }
        if(action==DOWN) {
            Long since=uncertain.get(device);
            if(since!=null && downAt<since) return;
            uncertain.remove(device); active.put(device,downAt);
        }
        else if(action==UP) {
            if(uncertain.containsKey(device)) return;
            Long existing=active.get(device);
            if(existing!=null && existing!=downAt) return;
            active.remove(device); observedUp=true; releasedAt=at;
        } else if(action==CANCEL) { active.remove(device); uncertainDevice(device,now); }
        else if(action==MOVE || action==POINTER_DOWN || action==POINTER_UP) {
            Long existing=active.get(device);
            if(existing==null || existing!=downAt) uncertainDevice(device,now);
        }
        checkLimit();
    }
    public synchronized boolean released() { return observedUp && !overflow && active.isEmpty() && uncertain.isEmpty(); }
    public synchronized boolean unavailable() { return overflow; }
    public synchronized boolean isInjected(int display, int device, long downAt, long now) {
        prune(now); return injected.containsKey(new Stream(display, device, downAt));
    }
    public synchronized boolean soleStream(int display, int device, long downAt) {
        Long activeDown = active.get(device);
        return display == 0 && !overflow && uncertain.isEmpty() && active.size() == 1
                && activeDown != null && activeDown == downAt;
    }
    public synchronized long releasedAt() { return released()?releasedAt:-1; }
    public synchronized long releasedElapsedAt() { return released()?releasedElapsedAt:-1; }
    public synchronized void markReleasedElapsed(long nowElapsed) {
        if (released() && nowElapsed >= 0) releasedElapsedAt = nowElapsed;
    }
    public synchronized void unknown() { observedUp=false; overflow=true; }
    /** A readable device with one malformed event cannot disable unrelated future gestures globally. */
    public synchronized void uncertainDevice(int device,long now) {
        if(overflow) return;
        if(now<0) { unknown(); return; }
        uncertain.merge(device,now,Math::max); checkLimit();
    }
    public static boolean isTouchAction(int action) {
        return action==DOWN || action==UP || action==MOVE || action==CANCEL || action==POINTER_DOWN || action==POINTER_UP;
    }
    /** Android public input source bits: pointer=0x2 and mouse=0x2002; stylus and touch remain eligible. */
    public static boolean isTouchPointer(int source,int action) {
        return (source&0x2)!=0 && (source&0x2002)!=0x2002 && isTouchAction(action);
    }
    private void checkLimit() {
        int devices=active.size();
        for(Integer device:uncertain.keySet())if(!active.containsKey(device))devices++;
        if(devices>16)overflow=true;
    }
    private void prune(long now) {
        for(Iterator<Map.Entry<Stream,Long>> it=injected.entrySet().iterator();it.hasNext();) if(now>=it.next().getValue()) it.remove();
    }
}
