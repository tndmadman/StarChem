from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "src/main/java/com/tndmadman/rts/ScoutSystem.java"
text = path.read_text(encoding="utf-8")
old_update = '''    private void updateLocalMiner(World world, Unit miner) {
        if (miner.task != UnitTask.IDLE || miner.freeCargo() <= 0.05) return;
'''
new_update = '''    private void updateLocalMiner(World world, Unit miner) {
        if (miner.task != UnitTask.IDLE || miner.orderType != UnitOrderType.NONE
                || UnitCommandQueueSystem.hasPlayerIntent(world, miner) || miner.freeCargo() <= 0.05) return;
'''
if text.count(old_update) != 1:
    raise RuntimeError(f"expected one local miner update guard, found {text.count(old_update)}")
text = text.replace(old_update, new_update, 1)
old_idle = '''        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(playerId) || unit.task != UnitTask.IDLE) continue;
            if (unit.type().harvestKinds.isEmpty() || unit.freeCargo() <= 0.05) continue;
'''
new_idle = '''        for (Unit unit : world.units.values()) {
            if (!unit.playerId.equals(playerId) || unit.task != UnitTask.IDLE
                    || unit.orderType != UnitOrderType.NONE
                    || UnitCommandQueueSystem.hasPlayerIntent(world, unit)) continue;
            if (unit.type().harvestKinds.isEmpty() || unit.freeCargo() <= 0.05) continue;
'''
if text.count(old_idle) != 1:
    raise RuntimeError(f"expected one radar idle-worker guard, found {text.count(old_idle)}")
text = text.replace(old_idle, new_idle, 1)
path.write_text(text, encoding="utf-8")
print("Issue 291 player intent protected from scout/radar miner automation.")
