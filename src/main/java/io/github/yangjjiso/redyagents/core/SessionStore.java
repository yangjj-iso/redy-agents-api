package io.github.yangjjiso.redyagents.core;

import java.io.IOException;
import java.util.Map;

/** Stores opaque session snapshots for recovery after a process restart. */
public interface SessionStore {
    void save(String sessionId, byte[] snapshot) throws IOException;

    Map<String, byte[]> loadAll() throws IOException;
}
