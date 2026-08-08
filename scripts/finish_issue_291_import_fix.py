from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "src/main/java/com/tndmadman/rts/TcpMultiplayerValidator.java"
text = path.read_text(encoding="utf-8")
old = "import java.nio.file.Path;\nimport java.util.Set;\n"
new = "import java.nio.file.Path;\nimport java.util.List;\nimport java.util.Set;\n"
if text.count(old) != 1:
    raise RuntimeError(f"expected one TCP validator import block, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Issue 291 TCP validator import fixed.")
