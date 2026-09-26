package io.github.yangjjiso.redyagents.cube;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CubeAPI control plane: provision and manage a sandbox's lifecycle. */
final class CubeControlClient {
    private final CubeSandboxConfig config;
    private final HttpClient http;
    private final ObjectMapper json;

    CubeControlClient(CubeSandboxConfig config, HttpClient http, ObjectMapper json) {
        this.config = config;
        this.http = http;
        this.json = json;
    }

    Sandbox create(String templateId) {
        String selected = templateId == null || templateId.isBlank() ? config.templateId() : templateId.trim();
        if (selected.isBlank()) {
            throw new IllegalArgumentException("CubeSandbox template ID is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateID", selected);
        payload.put("allowInternetAccess", config.allowInternetAccess());
        payload.put("timeout", config.idleTimeoutSeconds());
        payload.put("lifecycle", Map.of("onTimeout", "pause", "autoResume", true));
        if (!config.allowInternetAccess()) {
            payload.put("network", Map.of("denyOut", new String[]{"0.0.0.0/0"}));
        }
        HttpResponse<byte[]> response = request("POST", "/sandboxes", payload);
        CubeHttp.expectStatus(response, 200, 201);
        return decodeSandbox(response.body());
    }

    Sandbox connect(String sandboxId) {
        HttpResponse<byte[]> response = request("POST", "/sandboxes/"
                + CubeHttp.validId(sandboxId) + "/connect", Map.of());
        CubeHttp.expectStatus(response, 200);
        return decodeSandbox(response.body());
    }

    void pause(String sandboxId) {
        HttpResponse<byte[]> response = request("POST", "/sandboxes/"
                + CubeHttp.validId(sandboxId) + "/pause", null);
        CubeHttp.expectStatus(response, 200, 204);
    }

    String getState(String sandboxId) {
        HttpResponse<byte[]> response = request("GET", "/sandboxes/" + CubeHttp.validId(sandboxId), null);
        CubeHttp.expectStatus(response, 200);
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

    void kill(String sandboxId) {
        HttpResponse<byte[]> response = request("DELETE", "/sandboxes/" + CubeHttp.validId(sandboxId), null);
        CubeHttp.expectStatus(response, 200, 204, 404);
    }

    private HttpResponse<byte[]> request(String method, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.apiUrl() + path))
                .timeout(config.requestTimeout()).header("Accept", "application/json");
        if (!config.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + config.apiKey());
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(CubeHttp.encodeJson(json, body)));
        }
        return CubeHttp.sendBytes(http, request.build());
    }

    private Sandbox decodeSandbox(byte[] body) {
        try {
            JsonNode node = json.readTree(body);
            String id = node.path("sandboxID").asText("");
            if (id.isBlank()) {
                throw new CubeSandboxException("CubeSandbox response omitted sandboxID", -1);
            }
            return new Sandbox(CubeHttp.validId(id), node.path("templateID").asText(""),
                    node.path("domain").asText(""), node.path("envdAccessToken").asText(""),
                    node.path("trafficAccessToken").asText(""));
        } catch (JacksonException error) {
            throw new CubeSandboxException("Invalid CubeSandbox response", -1, error);
        }
    }
}
