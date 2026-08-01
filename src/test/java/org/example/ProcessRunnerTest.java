package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.StructuredTaskScope.FailedException;
import java.util.concurrent.StructuredTaskScope.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.example.ProcessRunner.startProcess;
import static org.junit.jupiter.api.Assertions.*;

/// Assumes that every system running these tests has gzip, perl, sh, ps, lsof and kill installed.
class ProcessRunnerTest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public static RunningProcess startDefault(String[] cmd, byte[] stdin) throws IOException {
        return startProcess(cmd, stdin, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT);
    }

    public static RunningProcess startDefault(String[] cmd, StdinSource stdin) throws IOException {
        return startProcess(cmd, stdin, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT);
    }

    public static RunningProcess startDefault(String[] cmd) throws IOException {
        return startProcess(cmd, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT);
    }

    @Test
    @DisplayName("call external program successfully")
    void callExternalProgramSuccessfully() throws Exception {
        var data = "tell me o muse of that ingenious hero".getBytes(UTF_8);

        try (var runningProcess = startDefault(new String[]{"gzip", "-c"}, data)) {
            var result = runningProcess.waitFor();

            var unzipped = new GZIPInputStream(new ByteArrayInputStream(result.stdout())).readAllBytes();

            assertEquals(0, result.exitValue());
            assertFalse(Arrays.equals(data, result.stdout()), "stdout should be gzipped, not the original data");
            assertArrayEquals(data, unzipped);
        }
    }

    @Test
    @DisplayName("time out")
    void timeOut() throws Exception {
        var timeoutAfter = Duration.ofMillis(200);
        var processDurationSeconds = 10;

        var start = System.nanoTime();
        var pid = using(startProcess(new String[]{"sh"}, "echo 'hello';sleep 10".getBytes(UTF_8), timeoutAfter, timeoutAfter), runningProcess -> {
            assertThrows(TimeoutException.class, runningProcess::waitFor);

            return runningProcess.getProcess().pid();
        });
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // it waited for the timeout instead of failing immediately, and it did not wait for the process to finish
        assertTrue(elapsed.compareTo(timeoutAfter) >= 0, "should have waited at least " + timeoutAfter + " but was " + elapsed);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(processDurationSeconds)) < 0, "should not have waited for the process to finish but was " + elapsed);

        // closing the RunningProcess must have destroyed the process
        assertTrue(awaitProcessGone(pid), "process " + pid + " should no longer exist after close()");
    }

    @Test
    @DisplayName("signal")
    void signal() throws Exception {
        try (var runningProcess = startDefault(new String[]{"sleep", "5"})) {
            var pid = runningProcess.getProcess().pid();

            try (var kill = startDefault(new String[]{"kill", "-s", "TERM", Long.toString(pid)})) {
                assertEquals(0, kill.waitFor().exitValue());
            }

            var result = runningProcess.waitFor();

            assertEquals(143, result.exitValue()); // 128 + 15 (SIGTERM)
            assertEquals(pid, result.processId());
        }
    }

    @Test
    @DisplayName("large argument")
    void largeResponse(@TempDir Path tempDir) throws Exception {
        var largeFile = tempDir.resolve("nodes.json");
        Files.writeString(largeFile, "x".repeat(4 * 1024 * 1024), UTF_8);

        try (var runningProcess = startDefault(new String[]{"cat", largeFile.toString()})) {
            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertTrue(result.stdout().length > 0);
            assertEquals(Files.size(largeFile), result.stdout().length);
            assertEquals(0, result.stderr().length);
        }
    }

    @Test
    @DisplayName("fail when command does not exist")
    void failWhenCommandDoesNotExist() {
        // if you supply a command which does not exist, java fails with IOException 'No such file or directory'
        assertThrows(IOException.class, () -> {
            // startProcess throws before there is anything to close, but should that ever change we must not leak it
            try (var runningProcess = startDefault(new String[]{"gzipgzipgzipgzipgzip", "-c"}, "tell me o muse of that ingenious hero".getBytes(UTF_8))) {
                runningProcess.waitFor();
            }
        });
    }

    @Test
    @DisplayName("fail when command fails")
    void failWhenCommandFails() throws Exception {
        var cmd = new String[]{"perl", "-e", "print \"voyage  started\"; print STDERR \"shipwreck error\"; exit(1);"};

        try (var runningProcess = startDefault(cmd, "tell me o muse of that ingenious hero".getBytes(UTF_8))) {
            var result = runningProcess.waitFor();

            assertEquals(1, result.exitValue());
            assertArrayEquals("voyage  started".getBytes(UTF_8), result.stdout());
            assertEquals("shipwreck error", new String(result.stderr(), UTF_8));
        }
    }

    @Test
    @DisplayName("run without stdin")
    void runWithoutStdin() throws Exception {
        var cmd = new String[]{"perl", "-e", "print \"voyage  started\"; print STDERR \"shipwreck error\""};

        try (var runningProcess = startDefault(cmd)) {
            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertArrayEquals("voyage  started".getBytes(UTF_8), result.stdout());
            assertEquals("shipwreck error", new String(result.stderr(), UTF_8));
        }
    }

    @Test
    @DisplayName("no deadlock when both stdout and stderr exceed the pipe buffer")
    void noDeadlockOnLargeStdoutAndStderr() throws Exception {
        // a single thread reading stdout first would block forever once the stderr pipe buffer (~64k) fills up
        var cmd = new String[]{"perl", "-e", "print STDOUT 'o' x 1000000; print STDERR 'e' x 1000000;"};

        try (var runningProcess = startDefault(cmd)) {
            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertEquals(1_000_000, result.stdout().length);
            assertEquals(1_000_000, result.stderr().length);
        }
    }

    @Test
    @DisplayName("no deadlock when stdin exceeds the pipe buffer")
    void noDeadlockOnLargeStdin() throws Exception {
        // writing stdin on the calling thread would block once the pipe buffer fills up and nobody drains stdout
        var stdin = "x".repeat(1_000_000).getBytes(UTF_8);

        try (var runningProcess = startDefault(new String[]{"cat"}, stdin)) {
            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertArrayEquals(stdin, result.stdout());
        }
    }

    @Test
    @DisplayName("report exit value and partial output when the process is killed")
    void killedByTheOperatingSystem() throws Exception {
        // the scenario the process id is needed for: killed by the OS or by kubernetes
        // exec, so that sleep replaces the shell and the pid we kill is the process itself
        try (var runningProcess = startDefault(new String[]{"sh", "-c", "echo partial; exec sleep 5"})) {
            var pid = runningProcess.getProcess().pid();

            try (var kill = startDefault(new String[]{"sh", "-c", "sleep 0.2; kill -9 " + pid})) {
                assertEquals(0, kill.waitFor().exitValue());
            }

            var result = runningProcess.waitFor();

            assertEquals(137, result.exitValue()); // 128 + 9 (SIGKILL)
            assertEquals("partial", new String(result.stdout(), UTF_8).trim());
        }
    }

    @Test
    @DisplayName("close without ever waiting for the result")
    void closeWithoutWaitFor() throws Exception {
        var pid = using(startDefault(new String[]{"sleep", "30"}), runningProcess ->
                runningProcess.getProcess().pid());

        assertTrue(awaitProcessGone(pid), "process " + pid + " should have been destroyed by close()");
    }

    @Test
    @DisplayName("leak neither processes, threads nor file descriptors")
    void noResourceLeaks() throws Exception {
        // warm up, so the virtual thread carrier pool and the jit have settled
        repeat(20);

        var threadsBefore = Thread.getAllStackTraces().size();
        var fdsBefore = openFileDescriptors();

        repeat(200);

        assertEquals(0, ProcessHandle.current().children().count(), "no child process should have survived");
        assertTrue(Thread.getAllStackTraces().size() <= threadsBefore + 4,
                "platform threads grew from " + threadsBefore + " to " + Thread.getAllStackTraces().size());
        assertTrue(openFileDescriptors() <= fdsBefore + 8,
                "open file descriptors grew from " + fdsBefore + " to " + openFileDescriptors());
    }

    @Test
    @DisplayName("leak neither processes nor file descriptors when timing out")
    void noResourceLeaksOnTimeout() throws Exception {
        repeatTimingOut(10);

        var threadsBefore = Thread.getAllStackTraces().size();
        var fdsBefore = openFileDescriptors();

        repeatTimingOut(50);

        assertEquals(0, ProcessHandle.current().children().count(), "no child process should have survived");
        assertTrue(Thread.getAllStackTraces().size() <= threadsBefore + 4,
                "platform threads grew from " + threadsBefore + " to " + Thread.getAllStackTraces().size());
        assertTrue(openFileDescriptors() <= fdsBefore + 8,
                "open file descriptors grew from " + fdsBefore + " to " + openFileDescriptors());
    }

    @Test
    @DisplayName("succeed when the process does not read stdin")
    void processThatDoesNotReadStdin() throws Exception {
        var stdin = "x".repeat(1_000_000).getBytes(UTF_8);

        // the process exits without draining stdin, which breaks the pipe. That is not a failure of ours.
        try (var runningProcess = startDefault(new String[]{"sh", "-c", "exit 0"}, stdin)) {
            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertEquals(0, result.stdout().length);
        }
    }

    @Test
    @DisplayName("stream stdin from a file instead of from memory")
    void streamStdinFromFile(@TempDir Path tempDir) throws Exception {
        var cargo = tempDir.resolve("cargo.txt");
        Files.writeString(cargo, "x".repeat(4 * 1024 * 1024), UTF_8);

        // no wrapping needed: a StdinSource may throw IOException
        try (var runningProcess = startDefault(new String[]{"cat"}, () -> Files.newInputStream(cargo))) {
            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertEquals(Files.size(cargo), result.stdout().length);
        }
    }

    @Test
    @DisplayName("open supplied stdin only when waited for, and close it afterwards")
    void suppliedStdinIsOpenedLazilyAndClosed() throws Exception {
        var opened = new AtomicInteger();
        var closed = new AtomicBoolean();

        StdinSource stdin = () -> {
            opened.incrementAndGet();
            return new FilterInputStream(new ByteArrayInputStream("cargo".getBytes(UTF_8))) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };
        };

        try (var runningProcess = startDefault(new String[]{"cat"}, stdin)) {
            assertEquals(0, opened.get(), "the stream should not be opened before waitFor");

            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertArrayEquals("cargo".getBytes(UTF_8), result.stdout());
            assertEquals(1, opened.get(), "the stream should be opened exactly once");
            assertTrue(closed.get(), "we opened the stream, so we should have closed it");
        }
    }

    @Test
    @DisplayName("close supplied stdin even when the process does not read it")
    void suppliedStdinIsClosedWhenTheProcessDoesNotReadIt() throws Exception {
        var closed = new AtomicBoolean();

        StdinSource stdin = () -> new FilterInputStream(new ByteArrayInputStream("x".repeat(1_000_000).getBytes(UTF_8))) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        // the broken pipe must not leave the stream we opened dangling
        try (var runningProcess = startDefault(new String[]{"sh", "-c", "exit 0"}, stdin)) {
            var result = runningProcess.waitFor();

            assertEquals(0, result.exitValue());
            assertTrue(closed.get(), "the stream should be closed even though the pipe broke");
        }
    }

    @Test
    @DisplayName("fail when opening stdin fails")
    void failWhenOpeningStdinFails() throws Exception {
        StdinSource stdin = () -> {
            throw new IOException("no such cargo");
        };

        // unlike a process that ignores stdin, failing to produce stdin at all is our failure to report, and the
        // IOException must not be swallowed as if it were the broken pipe of a process that stopped reading
        try (var runningProcess = startDefault(new String[]{"cat"}, stdin)) {
            var failure = assertThrows(FailedException.class, runningProcess::waitFor);

            var cause = assertInstanceOf(IOException.class, failure.getCause());
            assertEquals("no such cargo", cause.getMessage());
        }
    }

    @Test
    @DisplayName("fail when stdin is opened as null")
    void failWhenStdinIsOpenedAsNull() throws Exception {
        StdinSource stdin = () -> null;

        try (var runningProcess = startDefault(new String[]{"cat"}, stdin)) {
            var failure = assertThrows(FailedException.class, runningProcess::waitFor);

            assertInstanceOf(NullPointerException.class, failure.getCause());
        }
    }

    @Test
    @DisplayName("hand stdout lines to the handler as they arrive, not at the end")
    void streamStdoutLinesAsTheyArrive() throws Exception {
        // flushes the first line and only then pauses, so an eager reader would deliver both lines at the same moment
        var cmd = new String[]{"perl", "-e", "$| = 1; print \"one\\n\"; sleep 1; print \"two\\n\";"};
        var arrivalMillis = new ArrayList<Long>();

        try (var runningProcess = startDefault(cmd)) {
            var start = System.nanoTime();

            var result = runningProcess.waitForLines(
                    lines -> lines.map(line -> {
                        arrivalMillis.add((System.nanoTime() - start) / 1_000_000);
                        return line;
                    }).toList(),
                    Stream::count);

            assertEquals(0, result.exitValue());
            assertEquals(List.of("one", "two"), result.stdout());
            assertEquals(2, arrivalMillis.size());
            assertTrue(arrivalMillis.get(1) - arrivalMillis.getFirst() > 500,
                    "the lines should arrive a second apart, but arrived at " + arrivalMillis);
        }
    }

    @Test
    @DisplayName("handle stdout and stderr independently")
    void handleStdoutAndStderrIndependently() throws Exception {
        var cmd = new String[]{"perl", "-e", "print STDOUT \"cargo $_\\n\" for 1..3; print STDERR \"warning $_\\n\" for 1..5;"};

        try (var runningProcess = startDefault(cmd)) {
            var result = runningProcess.waitForLines(lines -> lines.map(String::toUpperCase).toList(), Stream::count);

            assertEquals(0, result.exitValue());
            assertEquals(List.of("CARGO 1", "CARGO 2", "CARGO 3"), result.stdout());
            assertEquals(5L, (long) result.stderr());
        }
    }

    @Test
    @DisplayName("boil down more output than we would want to hold in memory")
    void streamLargeStdoutWithoutCapturingIt() throws Exception {
        var cmd = new String[]{"perl", "-e", "print \"line $_\\n\" for 1..200000;"};

        try (var runningProcess = startDefault(cmd)) {
            var result = runningProcess.waitForLines(lines -> lines.filter(line -> line.endsWith("7")).count(), Stream::count);

            assertEquals(0, result.exitValue());
            assertEquals(20_000L, (long) result.stdout(), "every tenth of the 200000 lines ends in a 7");
        }
    }

    @Test
    @DisplayName("fail when a line handler throws")
    void failWhenLineHandlerThrows() throws Exception {
        try (var runningProcess = startDefault(new String[]{"echo", "cargo"})) {
            var failure = assertThrows(FailedException.class, () -> runningProcess.<String, Long>waitForLines(
                    _ -> {
                        throw new IllegalStateException("handler gave up");
                    },
                    Stream::count));

            assertInstanceOf(IllegalStateException.class, failure.getCause());
        }
    }

    @Test
    @DisplayName("no deadlock when both streamed stdout and stderr exceed the pipe buffer")
    void noDeadlockOnLargeStreamedStdoutAndStderr() throws Exception {
        // the reason the streams are handled inside the scope: a stream handed back to the caller would let them drain
        // one pipe while the other filled up, which is the deadlock a forked task per pipe avoids
        var cmd = new String[]{"perl", "-e", "print STDOUT \"o$_\\n\" for 1..100000; print STDERR \"e$_\\n\" for 1..100000;"};

        try (var runningProcess = startDefault(cmd)) {
            var result = runningProcess.waitForLines(Stream::count, Stream::count);

            assertEquals(0, result.exitValue());
            assertEquals(100_000L, (long) result.stdout());
            assertEquals(100_000L, (long) result.stderr());
        }
    }

    @Test
    @DisplayName("destroy grandchildren too")
    void grandchildIsDestroyed() throws Exception {
        record Pids(long process, long grandchild) {
        }

        var pids = using(startProcess(new String[]{"sh", "-c", "sleep 30 & wait"}, Duration.ofMillis(500), Duration.ofMillis(500)), runningProcess -> {
            var grandchildPid = awaitDescendant(runningProcess.getProcess());

            assertThrows(TimeoutException.class, runningProcess::waitFor);

            return new Pids(runningProcess.getProcess().pid(), grandchildPid);
        });

        assertTrue(awaitProcessGone(pids.process()), "the process should be destroyed");
        assertTrue(awaitProcessGone(pids.grandchild()), "grandchild " + pids.grandchild() + " should be destroyed as well");
    }

    @Test
    @DisplayName("kill a process that ignores being asked to terminate")
    void processThatIgnoresSigterm() throws Exception {
        var pid = using(startProcess(new String[]{"sh", "-c", "trap '' TERM; sleep 30"}, Duration.ofMillis(200), Duration.ofMillis(200)), runningProcess -> {
            assertThrows(TimeoutException.class, runningProcess::waitFor);

            return runningProcess.getProcess().pid();
        });

        assertTrue(awaitProcessGone(pid), "process " + pid + " should have been killed forcibly");
    }

    @Test
    @DisplayName("wait for the configured grace period before killing forcibly")
    void explicitGracePeriod() throws Exception {
        var gracePeriod = Duration.ofSeconds(1);

        var start = System.nanoTime();
        var pid = using(startProcess(new String[]{"sh", "-c", "trap '' TERM; sleep 30"}, Duration.ofMillis(100), gracePeriod), runningProcess -> {
            assertThrows(TimeoutException.class, runningProcess::waitFor);

            return runningProcess.getProcess().pid();
        });
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // the process ignores being asked to terminate, so close() waits the full grace period before killing it
        assertTrue(elapsed.compareTo(gracePeriod) >= 0, "close should have waited at least " + gracePeriod + " but was " + elapsed);
        assertTrue(awaitProcessGone(pid), "process " + pid + " should have been killed forcibly");
    }

    @Test
    @DisplayName("derive the grace period from the timeout when it is not configured")
    void defaultGracePeriodFollowsTheTimeout() throws Exception {
        // a tenth of the timeout, so a short timeout means a short wait before killing forcibly
        var start = System.nanoTime();
        var pid = using(startProcess(new String[]{"sh", "-c", "trap '' TERM; sleep 30"}, Duration.ofMillis(100), Duration.ofMillis(100)), runningProcess -> {
            assertThrows(TimeoutException.class, runningProcess::waitFor);

            return runningProcess.getProcess().pid();
        });
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(elapsed.compareTo(Duration.ofMillis(900)) < 0, "should not have waited a full second but was " + elapsed);
        assertTrue(awaitProcessGone(pid), "process " + pid + " should have been killed forcibly");
    }

    private static long awaitDescendant(Process process) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var descendant = process.descendants().findFirst();
            if (descendant.isPresent()) {
                return descendant.get().pid();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("process " + process.pid() + " never spawned a grandchild");
    }

    private static void repeat(int times) throws Exception {
        for (var i = 0; i < times; i++) {
            try (var runningProcess = startDefault(new String[]{"echo", "hello"}, "in".getBytes(UTF_8))) {
                assertEquals(0, runningProcess.waitFor().exitValue());
            }
        }
    }

    private static void repeatTimingOut(int times) throws Exception {
        for (var i = 0; i < times; i++) {
            try (var runningProcess = startProcess(new String[]{"sleep", "30"}, Duration.ofMillis(5), Duration.ofMillis(5))) {
                assertThrows(TimeoutException.class, runningProcess::waitFor);
            }
        }
    }

    private static long openFileDescriptors() throws Exception {
        var cmd = new String[]{"sh", "-c", "lsof -p " + ProcessHandle.current().pid() + " 2>/dev/null | wc -l"};

        return using(startDefault(cmd), lsof ->
                Long.parseLong(new String(lsof.waitFor().stdout(), UTF_8).trim()));
    }

    private static boolean awaitProcessGone(long pid) throws Exception {
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            // already gone and reaped, so there is nothing left to wait for
            return true;
        }

        try {
            handle.get().onExit().get(5, TimeUnit.SECONDS);
            return true;

            // the fully qualified name matters: this class imports StructuredTaskScope.TimeoutException as TimeoutException
        } catch (java.util.concurrent.TimeoutException _) {
            return false;
        }
    }

    /**
     * Java has no try-with-resources <em>expression</em>, so this turns the statement into one.
     */
    private static <R extends AutoCloseable, T> T using(R resource, ThrowingFunction<R, T> f) throws Exception {
        try (resource) {
            return f.apply(resource);
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<R, T> {
        T apply(R resource) throws Exception;
    }
}
