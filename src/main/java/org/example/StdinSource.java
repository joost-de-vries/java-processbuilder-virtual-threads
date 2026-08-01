package org.example;

import java.io.IOException;
import java.io.InputStream;

/// Opens the stream to feed to the standard input of a process.
///
/// A source rather than a plain [InputStream] makes ownership unambiguous: whoever receives the source opens the
/// stream, so they close it too. That keeps a caller its own streams, such as [System#in], from being closed
/// underneath them.
///
/// Unlike [java.util.function.Supplier] this can throw [IOException], so the common case of streaming from a file
/// needs no wrapping: `() -> Files.newInputStream(path)`.
@FunctionalInterface
public interface StdinSource {

    /// Called at most once, on the thread that writes to the process, and never before the process is waited for.
    /// The returned stream must not be null.
    InputStream open() throws IOException;
}
