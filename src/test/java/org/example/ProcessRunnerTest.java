package org.example;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.StructuredTaskScope.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.example.ProcessRunner.startProcess;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Assumes that every system running these tests has gzip, perl, sh, ps, lsof and kill installed.
class ProcessRunnerTest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public static RunningProcess startDefault(String[] cmd, byte[] stdin) throws IOException {
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
    @DisplayName("teleport large response")
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
    @DisplayName("destroy grandchildren too")
    void grandchildIsDestroyed() throws Exception {
        record Pids(long process, long grandchild) {}

        var pids = using(startProcess(new String[]{"sh", "-c", "sleep 30 & wait"}, Duration.ofMillis(500),Duration.ofMillis(500)), runningProcess -> {
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
            try (var runningProcess = startProcess(new String[]{"sleep", "30"}, Duration.ofMillis(5),Duration.ofMillis(5))) {
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
