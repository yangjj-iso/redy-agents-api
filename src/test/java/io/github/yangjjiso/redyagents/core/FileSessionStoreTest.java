package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSessionStoreTest {
    @TempDir
    Path directory;

    @Test
    void snapshotsSurviveStoreRecreationAndReplacement() throws Exception {
        Path snapshots = directory.resolve("sessions");
        FileSessionStore first = new FileSessionStore(snapshots);
        first.save("sess_one", bytes("old"));
        first.save("sess_two", bytes("second"));
        first.save("sess_one", bytes("new"));

        FileSessionStore restarted = new FileSessionStore(snapshots);
        Map<String, byte[]> loaded = restarted.loadAll();
        assertEquals(2, loaded.size());
        assertArrayEquals(bytes("new"), loaded.get("sess_one"));
        assertArrayEquals(bytes("second"), loaded.get("sess_two"));
        try (Stream<Path> files = Files.list(snapshots)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void rejectsIdsThatCouldEscapeOrForgeSnapshotPaths() throws Exception {
        FileSessionStore store = new FileSessionStore(directory.resolve("sessions"));
        for (String id : new String[] {"../escape", "sess_../escape", "sess_/escape",
                "sess_..", "sess_bad.snapshot", "", "other"}) {
            assertThrows(IllegalArgumentException.class, () -> store.save(id, bytes("data")), id);
        }
        assertThrows(IllegalArgumentException.class, () -> store.save(null, bytes("data")));
        assertEquals(Map.of(), store.loadAll());
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().contains("escape")).count());
        }
    }

    @Test
    void ignoresAbandonedTemporaryFilesDuringRecovery() throws Exception {
        Path snapshots = directory.resolve("sessions");
        FileSessionStore store = new FileSessionStore(snapshots);
        store.save("sess_good", bytes("complete"));
        Files.write(snapshots.resolve("sess_good-abandoned.tmp"), bytes("partial"));

        assertEquals(1, store.loadAll().size());
        assertArrayEquals(bytes("complete"), store.loadAll().get("sess_good"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
