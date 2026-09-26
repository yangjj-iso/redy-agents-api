package io.github.yangjjiso.redyagents.cube;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal Java CubeSandbox client. Control calls use CubeAPI. Commands and files
 * use envd through CubeProxy's documented path route (or its virtual host route
 * when no proxy address is configured).
 */
public final class CubeSandboxClient {
    private static final int ENVD_PORT = 49983;
    private static final int MAX_CONNECT_FRAME = 2 * 1024 * 1024;
    private static final int MAX_CAPTURE_BYTES = 1024 * 1024;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 4096;
    private static final Pattern SANDBOX_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private final CubeSandboxConfig config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> trafficTokens = new ConcurrentHashMap<>();

    public CubeSandboxClient(CubeSandboxConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Sandbox create() {
        return create(config.templateId());
    }

    public Sandbox create(String templateId) {
        String selected = templateId == null || templateId.isBlank() ? config.templateId() : templateId.trim();
        if (selected.isBlank()) {
            throw new IllegalArgumentException("CubeSandbox template ID is required (CUBE_TEMPLATE_ID)");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateID", selected);
        payload.put("allowInternetAccess", config.allowInternetAccess());
        payload.put("timeout", config.idleTimeoutSeconds());
        payload.put("lifecycle", Map.of("onTimeout", "pause", "autoResume", true));
        if (!config.allowInternetAccess()) {
            payload.put("network", Map.of("denyOut", new String[]{"0.0.0.0/0"}));
        }
        HttpResponse<byte[]> response = control("POST", "/sandboxes", payload);
        expectStatus(response, 200, 201);
        Sandbox sandbox = decodeSandbox(response.body());
        if (!sandbox.trafficAccessToken().isBlank()) {
            trafficTokens.put(sandbox.id(), sandbox.trafficAccessToken());
        }
        return sandbox;
    }

    /** Connect resumes a paused sandbox and refreshes envd credentials. */
    public Sandbox connect(String sandboxId) {
        HttpResponse<byte[]> response = control("POST", "/sandboxes/" + validId(sandboxId) + "/connect", Map.of());
        expectStatus(response, 200);
        Sandbox sandbox = decodeSandbox(response.body());
        String token = sandbox.trafficAccessToken().isBlank()
                ? trafficTokens.getOrDefault(sandbox.id(), "") : sandbox.trafficAccessToken();
        return new Sandbox(sandbox.id(), sandbox.templateId(), sandbox.domain(),
                sandbox.envdAccessToken(), token);
    }

    public void pause(String sandboxId) {
        HttpResponse<byte[]> response = control("POST", "/sandboxes/" + validId(sandboxId) + "/pause", null);
        expectStatus(response, 200, 204);
    }

    /** Returns CubeAPI's current state, such as "running" or "paused". */
    public String getState(String sandboxId) {
        HttpResponse<byte[]> response = control("GET", "/sandboxes/" + validId(sandboxId), null);
        expectStatus(response, 200);
        try {
            String state = json.readTree(response.body()).path("state").asText("");
            if (state.isBlank()) {
                throw new CubeSandboxException("CubeSandbox response omitted state", -1);
            }
            return state;
        } catch (JacksonException error) {
            throw new CubeSandboxException("Invalid CubeSandbox state response", -1, error);
        }
    }

    public void kill(String sandboxId) {
        HttpResponse<byte[]> response = control("DELETE", "/sandboxes/" + validId(sandboxId), null);
        // A retry after a lost acknowledgement may see an already-deleted sandbox.
        expectStatus(response, 200, 204, 404);
        trafficTokens.remove(sandboxId);
    }

    /** A nonzero process exit is returned in CommandResult, not treated as a transport failure. */
    public CommandResult run(String sandboxId, String command, Duration timeout) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        Sandbox sandbox = connect(sandboxId);
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? config.requestTimeout() : timeout;
        byte[] payload = encodeJson(Map.of(
                "process", Map.of("cmd", "/bin/bash", "args", new String[]{"-l", "-c", command},
                        "envs", Map.of()),
                "stdin", false));
        ByteBuffer frame = ByteBuffer.allocate(5 + payload.length);
        frame.put((byte) 0).putInt(payload.length).put(payload);
        HttpRequest.Builder request = dataRequest(sandbox, "POST", "/process.Process/Start", "")
                .timeout(effectiveTimeout.plus(config.requestTimeout()))
                .header("Content-Type", "application/connect+json")
                .header("Connect-Protocol-Version", "1")
                .header("Connect-Content-Encoding", "identity")
                .header("Connect-Timeout-Ms", Long.toString(effectiveTimeout.toMillis()))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("root:".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(frame.array()));
        HttpResponse<InputStream> response = sendStream(request.build());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiError(response.statusCode(), readBounded(body, MAX_ERROR_BYTES));
            }
            return parseProcessStream(body);
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox command stream failed", -1, error);
        }
    }

    public byte[] readFile(String sandboxId, String path) {
        Sandbox sandbox = connect(sandboxId);
        HttpRequest request = dataRequest(sandbox, "GET", "/files", fileQuery(path))
                .timeout(config.requestTimeout()).GET().build();
        HttpResponse<InputStream> response = sendStream(request);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw apiError(response.statusCode(), readBounded(body, MAX_ERROR_BYTES));
            }
            return readBounded(body, MAX_FILE_BYTES);
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox file read failed", -1, error);
        }
    }

    public void writeFile(String sandboxId, String path, byte[] content) {
        Sandbox sandbox = connect(sandboxId);
        if (content == null) {
            throw new IllegalArgumentException("file content is required");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file content exceeds 16 MiB");
        }
        String query = fileQuery(path);
        HttpRequest rawRequest = dataRequest(sandbox, "POST", "/files", query)
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content)).build();
        HttpResponse<byte[]> rawResponse = sendBytes(rawRequest);
        if (rawResponse.statusCode() >= 200 && rawResponse.statusCode() < 300) {
            return;
        }
        // Older envd builds only accept multipart file upload. Retry only when
        // the first response indicates an unsupported request format.
        if (rawResponse.statusCode() != 400 && rawResponse.statusCode() != 415
                && rawResponse.statusCode() != 422) {
            throw apiError(rawResponse.statusCode(), rawResponse.body());
        }
        String boundary = "----redy-cube-" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] multipart = multipartBody(path, content, boundary);
        HttpRequest multipartRequest = dataRequest(sandbox, "POST", "/files", query)
                .timeout(config.requestTimeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart)).build();
        HttpResponse<byte[]> response = sendBytes(multipartRequest);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), response.body());
        }
    }

    private HttpResponse<byte[]> control(String method, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.apiUrl() + path))
                .timeout(config.requestTimeout()).header("Accept", "application/json");
        if (!config.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + config.apiKey());
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(encodeJson(body)));
        }
        return sendBytes(request.build());
    }

    private HttpRequest.Builder dataRequest(Sandbox sandbox, String method, String path, String query) {
        String id = validId(sandbox.id());
        String target;
        if (!config.proxyNodeIp().isBlank()) {
            String host = config.proxyNodeIp();
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            target = config.proxyScheme() + "://" + host + ":" + config.proxyPort()
                    + "/sandbox/" + id + "/" + ENVD_PORT + path + query;
        } else {
            String domain = sandbox.domain() == null || sandbox.domain().isBlank()
                    ? config.sandboxDomain() : sandbox.domain();
            if (!domain.matches("[A-Za-z0-9.-]+")) {
                throw new CubeSandboxException("Invalid CubeSandbox domain", -1);
            }
            target = config.proxyScheme() + "://" + ENVD_PORT + "-" + id + "." + domain + path + query;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
                .header("Accept-Encoding", "identity");
        if (sandbox.envdAccessToken() != null && !sandbox.envdAccessToken().isBlank()) {
            request.header("X-Access-Token", sandbox.envdAccessToken());
        }
        if (sandbox.trafficAccessToken() != null && !sandbox.trafficAccessToken().isBlank()) {
            request.header("e2b-traffic-access-token", sandbox.trafficAccessToken());
            request.header("cube-traffic-access-token", sandbox.trafficAccessToken());
        }
        return request;
    }

    private static String fileQuery(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("sandbox file path is required");
        }
        return "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    private byte[] encodeJson(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JacksonException error) {
            throw new CubeSandboxException("Could not encode CubeSandbox request", -1, error);
        }
    }

    private Sandbox decodeSandbox(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            String id = node.path("sandboxID").asText("");
            if (id.isBlank()) {
                throw new CubeSandboxException("CubeSandbox response omitted sandboxID", -1);
            }
            return new Sandbox(validId(id), node.path("templateID").asText(""),
                    node.path("domain").asText(""), node.path("envdAccessToken").asText(""),
                    node.path("trafficAccessToken").asText(""));
        } catch (JacksonException error) {
            throw new CubeSandboxException("Invalid CubeSandbox response", -1, error);
        }
    }

    private static String validId(String id) {
        if (id == null || !SANDBOX_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid CubeSandbox ID");
        }
        return id;
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox HTTP request failed", -1, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CubeSandboxException("CubeSandbox HTTP request interrupted", -1, error);
        }
    }

    private HttpResponse<InputStream> sendStream(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox HTTP request failed", -1, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CubeSandboxException("CubeSandbox HTTP request interrupted", -1, error);
        }
    }

    private static void expectStatus(HttpResponse<byte[]> response, int... accepted) {
        for (int status : accepted) {
            if (response.statusCode() == status) {
                return;
            }
        }
        throw apiError(response.statusCode(), response.body());
    }

    private static CubeSandboxException apiError(int status, byte[] body) {
        String message = new String(body, 0, Math.min(body.length, MAX_ERROR_BYTES), StandardCharsets.UTF_8);
        return new CubeSandboxException("CubeSandbox HTTP " + status + (message.isBlank() ? "" : ": " + message), status);
    }

    private static byte[] readBounded(InputStream body, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = body.read(chunk)) != -1) {
            if (result.size() + count > limit) {
                throw new CubeSandboxException("CubeSandbox response exceeds " + limit + " bytes", -1);
            }
            result.write(chunk, 0, count);
        }
        return result.toByteArray();
    }

    private static byte[] multipartBody(String path, byte[] content, String boundary) {
        String filename = path.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace('\r', '_').replace('\n', '_');
        byte[] header = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream(header.length + content.length + footer.length);
        body.writeBytes(header);
        body.writeBytes(content);
        body.writeBytes(footer);
        return body.toByteArray();
    }

    private CommandResult parseProcessStream(InputStream body) throws IOException {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        boolean sawEnd = false;
        int exitCode = 0;
        while (true) {
            int flags = body.read();
            if (flags == -1) {
                break;
            }
            byte[] lengthBytes = body.readNBytes(4);
            if (lengthBytes.length != 4) {
                throw new CubeSandboxException("Truncated CubeSandbox Connect frame header", -1);
            }
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(lengthBytes).getInt());
            if (size > MAX_CONNECT_FRAME) {
                throw new CubeSandboxException("CubeSandbox Connect frame is too large", -1);
            }
            byte[] payload = body.readNBytes((int) size);
            if (payload.length != size) {
                throw new CubeSandboxException("Truncated CubeSandbox Connect frame", -1);
            }
            if ((flags & 1) != 0) {
                throw new CubeSandboxException("Compressed CubeSandbox Connect frames are unsupported", -1);
            }
            if ((flags & ~2) != 0) {
                throw new CubeSandboxException("Unsupported CubeSandbox Connect frame flags", -1);
            }
            if ((flags & 2) != 0 && payload.length == 0) {
                continue;
            }
            JsonNode frame;
            try {
                frame = json.readTree(payload);
            } catch (JacksonException error) {
                throw new CubeSandboxException("Invalid CubeSandbox Connect frame", -1, error);
            }
            if ((flags & 2) != 0) {
                JsonNode streamError = frame.path("error");
                if (!streamError.isMissingNode() && !streamError.isNull()) {
                    throw new CubeSandboxException("CubeSandbox Connect error: "
                            + streamError.path("code").asText("") + " "
                            + streamError.path("message").asText(""), -1);
                }
                continue;
            }
            JsonNode event = frame.path("event");
            JsonNode data = event.path("data");
            appendBase64(stdout, data.path("stdout").asText(""));
            appendBase64(stderr, data.path("stderr").asText(""));
            JsonNode end = event.path("end");
            if (!end.isMissingNode() && !end.isNull()) {
                String processError = end.path("error").asText("");
                if (!processError.isBlank()) {
                    throw new CubeSandboxException("CubeSandbox process failed: " + processError, -1);
                }
                if (end.has("exitCode")) {
                    exitCode = end.path("exitCode").asInt();
                } else if (end.has("exit_code")) {
                    exitCode = end.path("exit_code").asInt();
                } else {
                    String status = end.path("status").asText("");
                    if (status.startsWith("exit status ")) {
                        try {
                            exitCode = Integer.parseInt(status.substring("exit status ".length()).trim());
                        } catch (NumberFormatException error) {
                            if (end.path("exited").asBoolean(false)) {
                                exitCode = 0;
                            } else {
                                throw new CubeSandboxException("Invalid CubeSandbox exit status", -1, error);
                            }
                        }
                    } else if (end.path("exited").asBoolean(false)) {
                        exitCode = 0;
                    } else {
                        throw new CubeSandboxException("CubeSandbox process end omitted exit code", -1);
                    }
                }
                sawEnd = true;
            }
        }
        if (!sawEnd) {
            throw new CubeSandboxException("CubeSandbox process stream ended without EndEvent", -1);
        }
        return new CommandResult(new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8), exitCode);
    }

    private static void appendBase64(ByteArrayOutputStream out, String encoded) {
        if (encoded.isEmpty()) {
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new CubeSandboxException("Invalid CubeSandbox process output", -1, error);
        }
        int allowed = Math.min(decoded.length, MAX_CAPTURE_BYTES - out.size());
        if (allowed > 0) {
            out.write(decoded, 0, allowed);
        }
    }
}
