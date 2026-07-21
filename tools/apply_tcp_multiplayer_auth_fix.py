from pathlib import Path

path = Path(__file__).resolve().parents[1] / "src/main/java/com/tndmadman/rts/TcpMultiplayerValidator.java"
text = path.read_text(encoding="utf-8")
old = '            SessionTokenStore.saveAuthDigest(clientConfig, PasswordAuth.verifier(clientConfig.playerName, "validator-password"));'
new = '            PendingPlayerPassword.remember(clientConfig, "validator-password".toCharArray(), false);'
if text.count(old) != 1:
    raise RuntimeError(f"Expected one TCP multiplayer credential setup, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Adapted TCP multiplayer validation to derive credentials after TLS verification.")
