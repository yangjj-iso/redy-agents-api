package io.github.yangjjiso.redyagents.cube;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Shared HTTP mechanics for CubeAPI and envd; protocol routes live in their clients. */
final class CubeHttp {
    static final int MAX_ERROR_BYTES = 4096;
    private static final Pattern SANDBOX_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private CubeHttp() {}

    static String validId(String id) {
        if (id == null || !SANDBOX_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid CubeSandbox ID");
        }
        return id;
    }

    static byte[] encodeJson(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JacksonException error) {
            throw new CubeSandboxException("Could not encode CubeSandbox request", -1, error);
        }
    }

    static HttpResponse<byte[]> sendBytes(HttpClient http, HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox HTTP request failed", -1, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CubeSandboxException("CubeSandbox HTTP request interrupted", -1, error);
        }
    }

    static HttpResponse<InputStream> sendStream(HttpClient http, HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException error) {
            throw new CubeSandboxException("CubeSandbox HTTP request failed", -1, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CubeSandboxException("CubeSandbox HTTP request interrupted", -1, error);
        }
    }

    static void expectStatus(HttpResponse<byte[]> response, int... accepted) {
        for (int status : accepted) {
            if (response.statusCode() == status) {
                return;
            }
        }
        throw apiError(response.statusCode(), response.body());
    }

    static CubeSandboxException apiError(int status, byte[] body) {
        String message = new String(body, 0, Math.min(body.length, MAX_ERROR_BYTES), StandardCharsets.UTF_8);
        return new CubeSandboxException("CubeSandbox HTTP " + status
                + (message.isBlank() ? "" : ": " + message), status);
    }

    static byte[] readBounded(InputStream body, int limit) throws IOException {
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
}
