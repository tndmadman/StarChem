import runpy
from pathlib import Path

runpy.run_path('tools/phase4_repair_v2.py', run_name='__main__')

path = Path('src/main/java/com/tndmadman/rts/AiBrainLogAsyncWriter.java')
text = path.read_text(encoding='utf-8')

old_fields = '''    private final Path directory;
    private final ArrayBlockingQueue<Entry> queue;
    private final Consumer<String> errorSink;
'''
new_fields = '''    private final Path directory;
    private final ArrayBlockingQueue<Entry> queue;
    private final int queueCapacity;
    private final int highPriorityReserve;
    private final Consumer<String> errorSink;
'''
if text.count(old_fields) != 1:
    raise SystemExit(f'writer fields: expected one match, found {text.count(old_fields)}')
text = text.replace(old_fields, new_fields, 1)

old_constructor = '''        this.directory = directory;
        this.queue = new ArrayBlockingQueue<>(Math.max(2, capacity));
        this.errorSink = errorSink == null ? ignored -> { } : errorSink;
'''
new_constructor = '''        this.directory = directory;
        this.queueCapacity = Math.max(2, capacity);
        this.highPriorityReserve = Math.max(1, queueCapacity / 4);
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.errorSink = errorSink == null ? ignored -> { } : errorSink;
'''
if text.count(old_constructor) != 1:
    raise SystemExit(f'writer constructor: expected one match, found {text.count(old_constructor)}')
text = text.replace(old_constructor, new_constructor, 1)

start = text.find('    boolean offer(Entry entry) {\n')
end = text.find('    void stopAndDrain(long timeoutMillis) {\n', start)
if start < 0 or end < 0:
    raise SystemExit('offer method markers not found')
new_offer = '''    boolean offer(Entry entry) {
        if (!accepting || entry == null) return false;
        if (entry.lowPriority() && queue.size() >= queueCapacity - highPriorityReserve) {
            droppedRecords.incrementAndGet();
            return false;
        }
        if (queue.offer(entry)) return true;
        droppedRecords.incrementAndGet();
        return false;
    }

'''
text = text[:start] + new_offer + text[end:]

old_freeze = '''        private static Map<String, Object> freezeMap(Map<String, ?> source) {
            if (source == null || source.isEmpty()) return Map.of();
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }

        private static Object freeze(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> copy = new ArrayList<>();
                for (Object item : iterable) copy.add(freeze(item));
                return List.copyOf(copy);
            }
            return value;
        }
'''
new_freeze = '''        private static Map<String, Object> freezeMap(Map<String, ?> source) {
            if (source == null || source.isEmpty()) return Map.of();
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Collections.unmodifiableMap(copy);
        }
'''
if text.count(old_freeze) != 1:
    raise SystemExit(f'freeze implementation: expected one match, found {text.count(old_freeze)}')
text = text.replace(old_freeze, new_freeze, 1)

text = text.replace('"queueCapacity", queue.remainingCapacity() + queue.size()',
                    '"queueCapacity", queueCapacity', 1)
path.write_text(text, encoding='utf-8')

docs = Path('docs/AI_BRAIN_LOG.md')
doc_text = docs.read_text(encoding='utf-8')
doc_text = doc_text.replace(
    'Under sustained pressure, position/checkpoint rows may be coalesced or dropped and one `logger_backpressure` record reports the loss.',
    'Under sustained pressure, position/checkpoint rows are dropped before the reserved critical-event capacity is consumed, and one `logger_backpressure` record reports the loss.')
docs.write_text(doc_text, encoding='utf-8')
print('Phase 4 producer-path optimization applied.')
