package org.example;

import java.io.IOException;
import java.io.InputStream;

/// Opens the stream to feed to the standard input of a process.
/// Unlike [java.util.function.Supplier] this can throw [IOException], so the common case of streaming from a file
/// needs no wrapping: `() -> Files.newInputStream(path)`.
@FunctionalInterface
public interface StdinSource {

    /// Called at most once, on a virtual thread. Never before the process is waited for.
    /// The returned stream must not be null.
    InputStream open() throws IOException;
}
