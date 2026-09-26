package io.github.yangjjiso.redyagents.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Marker for an application-owned function whose result arrives in a later request. */
public interface ExternalFunctionTool extends Tool {
    ObjectReader ARGUMENT_READER = new ObjectMapper().readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    default void validateArguments(byte[] arguments) throws ToolFailure {
        validateJsonArguments(arguments);
    }

    /** Harness-level shape validation, independent of an implementation's semantic validator. */
    static void validateJsonArguments(byte[] arguments) throws ToolFailure {
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(arguments == null ? new byte[0] : arguments))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new ToolFailure("function arguments must be a UTF-8 JSON object", false);
        }
        try {
            Object value = ARGUMENT_READER.readValue(json);
            if (!(value instanceof Map<?, ?>)) {
                throw new ToolFailure("function arguments must be a UTF-8 JSON object", false);
            }
        } catch (JacksonException error) {
            throw new ToolFailure("function arguments must be a UTF-8 JSON object", false);
        }
    }

    @Override
    default boolean isExternal() {
        return true;
    }

    @Override
    default String execute(CancellationToken cancellation, byte[] arguments) {
        throw new UnsupportedOperationException("external function tools are not executed in the harness");
    }
}
