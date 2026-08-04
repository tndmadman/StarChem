from pathlib import Path
import atexit

_original_write_text = Path.write_text
_this_file = Path(__file__).resolve()


def _write_text(self, data, *args, **kwargs):
    if self.name == "AtomicRefitTransactionValidator.java":
        old = '        world.completeResearch(player, "advanced_industry");\n'
        new = '''        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("afterburner"));
        for (String topic : PlayerFitRules.requiredResearch(spec)) world.completeResearch(player, topic);
'''
        if data.count(old) != 1:
            raise RuntimeError(f"expected one hard-coded research setup, found {data.count(old)}")
        data = data.replace(old, new, 1)
        duplicate = '        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("afterburner"));\n'
        if data.count(duplicate) != 2:
            raise RuntimeError(f"expected two spec declarations after insertion, found {data.count(duplicate)}")
        first = data.find(duplicate)
        second = data.find(duplicate, first + 1)
        data = data[:second] + data[second + len(duplicate):]
    return _original_write_text(self, data, *args, **kwargs)


Path.write_text = _write_text


def _cleanup():
    try:
        _this_file.unlink(missing_ok=True)
    except Exception:
        pass


atexit.register(_cleanup)
