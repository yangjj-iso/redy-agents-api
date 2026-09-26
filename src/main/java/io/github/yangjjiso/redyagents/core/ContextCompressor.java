package io.github.yangjjiso.redyagents.core;

import java.util.List;

/** Compresses older messages into summary text within a requested approximate token budget. */
@FunctionalInterface
public interface ContextCompressor {
    String compress(List<Message> messages, int targetTokens);
}
