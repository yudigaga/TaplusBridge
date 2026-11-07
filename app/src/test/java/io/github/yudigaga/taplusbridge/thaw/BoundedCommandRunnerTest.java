package io.github.yudigaga.taplusbridge.thaw;

import org.junit.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real subprocess tests; no su, package state changes or Android device required. */
public class BoundedCommandRunnerTest {
    private ProcessBuilder fixture(String action) throws Exception {
        String java = new File(System.getProperty("java.home"),
                "bin/" + (System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")).getPath();
        String classes = new File(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        return new ProcessBuilder(java, "-cp", classes, Fixture.class.getName(), action);
    }

    @Test
    public void capturesCompletedCommandAndExitCode() throws Exception {
        BoundedCommandRunner.Outcome out = BoundedCommandRunner.run(fixture("ok"), 5_000L);
        assertTrue(out.successful());
        assertEquals("0", out.output.trim());
    }

    @Test
    public void nonzeroExitIsFailureEvenWithSuccessLookingText() throws Exception {
        BoundedCommandRunner.Outcome out = BoundedCommandRunner.run(fixture("failure"), 5_000L);
        assertTrue(out.completed);
        assertEquals(23, out.exitCode);
        assertFalse(out.successful());
    }

    @Test
    public void verboseChildIsDrainedWhileRetainedOutputIsBounded() throws Exception {
        BoundedCommandRunner.Outcome out = BoundedCommandRunner.run(fixture("verbose"), 5_000L);
        assertTrue(out.successful());
        assertEquals(4096, out.output.length());
    }

    @Test
    public void blockedChildTimesOutAndReturnsWithinBound() throws Exception {
        long start = System.nanoTime();
        BoundedCommandRunner.Outcome out = BoundedCommandRunner.run(fixture("blocked"), 500L);
        assertFalse(out.completed);
        assertFalse(out.successful());
        assertEquals("命令超时", out.failure);
        assertTrue((System.nanoTime() - start) / 1_000_000L < 4_000L);
    }

    @Test
    public void interruptionIsPreservedAndDoesNotWaitForChild() throws Exception {
        AtomicReference<BoundedCommandRunner.Outcome> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        ProcessBuilder child = fixture("blocked");
        Thread caller = new Thread(() -> {
            result.set(BoundedCommandRunner.run(child, 10_000L));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        Thread.sleep(300L);
        caller.interrupt();
        caller.join(4_000L);
        assertFalse(caller.isAlive());
        assertNotNull(result.get());
        assertFalse(result.get().successful());
        assertTrue(interrupted.get());
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "ok": System.out.println("0"); return;
                case "failure": System.out.println("success"); System.exit(23); return;
                case "verbose":
                    for (int i = 0; i < 200_000; i++) System.out.print('x');
                    return;
                default: Thread.sleep(30_000L);
            }
        }
    }
}
