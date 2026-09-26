package io.github.yangjjiso.redyagents.core;

/** Provider boundary for session-owned compute. Implementations must make lifecycle calls idempotent. */
public interface SandboxProvisioner {
    String create(String templateId);

    void pause(String sandboxId);

    void resume(String sandboxId);

    void kill(String sandboxId);
}
