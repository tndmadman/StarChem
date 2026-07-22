from pathlib import Path


def replace_once(path_name: str, old: str, new: str) -> None:
    path = Path(path_name)
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f'{path_name} did not contain exactly one expected block: {old!r}')
    path.write_text(text.replace(old, new, 1))


replace_once(
    'src/main/java/com/tndmadman/rts/HeadlessGameServer.java',
    '    private ServerShutdownResult stop(boolean forced) {\n',
    '    private synchronized ServerShutdownResult stop(boolean forced) {\n',
)

validator = 'src/main/java/com/tndmadman/rts/ServerShutdownValidator.java'
replace_once(
    validator,
    'import java.nio.file.Path;\n',
    'import java.nio.file.Path;\n'
    'import java.util.concurrent.CountDownLatch;\n'
    'import java.util.concurrent.atomic.AtomicReference;\n',
)
replace_once(
    validator,
    '''    static void validate() throws Exception {
        validateGracefulFailureAndRetry();
        validateForcedFailureStatus();
    }
''',
    '''    static void validate() throws Exception {
        validateGracefulFailureAndRetry();
        validateForcedFailureStatus();
        validateConcurrentForcedStop();
    }
''',
)
replace_once(
    validator,
    '''    private static Path blockSaveDirectory(Path saveDir) throws Exception {
''',
    '''    private static void validateConcurrentForcedStop() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.dedicated()) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<ServerShutdownResult> first = new AtomicReference<>();
            AtomicReference<ServerShutdownResult> second = new AtomicReference<>();
            Thread one = new Thread(() -> forceAfter(start, harness, first), "shutdown-validator-one");
            Thread two = new Thread(() -> forceAfter(start, harness, second), "shutdown-validator-two");
            one.start();
            two.start();
            start.countDown();
            one.join(15_000);
            two.join(15_000);

            require(!one.isAlive() && !two.isAlive(),
                    "concurrent forced shutdown callers did not complete");
            require(first.get() != null && first.get().stopped() && first.get().clean(),
                    "first concurrent shutdown caller did not receive the clean final result");
            require(second.get() != null && second.get().stopped() && second.get().clean(),
                    "second concurrent shutdown caller did not receive the clean final result");
            require(harness.headlessServer.shutdownExitCode() == 0,
                    "concurrent clean shutdown produced an unclean process status");
        }
    }

    private static void forceAfter(CountDownLatch start, TcpIntegrationHarness harness,
                                   AtomicReference<ServerShutdownResult> result) {
        try {
            start.await();
            result.set(harness.headlessServer.forceStop());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path blockSaveDirectory(Path saveDir) throws Exception {
''',
)

print('Applied serialized shutdown completion and concurrency validation.')
