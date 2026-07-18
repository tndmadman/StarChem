from pathlib import Path

TEMPORARY_PATHS = [
    '.github/workflows/phase4-repair.yml',
    '.github/workflows/phase4-diagnostic.yml',
    '.github/workflows/phase4-suite-diagnostic.yml',
    '.github/workflows/phase4-cleanup.yml',
    'tools/phase4_repair.py',
    'tools/phase4_repair_v2.py',
    'tools/phase4_repair_v3.py',
    'tools/phase4_benchmark.sh',
    'tools/phase4_cleanup.py',
    'tools/phase4_cleanup_commit.sh',
]

for raw in TEMPORARY_PATHS:
    path = Path(raw)
    if path.exists():
        path.unlink()
        print(f'removed {raw}')
