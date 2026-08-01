package org.example;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;

public final class ProcessRunner {

    public static RunningProcess startProcess(String[] cmd, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        return startProcess(cmd, (StdinSource) null, timeoutAfter, gracePeriod);
    }

    /// Convenience overload for callers that already have the entire stdin content in memory.
    public static RunningProcess startProcess(String[] cmd, byte @Nullable [] stdin, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        StdinSource source = stdin == null ? null : () -> new ByteArrayInputStream(stdin);

        return startProcess(cmd, source, timeoutAfter, gracePeriod);
    }

    /// Streams stdin from the given [StdinSource] instead of requiring the caller to hold it all in memory first.
    ///
    /// The source is opened on a dedicated thread and only once the process is waited for, and closed once written or
    /// once the process stops reading. Opening is our job, so closing is too — see [StdinSource].
    ///
    /// A source that fails to open, or that hands back `null`, fails [RunningProcess#waitFor()] — unlike a process
    /// that stops reading stdin, which is not our failure to report.
    public static RunningProcess startProcess(String[] cmd, @Nullable StdinSource stdin, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(requireNonNull(cmd, "cmd"));

        var process = processBuilder.start();

        return new RunningProcess(process, stdin, timeoutAfter, gracePeriod);
    }

    private ProcessRunner() { // static only
    }
}
