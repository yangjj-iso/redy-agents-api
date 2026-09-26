package io.github.yangjjiso.redyagents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.github.yangjjiso.redyagents.cube.CubeSandboxClient;
import io.github.yangjjiso.redyagents.cube.CubeSandboxConfig;
import io.github.yangjjiso.redyagents.cube.CubeSandboxException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CubeSandboxBackendTest {
    @Test
    void repeatedPauseOnlySucceedsWhenRemoteStateIsPaused() throws Exception {
        AtomicReference<String> state = new AtomicReference<>("paused");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sandboxes/sb1", exchange -> {
            byte[] body;
            int status;
            if (exchange.getRequestMethod().equals("DELETE")) {
                status = 404;
                body = new byte[0];
            } else if (exchange.getRequestURI().getPath().endsWith("/pause")) {
                status = 409;
                body = new byte[0];
            } else {
                status = 200;
                body = ("{\"state\":\"" + state.get() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            CubeSandboxBackend backend = new CubeSandboxBackend(new CubeSandboxClient(
                    new CubeSandboxConfig(url, "", "tpl", "127.0.0.1",
                            server.getAddress().getPort(), "http", "cube.app",
                            Duration.ofSeconds(2), false)));
            assertDoesNotThrow(() -> backend.pause("sb1"));
            state.set("running");
            assertThrows(CubeSandboxException.class, () -> backend.pause("sb1"));
            assertDoesNotThrow(() -> backend.kill("sb1"));
        } finally {
            server.stop(0);
        }
    }
}
