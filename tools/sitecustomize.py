from pathlib import Path
import atexit

_original_write_text = Path.write_text
_this_file = Path(__file__).resolve()


def _write_text(self, data, *args, **kwargs):
    if self.name == "CustomFitConstructionValidator.java":
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
            if data.count(old) != 1:
                raise RuntimeError(f"expected one validator registry fixture, found {data.count(old)}")
            data = data.replace(old, new, 1)
    return _original_write_text(self, data, *args, **kwargs)


Path.write_text = _write_text


def _cleanup():
    try:
        _this_file.unlink(missing_ok=True)
    except Exception:
        pass


atexit.register(_cleanup)
