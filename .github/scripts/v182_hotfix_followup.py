from pathlib import Path

p = Path('src/main/java/com/tndmadman/rts/GalaxyMapWire.java')
text = p.read_text(encoding='utf-8')
old = '''        if (strategic != null && (!ownerMarker || !ownerId.equals(strategic.ownerId()))) {
            throw new SnapshotDecodeException("Strategic empire summary does not match the owner projection.");
        }
        World activeWorld = PlayerRegistry.activeWorld();'''
new = '''        if (strategic != null) {
            if (!ownerMarker || !ownerId.equals(strategic.ownerId())) {
                throw new SnapshotDecodeException("Strategic empire summary does not match the owner projection.");
            }
            World strategicWorld = PlayerRegistry.activeWorld();
            String localOwner = PlayerRegistry.localId();
            if (strategicWorld != null && localOwner != null && localOwner.equals(ownerId)) {
                StrategicSummaryRegistry.replace(strategicWorld, strategic);
            }
        }
        World activeWorld = PlayerRegistry.activeWorld();'''
if old not in text:
    raise SystemExit('Expected refined GalaxyMapWire patch context not found')
p.write_text(text.replace(old, new, 1), encoding='utf-8')
