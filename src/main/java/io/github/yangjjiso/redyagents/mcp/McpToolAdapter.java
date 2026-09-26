package io.github.yangjjiso.redyagents.mcp;

import io.github.yangjjiso.redyagents.core.CancellationToken;
import io.github.yangjjiso.redyagents.core.Tool;
import io.github.yangjjiso.redyagents.core.ToolDefinition;
import io.github.yangjjiso.redyagents.core.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Converts a discovered MCP tool into the harness's model-visible tool contract. */
final class McpToolAdapter implements Tool {
    private static final int MAX_ARGUMENT_BYTES = 1024 * 1024;
    private static final int MAX_RESULT_CHARS = 65536;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader ARGUMENT_READER = JSON.readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final JsonSchemaValidator SCHEMA_VALIDATOR = McpJsonDefaults.getSchemaValidator();

    private final String serverName;
    private final McpConnection connection;
    private final io.modelcontextprotocol.spec.McpSchema.Tool tool;

    McpToolAdapter(String serverName, McpConnection connection,
                   io.modelcontextprotocol.spec.McpSchema.Tool tool) {
        this.serverName = serverName;
        this.connection = connection;
        this.tool = tool;
    }

    @Override
    public ToolDefinition definition(String registeredName) {
        String description = tool.description() == null ? "" : tool.description();
        return new ToolDefinition(registeredName,
                "MCP server " + serverName + ", tool " + tool.name() + ". " + description,
                tool.inputSchema());
    }

    @Override
    public void validateArguments(byte[] arguments) throws ToolFailure {
        parseArguments(arguments);
    }

    @Override
    public String execute(CancellationToken cancellation, byte[] arguments) throws ToolFailure {
        cancellation.throwIfCancelled();
        Map<String, Object> parsed = parseArguments(arguments);
        CallToolResult result;
        try {
            result = connection.callTool(CallToolRequest.builder(tool.name()).arguments(parsed).build());
        } catch (RuntimeException failure) {
            cancellation.throwIfCancelled();
            throw new ToolFailure("MCP server " + serverName + " request failed ("
                    + failure.getClass().getSimpleName() + "); side effects may be uncertain", failure, false);
        }
        cancellation.throwIfCancelled();
        if (result == null) {
            throw new ToolFailure("MCP server " + serverName + " returned no tool result");
        }
        validateStructuredResult(result);
        String output = formatResult(result);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolFailure("MCP tool error: " + output);
        }
        return output;
    }

    private Map<String, Object> parseArguments(byte[] bytes) throws ToolFailure {
        if (bytes == null || bytes.length > MAX_ARGUMENT_BYTES) {
            throw new ToolFailure("MCP tool arguments exceed the size limit");
        }
        try {
            Object value = ARGUMENT_READER.readValue(bytes);
            if (!(value instanceof Map<?, ?> object)) {
                throw new ToolFailure("MCP tool arguments must be a JSON object");
            }
            Map<String, Object> parsed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ToolFailure("MCP tool arguments must have string keys");
                }
                parsed.put(key, entry.getValue());
            }
            if (tool.inputSchema() != null && !SCHEMA_VALIDATOR.validate(
                    tool.inputSchema(), parsed).valid()) {
                throw new ToolFailure("MCP tool arguments do not match its input schema");
            }
            return parsed;
        } catch (ToolFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ToolFailure("MCP tool arguments must be a JSON object");
        }
    }

    private void validateStructuredResult(CallToolResult result) throws ToolFailure {
        if (Boolean.TRUE.equals(result.isError()) || tool.outputSchema() == null) {
            return;
        }
        if (result.structuredContent() == null || !SCHEMA_VALIDATOR.validate(
                tool.outputSchema(), result.structuredContent()).valid()) {
            throw new ToolFailure("MCP tool result does not match its output schema");
        }
    }

    private static String formatResult(CallToolResult result) throws ToolFailure {
        List<Content> content = result.content() == null ? List.of() : result.content();
        if (result.structuredContent() == null && content.size() == 1
                && content.get(0) instanceof TextContent text) {
            return truncate(text.text());
        }
        Map<String, Object> output = new LinkedHashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Content part : content) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            if (part instanceof TextContent text) {
                mapped.put("type", "text");
                mapped.put("text", truncate(text.text()));
            } else if (part instanceof ResourceLink link) {
                mapped.put("type", "resource_link");
                mapped.put("uri", link.uri());
                mapped.put("name", link.name());
            } else if (part instanceof EmbeddedResource resource) {
                mapped.put("type", "resource");
                mapped.put("uri", resource.resource().uri());
                if (resource.resource() instanceof TextResourceContents text) {
                    mapped.put("text", truncate(text.text()));
                } else if (resource.resource() instanceof BlobResourceContents blob) {
                    mapped.put("mime_type", blob.mimeType());
                    mapped.put("binary_omitted", true);
                }
            } else if (part instanceof ImageContent image) {
                mapped.put("type", "image");
                mapped.put("mime_type", image.mimeType());
                mapped.put("binary_omitted", true);
            } else if (part instanceof AudioContent audio) {
                mapped.put("type", "audio");
                mapped.put("mime_type", audio.mimeType());
                mapped.put("binary_omitted", true);
            } else {
                mapped.put("type", "unsupported");
            }
            parts.add(mapped);
        }
        output.put("content", parts);
        if (result.structuredContent() != null) {
            output.put("structured_content", result.structuredContent());
        }
        try {
            String encoded = JSON.writeValueAsString(output);
            if (encoded.length() <= MAX_RESULT_CHARS) {
                return encoded;
            }
            // Keep the model-facing result valid JSON even when the MCP payload is too large.
            int previewLength = MAX_RESULT_CHARS / 2;
            while (previewLength > 0) {
                if (Character.isHighSurrogate(encoded.charAt(previewLength - 1))) {
                    previewLength--;
                }
                String bounded = JSON.writeValueAsString(Map.of(
                        "truncated", true,
                        "original_chars", encoded.length(),
                        "preview", encoded.substring(0, previewLength)));
                if (bounded.length() <= MAX_RESULT_CHARS) {
                    return bounded;
                }
                previewLength /= 2;
            }
            return "{\"truncated\":true}";
        } catch (Exception failure) {
            throw new ToolFailure("MCP tool result could not be encoded");
        }
    }

    private static String truncate(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= MAX_RESULT_CHARS) {
            return text;
        }
        int end = MAX_RESULT_CHARS - 14;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "...[truncated]";
    }
}
