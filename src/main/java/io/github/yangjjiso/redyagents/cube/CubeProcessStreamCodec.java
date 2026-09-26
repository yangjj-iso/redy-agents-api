package io.github.yangjjiso.redyagents.cube;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Connect JSON framing for envd process start and streamed output. */
final class CubeProcessStreamCodec {
    private static final int MAX_CONNECT_FRAME = 2 * 1024 * 1024;
    private static final int MAX_CAPTURE_BYTES = 1024 * 1024;
    private final ObjectMapper json;

    CubeProcessStreamCodec(ObjectMapper json) {
        this.json = json;
    }

    byte[] startRequest(String command) {
        byte[] payload = CubeHttp.encodeJson(json, Map.of(
                "process", Map.of("cmd", "/bin/bash", "args", new String[]{"-l", "-c", command},
                        "envs", Map.of()),
                "stdin", false));
        ByteBuffer frame = ByteBuffer.allocate(5 + payload.length);
        return frame.put((byte) 0).putInt(payload.length).put(payload).array();
    }

    CommandResult readResult(InputStream body) throws IOException {
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
