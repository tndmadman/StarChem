from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


game = Path('src/main/java/com/tndmadman/rts/GamePanel.java')
text = game.read_text(encoding='utf-8')
old_gate = '''    private boolean controlGroupInputBlocked() {
        if (galaxyMapOpen || ShipFittingWindow.active()) return true;
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focusOwner != null && focusOwner != this;
    }
'''
new_gate = '''    private boolean controlGroupInputBlocked() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return ControlGroupInputGate.blocked(galaxyMapOpen, ShipFittingWindow.active(), focusOwner, this);
    }
'''
if old_gate in text:
    text = replace_once(text, old_gate, new_gate, 'testable control group input gate')
elif new_gate not in text:
    raise SystemExit('control group input gate is in an unexpected state')
game.write_text(text, encoding='utf-8')

wire = Path('src/main/java/com/tndmadman/rts/GalaxyMapWire.java')
text = wire.read_text(encoding='utf-8')
text = text.replace('    private static final int MAX_OWNER_UNITS = 1_024;\n',
                    '    private static final int MAX_OWNER_UNITS = 10_000;\n', 1)
if '    private static final int MAX_OWNER_UNITS = 10_000;\n' not in text:
    raise SystemExit('owner fleet bound is in an unexpected state')
wire.write_text(text, encoding='utf-8')
