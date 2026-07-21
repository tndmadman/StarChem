from pathlib import Path

path = Path(__file__).resolve().parents[1] / "src/main/java/com/tndmadman/rts/AdmissionRecordingValidator.java"
text = path.read_text(encoding="utf-8")
old = '''            SessionTokenStore.clear(registered.config());\n            SessionTokenStore.saveAuthDigest(registered.config(),\n                    PasswordAuth.verifier(PLAYER_NAME, PASSWORD));\n            Thread.sleep(10);'''
new = '''            SessionTokenStore.clear(registered.config());\n            Thread.sleep(10);'''
if text.count(old) != 1:
    raise RuntimeError(f"Expected one admission legacy preload, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Removed redundant legacy verifier preload from admission validation.")
