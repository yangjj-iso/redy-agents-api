package io.github.yangjjiso.redyagents.cube;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** envd data plane through CubeProxy: commands and file I/O on a connected sandbox. */
final class CubeEnvdClient {
    private static final int ENVD_PORT = 49983;
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private final CubeSandboxConfig config;
    private final HttpClient http;
    private final CubeProcessStreamCodec processCodec;

    CubeEnvdClient(CubeSandboxConfig config, HttpClient http, ObjectMapper json) {
        this.config = config;
        this.http = http;
        this.processCodec = new CubeProcessStreamCodec(json);
    }

    CommandResult run(Sandbox sandbox, String command, Duration timeout) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? config.requestTimeout() : timeout;
        HttpRequest request = dataRequest(sandbox, "/process.Process/Start", "")
                .timeout(effectiveTimeout.plus(config.requestTimeout()))
                .header("Content-Type", "application/connect+json")
                .header("Connect-Protocol-Version", "1")
                .header("Connect-Content-Encoding", "identity")
                .header("Connect-Timeout-Ms", Long.toString(effectiveTimeout.toMillis()))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("root:".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(processCodec.startRequest(command)))
                .build();
        HttpResponse<InputStream> response = CubeHttp.sendStream(http, request);
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw CubeHttp.apiError(response.statusCode(), CubeHttp.readBounded(body, CubeHttp.MAX_ERROR_BYTES));
            }
            return processCodec.readResult(body);
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox command stream failed", -1, error);
        }
    }

    byte[] readFile(Sandbox sandbox, String path) {
        HttpRequest request = dataRequest(sandbox, "/files", fileQuery(path))
                .timeout(config.requestTimeout()).GET().build();
        HttpResponse<InputStream> response = CubeHttp.sendStream(http, request);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw CubeHttp.apiError(response.statusCode(), CubeHttp.readBounded(body, CubeHttp.MAX_ERROR_BYTES));
            }
            return CubeHttp.readBounded(body, MAX_FILE_BYTES);
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox file read failed", -1, error);
        }
    }

    void writeFile(Sandbox sandbox, String path, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("file content is required");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file content exceeds 16 MiB");
        }
        String query = fileQuery(path);
        HttpRequest rawRequest = dataRequest(sandbox, "/files", query)
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content)).build();
        HttpResponse<byte[]> rawResponse = CubeHttp.sendBytes(http, rawRequest);
        if (rawResponse.statusCode() >= 200 && rawResponse.statusCode() < 300) {
            return;
        }
        // Older envd builds only accept multipart file upload. Retry for unsupported formats.
        if (rawResponse.statusCode() != 400 && rawResponse.statusCode() != 415
                && rawResponse.statusCode() != 422) {
            throw CubeHttp.apiError(rawResponse.statusCode(), rawResponse.body());
        }
        String boundary = "----redy-cube-" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest multipartRequest = dataRequest(sandbox, "/files", query)
                .timeout(config.requestTimeout())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(path, content, boundary)))
                .build();
        HttpResponse<byte[]> response = CubeHttp.sendBytes(http, multipartRequest);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw CubeHttp.apiError(response.statusCode(), response.body());
        }
    }

    private HttpRequest.Builder dataRequest(Sandbox sandbox, String path, String query) {
        String id = CubeHttp.validId(sandbox.id());
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
}
