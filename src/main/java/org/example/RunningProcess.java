package org.example;

import module java.base;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.StructuredTaskScope.Joiner;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class RunningProcess implements AutoCloseable {

    RunningProcess(Process process, byte @Nullable [] stdin, Duration timeoutAfter, Duration gracePeriod) {
        this.process = requireNonNull(process, "process");
        this.stdin = stdin;
        this.gracePeriod = requireNonNull(gracePeriod, "gracePeriod");

        requireNonNull(timeoutAfter, "timeoutAfter");
        // the timeout starts running here, when the scope is opened, so it covers the entire life of the process
        this.scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow(), configuration -> configuration.withTimeout(timeoutAfter));
    }

    /// @throws StructuredTaskScope.TimeoutException if the process did not finish within the timeout
    /// @throws StructuredTaskScope.FailedException  if reading stdout or stderr failed
    public ProcessResult waitFor() throws InterruptedException {
        scope.fork(this::readStdin);

        var stdout = scope.fork(() -> readInputStream(new BufferedInputStream(process.getInputStream())));
        var stderr = scope.fork(() -> readInputStream(new BufferedInputStream(process.getErrorStream())));
        // an explicit lambda rather than a method reference: Process.waitFor is overloaded, which makes the
        // method reference inexact and thus ambiguous between fork(Callable) and fork(Runnable)
        var exitValue = scope.fork(() -> process.waitFor());

        scope.join();

        return new ProcessResult(exitValue.get(), stdout.get(), stderr.get(), process.pid());
    }

    private void readStdin() {
        if (stdin == null) {
            return;
        }

        try (var outputStream = new BufferedOutputStream(process.getOutputStream())) {
            outputStream.write(stdin);

        } catch (IOException _) {
            // The process is free to exit or close its stdin without reading all of it. The pipe then breaks,
            // which surfaces as 'Broken pipe' or 'Stream closed' depending on timing. That is the process its
            // prerogative and not a failure of ours, so it must not fail the scope: the exit value tells the story.
        }
    }

    @Override
    public void close() {
        try {
            destroyProcessTree();
        } finally {
            scope.close();
        }
    }

    /// [Process#destroy()] signals the process itself only, so any process it spawned in turn would be orphaned.
    private void destroyProcessTree() {
        // snapshot the descendants first: once the process dies its children are reparented and can no longer be found
        var descendants = process.descendants().toList();

        process.destroy();
        descendants.forEach(ProcessHandle::destroy);

        if (!awaitExit(descendants)) {
            // destroy() only requests termination, which a process is free to ignore, so we insist
            process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
    }

    private boolean awaitExit(List<ProcessHandle> descendants) {
        var exited = Stream.concat(Stream.of(process.onExit()), descendants.stream().map(ProcessHandle::onExit))
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(exited).get(gracePeriod.toMillis(), MILLISECONDS);
            return true;

        } catch (TimeoutException | ExecutionException _) {
            return false;

        } catch (InterruptedException _) {
            // an interrupted caller should not be kept waiting, so stop being patient and kill the process
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Process getProcess() {
        return process;
    }

    private final Process process;
    private final byte @Nullable [] stdin;
    private final Duration gracePeriod;
    private final StructuredTaskScope<Object, Void> scope;

    private static byte[] readInputStream(BufferedInputStream inputStream) throws IOException {
        try (inputStream) {
            return inputStream.readAllBytes();
        }
    }
}
