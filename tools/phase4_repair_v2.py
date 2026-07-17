import runpy
from pathlib import Path

runpy.run_path('tools/phase4_repair.py', run_name='__main__')

path = Path('src/main/java/com/tndmadman/rts/AiBrainLogValidator.java')
text = path.read_text(encoding='utf-8')
old = '''            require(await(AiBrainLog::recording, 3_000),
                    "async writer did not open for backpressure validation");

            AiBrainLog.pauseWriterForTests(true);
'''
new = '''            require(await(AiBrainLog::recording, 3_000),
                    "async writer did not open for backpressure validation");
            require(AiBrainLog.awaitIdleForTests(3_000),
                    "async writer did not drain startup rows before backpressure validation");

            AiBrainLog.pauseWriterForTests(true);
'''
if text.count(old) != 1:
    raise SystemExit(f'backpressure setup: expected one match, found {text.count(old)}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Phase 4 validator stabilization applied.')
