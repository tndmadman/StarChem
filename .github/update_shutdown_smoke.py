from pathlib import Path

for path_name in ('.github/workflows/ci.yml', '.github/workflows/release.yml'):
    path = Path(path_name)
    text = path.read_text()
    old = "grep -Fq 'Dedicated server stopped.'"
    new = "grep -Fq 'Dedicated server stopped cleanly.'"
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{path_name} contained {count} old shutdown assertions, expected 1')
    path.write_text(text.replace(old, new, 1))

print('Updated shutdown smoke expectations.')
