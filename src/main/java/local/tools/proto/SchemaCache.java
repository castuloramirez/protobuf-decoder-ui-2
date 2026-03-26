package local.tools.proto;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class SchemaCache {
    record Entry(byte[] desc, Instant createdAt) {}

    private static final long TTL_SECONDS = 20 * 60; // 20 min
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    String put(byte[] descBytes) {
        cleanup();
        String id = UUID.randomUUID().toString();
        cache.put(id, new Entry(descBytes, Instant.now()));
        return id;
    }

    byte[] get(String id) {
        cleanup();
        Entry e = cache.get(id);
        if (e == null) throw new IllegalArgumentException("Unknown/expired schemaId. Click 'Validate Proto' again.");
        return e.desc();
    }

    private void cleanup() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(en -> now.getEpochSecond() - en.getValue().createdAt().getEpochSecond() > TTL_SECONDS);
    }
}
