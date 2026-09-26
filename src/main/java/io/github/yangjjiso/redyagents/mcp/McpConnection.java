package io.github.yangjjiso.redyagents.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

/** Narrow client boundary so discovery and tool execution can be tested without a process. */
interface McpConnection extends AutoCloseable {
    ListToolsResult listTools(String cursor);

    CallToolResult callTool(CallToolRequest request);

    @Override
    void close();
}
