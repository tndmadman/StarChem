from pathlib import Path
from textwrap import dedent, indent

path = Path("src/main/java/com/tndmadman/rts/ServerModerationStore.java")
text = path.read_text(encoding="utf-8")

clear_method = indent(dedent("""\
synchronized void clear() {
    ServerEvent cleared = new ServerEvent(System.currentTimeMillis(), "ADMIN", "activity",
            "previous activity history cleared");
    if (path == null) {
        events.clear();
        events.addLast(cleared);
        return;
    }
    try {
        replace(List.of(cleared));
    } catch (IOException ex) {
        throw new IllegalStateException("Could not clear server activity journal: " + ex.getMessage(), ex);
    }
    events.clear();
    events.addLast(cleared);
}
"""), "    ")
clear_marker = "synchronized void clear() {"
clear_match = text.index(clear_marker)
clear_start = text.rfind("\n", 0, clear_match) + 1
clear_end = text.index("\n    private void load()", clear_match)
text = text[:clear_start] + clear_method + text[clear_end:]

rewrite_method = indent(dedent("""\
private void rewrite() throws IOException {
    replace(events);
}

private void replace(Iterable<ServerEvent> replacement) throws IOException {
    Path target = path.toAbsolutePath();
    Path parent = target.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path temp = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
    boolean replaced = false;
    try {
        ArrayList<String> rows = new ArrayList<>();
        for (ServerEvent event : replacement) {
            rows.add(event.at() + "\\t" + encode(event.type()) + "\\t" + encode(event.subject()) + "\\t" + encode(event.detail()));
        }
        Files.write(temp, rows, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        replaced = true;
    } finally {
        if (!replaced) Files.deleteIfExists(temp);
    }
}
"""), "    ")
rewrite_marker = "private void rewrite() throws IOException {"
rewrite_match = text.index(rewrite_marker)
rewrite_start = text.rfind("\n", 0, rewrite_match) + 1
rewrite_end = text.index("\n    private static String format", rewrite_match)
path.write_text(text[:rewrite_start] + rewrite_method + text[rewrite_end:], encoding="utf-8")
