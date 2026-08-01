
# java ProcessBuilder and Loom structured concurrency

A simple utility method to run a process from Java using Loom structured concurrency. 
Using some newer Java features.

Requires **Java 25** with `--enable-preview`, because `java.util.concurrent.StructuredTaskScope` is a preview api ([JEP 505](https://openjdk.org/jeps/505)).

Our requirements:
- The `java.lang.ProcessBuilder` api requires us to read the standard output and standard error of the process in separate threads.  
- We need to destroy the process after we are done with it. For error cases as well.  
- Running a process is unpredictable; we need to specify a timeout. Because a hanging process is a resource leak.
- children processes should be destroyed as well
- We want the `process id` before we start waiting for the process to finish. So we can test error scenarios where the process is killed by the OS or by Kubernetes.

Loom structured concurrency can help us with this. The blocking calls can be handled with lightweight Loom threads. And the structured concurrency helps us with error handling and making sure to clean up resources.

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
Our `startProcess` method returns our `RunningProcess` object that implements `AutoCloseable`. And thus we support `try-with-resources`.
```java
    public static RunningProcess startProcess(String[] cmd, byte @Nullable [] stdin, Duration timeoutAfter, Duration gracePeriod) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(requireNonNull(cmd, "cmd"));
    
        var process = processBuilder.start();
    
        return new RunningProcess(process, stdin, timeoutAfter, gracePeriod);
    }
```

It combines the resources we need to cleanup: the `java.lang.Process` plus the `java.util.concurrent.StructuredTaskScope` that captures the threads that read standard output and standard error.
By using `try-with-resources` we make sure that both the process is always destroyed and the threads are always finished or interrupted.

Btw If we call `startProcess` without a `try-with-resources` statement our IDE suggests introducing it so that makes the method almost self documenting.

Our `RunningProcess.waitFor` method:
```java
    public ProcessResult waitFor() throws InterruptedException {
        scope.fork(this::readStdin);
    
        var stdout = scope.fork(() -> readInputStream(new BufferedInputStream(process.getInputStream())));
        var stderr = scope.fork(() -> readInputStream(new BufferedInputStream(process.getErrorStream())));
        var exitValue = scope.fork(() -> process.waitFor());
    
        scope.join(); // await all four, or throw TimeoutException / FailedException
    
        return new ProcessResult(exitValue.get(), stdout.get(), stderr.get(), process.pid());
    }
    
    private boolean readStdin() {
        if (stdin == null) {
            return true;
        }
    
        try (var outputStream = new BufferedOutputStream(process.getOutputStream())) {
            outputStream.write(stdin);
    
        } catch (IOException _) {
            // the process is free to exit without draining stdin, which breaks the pipe. Not a failure of ours.
        }
        return true;
    }
```
We fork lightweight threads to write stdin, read stdout, read stderr and wait for the process to finish. And gather them in a scope that awaits all four and propagates the first failure:
```java
    this.scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow(), configuration -> configuration.withTimeout(timeoutAfter));
```
Note that the timeout starts running when the scope is opened, so it covers the entire life of the process rather than just the waiting.

Cleaning up is more than destroying the process, because `Process.destroy` only signals the process itself. Any process that it spawned in turn would be orphaned, so we destroy the descendants too. And since `destroy` merely *requests* termination, which a process is free to ignore, we escalate to `destroyForcibly` after a grace period:
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

Alternatives:  
We could follow the lead of `java.lang.Process.onExit` and use `CompletableFuture`s to read the input streams. For instance using `CompletableFuture.supplyAsync`. That would run the task on the `ForkJoinPool.commonPool()`. But then we need to take special care to mark the task as blocking. Otherwise our pool will quickly run out. For instance using `ForkJoinPool.managedBlock`. It's doable but involves more code and joining the 4 `CompletableFuture`s is not that straightforward in Java. Java not having something like a `do notation` (for comprehension ...) to easily combine futures.  
We could create a `Runnable` class to read from input stream. But then the threads will have to report back errors to the main thread. 


Java has improved a lot in recent years!
- Loom structured concurrency and lightweight threads.
- Records and sealed interfaces and pattern matching for data oriented programming.
- try-with-resources for AutoCloseable resources.
- `var` for local variables.
- `_` for unnamed variables, so a caught exception we deliberately ignore says so.
- JSpecify `@NullMarked` / `@Nullable` to distinguish mandatory from optional arguments. No [JEP 8303099](https://openjdk.org/jeps/8303099) (Null-Restricted and Nullable Types) yet unfortunately, that is still a draft.
- Compact source files and instance `main` methods ([JEP 512](https://openjdk.org/jeps/512))
- `java.lang.IO.println` instead of `System.out.println`
- Module import declarations ([JEP 511](https://openjdk.org/jeps/511)): `import module java.base;` replaces a dozen single-type imports in `RunningProcess`.
- Markdown documentation comments ([JEP 467](https://openjdk.org/jeps/467)): `///` instead of `/** */`, with `[Process#destroy()]` instead of `{@link Process#destroy()}`.
