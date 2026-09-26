package io.github.yangjjiso.redyagents.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.net.http.HttpRequest;

/** Official Java SDK transport and protocol lifecycle. */
final class SdkMcpConnection implements McpConnection {
    private static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;
    private final McpSyncClient client;

    private SdkMcpConnection(McpSyncClient client) {
        this.client = client;
    }

    static SdkMcpConnection connect(McpServerConfig config) {
        McpClientTransport transport;
        if ("stdio".equals(config.transport())) {
            ServerParameters parameters = ServerParameters.builder(config.command())
                    .args(config.args()).env(config.env()).build();
            StdioClientTransport stdio = new StdioClientTransport(
                    parameters, McpJsonDefaults.getMapper(), MAX_MESSAGE_BYTES) {
                @Override
                protected ProcessBuilder getProcessBuilder() {
                    ProcessBuilder builder = super.getProcessBuilder();
                    // The SDK adds ServerParameters.env after this hook. Without clearing,
                    // ProcessBuilder would also inherit every environment variable of Redy.
                    builder.environment().clear();
                    return builder;
                }
            };
            // A child process can put secrets in stderr; do not forward that stream to app logs.
            stdio.setStdErrorHandler(ignored -> {});
            transport = stdio;
        } else {
            HttpRequest.Builder request = HttpRequest.newBuilder();
            config.headers().forEach(request::header);
            transport = HttpClientStreamableHttpTransport.builder(config.url())
                    .endpoint(config.endpoint())
                    .requestBuilder(request)
                    .connectTimeout(config.requestTimeout())
                    .maxResponseSize(MAX_MESSAGE_BYTES)
                    .openConnectionOnStartup(false)
                    .build();
        }
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new Implementation("redy-agents-api", "0.3.0"))
                .initializationTimeout(config.requestTimeout())
                .requestTimeout(config.requestTimeout())
                .build();
        try {
            client.initialize();
            return new SdkMcpConnection(client);
        } catch (RuntimeException failure) {
            try {
                client.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public ListToolsResult listTools(String cursor) {
        return client.listTools(cursor);
    }

    @Override
    public CallToolResult callTool(CallToolRequest request) {
        return client.callTool(request);
    }

    @Override
    public void close() {
        client.closeGracefully();
    }
}
