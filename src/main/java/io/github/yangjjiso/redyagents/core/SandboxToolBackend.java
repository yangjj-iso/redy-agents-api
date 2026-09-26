package io.github.yangjjiso.redyagents.core;

import java.time.Duration;

/** Operations exposed to tools inside a session's remote sandbox. */
public interface SandboxToolBackend {
    record CommandOutput(int exitCode, String stdout, String stderr) {}

    CommandOutput run(String sandboxId, String command, Duration timeout) throws Exception;

    byte[] readFile(String sandboxId, String path) throws Exception;

    void writeFile(String sandboxId, String path, byte[] content) throws Exception;
}
