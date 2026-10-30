package io.github.yudigaga.taplusbridge.probe;

import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Generation-bound input lease. Finished probes never globally block ordinary source links. */
public final class ProbeGate {
    public static final long READY_MILLIS=2500, FINGERPRINT_MILLIS=10000;
    public enum Decision { PASS,CAPTURE,BLOCK_LATE }
    public record Ticket(long generation,String nonce,String source,int uid,int user,long armedAt) { }
    public record RequestedUser(int boundUser,boolean supported) { }
    public static final class Rejected extends IllegalStateException {
        public final String reason;
        private Rejected(String reason) { super(reason); this.reason=reason; }
    }
    private enum Phase { WAIT_RELEASE,READY,TAPPED,CLAIMED,TERMINAL }
    private record Fingerprint(String source,int uid,int user,String hash,long generation,long until) { }
    private final List<Fingerprint> recent=new ArrayList<>();
    private long generation,readyAt,tappedAt;
    private Ticket current;
    private Phase phase;
    public synchronized Ticket arm(String nonce,String source,int uid,int user,long now) {
        prune(now);
        if(nonce==null || !nonce.matches("[A-Za-z0-9_-]{43}") || !CaptureLease.allowedSource(source)
                || uid<0 || user<0 || uid/100000!=user || now<0) throw new IllegalArgumentException("invalid_identity");
        if(generation==Long.MAX_VALUE) throw new IllegalStateException("generation_exhausted");
        current=new Ticket(++generation,nonce,source,uid,user,now);
        phase=Phase.WAIT_RELEASE; readyAt=tappedAt=-1; return current;
    }
    /** Called only after physical terminal-UP evidence, never after a timer or CANCEL. */
    public synchronized boolean released(Ticket ticket,long now) {
        if(!owns(ticket) || phase!=Phase.WAIT_RELEASE || now<ticket.armedAt()) return false;
        phase=Phase.READY; readyAt=now; return true;
    }
    public synchronized boolean tap(String nonce,int user,long now) {
        if(current==null || phase!=Phase.READY || !current.nonce().equals(nonce) || current.user()!=user
                || now<readyAt || now-readyAt>=READY_MILLIS) return false;
        phase=Phase.TAPPED; tappedAt=now; return true;
    }
    public synchronized Decision claim(String source,int uid,int user,boolean knownWeb,long now) {
        return claim(source,uid,user,knownWeb,null,now);
    }
    public synchronized Decision claim(String source,int uid,int user,boolean knownWeb,String hash,long now) {
        prune(now);
        if(current==null || phase==Phase.TERMINAL || !matches(current.source(),current.uid(),current.user(),source,uid,user)) return Decision.PASS;
        // The new, physically released input wins even for the same URL. Its callback has a new nonce.
        if(phase==Phase.TAPPED && now>=tappedAt && now-tappedAt<ProbeContract.TIMEOUT_MILLIS) {
            phase=Phase.CLAIMED;
            if(knownWeb && validHash(hash)) {
                recent.add(new Fingerprint(source,uid,user,hash,current.generation(),now+FINGERPRINT_MILLIS));
                while(recent.size()>16) recent.remove(0);
            }
            return Decision.CAPTURE;
        }
        if(knownWeb && validHash(hash)) for(Fingerprint old:recent)
            if(matches(old.source(),old.uid(),old.user(),source,uid,user) && old.hash().equals(hash)) return Decision.BLOCK_LATE;
        return Decision.PASS;
    }
    public synchronized boolean activeFor(String source,int uid) {
        return current!=null && phase!=Phase.TERMINAL && current.source().equals(source) && current.uid()==uid;
    }
    public synchronized Ticket current() { return current; }
    public synchronized RequestedUser requestedUser(String source,int uid,int requested) {
        if(activeFor(source,uid) && uid>=0 && uid/100000==current.user())
            return new RequestedUser(current.user(),requested==current.user() || requested==-2 || requested==-3);
        return new RequestedUser(requested,true);
    }
    public synchronized boolean awaitingRelease(Ticket ticket) { return owns(ticket) && phase==Phase.WAIT_RELEASE; }
    public synchronized boolean awaitingTap(Ticket ticket) { return owns(ticket) && phase==Phase.READY; }
    public synchronized boolean awaitingLaunch(Ticket ticket) { return owns(ticket) && phase==Phase.TAPPED; }
    public synchronized boolean inFlight(Ticket ticket) { return owns(ticket) && (phase==Phase.TAPPED || phase==Phase.CLAIMED); }
    public synchronized boolean finish(Ticket ticket) {
        if(!owns(ticket) || phase==Phase.TERMINAL) return false;
        phase=Phase.TERMINAL; return true;
    }
    public synchronized boolean cancel(String nonce,int user) {
        return current!=null && current.nonce().equals(nonce) && current.user()==user && finish(current);
    }
    private boolean owns(Ticket ticket) {
        return ticket!=null && current!=null && ticket.generation()==current.generation() && ticket.nonce().equals(current.nonce());
    }
    private static boolean validHash(String hash) { return hash!=null && hash.matches("[0-9a-f]{64}"); }
    private void prune(long now) { for(Iterator<Fingerprint> it=recent.iterator();it.hasNext();) if(now>=it.next().until()) it.remove(); }
    private static boolean matches(String a,int auid,int auser,String b,int buid,int buser) { return a.equals(b) && auid==buid && auser==buser; }
}
