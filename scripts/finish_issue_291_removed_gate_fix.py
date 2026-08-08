from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "src/main/java/com/tndmadman/rts/Issue291CommandQueueValidator.java"
text = path.read_text(encoding="utf-8")
old = '''        world.wormholes.removeIf(candidate -> gate.id.equals(candidate.id));
        world.saveActiveSystem();
        runUntil(world, () -> UnitCommandQueueSystem.commands(world, key).isEmpty(), 1200,
                "removed wormhole did not halt the queued cross-system chain");
'''
new = '''        world.wormholes.removeIf(candidate -> gate.id.equals(candidate.id));
        world.saveActiveSystem();
        for (int i = 0; i < 1200 && !UnitCommandQueueSystem.commands(world, key).isEmpty(); i++) {
            world.updateCurrentSystem(0.05);
        }
        require(UnitCommandQueueSystem.commands(world, key).isEmpty(),
                "removed wormhole did not halt the queued cross-system chain");
'''
if text.count(old) != 1:
    raise RuntimeError(f"expected one removed-wormhole fixture block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Issue 291 removed-wormhole fixture corrected.")
