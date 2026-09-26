package io.github.yangjjiso.redyagents.cube;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.ObjectMapper;

/** Public CubeSandbox facade. Lifecycle uses CubeAPI; commands and files use envd. */
public final class CubeSandboxClient {
    private final CubeControlClient control;
    private final CubeEnvdClient envd;
    private final Map<String, String> trafficTokens = new ConcurrentHashMap<>();

    public CubeSandboxClient(CubeSandboxConfig config) {
        Objects.requireNonNull(config, "config");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        ObjectMapper json = new ObjectMapper();
        control = new CubeControlClient(config, http, json);
        envd = new CubeEnvdClient(config, http, json);
    }

    public Sandbox create() {
        return create(null);
    }

    public Sandbox create(String templateId) {
        Sandbox sandbox = control.create(templateId);
        rememberTrafficToken(sandbox);
        return sandbox;
    }

    /** Connect resumes a paused sandbox and refreshes envd credentials. */
    public Sandbox connect(String sandboxId) {
        Sandbox sandbox = control.connect(sandboxId);
        String token = sandbox.trafficAccessToken().isBlank()
                ? trafficTokens.getOrDefault(sandbox.id(), "") : sandbox.trafficAccessToken();
        Sandbox connected = new Sandbox(sandbox.id(), sandbox.templateId(), sandbox.domain(),
                sandbox.envdAccessToken(), token);
        rememberTrafficToken(connected);
        return connected;
    }

    public void pause(String sandboxId) {
        control.pause(sandboxId);
    }

    public String getState(String sandboxId) {
        return control.getState(sandboxId);
    }

    public void kill(String sandboxId) {
        control.kill(sandboxId);
        trafficTokens.remove(sandboxId);
    }

    /** Nonzero process exits are command results, not transport failures. */
    public CommandResult run(String sandboxId, String command, Duration timeout) {
        return envd.run(connect(sandboxId), command, timeout);
    }

    public byte[] readFile(String sandboxId, String path) {
        return envd.readFile(connect(sandboxId), path);
    }

    public void writeFile(String sandboxId, String path, byte[] content) {
        envd.writeFile(connect(sandboxId), path, content);
    }

    private void rememberTrafficToken(Sandbox sandbox) {
        if (!sandbox.trafficAccessToken().isBlank()) {
            trafficTokens.put(sandbox.id(), sandbox.trafficAccessToken());
        }
    }
}
