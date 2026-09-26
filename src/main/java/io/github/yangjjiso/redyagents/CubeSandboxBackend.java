package io.github.yangjjiso.redyagents;

import io.github.yangjjiso.redyagents.core.SandboxProvisioner;
import io.github.yangjjiso.redyagents.core.SandboxToolBackend;
import io.github.yangjjiso.redyagents.cube.CommandResult;
import io.github.yangjjiso.redyagents.cube.CubeSandboxClient;
import io.github.yangjjiso.redyagents.cube.CubeSandboxException;
import java.time.Duration;
import java.util.Objects;

/** Bridges the CubeSandbox control and envd APIs to session lifecycle and local tools. */
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
