from pathlib import Path

path = Path('src/main/java/com/tndmadman/rts/GamePanel.java')
text = path.read_text(encoding='utf-8')
old = '''            if (state.initialized() && state.ownerId().equals(localPlayerId)) {
                base = state.locations();
                controlGroupLocationsReady = true;
            } else if (!state.ownerId().isBlank() && !state.ownerId().equals(localPlayerId)) {
                base = Map.of();
                controlGroupLocationsReady = false;
            }
'''
new = '''            if (state.initialized() && state.ownerId().equals(localPlayerId)) {
                base = state.locations();
                controlGroupLocationsReady = true;
            } else {
                base = Map.of();
                controlGroupLocationsReady = false;
            }
'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit('control-group reconnect reconciliation block is in an unexpected state')
path.write_text(text, encoding='utf-8')
