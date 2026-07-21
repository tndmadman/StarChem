from pathlib import Path

path = Path(__file__).resolve().parents[1] / "src/main/java/com/tndmadman/rts/NumericCommandValidationValidator.java"
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one numeric validator match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)

replace_once(
    "package com.tndmadman.rts;\n\nimport java.net.InetAddress;",
    "package com.tndmadman.rts;\n\nimport java.io.DataInputStream;\nimport java.net.InetAddress;",
)

replace_once(
    '''            String verifier = PasswordAuth.verifier("Numeric Client", "numeric-validation-password");\n            server.join(connectionId, loopback, socket.getLocalPort(), "Numeric Client", verifier, false, "");\n            world.activateSystem(world.playerHomeSystemId("P1"));''',
    '''            server.join(connectionId, loopback, socket.getLocalPort(), "Numeric Client", false, "");\n            String registration = receive(socket, "AUTH_REQUIRED|");\n            String[] registrationParts = registration.split("\\\\|", -1);\n            require(registrationParts.length == 3\n                            && PasswordAuth.decodeHex(registrationParts[2]).length == 16,\n                    "server did not issue a scoped numeric-validator registration salt");\n            String verifier = PasswordAuth.scopedVerifier("Numeric Client", "numeric-validation-password",\n                    "55".repeat(32), PasswordAuth.decodeHex(registrationParts[2]));\n            server.join(connectionId, loopback, socket.getLocalPort(), "Numeric Client", verifier, false, "");\n            receive(socket, "WELCOME|");\n            world.activateSystem(world.playerHomeSystemId("P1"));''',
)

replace_once(
    '''    private static void waitConnection(PeerTransport transport, InetAddress address, int port) throws Exception {''',
    '''    private static String receive(Socket socket, String prefix) throws Exception {\n        DataInputStream input = new DataInputStream(socket.getInputStream());\n        for (int attempt = 0; attempt < 200; attempt++) {\n            TcpFrameCodec.DecodedFrame frame = TcpFrameCodec.read(input);\n            if (frame == null) break;\n            if (frame.message().startsWith(prefix)) return frame.message();\n        }\n        throw new IllegalStateException("Did not receive TCP frame starting with " + prefix);\n    }\n\n    private static void waitConnection(PeerTransport transport, InetAddress address, int port) throws Exception {''',
)

path.write_text(text, encoding="utf-8")
print("Adapted numeric command validation to scoped registration.")
