package org.example;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;

public final class ProcessRunner {

    public static RunningProcess startProcess(String[] cmd, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        return startProcess(cmd, null, timeoutAfter, gracePeriod);
    }

    public static RunningProcess startProcess(String[] cmd, byte @Nullable [] stdin, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(requireNonNull(cmd, "cmd"));

        var process = processBuilder.start();

        return new RunningProcess(process, stdin, timeoutAfter, gracePeriod);
    }

    private ProcessRunner() { // static only
    }
}
