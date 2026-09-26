package io.github.yangjjiso.redyagents.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** One snapshot file per session. A complete temporary file atomically replaces the prior snapshot. */
public final class FileSessionStore implements SessionStore {
    private static final String EXTENSION = ".snapshot";
    private static final Pattern SESSION_ID = Pattern.compile("sess_[A-Za-z0-9_-]{1,120}");

    private final Path directory;

    public FileSessionStore(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("session store path is not a directory");
        }
    }

    @Override
    public synchronized void save(String sessionId, byte[] snapshot) throws IOException {
        validateSessionId(sessionId);
        byte[] content = Objects.requireNonNull(snapshot, "snapshot").clone();
        Path destination = directory.resolve(sessionId + EXTENSION);
        Path temporary = Files.createTempFile(directory, sessionId + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized Map<String, byte[]> loadAll() throws IOException {
        Map<String, byte[]> snapshots = new LinkedHashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String sessionId = name.substring(0, name.length() - EXTENSION.length());
                validateSessionId(sessionId);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("session snapshot is not a regular file: " + name);
                }
                snapshots.put(sessionId, Files.readAllBytes(file));
            }
        }
        return snapshots;
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("invalid session ID");
        }
    }
}
