package io.github.yangjjiso.redyagents.cube;

import io.github.yangjjiso.redyagents.core.SandboxProvisioner;
import io.github.yangjjiso.redyagents.core.SandboxToolBackend;
import java.time.Duration;
import java.util.Objects;

/** Adapts CubeSandbox control and envd APIs to the harness's lifecycle and tool ports. */
public final class CubeSandboxBackend implements SandboxProvisioner, SandboxToolBackend {
    private final CubeSandboxClient client;

    public CubeSandboxBackend(CubeSandboxClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String create(String templateId) {
        return client.create(templateId).id();
    }

    @Override
    public void pause(String sandboxId) {
        try {
            client.pause(sandboxId);
        } catch (CubeSandboxException conflict) {
            if (conflict.statusCode() != 409 || !"paused".equals(client.getState(sandboxId))) {
                throw conflict;
            }
        }
    }

    @Override
    public void resume(String sandboxId) {
        client.connect(sandboxId);
    }

    @Override
    public void kill(String sandboxId) {
        client.kill(sandboxId);
    }

    @Override
    public CommandOutput run(String sandboxId, String command, Duration timeout) {
        CommandResult result = client.run(sandboxId, command, timeout);
        return new CommandOutput(result.exitCode(), result.stdout(), result.stderr());
    }

    @Override
    public byte[] readFile(String sandboxId, String path) {
        return client.readFile(sandboxId, path);
    }

    @Override
    public void writeFile(String sandboxId, String path, byte[] content) {
        client.writeFile(sandboxId, path, content);
    }
}
