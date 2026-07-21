from pathlib import Path

path = Path(__file__).resolve().parents[1] / "src/main/java/com/tndmadman/rts/TcpConnectionIdentityValidator.java"
text = path.read_text(encoding="utf-8")
old = '''            server.join(firstId, loopback, first.getLocalPort(), "Identity Client",\n                    PasswordAuth.verifier("Identity Client", "validator-password"), false, "");\n            String firstWelcome = receive(first, "WELCOME|");'''
new = '''            server.join(firstId, loopback, first.getLocalPort(), "Identity Client", false, "");\n            String registration = receive(first, "AUTH_REQUIRED|");\n            String[] registrationParts = registration.split("\\\\|", -1);\n            TcpIntegrationHarness.require(registrationParts.length == 3\n                            && PasswordAuth.decodeHex(registrationParts[2]).length == 16,\n                    "server did not issue a scoped registration salt");\n            String verifier = PasswordAuth.scopedVerifier("Identity Client", "validator-password",\n                    "44".repeat(32), PasswordAuth.decodeHex(registrationParts[2]));\n            server.join(firstId, loopback, first.getLocalPort(), "Identity Client", verifier, false, "");\n            String firstWelcome = receive(first, "WELCOME|");'''
if text.count(old) != 1:
    raise RuntimeError(f"Expected one identity validator registration block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Adapted connection identity validator to scoped registration.")
