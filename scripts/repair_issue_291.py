from pathlib import Path

path = Path("src/main/java/com/tndmadman/rts/RefitQuotePersistenceValidator.java")
text = path.read_text(encoding="utf-8")
old = '''        require(MultiplayerCompatibility.PROTOCOL_VERSION == 14, "protocol version was not bumped");
        require(ServerSaveStore.SAVE_FORMAT_VERSION == 5, "save format was not bumped");
'''
new = '''        require(MultiplayerCompatibility.PROTOCOL_VERSION == 15, "protocol version was not bumped");
        require(ServerSaveStore.SAVE_FORMAT_VERSION == 6, "save format was not bumped");
'''
if text.count(old) != 1:
    raise RuntimeError("expected compatibility-version guard was not found exactly once")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Issue 291 compatibility validator repair applied.")
