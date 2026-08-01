package org.example;

import static java.util.Objects.requireNonNull;

public record ProcessResult(int exitValue, byte[] stdout, byte[] stderr, long processId) {
    public ProcessResult {
        requireNonNull(stdout, "stdout");
        requireNonNull(stderr, "stderr");
    }
}
