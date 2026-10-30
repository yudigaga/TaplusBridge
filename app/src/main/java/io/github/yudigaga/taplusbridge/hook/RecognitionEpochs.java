package io.github.yudigaga.taplusbridge.hook;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Binds actual host asynchronous objects to their originating pick, never to callback arrival time. */
public final class RecognitionEpochs {
    @FunctionalInterface public interface Call<T> { T run() throws Throwable; }
    private final Map<Object,Long> owners=new WeakHashMap<>();
    private final ThreadLocal<Long> scope=new ThreadLocal<>();
    private long current=-1;
    public synchronized void reset(long epoch) { current=epoch; }
    public synchronized boolean bind(Object owner,long epoch) {
        if(owner==null || current!=epoch)return false;
        owners.putIfAbsent(owner,epoch); return owners.get(owner)==epoch;
    }
    public synchronized long owner(Object value) { return owners.getOrDefault(value,-1L); }
    public long scopeEpoch() { Long value=scope.get();return value==null?-1:value; }
    public synchronized boolean scopeCurrent(long epoch) { return current==epoch && scopeEpoch()==epoch; }
    public <T>T scoped(long epoch,Call<T> operation) throws Throwable {
        Long previous=scope.get();scope.set(epoch);
        try { return operation.run(); }
        finally { if(previous==null)scope.remove();else scope.set(previous); }
    }
    /** Unknown callbacks remain usable outside a pick, e.g. clipboard; active picks reject them. */
    public <T>T callback(Object owner,BooleanSupplier active,Call<T> operation) throws Throwable {
        long origin=owner(owner);
        if(origin<0)return active.getAsBoolean()?null:operation.run();
        if(!active.getAsBoolean())return null;
        synchronized(this) { if(origin!=current)return null; }
        return scoped(origin,operation);
    }
}
