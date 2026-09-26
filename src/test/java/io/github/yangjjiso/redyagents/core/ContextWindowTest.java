package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ContextWindowTest {
    private final ContextWindow window = new ContextWindow();

    @Test
    void leavesMessagesUnchangedWhenTheyFit() {
        List<Message> original = List.of(
                new Message("user", "earlier"), new Message("assistant", "reply"),
                new Message("user", "now"));

        ContextWindow.Result result = window.fit(original, "Be concise.", 500);

        assertEquals(original, result.messages());
        assertFalse(result.compacted());
        assertEquals(0, result.omittedMessages());
        assertTrue(result.estimatedTokens() <= 500);
    }

    @Test
    void summarizesOlderTurnsAndKeepsRecentCompletePair() {
        List<Message> original = List.of(
                new Message("user", "A".repeat(100)), new Message("assistant", "B".repeat(100)),
                new Message("user", "recent"), new Message("assistant", "answer"),
                new Message("user", "now"));

        ContextWindow.Result result = window.fit(original, "", 120);

        assertTrue(result.compacted());
        assertEquals(2, result.omittedMessages());
        assertEquals("summary", result.messages().get(0).role());
        assertTrue(result.messages().get(0).content().contains("[assistant]"));
        assertEquals(original.subList(2, 5), result.messages().subList(1, result.messages().size()));
        assertTrue(result.estimatedTokens() <= 120);
    }

    @Test
    void tinyBudgetDropsOldPairButNeverTruncatesLatestUser() {
        List<Message> original = List.of(
                new Message("user", "older"), new Message("assistant", "reply"),
                new Message("user", "now"));

        ContextWindow.Result result = window.fit(original, "", 15);

        assertEquals(List.of(new Message("user", "now")), result.messages());
        assertEquals(2, result.omittedMessages());
        assertEquals(15, result.estimatedTokens());
    }

    @Test
    void truncatesLargeCurrentToolResultButRetainsToolPairAndName() {
        List<Message> original = List.of(
                new Message("user", "run"), new Message("assistant", "calling echo"),
                new Message("tool", "x".repeat(1000), "echo"));

        ContextWindow.Result result = window.fit(original, "", 90);

        assertTrue(result.compacted());
        assertEquals(0, result.omittedMessages());
        assertEquals(original.get(0), result.messages().get(0));
        assertEquals(original.get(1), result.messages().get(1));
        assertEquals("tool", result.messages().get(2).role());
        assertEquals("echo", result.messages().get(2).tool());
        assertTrue(result.messages().get(2).content().length() < 1000);
        assertTrue(result.estimatedTokens() <= 90);
    }

    @Test
    void keepsOrOmitsHistoricalToolSequenceAsOneTurn() {
        List<Message> original = List.of(
                new Message("user", "A".repeat(100)), new Message("assistant", "B".repeat(100)),
                new Message("user", "run"), new Message("assistant", "calling"),
                new Message("tool", "result", "echo"), new Message("assistant", "done"),
                new Message("user", "now"));

        ContextWindow.Result roomy = window.fit(original, "", 140);
        assertEquals(original.subList(2, 7), roomy.messages().subList(1, roomy.messages().size()));
        assertEquals(2, roomy.omittedMessages());

        ContextWindow.Result tight = window.fit(original, "", 90);
        assertEquals(6, tight.omittedMessages());
        assertEquals("user", tight.messages().get(tight.messages().size() - 1).role());
        assertTrue(tight.messages().stream().noneMatch(message -> "tool".equals(message.role())));
        assertTrue(tight.estimatedTokens() <= 90);
    }

    @Test
    void carriesExistingSummaryIntoAnotherDemoCompaction() {
        List<Message> original = List.of(
                new Message("summary", "prior facts"),
                new Message("user", "A".repeat(100)), new Message("assistant", "B".repeat(100)),
                new Message("user", "recent"), new Message("assistant", "answer"),
                new Message("user", "now"));

        ContextWindow.Result result = window.fit(original, "", 120);

        assertTrue(result.compacted());
        assertEquals("summary", result.messages().get(0).role());
        assertTrue(result.messages().get(0).content().contains("[summary] prior facts"));
        assertEquals(original.subList(3, 6), result.messages().subList(1, result.messages().size()));
        assertTrue(result.estimatedTokens() <= 120);
    }

    @Test
    void acceptsInjectedCompressorAndPassesPriorSummaryToIt() {
        AtomicBoolean sawPriorSummary = new AtomicBoolean();
        ContextCompressor compressor = (older, target) -> {
            sawPriorSummary.set(older.stream().anyMatch(message -> "summary".equals(message.role())));
            return "[summary] retained";
        };
        ContextWindow custom = new ContextWindow(String::length, compressor);
        List<Message> original = List.of(
                new Message("summary", "prior"),
                new Message("user", "A".repeat(100)), new Message("assistant", "B".repeat(100)),
                new Message("user", "now"));

        ContextWindow.Result result = custom.fit(original, "", 80);

        assertTrue(sawPriorSummary.get());
        assertEquals("[summary] retained", result.messages().get(0).content());
        assertTrue(result.estimatedTokens() <= 80);
    }

    @Test
    void reservesToolDefinitionBytesBeforeKeepingConversationHistory() {
        List<Message> original = List.of(
                new Message("user", "A".repeat(180)), new Message("assistant", "B".repeat(180)),
                new Message("user", "now"));
        ToolDefinition search = new ToolDefinition("search", "Search an index of documents",
                Map.of("type", "object", "properties", Map.of("query", Map.of(
                        "type", "string", "description", "A phrase to search for"))));
        int budget = window.fit(original, "", 1000).estimatedTokens();

        ContextWindow.Result result = window.fit(original, "", List.of(search), budget);

        assertTrue(result.compacted());
        assertEquals(2, result.omittedMessages());
        assertEquals(new Message("user", "now"), result.messages().get(result.messages().size() - 1));
        assertTrue(result.estimatedTokens() <= budget);
        assertTrue(result.estimatedTokens()
                > window.fit(result.messages(), "", 1000).estimatedTokens(),
                "reported estimate must include tool schema and description");
    }

    @Test
    void rejectsToolCatalogThatLeavesNoRoomForLatestUserInput() {
        ToolDefinition large = new ToolDefinition("search", "x".repeat(200),
                Map.of("type", "object"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> window.fit(List.of(new Message("user", "now")), "", List.of(large), 100));

        assertTrue(error.getMessage().contains("prompt budget"));
    }

    @Test
    void rejectsLatestUserInputThatAloneExceedsBudget() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> window.fit(List.of(new Message("user", "x".repeat(100))), "", 20));
        assertTrue(error.getMessage().contains("latest user input"));
    }
}
