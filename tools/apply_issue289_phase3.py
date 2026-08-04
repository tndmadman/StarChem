#!/usr/bin/env python3
# Runs the phase-three implementation and normalizes its generated test fixture.
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
IMPLEMENTATION = Path(__file__).with_name("apply_issue289_phase3_impl.py")
runpy.run_path(str(IMPLEMENTATION), run_name="__main__")

path = ROOT / "src/main/java/com/tndmadman/rts/CustomFitConstructionValidator.java"
text = path.read_text(encoding="utf-8")
replacements = {
    '''        PlayerRegistry.reset(player, "Custom Builder", 0x50BEFF);
        World world = new World("Custom Builder", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
''': '''        World world = new World("Custom Builder", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Custom Builder", 0x50BEFF);
''',
    '''        PlayerRegistry.reset(player, "Station Select", 0x50BEFF);
        World world = new World("Station Select", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
''': '''        World world = new World("Station Select", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Station Select", 0x50BEFF);
'''
}
for old, new in replacements.items():
    if text.count(old) != 1:
        raise SystemExit(f"expected one validator registry fixture, found {text.count(old)}")
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("Corrected custom construction validator world registration.")
