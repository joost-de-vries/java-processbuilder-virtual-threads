import org.example.RunningProcess;

import static org.example.ProcessRunner.startProcess;
import static java.lang.IO.println;

void main() throws IOException, InterruptedException {
    zipInMemory();
    batchEventsAsTheyArrive();
}

/// Simple in memory example
static void zipInMemory() throws IOException, InterruptedException {
    var toZip = "zip me";

    String[] cmd = {"gzip", "-c"};
    try (RunningProcess runningProcess = startProcess(cmd, toZip.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5), Duration.ofSeconds(5))) {

        println("started process with pid: " + runningProcess.getProcess().pid());
        var result = runningProcess.waitFor();
        println("exit value: " + result.exitValue());
        var zipped = result.stdout();

        var unzippedAgain = unzip(zipped);

        println("as expected: " + unzippedAgain.equals(toZip));
    }
}

static String unzip(byte[] zipped) throws IOException {
    var bytes = new GZIPInputStream(new ByteArrayInputStream(zipped)).readAllBytes();
    return new String(bytes, StandardCharsets.UTF_8);
}

/// For a long running task there can be more output than we want to load in memory at the same time.
/// Also we may want to respond as lines come in.
/// Here we use [Gatherers#windowFixed(int)] to batch events before we handle them
static void batchEventsAsTheyArrive() throws IOException, InterruptedException {
    String[] cmd = {"perl", "-e", "$| = 1; for (1..7) { print \"event $_\\n\"; select(undef, undef, undef, 0.25); }"};

    try (var runningProcess = startProcess(cmd, Duration.ofSeconds(10), Duration.ofSeconds(5))) {

        println("streaming events from pid: " + runningProcess.getProcess().pid());
        var start = System.nanoTime();

        var result = runningProcess.waitForLines(
                events -> events
                        .gather(Gatherers.windowFixed(3))
                        .map(batch ->
                                writeBatch(batch, Duration.ofNanos(System.nanoTime() - start))
                        )
                        .reduce(0, Integer::sum),
                Stream::count);

        println("exit value: " + result.exitValue());
        println("events written: " + result.stdout());
        println("lines on stderr: " + result.stderr());
    }
}

/// Stands in for a bulk write.
static int writeBatch(List<String> batch, Duration elapsed) {
    println("writing batch of " + batch.size() + " after " + elapsed.toMillis() + "ms: " + batch);
    return batch.size();
}
