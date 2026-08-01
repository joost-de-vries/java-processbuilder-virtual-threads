package org.example;

import org.jspecify.annotations.Nullable;

/// @param <StdoutResult> what the stdout handler derived from the lines it was given
/// @param <StderrResult> what the stderr handler derived from the lines it was given
public record StreamedResult<StdoutResult extends @Nullable Object, StderrResult extends @Nullable Object>(
        int exitValue, StdoutResult stdout, StderrResult stderr, long processId) {
}
