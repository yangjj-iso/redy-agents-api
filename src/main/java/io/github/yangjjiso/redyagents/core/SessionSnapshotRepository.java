package io.github.yangjjiso.redyagents.core;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Encodes and stores the durable snapshot of each session. */
final class SessionSnapshotRepository {
    private final SessionStore store;
    private final ObjectMapper json = new ObjectMapper();

    SessionSnapshotRepository(SessionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    Map<String, AgentService.Snapshot> loadAll() throws IOException {
        Map<String, AgentService.Snapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : store.loadAll().entrySet()) {
            AgentService.Snapshot snapshot;
            try {
                snapshot = json.readValue(entry.getValue(), AgentService.Snapshot.class);
            } catch (Exception failure) {
                throw new IOException("invalid session snapshot: " + entry.getKey(), failure);
            }
            if (snapshot == null || snapshot.session() == null
                    || !entry.getKey().equals(snapshot.session().id())) {
                throw new IOException("invalid session snapshot: " + entry.getKey());
            }
            snapshots.put(entry.getKey(), snapshot);
        }
        return snapshots;
    }

    void save(AgentService.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean restoreInterrupt = Thread.interrupted();
        try {
            byte[] bytes = json.writeValueAsBytes(snapshot);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    store.save(snapshot.session().id(), bytes);
                    return;
                } catch (ClosedByInterruptException interrupted) {
                    restoreInterrupt = true;
                    Thread.interrupted();
                    if (attempt == 1) {
                        throw interrupted;
                    }
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("cannot persist session " + snapshot.session().id(), failure);
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
