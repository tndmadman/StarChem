from pathlib import Path

path = Path('src/main/java/com/tndmadman/rts/HeadlessGameServer.java')
text = path.read_text()
old = 'return new ServerShutdownResult(true, true, "Dedicated server stopped cleanly.");'
new = 'return new ServerShutdownResult(true, true, "Dedicated server stopped. Clean shutdown confirmed.");'
count = text.count(old)
if count != 1:
    raise RuntimeError(f'HeadlessGameServer contained {count} clean shutdown messages, expected 1')
path.write_text(text.replace(old, new, 1))

print('Updated clean shutdown output for existing smoke checks.')
