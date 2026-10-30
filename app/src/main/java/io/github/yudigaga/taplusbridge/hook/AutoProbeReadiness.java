package io.github.yudigaga.taplusbridge.hook;

import java.util.function.BooleanSupplier;

/** Current-pick recognition barriers. Elapsed time never proves a result or a physical UP. */
public final class AutoProbeReadiness {
    private final BooleanSupplier hasLink;
    private long epoch=-1, taskGeneration;
    private boolean localComplete, recommendationPending, claimed;
    public AutoProbeReadiness(BooleanSupplier hasLink) { this.hasLink=hasLink; }
    public synchronized void reset(long nextEpoch) {
        epoch=nextEpoch; localComplete=false; recommendationPending=false; claimed=false; taskGeneration++;
    }
    public synchronized void segmentComplete(long eventEpoch) {
        if(epoch==eventEpoch)localComplete=true;
    }
    /** Empty online local OCR returns synchronously without requesting segment or recommendations. */
    public synchronized void localOcrComplete(long eventEpoch,String text) {
        if(epoch==eventEpoch && (text==null || text.isEmpty()))localComplete=true;
    }
    public synchronized long recommendationStarted(long eventEpoch) {
        if(epoch!=eventEpoch)return -1;
        recommendationPending=true; return ++taskGeneration;
    }
    public synchronized boolean recommendationFinished(long eventEpoch,long task) {
        if(epoch!=eventEpoch || task<0 || task!=taskGeneration || !recommendationPending)return false;
        recommendationPending=false; return true;
    }
    public synchronized boolean awaitingRecommendation(long eventEpoch,long task) {
        return epoch==eventEpoch && task>=0 && task==taskGeneration && recommendationPending;
    }
    public synchronized boolean ready(long eventEpoch) {
        return epoch==eventEpoch && localComplete && !recommendationPending && !claimed && !hasLink.getAsBoolean();
    }
    public synchronized boolean claim(long eventEpoch) {
        if(!ready(eventEpoch))return false;
        claimed=true; return true;
    }
    /** Re-evaluated before ARM, READY and the actual TAP command, including late server links. */
    public synchronized boolean current(long eventEpoch) {
        return epoch==eventEpoch && localComplete && !recommendationPending && claimed && !hasLink.getAsBoolean();
    }
}
