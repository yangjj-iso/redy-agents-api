package io.github.yangjjiso.redyagents.core;

import io.github.yangjjiso.redyagents.cube.SandboxTools;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SandboxToolsTest {
    @Test
    void loopRoutesShellCallToItsSessionSandbox() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        AtomicInteger decisions = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> decisions.incrementAndGet() == 1
                ? Decision.toolCall("run", new ToolCall("sandbox.shell",
                        bytes("{\"command\":\"pwd\",\"timeout_ms\":5000}"), "call_one"))
                : Decision.finalMessage(messages.get(messages.size() - 1).content());

        String answer = new LoopRunner(model, SandboxTools.create(backend), 4)
                .run(new CancellationToken(), session("active"), List.of(), "where am I", (type, data) -> {});

        assertEquals("sbx_one", backend.sandboxId);
        assertEquals("pwd", backend.command);
        assertEquals(Duration.ofSeconds(5), backend.timeout);
        assertTrue(answer.contains("\"exit_code\":0"));
        assertTrue(answer.contains("/workspace"));
    }

    @Test
    void nonzeroExitIsACommandResultAndCanBeRepeated() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.commandOutput = new SandboxToolBackend.CommandOutput(127, "", "not found");
        AtomicInteger decisions = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> decisions.incrementAndGet() <= 2
                ? Decision.toolCall("run", new ToolCall("sandbox.shell",
                        bytes("{\"command\":\"missing\"}"), "call_" + decisions.get()))
                : Decision.finalMessage(messages.get(messages.size() - 1).content());
        List<String> events = new ArrayList<>();

        String answer = new LoopRunner(model, SandboxTools.create(backend), 4)
                .run(new CancellationToken(), session("active"), List.of(), "run",
                        (type, data) -> events.add(type));

        assertEquals(2, backend.runs);
        assertTrue(answer.contains("\"exit_code\":127"));
        assertTrue(answer.contains("not found"));
        assertEquals(2, events.stream().filter("tool.call.completed"::equals).count());
        assertTrue(events.stream().noneMatch("tool.call.failed"::equals));
    }

    @Test
    void textFileToolsValidateAndUseRemoteBackend() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        Map<String, Tool> tools = SandboxTools.create(backend);
        ToolExecutionContext context = new ToolExecutionContext(session("active"), "call_1");
        Tool write = tools.get("sandbox.write_file");
        Tool read = tools.get("sandbox.read_file");
        byte[] writeArgs = bytes("{\"path\":\"/workspace/note.txt\",\"content\":\"hello\"}");

        write.validateArguments(writeArgs);
        assertTrue(write.execute(new CancellationToken(), context, writeArgs).contains("bytes_written"));
        assertEquals("/workspace/note.txt", backend.path);
        assertArrayEquals(bytes("hello"), backend.file);
        assertTrue(read.execute(new CancellationToken(), context,
                bytes("{\"path\":\"/workspace/note.txt\"}")).contains("hello"));
        assertThrows(ToolFailure.class, () -> write.validateArguments(
                bytes("{\"path\":\"../host\",\"content\":\"x\"}")));
        assertThrows(ToolFailure.class, () -> read.execute(new CancellationToken(),
                new ToolExecutionContext(session("paused"), "call_2"),
                bytes("{\"path\":\"/workspace/note.txt\"}")));
    }

    private static Session session(String environmentStatus) {
        Instant now = Instant.now();
        return new Session("sess_one", new AgentConfig("test", "demo", ""), "in_progress",
                "turn_one", now, now, List.of(),
                new SessionEnvironment("cube", "sbx_one", "template", environmentStatus));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingBackend implements SandboxToolBackend {
        private String sandboxId;
        private String command;
        private Duration timeout;
        private int runs;
        private String path;
        private byte[] file = bytes("hello");
        private CommandOutput commandOutput = new CommandOutput(0, "/workspace\n", "");

        @Override
        public CommandOutput run(String sandboxId, String command, Duration timeout) {
            this.sandboxId = sandboxId;
            this.command = command;
            this.timeout = timeout;
            runs++;
            return commandOutput;
        }

        @Override
        public byte[] readFile(String sandboxId, String path) {
            this.sandboxId = sandboxId;
            this.path = path;
            return file;
        }

        @Override
        public void writeFile(String sandboxId, String path, byte[] content) {
            this.sandboxId = sandboxId;
            this.path = path;
            file = content;
        }
    }
}
