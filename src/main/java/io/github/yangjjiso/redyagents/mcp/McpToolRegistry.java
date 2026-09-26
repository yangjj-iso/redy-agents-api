package io.github.yangjjiso.redyagents.mcp;

import io.github.yangjjiso.redyagents.core.Tool;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Connects configured external MCP servers and exposes their discovered tools to the harness. */
public final class McpToolRegistry implements AutoCloseable {
    private static final int MAX_PAGES = 32;
    private static final int MAX_TOOLS_PER_SERVER = 256;
    private final List<McpConnection> connections = new ArrayList<>();
    private final Map<String, Tool> tools;
    private boolean closed;

    public McpToolRegistry(List<McpServerConfig> servers) {
        this(servers, SdkMcpConnection::connect);
    }

    McpToolRegistry(List<McpServerConfig> servers, Function<McpServerConfig, McpConnection> connector) {
        Objects.requireNonNull(servers, "servers");
        Objects.requireNonNull(connector, "connector");
        Map<String, Tool> discovered = new LinkedHashMap<>();
        Set<String> serverNames = new HashSet<>();
        try {
            for (McpServerConfig server : servers) {
                Objects.requireNonNull(server, "server");
                if (!serverNames.add(server.name())) {
                    throw new IllegalArgumentException("duplicate MCP server name: " + server.name());
                }
                McpConnection connection;
                try {
                    connection = Objects.requireNonNull(connector.apply(server), "MCP connection");
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("cannot connect MCP server " + server.name(), failure);
                }
                connections.add(connection);
                for (io.modelcontextprotocol.spec.McpSchema.Tool tool : listTools(connection, server.name())) {
                    String alias = alias(server.name(), tool.name());
                    Tool previous = discovered.putIfAbsent(alias,
                            new McpToolAdapter(server.name(), connection, tool));
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate MCP tool alias: " + alias);
                    }
                }
            }
            tools = Collections.unmodifiableMap(discovered);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public Map<String, Tool> tools() {
        return tools;
    }

    private static List<io.modelcontextprotocol.spec.McpSchema.Tool> listTools(
            McpConnection connection, String serverName) {
        List<io.modelcontextprotocol.spec.McpSchema.Tool> all = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<String> names = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ListToolsResult result;
            try {
                result = connection.listTools(cursor);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("cannot list tools from MCP server " + serverName, failure);
            }
            if (result == null || result.tools() == null) {
                throw new IllegalStateException("MCP server " + serverName + " returned no tool list");
            }
            for (io.modelcontextprotocol.spec.McpSchema.Tool tool : result.tools()) {
                if (tool == null || tool.name() == null || tool.name().isBlank()) {
                    throw new IllegalStateException("MCP server " + serverName + " returned an unnamed tool");
                }
                if (!names.add(tool.name())) {
                    throw new IllegalStateException("MCP server " + serverName + " repeated tool " + tool.name());
                }
                all.add(tool);
                if (all.size() > MAX_TOOLS_PER_SERVER) {
                    throw new IllegalStateException("MCP server " + serverName + " has too many tools");
                }
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isEmpty()) {
                return List.copyOf(all);
            }
            if (!cursors.add(cursor)) {
                throw new IllegalStateException("MCP server " + serverName + " repeated a tools cursor");
            }
        }
        throw new IllegalStateException("MCP server " + serverName + " has too many tool pages");
    }

    private static String alias(String serverName, String toolName) {
        String prefix = "mcp__" + serverName + "__";
        if (toolName.matches("[A-Za-z0-9_-]+") && prefix.length() + toolName.length() <= 64) {
            return prefix + toolName;
        }
        String slug = toolName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (slug.isBlank()) {
            slug = "tool";
        }
        if (slug.length() > 32) {
            slug = slug.substring(0, 32);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(toolName.getBytes(StandardCharsets.UTF_8));
            return prefix + slug + "_" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int i = connections.size() - 1; i >= 0; i--) {
            try {
                connections.get(i).close();
            } catch (RuntimeException ignored) {
                // Continue closing remaining subprocesses/connections.
            }
        }
        connections.clear();
    }
}
