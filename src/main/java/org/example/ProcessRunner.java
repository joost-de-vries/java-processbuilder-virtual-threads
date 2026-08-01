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

    public static RunningProcess startProcess(String[] cmd, byte @Nullable [] stdin, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        StdinSource source = stdin == null ? null : () -> new ByteArrayInputStream(stdin);

        return startProcess(cmd, source, timeoutAfter, gracePeriod);
    }

    /// Streams stdin from the given lazy std OutputStream
    public static RunningProcess startProcess(String[] cmd, @Nullable StdinSource stdin, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(requireNonNull(cmd, "cmd"));

        var process = processBuilder.start();

        return new RunningProcess(process, stdin, timeoutAfter, gracePeriod);
    }

    private ProcessRunner() { // static only
    }
}
