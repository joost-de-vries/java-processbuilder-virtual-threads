import org.example.RunningProcess;

import static org.example.ProcessRunner.startProcess;
import static java.lang.IO.println;

void main() throws IOException, InterruptedException {
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
