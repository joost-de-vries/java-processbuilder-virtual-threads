package org.example;

import module java.base;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.StructuredTaskScope.Joiner;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class RunningProcess implements AutoCloseable {

    RunningProcess(Process process, @Nullable StdinSource stdin, Duration timeoutAfter, Duration gracePeriod) {
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
        var result = waitFor(RunningProcess::readInputStream, RunningProcess::readInputStream);

        return new ProcessResult(result.exitValue(), result.stdout(), result.stderr(), result.processId());
    }

    /// Hands stdout and stderr to the given handlers as lazy streams of lines, so output is processed as it arrives
    /// instead of being held in memory in full.
    ///
    /// The handlers run on virtual threads that are guaranteed to be destroyed when waitForLines terminates.
    ///
    /// For that reason a handler must not let the stream escape, by returning it or by stashing it somewhere. Derive
    /// a value from it — [Stream#toList()], [Stream#count()], a [java.util.stream.Gatherer] — and return that.
    ///
    /// @param <StdoutResult> what the stdout handler derives from the lines, and so what [StreamedResult#stdout()] holds
    /// @param <StderrResult> what the stderr handler derives from the lines, and so what [StreamedResult#stderr()] holds
    /// @throws StructuredTaskScope.TimeoutException if the process did not finish within the timeout
    /// @throws StructuredTaskScope.FailedException  if a handler threw, or reading stdout or stderr failed
    public <StdoutResult extends @Nullable Object, StderrResult extends @Nullable Object>
    StreamedResult<StdoutResult, StderrResult> waitForLines(Charset charset,
                                                            Function<Stream<String>, StdoutResult> stdout,
                                                            Function<Stream<String>, StderrResult> stderr)
            throws InterruptedException {

        requireNonNull(charset, "charset");
        requireNonNull(stdout, "stdout");
        requireNonNull(stderr, "stderr");

        return waitFor(inputStream -> handleLines(inputStream, charset, stdout),
                inputStream -> handleLines(inputStream, charset, stderr));
    }

    /// Assumes the process writes UTF-8. See [RunningProcess#waitForLines(Charset, Function, Function)]
    public <StdoutResult extends @Nullable Object, StderrResult extends @Nullable Object>
    StreamedResult<StdoutResult, StderrResult> waitForLines(Function<Stream<String>, StdoutResult> stdout,
                                                            Function<Stream<String>, StderrResult> stderr)
            throws InterruptedException {

        return waitForLines(StandardCharsets.UTF_8, stdout, stderr);
    }

    private <StdoutResult extends @Nullable Object, StderrResult extends @Nullable Object>
    StreamedResult<StdoutResult, StderrResult> waitFor(OutputHandler<StdoutResult> stdoutHandler,
                                                       OutputHandler<StderrResult> stderrHandler)
            throws InterruptedException {

        scope.fork(this::readStdin);

        var stdout = scope.fork(() -> stdoutHandler.handle(process.getInputStream()));
        var stderr = scope.fork(() -> stderrHandler.handle(process.getErrorStream()));
        var exitValue = scope.fork(() -> process.waitFor());

        scope.join();

        return new StreamedResult<>(exitValue.get(), stdout.get(), stderr.get(), process.pid());
    }

    /// Turns one pipe into whatever its handler makes of it, and is where a handler runs.
    @FunctionalInterface
    private interface OutputHandler<Result extends @Nullable Object> {
        Result handle(InputStream inputStream) throws IOException;
    }

    /// Returns [Void] rather than being a plain `void` method so that it forks as a [Callable] and can report the
    /// [IOException] of a source that fails to open.
    private @Nullable Void readStdin() throws IOException {
        if (stdin == null) {
            return null;
        }

        // opening here rather than in the constructor keeps it off the caller its thread and means a stream is only ever
        // created for a process that gets as far as being waited for
        var source = requireNonNull(stdin.open(), "stdin.open()");

        // closing order matters and is the reverse of this list: stdin of the process closes first, so it sees end of
        // input, and only then the source we opened.
        try (source; var outputStream = process.getOutputStream()) {
            source.transferTo(outputStream);

        } catch (IOException _) {
            // The process is free to exit or close its stdin without reading all of it. The pipe then breaks,
            // which surfaces as 'Broken pipe' or 'Stream closed' depending on timing. That is the process its
            // prerogative and not a failure of ours, so it must not fail the scope: the exit value tells the story.
        }

        return null;
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
    private final @Nullable StdinSource stdin;
    private final Duration gracePeriod;
    private final StructuredTaskScope<Object, Void> scope;

    private static byte[] readInputStream(InputStream inputStream) throws IOException {
        try (inputStream) {
            return inputStream.readAllBytes();
        }
    }

    /// Use a [BufferedReader] not to read a line at a time
    private static <Result extends @Nullable Object> Result handleLines(
            InputStream inputStream, Charset charset, Function<Stream<String>, Result> handler) throws IOException {

        try (var reader = new BufferedReader(new InputStreamReader(inputStream, charset))) {
            return handler.apply(reader.lines());
        }
    }
}
