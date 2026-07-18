package com.tndmadman.rts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Validates bounded console input and authoritative-thread command dispatch. */
public final class ServerConsoleValidator {
    private ServerConsoleValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem server console validation passed.");
    }

    static void validate() throws Exception {
        validateBoundedQueue();
        validateReaderEof();
        validateDispatch();
    }

    private static void validateBoundedQueue() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        ServerConsole console = ServerConsole.detached(2, stream(errors));
        require(console.submit("status"), "first command was rejected");
        require(console.submit("players"), "second command was rejected");
        require(!console.submit("save"), "full command queue accepted another command");
        require(console.pendingCount() == 2, "bounded command queue size changed unexpectedly");
        require("status".equals(console.poll()), "commands did not preserve FIFO order");
        require("players".equals(console.poll()), "commands did not preserve FIFO order");
        require(!console.submit("x".repeat(ServerConsole.MAX_LINE_LENGTH + 1)), "oversized command was accepted");
        console.close();
        require(!console.submit("status"), "closed console accepted a command");
        String errorText = errors.toString(StandardCharsets.UTF_8);
        require(errorText.contains("queue is full"), "queue overflow was not reported");
        require(errorText.contains("exceeded"), "oversized input was not reported");
    }

    private static void validateReaderEof() throws Exception {
        byte[] input = "status\nplayers\n".getBytes(StandardCharsets.UTF_8);
        ServerConsole console = ServerConsole.start(new ByteArrayInputStream(input), stream(new ByteArrayOutputStream()));
        await(() -> console.pendingCount() == 2 && !console.acceptingInput(), 1_000,
                "console reader did not preserve queued commands after EOF");
        require("status".equals(console.poll()), "reader changed the first command");
        require("players".equals(console.poll()), "reader changed the second command");
        console.close();
    }

    private static void validateDispatch() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        FakeTarget target = new FakeTarget();
        ServerCommandDispatcher dispatcher = new ServerCommandDispatcher(target, stream(output), stream(errors));
        ServerConsole queue = ServerConsole.detached(4, stream(errors));

        require(queue.submit("status"), "status command was not queued");
        require(target.statusCalls == 0, "queued command ran outside authoritative dispatch");
        dispatcher.execute(queue.poll());
        require(target.statusCalls == 1, "status command was not dispatched");

        dispatcher.execute("players");
        dispatcher.execute("save");
        dispatcher.execute("help \"status\"");
        dispatcher.execute("unknown");
        dispatcher.execute("help \"unterminated");
        dispatcher.execute("shutdown");

        require(target.playerCalls == 1, "players command was not dispatched");
        require(target.saveCalls == 1, "save command was not dispatched");
        require(target.stopCalls == 1 && !target.running, "shutdown alias did not stop the target");
        require(ServerCommandDispatcher.tokenize("help 'status'").equals(List.of("help", "status")),
                "quoted command tokenization failed");
        String outputText = output.toString(StandardCharsets.UTF_8);
        String errorText = errors.toString(StandardCharsets.UTF_8);
        require(outputText.contains("HOST Test"), "status output was not printed");
        require(outputText.contains("P1 | Alpha | connected"), "player output was not printed");
        require(outputText.contains("status -"), "command-specific help was not printed");
        require(errorText.contains("Unknown console command"), "unknown command was not rejected");
        require(errorText.contains("unterminated quote"), "unterminated quote was not rejected");
        queue.close();
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition, long timeoutMs, String message) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(condition.getAsBoolean(), message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class FakeTarget implements ServerCommandDispatcher.Target {
        int statusCalls;
        int playerCalls;
        int saveCalls;
        int stopCalls;
        boolean running = true;

        @Override public String status() {
            statusCalls++;
            return "HOST Test";
        }

        @Override public List<String> players() {
            playerCalls++;
            return List.of("P1 | Alpha | connected", "P2 | Beta | retained");
        }

        @Override public boolean save() {
            saveCalls++;
            return true;
        }

        @Override public void stop() {
            stopCalls++;
            running = false;
        }

        @Override public boolean running() {
            return running;
        }
    }
}
