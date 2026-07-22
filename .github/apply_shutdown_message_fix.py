from pathlib import Path


def replace_once(path_name: str, old: str, new: str) -> None:
    path = Path(path_name)
    text = path.read_text()
    if text.count(old) != 1:
        raise RuntimeError(f'{path_name} did not contain exactly one expected block: {old!r}')
    path.write_text(text.replace(old, new, 1))


replace_once(
    'src/main/java/com/tndmadman/rts/HeadlessGameServer.java',
    '        return new ServerShutdownResult(true, true, "Dedicated server stopped cleanly.");\n',
    '        return new ServerShutdownResult(true, true, "Dedicated server stopped. Clean shutdown confirmed.");\n',
)

replace_once(
    'src/main/java/com/tndmadman/rts/ServerShutdownValidator.java',
    '''    static void validate() throws Exception {
        validateGracefulFailureAndRetry();
        validateForcedFailureStatus();
        validateConcurrentForcedStop();
    }
''',
    '''    static void validate() throws Exception {
        require(ServerShutdownResult.cleanStop().message().contains("Dedicated server stopped."),
                "clean shutdown output no longer satisfies headless smoke checks");
        validateGracefulFailureAndRetry();
        validateForcedFailureStatus();
        validateConcurrentForcedStop();
    }
''',
)

print('Applied smoke-compatible clean shutdown message and regression assertion.')
