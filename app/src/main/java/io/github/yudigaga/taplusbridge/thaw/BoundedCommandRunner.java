package io.github.yudigaga.taplusbridge.thaw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs a process without blocking on a full pipe or retaining unlimited command output. */
final class BoundedCommandRunner {
    private static final int OUTPUT_LIMIT = 4096;

    static final class Outcome {
        final boolean completed;
        final int exitCode;
        final String output;
        final String failure;

        Outcome(boolean completed, int exitCode, String output, String failure) {
            this.completed = completed;
            this.exitCode = exitCode;
            this.output = output;
            this.failure = failure;
        }

        boolean successful() {
            return completed && exitCode == 0 && failure.isEmpty();
        }
    }

    private BoundedCommandRunner() {}

    static Outcome root(String command) {
        return run(new ProcessBuilder("su", "-c", command), 12_000L);
    }

    /** Package-private seam for actual process timeout/output tests on the local JVM. */
    static Outcome run(ProcessBuilder builder, long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis");
        Process process = null;
        Thread reader = null;
        CappedOutput captured = new CappedOutput();
        try {
            process = builder.redirectErrorStream(true).start();
            InputStream output = process.getInputStream();
            reader = new Thread(() -> captured.drain(output), "TaplusBridge-root-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return new Outcome(false, -1, captured.text(), "命令超时");
            }
            int exit = process.exitValue();
            reader.join(250L);
            return new Outcome(true, exit, captured.text(), "");
        } catch (IOException | RuntimeException error) {
            return new Outcome(false, -1, captured.text(), error.getClass().getSimpleName());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Outcome(false, -1, captured.text(), "操作已取消");
        } finally {
            if (process != null) {
                // A denied authorization dialog or an interrupted shell must not leave a job running.
                process.destroy();
                try {
                    if (!process.waitFor(150L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                } catch (RuntimeException ignored) {
                    // Some vendor process implementations cannot force termination.
                }
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
            }
            if (reader != null) {
                try { reader.join(250L); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static final class CappedOutput {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        void drain(InputStream stream) {
            byte[] buffer = new byte[1024];
            try {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (this) {
                        int remaining = OUTPUT_LIMIT - bytes.size();
                        if (remaining > 0) bytes.write(buffer, 0, Math.min(remaining, count));
                    }
                    // Continue draining after the cap so a verbose child cannot block the shell.
                }
            } catch (IOException ignored) {
                // Closing the stream during timeout cleanup is expected.
            }
        }

        synchronized String text() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
