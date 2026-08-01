
# Java ProcessBuilder, Virtual Threads and Structured Concurrency

A few utility methods to run a process from Java with structured concurrency, using some newer Java features.

Requires Java `25` with `--enable-preview`.

## Design
Our requirements:
- The `java.lang.ProcessBuilder` api requires us to read the standard output and standard error of the process in separate threads.  
- We need to destroy the process after we are done with it. For error cases as well.  
- Running a process is unpredictable; we need to specify a timeout. Because a hanging process is a resource leak.
- child processes should be destroyed as well
- we want the option of not reading stdin, stdout, stderr all at once in memory
- We want the `process id` before we start waiting for the process to finish. So we can test error scenarios where the process is killed by the OS or by Kubernetes.

Structured concurrency can help us with this. The blocking calls can be handled with virtual threads, of which we can afford one per stream. And the structured concurrency helps us with error handling and making sure to clean up resources. Both came out of Project Loom, which is where this repo got its name.

## How to use
In memory example:
```java
    var toZip = "zip me";

    String[] cmd = {"gzip", "-c"};
    try (RunningProcess runningProcess = startProcess(cmd, toZip.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5), Duration.ofSeconds(5))) {

        IO.println("started process with pid: " + runningProcess.getProcess().pid());
        var result = runningProcess.waitFor();
        IO.println("exit value: " + result.exitValue());
        var zipped = result.stdout();

        var unzippedAgain = unzip(zipped);

        IO.println("as expected: "+ unzippedAgain.equals(toZip));
    }
```

If stdin is too large to hold in memory provide a lazy inputstream instead of the bytes:
```java
    try (var runningProcess = startProcess(cmd, () -> Files.newInputStream(cargo), Duration.ofSeconds(5), Duration.ofSeconds(5))) {
```

For a long running task there can be more output than we want to load in memory at the same time. Also we may want to respond as lines come in.
Here we use `Gatherers#windowFixed(int)` to batch events before we handle them as they arrive.
```java
    String[] cmd = {"perl", "-e", "$| = 1; for (1..7) { print \"event $_\\n\"; select(undef, undef, undef, 0.25); }"};

    try (var runningProcess = startProcess(cmd, Duration.ofSeconds(10), Duration.ofSeconds(5))) {

        IO.println("streaming events from pid: " + runningProcess.getProcess().pid());
        var start = System.nanoTime();

        var result = runningProcess.waitForLines(
                events -> events
                        .gather(Gatherers.windowFixed(3))
                        .map(batch ->
                                writeBatch(batch, Duration.ofNanos(System.nanoTime() - start))
                        )
                        .reduce(0, Integer::sum),
                Stream::count);

        IO.println("exit value: " + result.exitValue());
        IO.println("events written: " + result.stdout());
        IO.println("lines on stderr: " + result.stderr());
    }
```
## How does it work
The `startProcess` method returns a `RunningProcess` that implements `AutoCloseable`. And thus we support `try-with-resources`.
```java
    public static RunningProcess startProcess(String[] cmd, @Nullable StdinSource stdin, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(requireNonNull(cmd, "cmd"));

        var process = processBuilder.start();

        return new RunningProcess(process, stdin, timeoutAfter, gracePeriod);
    }
```

It combines the resources we need to cleanup: the `java.lang.Process` plus its children plus the `java.util.concurrent.StructuredTaskScope` that captures the threads that read standard output and standard error.
By using `try-with-resources` we make sure that both the process is always destroyed and the threads are always finished or interrupted.

Btw if we call `startProcess` without a `try-with-resources` statement our IDE suggests introducing it, so that makes the method almost self documenting.

Our core`RunningProcess.waitFor` generic types are a bit hard to read. Basically they're just callbacks that receive the stdout or stderr `InputStream`s and process them on structured concurrency virtual threads. Either in memory or as java streams.  
We fork virtual threads to write stdin, read stdout, read stderr and wait for the process to finish. And gather them in a scope that awaits all four and propagates the first failure
```java
    private <StdoutResult, StderrResult> StreamedResult<StdoutResult, StderrResult> waitFor(
            OutputHandler<StdoutResult> stdoutHandler, OutputHandler<StderrResult> stderrHandler) throws InterruptedException {

        scope.fork(this::readStdin);

        var stdout = scope.fork(() -> stdoutHandler.handle(process.getInputStream()));
        var stderr = scope.fork(() -> stderrHandler.handle(process.getErrorStream()));
        var exitValue = scope.fork(() -> process.waitFor());

        scope.join(); // await all four, or throw TimeoutException / FailedException

        return new StreamedResult<>(exitValue.get(), stdout.get(), stderr.get(), process.pid());
    }
```

Unfortunately `java.lang.Process.deastroy()` doesn't signal child processes. So we do that manually. Also if processes do not terminate gracefully we do that by force after the grace period.
```java
    private void destroyProcessTree() {
        // snapshot the descendants first: once the process dies its children are reparented and can no longer be found
        var descendants = process.descendants().toList();
    
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);
    
        if (!awaitExit(descendants)) {
            process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
    }
```

Some alternatives that were considered before using virtual threads and structured concurrency:  
We could follow the lead of `java.lang.Process.onExit` and use `CompletableFuture`s to read the input streams. For instance using `CompletableFuture.supplyAsync`. That would run the task on the `ForkJoinPool.commonPool()`. But then we need to take special care to mark the task as blocking. Otherwise our pool will quickly run out. For instance using `ForkJoinPool.managedBlock`. It's doable but involves more code and joining the 4 `CompletableFuture`s is not that straightforward in Java. Java not having something like a `do notation` (for comprehension ...) to easily combine futures.  
We could create a `Runnable` class to read from input stream. But then the threads will have to report back errors to the main thread. 

## Modern java
The `--enable-preview` flag is needed because `java.util.concurrent.StructuredTaskScope` is still a preview api ([JEP 505](https://openjdk.org/jeps/505)). Virtual threads themselves are final since Java 21.

Java has improved a lot in recent years!
- Virtual threads ([JEP 444](https://openjdk.org/jeps/444)) and structured concurrency.
- Records and sealed interfaces and pattern matching for data oriented programming.
- try-with-resources for AutoCloseable resources.
- `var` for local variables.
- JSpecify `@NullMarked` / `@Nullable` to distinguish mandatory from optional arguments. No [JEP 8303099](https://openjdk.org/jeps/8303099) (Null-Restricted and Nullable Types) yet, unfortunately, that is still a draft.
- stream gatherers for stream processing ([JEP 485](https://openjdk.org/jeps/485))
- Compact source files and instance `main` methods ([JEP 512](https://openjdk.org/jeps/512))
- `java.lang.IO.println` instead of `System.out.println`
- Module import declarations ([JEP 511](https://openjdk.org/jeps/511)): `import module java.base;` replaces a dozen single-type imports in `RunningProcess`.
- `_` for unnamed variables, so a caught exception we deliberately ignore says so.
- Markdown documentation comments ([JEP 467](https://openjdk.org/jeps/467)): `///` instead of `/** */`, with `[Process#destroy()]` instead of `{@link Process#destroy()}`.
