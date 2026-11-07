package io.github.yudigaga.taplusbridge.probe;

import io.github.yudigaga.taplusbridge.capture.CaptureLease;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProbeGateTest {
    private static final String NONCE="a".repeat(43), OTHER="b".repeat(43);
    private static final String SOURCE=CaptureLease.WECHAT, HASH="c".repeat(64), NEW_HASH="d".repeat(64);
    private ProbeGate.Ticket arm(ProbeGate gate) { return gate.arm(NONCE,SOURCE,21001,0,100); }
    private ProbeGate.Ticket tapped(ProbeGate gate) {
        ProbeGate.Ticket ticket=arm(gate); assertTrue(gate.released(ticket,101));
        assertTrue(gate.tap(NONCE,0,102)); return ticket;
    }
    @Test public void automaticInputRequiresExplicitPhysicalReleaseEvidence() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket ticket=arm(gate);
        assertTrue(gate.awaitingRelease(ticket)); assertFalse(gate.awaitingTap(ticket));
        assertFalse(gate.tap(NONCE,0,101));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,HASH,101));
        assertTrue(gate.released(ticket,102)); assertFalse(gate.released(ticket,103));
        assertTrue(gate.tap(NONCE,0,103));
    }
    @Test public void tapRequiresCurrentNonceUserAndReleaseDeadline() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket ticket=arm(gate); gate.released(ticket,200);
        assertFalse(gate.tap(OTHER,0,201)); assertFalse(gate.tap(NONCE,1,201));
        assertFalse(gate.tap(NONCE,0,199)); assertFalse(gate.tap(NONCE,0,2700));
        assertTrue(gate.tap(NONCE,0,2699)); assertFalse(gate.tap(NONCE,0,2699));
    }
    @Test public void wrongIdentityNeverConsumesOwnedInput() {
        ProbeGate gate=new ProbeGate(); tapped(gate);
        assertEquals(ProbeGate.Decision.PASS,gate.claim(CaptureLease.QQ,21001,0,true,HASH,103));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21002,0,true,HASH,103));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,1,true,HASH,103));
        assertEquals(ProbeGate.Decision.CAPTURE,gate.claim(SOURCE,21001,0,true,HASH,103));
    }
    @Test public void firstUnknownComponentIsConsumedButDoesNotBlockUnrelatedRequests() {
        ProbeGate gate=new ProbeGate(); tapped(gate);
        assertEquals(ProbeGate.Decision.CAPTURE,gate.claim(SOURCE,21001,0,false,103));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,NEW_HASH,104));
    }
    @Test public void completedTransactionReleasesOrdinaryLinksImmediately() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket ticket=tapped(gate);
        assertEquals(ProbeGate.Decision.CAPTURE,gate.claim(SOURCE,21001,0,true,HASH,103));
        assertTrue(gate.finish(ticket));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,HASH,104));
        assertFalse(gate.activeFor(SOURCE,21001));
    }
    @Test public void timedOutTransactionDoesNotCreateWholeSourceTombstone() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket ticket=tapped(gate);
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,HASH,6102));
        assertTrue(gate.finish(ticket));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,HASH,6103));
        assertNotNull(gate.arm(OTHER,SOURCE,21001,0,6104));
    }
    @Test public void cancellationBeforeOrAfterTapNeverBlocksNormalInteractions() {
        for(boolean afterTap:new boolean[]{false,true}) {
            ProbeGate gate=new ProbeGate(); if(afterTap)tapped(gate); else arm(gate);
            assertTrue(gate.cancel(NONCE,0)); assertFalse(gate.tap(NONCE,0,104));
            assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,HASH,105));
        }
    }
    @Test public void newGenerationReplacesInFlightWithoutWaitingForCooldown() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket old=tapped(gate);
        ProbeGate.Ticket fresh=gate.arm(OTHER,SOURCE,21001,0,103);
        assertTrue(fresh.generation()>old.generation());
        assertFalse(gate.finish(old)); assertFalse(gate.released(old,104)); assertFalse(gate.cancel(NONCE,0));
        assertTrue(gate.awaitingRelease(fresh)); assertTrue(gate.released(fresh,104));
        assertTrue(gate.tap(OTHER,0,105));
    }
    @Test public void knownOldFingerprintIsBlockedBeforeNewInputWithoutClaimingLease() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket old=tapped(gate);
        assertEquals(ProbeGate.Decision.CAPTURE,gate.claim(SOURCE,21001,0,true,HASH,103)); gate.finish(old);
        ProbeGate.Ticket fresh=gate.arm(OTHER,SOURCE,21001,0,104);
        assertEquals(ProbeGate.Decision.BLOCK_LATE,gate.claim(SOURCE,21001,0,true,HASH,105));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,NEW_HASH,105));
        assertTrue(gate.released(fresh,106));
        assertEquals(ProbeGate.Decision.BLOCK_LATE,gate.claim(SOURCE,21001,0,true,HASH,107));
        assertTrue(gate.awaitingTap(fresh)); assertTrue(gate.tap(OTHER,0,108));
        assertEquals(ProbeGate.Decision.CAPTURE,gate.claim(SOURCE,21001,0,true,HASH,109));
    }
    @Test public void duplicateCurrentClaimIsFilteredWithoutBlockingAnotherUrl() {
        ProbeGate gate=new ProbeGate(); tapped(gate);
        assertEquals(ProbeGate.Decision.CAPTURE,gate.claim(SOURCE,21001,0,true,HASH,103));
        assertEquals(ProbeGate.Decision.BLOCK_LATE,gate.claim(SOURCE,21001,0,true,HASH,104));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,NEW_HASH,104));
    }
    @Test public void fingerprintExpiresWithoutIndefiniteSourceLock() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket old=tapped(gate);
        gate.claim(SOURCE,21001,0,true,HASH,103); gate.finish(old);
        gate.arm(OTHER,SOURCE,21001,0,10000);
        assertEquals(ProbeGate.Decision.BLOCK_LATE,gate.claim(SOURCE,21001,0,true,HASH,10102));
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,HASH,10103));
    }
    @Test public void fingerprintCacheRetainsAtMostSixteenCompletedHashes() {
        ProbeGate gate=new ProbeGate();
        for(int i=0;i<17;i++) {
            long now=100+i*4; ProbeGate.Ticket t=gate.arm(NONCE,SOURCE,21001,0,now);
            gate.released(t,now); gate.tap(NONCE,0,now);
            gate.claim(SOURCE,21001,0,true,String.format("%064x",i),now+1); gate.finish(t);
        }
        gate.arm(OTHER,SOURCE,21001,0,200);
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,String.format("%064x",0),201));
        assertEquals(ProbeGate.Decision.BLOCK_LATE,gate.claim(SOURCE,21001,0,true,String.format("%064x",16),201));
    }
    @Test public void malformedFingerprintNeverCreatesBroadBlocking() {
        ProbeGate gate=new ProbeGate(); tapped(gate);
        gate.claim(SOURCE,21001,0,true,"https://private.example/path",103);
        assertEquals(ProbeGate.Decision.PASS,gate.claim(SOURCE,21001,0,true,"https://private.example/path",104));
    }
    @Test public void repeatedTerminalCallbackAndForeignProfileAreRejected() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket ticket=tapped(gate);
        assertTrue(gate.finish(ticket)); assertFalse(gate.finish(ticket));
        try { gate.arm(NONCE,SOURCE,121001,0,100); fail(); } catch(IllegalArgumentException expected) { }
    }
    @Test public void currentUserAliasesBindOnlyToActiveAuthenticatedProfile() {
        ProbeGate gate=new ProbeGate(); ProbeGate.Ticket ticket=tapped(gate);
        for(int requested:new int[]{0,-2,-3}) {
            ProbeGate.RequestedUser bound=gate.requestedUser(SOURCE,21001,requested);
            assertEquals(0,bound.boundUser()); assertTrue(bound.supported());
        }
        for(int requested:new int[]{1,-1,-10000}) {
            ProbeGate.RequestedUser bound=gate.requestedUser(SOURCE,21001,requested);
            assertEquals(0,bound.boundUser()); assertFalse(bound.supported());
        }
        assertEquals(-2,gate.requestedUser(SOURCE,21002,-2).boundUser());
        gate.finish(ticket); assertEquals(-2,gate.requestedUser(SOURCE,21001,-2).boundUser());
    }
}
