package io.github.yudigaga.taplusbridge.probe;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

/** Bounded, process-local comparison only. Logs never contain stored screen coordinates. */
final class ProbePointEcho {
    private record Stream(int display,long down) { }
    private record Key(Stream stream,int action) { }
    private record Point(float x,float y) { }
    private final LinkedHashMap<Key,Point> expected=new LinkedHashMap<>();
    private final Set<Stream> mismatched=new HashSet<>();
    synchronized void expect(int display,long down,int action,float x,float y) {
        Key key=new Key(new Stream(display,down),action);expected.put(key,new Point(x,y));
        while(expected.size()>12){Key old=expected.keySet().iterator().next();expected.remove(old);mismatched.remove(old.stream);}
    }
    synchronized String observe(int display,long down,int action,float x,float y) {
        Stream stream=new Stream(display,down);Point point=expected.get(new Key(stream,action));
        if(point==null)return "unrelated";
        if(!Float.isFinite(x)||!Float.isFinite(y)||Math.hypot(x-point.x,y-point.y)>1){mismatched.add(stream);return "mismatch";}
        return "confirmed";
    }
    synchronized boolean valid(int display,long down){return !mismatched.contains(new Stream(display,down));}
}
