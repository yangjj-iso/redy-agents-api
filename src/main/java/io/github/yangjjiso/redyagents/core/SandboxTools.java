package io.github.yangjjiso.redyagents.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Local tools that route commands and file operations to the session's CubeSandbox. */
public final class SandboxTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader ARGUMENT_READER = JSON.readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_COMMAND_CHARS = 8192;
    private static final int MAX_PATH_CHARS = 4096;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_ARGUMENT_BYTES = MAX_FILE_BYTES + 16384;
    private static final int MAX_RESULT_CHARS = 65536;

    private SandboxTools() {}

    public static Map<String, Tool> create(SandboxToolBackend backend) {
        if (backend == null) {
            throw new IllegalArgumentException("sandbox backend is required");
        }
        return Map.of(
                "sandbox.shell", new Shell(backend),
                "sandbox.read_file", new ReadFile(backend),
                "sandbox.write_file", new WriteFile(backend));
    }

    private abstract static class SessionTool implements Tool {
        final SandboxToolBackend backend;

        SessionTool(SandboxToolBackend backend) {
            this.backend = backend;
        }

        @Override
        public String execute(CancellationToken cancellation, byte[] arguments) throws ToolFailure {
            throw new ToolFailure("tool requires a session sandbox");
        }

        static String sandboxId(ToolExecutionContext context) throws ToolFailure {
            if (context == null || context.session().environment() == null) {
                throw new ToolFailure("tool requires a CubeSandbox session");
            }
            SessionEnvironment environment = context.session().environment();
            if (!"cube".equals(environment.type()) || environment.sandboxId() == null
                    || environment.sandboxId().isBlank()) {
                throw new ToolFailure("tool requires a CubeSandbox session");
            }
            if (!"active".equals(environment.status())) {
                throw new ToolFailure("session sandbox is " + environment.status());
            }
            return environment.sandboxId();
        }

        static Map<String, Object> arguments(byte[] bytes, Set<String> allowed) throws ToolFailure {
            if (bytes == null || bytes.length > MAX_ARGUMENT_BYTES) {
                throw new ToolFailure("sandbox tool arguments exceed the size limit");
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException error) {
                throw new ToolFailure("arguments must be a UTF-8 JSON object");
            }
            Object value;
            try {
                value = ARGUMENT_READER.readValue(text);
            } catch (JacksonException error) {
                throw new ToolFailure("arguments must be a UTF-8 JSON object");
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw new ToolFailure("arguments must be a UTF-8 JSON object");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !allowed.contains(key)) {
                    throw new ToolFailure("unknown sandbox tool argument");
                }
                result.put(key, entry.getValue());
            }
            return result;
        }

        static String requiredText(Map<String, Object> arguments, String key, int maxChars)
                throws ToolFailure {
            Object value = arguments.get(key);
            if (!(value instanceof String text) || text.isBlank() || text.length() > maxChars
                    || text.indexOf('\0') >= 0) {
                throw new ToolFailure(key + " must be nonempty text of at most " + maxChars + " characters");
            }
            return text;
        }

        static String path(Map<String, Object> arguments) throws ToolFailure {
            String path = requiredText(arguments, "path", MAX_PATH_CHARS);
            if (!path.startsWith("/") || path.contains("/../") || path.endsWith("/..")) {
                throw new ToolFailure("path must be an absolute sandbox path without '..' segments");
            }
            return path;
        }

        static String bounded(String value) {
            if (value == null) {
                return "";
            }
            return value.length() > MAX_RESULT_CHARS ? value.substring(0, MAX_RESULT_CHARS) : value;
        }

        static String json(Map<String, Object> value) throws ToolFailure {
            try {
                return JSON.writeValueAsString(value);
            } catch (JacksonException error) {
                throw new ToolFailure("cannot encode sandbox result", error, false);
            }
        }
    }

    private static final class Shell extends SessionTool {
        Shell(SandboxToolBackend backend) {
            super(backend);
        }

        private Map<String, Object> parse(byte[] bytes) throws ToolFailure {
            Map<String, Object> args = arguments(bytes, Set.of("command", "timeout_ms"));
            requiredText(args, "command", MAX_COMMAND_CHARS);
            Object timeout = args.get("timeout_ms");
            if (timeout != null && (!(timeout instanceof Number number)
                    || number.longValue() < 100 || number.longValue() > 120000
                    || number.doubleValue() != number.longValue())) {
                throw new ToolFailure("timeout_ms must be an integer from 100 to 120000");
            }
            return args;
        }

        @Override
        public void validateArguments(byte[] arguments) throws ToolFailure {
            parse(arguments);
        }

        @Override
        public String execute(CancellationToken cancellation, ToolExecutionContext context,
                              byte[] arguments) throws Exception {
            Map<String, Object> args = parse(arguments);
            String sandboxId = sandboxId(context);
            cancellation.throwIfCancelled();
            Duration timeout = Duration.ofMillis(args.containsKey("timeout_ms")
                    ? ((Number) args.get("timeout_ms")).longValue() : 30000);
            SandboxToolBackend.CommandOutput output;
            try {
                output = backend.run(sandboxId, (String) args.get("command"), timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellation.throwIfCancelled();
                throw interrupted;
            } catch (Exception error) {
                cancellation.throwIfCancelled();
                throw new ToolFailure("sandbox command transport failed", error, false);
            }
            cancellation.throwIfCancelled();
            if (output == null) {
                throw new ToolFailure("sandbox returned no command result");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exit_code", output.exitCode());
            result.put("stdout", bounded(output.stdout()));
            result.put("stderr", bounded(output.stderr()));
            result.put("stdout_truncated", output.stdout() != null
                    && output.stdout().length() > MAX_RESULT_CHARS);
            result.put("stderr_truncated", output.stderr() != null
                    && output.stderr().length() > MAX_RESULT_CHARS);
            String encoded = json(result);
            if (output.exitCode() != 0) {
                throw new ToolFailure("sandbox command exited nonzero: " + encoded);
            }
            return encoded;
        }
    }

    private static final class ReadFile extends SessionTool {
        ReadFile(SandboxToolBackend backend) {
            super(backend);
        }

        @Override
        public boolean isIdempotent() {
            return true;
        }

        @Override
        public void validateArguments(byte[] arguments) throws ToolFailure {
            path(arguments(arguments, Set.of("path")));
        }

        @Override
        public String execute(CancellationToken cancellation, ToolExecutionContext context,
                              byte[] arguments) throws Exception {
            String path = path(arguments(arguments, Set.of("path")));
            String sandboxId = sandboxId(context);
            cancellation.throwIfCancelled();
            byte[] content;
            try {
                content = backend.readFile(sandboxId, path);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellation.throwIfCancelled();
                throw interrupted;
            } catch (Exception error) {
                cancellation.throwIfCancelled();
                throw new ToolFailure("sandbox file read failed", error, true);
            }
            cancellation.throwIfCancelled();
            if (content == null || content.length > MAX_FILE_BYTES) {
                throw new ToolFailure("sandbox file exceeds 1 MiB or was not returned");
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content)).toString();
            } catch (CharacterCodingException error) {
                throw new ToolFailure("sandbox file is not UTF-8 text");
            }
            return json(Map.of("path", path, "content", text));
        }
    }

    private static final class WriteFile extends SessionTool {
        WriteFile(SandboxToolBackend backend) {
            super(backend);
        }

        private Map<String, Object> parse(byte[] bytes) throws ToolFailure {
            Map<String, Object> args = arguments(bytes, Set.of("path", "content"));
            path(args);
            Object content = args.get("content");
            if (!(content instanceof String text) || text.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                throw new ToolFailure("content must be UTF-8 text of at most 1 MiB");
            }
            return args;
        }

        @Override
        public void validateArguments(byte[] arguments) throws ToolFailure {
            parse(arguments);
        }

        @Override
        public String execute(CancellationToken cancellation, ToolExecutionContext context,
                              byte[] arguments) throws Exception {
            Map<String, Object> args = parse(arguments);
            String sandboxId = sandboxId(context);
            String path = (String) args.get("path");
            byte[] content = ((String) args.get("content")).getBytes(StandardCharsets.UTF_8);
            cancellation.throwIfCancelled();
            try {
                backend.writeFile(sandboxId, path, content);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancellation.throwIfCancelled();
                throw interrupted;
            } catch (Exception error) {
                cancellation.throwIfCancelled();
                throw new ToolFailure("sandbox file write failed", error, false);
            }
            cancellation.throwIfCancelled();
            return json(Map.of("path", path, "bytes_written", content.length));
        }
    }
}
