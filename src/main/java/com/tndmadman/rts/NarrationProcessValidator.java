package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class NarrationProcessValidator {
    private static final int NOISY_OUTPUT_BYTES = 1024 * 1024;

    private NarrationProcessValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            runHelper(args);
            return;
        }
        validateCapturedOutputIsDrainedAndBounded();
        validateDiscardedOutputCannotBlock();
        validateTimeoutKillsDescendants();
        validateRunnerRemainsUsableAfterTimeout();
        validateIoThreadCountIsBounded();
        System.out.println("StarChem narration process validation passed.");
    }

    private static void validateCapturedOutputIsDrainedAndBounded() throws Exception {
        NarrationProcessRunner.CaptureResult result = NarrationProcessRunner.runAndRead(
                new ProcessBuilder(helperCommand("--emit", Integer.toString(NOISY_OUTPUT_BYTES))), 10);
        require(!result.timedOut(), "noisy captured process timed out because its output was not drained");
        require(result.exitCode() == 0, "noisy captured process did not exit successfully");
        require(result.truncated(), "captured process output was not bounded");
        require(!result.lines().isEmpty(), "captured process output was unexpectedly empty");
        int capturedCharacters = result.lines().stream().mapToInt(String::length).sum();
        require(capturedCharacters <= NarrationProcessRunner.MAX_CAPTURE_BYTES,
                "captured process retained more than the configured output limit");
    }

    private static void validateDiscardedOutputCannotBlock() throws Exception {
        NarrationProcessRunner.ExitResult result = NarrationProcessRunner.runDiscarding(
                new ProcessBuilder(helperCommand("--emit", Integer.toString(NOISY_OUTPUT_BYTES))), 10);
        require(!result.timedOut(), "noisy discarded process timed out because output was piped");
        require(result.exitCode() == 0, "noisy discarded process did not exit successfully");
    }

    private static void validateTimeoutKillsDescendants() throws Exception {
        NarrationProcessRunner.CaptureResult result = NarrationProcessRunner.runAndRead(
                new ProcessBuilder(helperCommand("--parent-hang")), 1);
        require(result.timedOut(), "hanging narration helper did not time out");
        require(!result.lines().isEmpty(), "hanging helper did not report its descendant process ID");
        long childPid = Long.parseLong(result.lines().get(0));
        waitFor(() -> ProcessHandle.of(childPid).map(handle -> !handle.isAlive()).orElse(true),
                3_000, "timed-out narration descendant remained alive");
    }

    private static void validateRunnerRemainsUsableAfterTimeout() throws Exception {
        NarrationProcessRunner.CaptureResult result = NarrationProcessRunner.runAndRead(
                new ProcessBuilder(helperCommand("--emit", "8192")), 5);
        require(!result.timedOut() && result.exitCode() == 0,
                "narration process runner was unusable after a timeout");
        require(!result.lines().isEmpty(), "post-timeout narration output was not captured");
    }

    private static void validateIoThreadCountIsBounded() throws Exception {
        for (int i = 0; i < 20; i++) {
            NarrationProcessRunner.CaptureResult result = NarrationProcessRunner.runAndRead(
                    new ProcessBuilder(helperCommand("--emit", "1024")), 5);
            require(!result.timedOut() && result.exitCode() == 0,
                    "narration process burst did not complete");
        }
        long ioThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive()
                        && thread.getName().startsWith("StarChem Narration Process I/O-"))
                .count();
        require(ioThreads <= 2, "narration output handling created unbounded I/O threads");
    }

    private static void runHelper(String[] args) throws Exception {
        switch (args[0]) {
            case "--emit" -> emit(Integer.parseInt(args[1]));
            case "--parent-hang" -> parentHang();
            case "--child-hang" -> Thread.sleep(TimeUnit.MINUTES.toMillis(2));
            default -> throw new IllegalArgumentException("Unknown helper mode: " + args[0]);
        }
    }

    private static void emit(int bytes) throws Exception {
        byte[] block = "0123456789abcdef".repeat(512).getBytes(StandardCharsets.UTF_8);
        int remaining = bytes;
        while (remaining > 0) {
            int count = Math.min(remaining, block.length);
            System.out.write(block, 0, count);
            remaining -= count;
        }
        System.out.println();
        System.out.flush();
    }

    private static void parentHang() throws Exception {
        Process child = new ProcessBuilder(helperCommand("--child-hang"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        System.out.println(child.pid());
        System.out.flush();
        Thread.sleep(TimeUnit.MINUTES.toMillis(2));
    }

    private static List<String> helperCommand(String... arguments) {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executableName).toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(NarrationProcessValidator.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMillis, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        require(condition.getAsBoolean(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
