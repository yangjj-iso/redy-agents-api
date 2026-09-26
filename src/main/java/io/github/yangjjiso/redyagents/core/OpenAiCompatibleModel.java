package io.github.yangjjiso.redyagents.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Non-streaming Chat Completions adapter for an OpenAI-compatible endpoint. */
public final class OpenAiCompatibleModel implements Model {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader STRICT_JSON = JSON.readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final URI endpoint;
    private final String apiKey;
    private final Duration requestTimeout;
    private final HttpClient http;

    public OpenAiCompatibleModel(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, timeout, timeout);
    }

    public OpenAiCompatibleModel(String baseUrl, String apiKey, Duration connectTimeout,
                                 Duration requestTimeout) {
        this.endpoint = endpoint(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        if (this.apiKey.indexOf('\r') >= 0 || this.apiKey.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid API key");
        }
        this.requestTimeout = positive(requestTimeout, "request timeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, "connect timeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public Decision next(CancellationToken cancellation, AgentConfig agent,
                         List<Message> messages) throws Exception {
        return next(cancellation, agent, messages, List.of());
    }

    @Override
    public Decision next(CancellationToken cancellation, AgentConfig agent, List<Message> messages,
                         List<ToolDefinition> tools) throws Exception {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        cancellation.throwIfCancelled();
        Map<String, Object> payload = requestBody(agent, messages, tools);
        byte[] requestBody;
        try {
            requestBody = JSON.writeValueAsBytes(payload);
        } catch (JacksonException failure) {
            throw new IOException("Could not encode model request");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        cancellation.throwIfCancelled();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), info -> new BoundedBodySubscriber());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("model request interrupted");
        } catch (HttpTimeoutException failure) {
            cancellation.throwIfCancelled();
            throw new IOException("Model request timed out");
        } catch (IOException failure) {
            cancellation.throwIfCancelled();
            if (containsTooLarge(failure)) {
                throw new IOException("Model response exceeds 2 MiB");
            }
            throw new IOException("Model HTTP request failed");
        }
        cancellation.throwIfCancelled();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Model HTTP status " + response.statusCode());
        }
        return decision(response.body());
    }

    private static Map<String, Object> requestBody(AgentConfig agent, List<Message> history,
                                                    List<ToolDefinition> tools) throws IOException {
        if (agent.model() == null || agent.model().isBlank()) {
            throw new IllegalArgumentException("agent model is required");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        if (agent.instructions() != null && !agent.instructions().isBlank()) {
            messages.add(Map.of("role", "system", "content", agent.instructions()));
        }
        for (Message message : history) {
            messages.add(mapMessage(Objects.requireNonNull(message, "messages contains null")));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", agent.model());
        payload.put("messages", messages);
        payload.put("stream", false);
        payload.put("parallel_tool_calls", false);
        if (agent.maxOutputTokens() != null && agent.maxOutputTokens() > 0) {
            payload.put("kimi-k3".equals(agent.model()) ? "max_completion_tokens" : "max_tokens",
                    agent.maxOutputTokens());
        }
        if (!tools.isEmpty()) {
            List<Map<String, Object>> functions = new ArrayList<>(tools.size());
            for (ToolDefinition tool : tools) {
                functions.add(Map.of("type", "function", "function", Map.of(
                        "name", tool.name(), "description", tool.description(),
                        "parameters", tool.inputSchema())));
            }
            payload.put("tools", functions);
        }
        return payload;
    }

    private static Map<String, Object> mapMessage(Message message) throws IOException {
        if (message.role() == null) {
            throw new IOException("Unsupported model message role");
        }
        String content = message.content() == null ? "" : message.content();
        return switch (message.role()) {
            case "user" -> Map.of("role", "user", "content", content);
            case "summary" -> Map.of("role", "system", "content", "Conversation summary:\n" + content);
            case "assistant" -> {
                if (message.tool() == null || message.tool().isBlank()) {
                    if (message.callId() != null || message.argumentsBase64() != null) {
                        throw new IOException("Assistant tool call metadata is incomplete");
                    }
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put("role", "assistant");
                    mapped.put("content", content);
                    addModelState(mapped, message.modelState());
                    yield mapped;
                }
                if (message.callId() == null || message.callId().isBlank()
                        || message.argumentsBase64() == null) {
                    throw new IOException("Assistant tool call metadata is incomplete");
                }
                byte[] arguments;
                try {
                    arguments = Base64.getDecoder().decode(message.argumentsBase64());
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Assistant tool arguments are invalid");
                }
                String argumentText;
                try {
                    argumentText = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(arguments)).toString();
                } catch (CharacterCodingException failure) {
                    throw new IOException("Assistant tool arguments are invalid UTF-8");
                }
                requireJsonObject(arguments);
                Map<String, Object> mapped = new LinkedHashMap<>();
                mapped.put("role", "assistant");
                mapped.put("content", content.isEmpty() ? null : content);
                mapped.put("tool_calls", List.of(Map.of(
                        "id", message.callId(), "type", "function", "function", Map.of(
                                "name", message.tool(), "arguments", argumentText))));
                addModelState(mapped, message.modelState());
                yield mapped;
            }
            case "tool" -> {
                if (message.callId() == null || message.callId().isBlank()) {
                    throw new IOException("Tool result has no call ID");
                }
                yield Map.of("role", "tool", "tool_call_id", message.callId(), "content", content);
            }
            default -> throw new IOException("Unsupported model message role");
        };
    }

    private static Decision decision(byte[] body) throws IOException {
        Object parsed;
        try {
            parsed = STRICT_JSON.readValue(body);
        } catch (Exception failure) {
            throw new IOException("Model response is not valid JSON");
        }
        Map<?, ?> response = object(parsed);
        List<?> choices = array(response.get("choices"));
        if (choices.isEmpty()) {
            throw new IOException("Model response has no choice");
        }
        Map<?, ?> choice = object(choices.get(0));
        Map<?, ?> message = object(choice.get("message"));
        if (!"assistant".equals(message.get("role"))) {
            throw new IOException("Model response has no assistant message");
        }
        Object finishValue = choice.get("finish_reason");
        if (!(finishValue instanceof String finish)
                || (!"stop".equals(finish) && !"tool_calls".equals(finish))) {
            throw new IOException("Model response did not complete normally");
        }
        Object contentValue = message.get("content");
        if (contentValue != null && !(contentValue instanceof String)) {
            throw new IOException("Model response content is invalid");
        }
        String content = contentValue == null ? "" : (String) contentValue;
        Map<String, Object> modelState = extractModelState(message);
        Object callsValue = message.get("tool_calls");
        if (callsValue == null) {
            if ("tool_calls".equals(finish) || contentValue == null) {
                throw new IOException("Model response is missing its content or tool call");
            }
            return Decision.finalMessage(content, modelState);
        }
        List<?> calls = array(callsValue);
        if (calls.size() > 1) {
            throw new IOException("Model returned multiple tool calls");
        }
        if (calls.isEmpty()) {
            throw new IOException("Model response has no tool call");
        }
        Map<?, ?> call = object(calls.get(0));
        if (!"function".equals(call.get("type"))) {
            throw new IOException("Model tool call type is invalid");
        }
        String id = nonblank(call.get("id"), "Model tool call ID is missing");
        Map<?, ?> function = object(call.get("function"));
        String name = nonblank(function.get("name"), "Model tool name is missing");
        if (!(function.get("arguments") instanceof String arguments)) {
            throw new IOException("Model tool arguments are missing");
        }
        byte[] argumentBytes = arguments.getBytes(StandardCharsets.UTF_8);
        requireJsonObject(argumentBytes);
        return Decision.toolCall(content, new ToolCall(name, argumentBytes, id), modelState);
    }

    private static Map<String, Object> extractModelState(Map<?, ?> message) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        Object reasoningContent = message.get("reasoning_content");
        if (reasoningContent != null) {
            if (!(reasoningContent instanceof String)) {
                throw new IOException("Model reasoning content is invalid");
            }
            state.put("reasoning_content", reasoningContent);
        }
        Object reasoningDetails = message.get("reasoning_details");
        if (reasoningDetails != null) {
            if (!(reasoningDetails instanceof List<?>)) {
                throw new IOException("Model reasoning details are invalid");
            }
            state.put("reasoning_details", reasoningDetails);
        }
        try {
            return ModelState.copy(state);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Model reasoning details are invalid");
        }
    }

    private static void addModelState(Map<String, Object> message, Map<String, Object> state) {
        for (String key : List.of("reasoning_content", "reasoning_details")) {
            if (state.containsKey(key)) {
                message.put(key, state.get(key));
            }
        }
    }

    private static void requireJsonObject(byte[] json) throws IOException {
        try {
            if (STRICT_JSON.readValue(json) instanceof Map<?, ?>) {
                return;
            }
        } catch (Exception ignored) {
            // Do not include model-supplied JSON or parser excerpts in an exception.
        }
        throw new IOException("Model tool arguments must be a JSON object");
    }

    private static Map<?, ?> object(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IOException("Model response has an invalid object");
    }

    private static List<?> array(Object value) throws IOException {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IOException("Model response has an invalid array");
    }

    private static String nonblank(Object value, String error) throws IOException {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IOException(error);
    }

    private static URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("model base URL is required");
        }
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid model base URL");
        }
        String host = base.getHost();
        boolean https = "https".equalsIgnoreCase(base.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(base.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host) || "[::1]".equals(host));
        if (host == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || (!https && !loopbackHttp)) {
            throw new IllegalArgumentException("invalid model base URL");
        }
        String root = base.toString().replaceAll("/+$", "");
        return URI.create(root + "/chat/completions");
    }

    private static Duration positive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static boolean containsTooLarge(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ResponseTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() {
            super("Model response exceeds 2 MiB");
        }
    }

    /** Rejects excess bytes before collecting an unbounded provider response. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean done;

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription next) {
            if (subscription != null) {
                next.cancel();
                return;
            }
            subscription = next;
            next.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            if (done) {
                return;
            }
            for (ByteBuffer chunk : chunks) {
                int count = chunk.remaining();
                if (count > MAX_RESPONSE_BYTES - bytes.size()) {
                    done = true;
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] copy = new byte[count];
                chunk.get(copy);
                bytes.write(copy, 0, count);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            done = true;
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            done = true;
            body.complete(bytes.toByteArray());
        }
    }
}
