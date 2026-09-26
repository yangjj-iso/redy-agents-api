package io.github.yangjjiso.redyagents.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fits a model prompt into an approximate budget. The default estimator counts UTF-8 bytes
 * plus eight units per message, so these are conservative budget units rather than exact
 * model tokens. The default compressor is a role-labelled extractive demo fallback; callers
 * can inject a model-backed compressor when semantic summarization is available.
 */
public final class ContextWindow {
    private static final int MESSAGE_OVERHEAD = 8;
    private final TokenEstimator estimator;
    private final ContextCompressor compressor;

    public record Result(List<Message> messages, boolean compacted, int estimatedTokens, int omittedMessages) {
        public Result {
            messages = List.copyOf(messages);
        }
    }

    public ContextWindow() {
        this(text -> text.getBytes(StandardCharsets.UTF_8).length, ContextWindow::extractiveDemoSummary);
    }

    public ContextWindow(TokenEstimator estimator) {
        this(estimator, ContextWindow::extractiveDemoSummary);
    }

    public ContextWindow(TokenEstimator estimator, ContextCompressor compressor) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.compressor = Objects.requireNonNull(compressor, "compressor");
    }

    public Result fit(List<Message> messages, String instructions, int promptBudgetTokens) {
        Objects.requireNonNull(messages, "messages");
        if (promptBudgetTokens <= 0) {
            throw new IllegalArgumentException("prompt budget must be positive");
        }
        int latestUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            Objects.requireNonNull(messages.get(i), "messages contains null");
            if ("user".equals(messages.get(i).role())) {
                latestUser = i;
            }
        }
        if (latestUser < 0) {
            throw new IllegalArgumentException("messages must contain the latest user input");
        }

        long instructionCost = textCost(instructions);
        long originalCost = instructionCost + messagesCost(messages);
        if (originalCost <= promptBudgetTokens) {
            return new Result(messages, false, (int) originalCost, 0);
        }

        Message latestInput = messages.get(latestUser);
        if (instructionCost + messageCost(latestInput) > promptBudgetTokens) {
            throw new IllegalArgumentException("latest user input exceeds prompt budget");
        }

        List<Message> currentTurn = new ArrayList<>(messages.subList(latestUser, messages.size()));
        long currentCost = instructionCost + messagesCost(currentTurn);
        if (currentCost > promptBudgetTokens) {
            currentCost = trimCurrentTurn(currentTurn, instructionCost, promptBudgetTokens);
            if (currentCost > promptBudgetTokens) {
                throw new IllegalArgumentException("prompt budget too small to preserve the current turn");
            }
        }

        List<Group> historicalGroups = historicalGroups(messages, latestUser);
        int keptFrom = historicalGroups.size();
        long keptCost = currentCost;
        while (keptFrom > 0) {
            Group previous = historicalGroups.get(keptFrom - 1);
            if (!previous.complete()) {
                break;
            }
            long candidateCost = messagesCost(messages.subList(previous.start(), previous.end()));
            if (keptCost + candidateCost > promptBudgetTokens) {
                break;
            }
            keptCost += candidateCost;
            keptFrom--;
        }

        int omittedMessages = keptFrom == 0 ? 0 : historicalGroups.get(keptFrom - 1).end();
        while (omittedMessages > 0 && keptFrom < historicalGroups.size()
                && promptBudgetTokens - keptCost - messageCost(new Message("summary", ""))
                        < minimumSummaryTextCost(messages.subList(0, omittedMessages))) {
            Group dropped = historicalGroups.get(keptFrom);
            keptCost -= messagesCost(messages.subList(dropped.start(), dropped.end()));
            keptFrom++;
            omittedMessages = dropped.end();
        }
        List<Message> fitted = new ArrayList<>();
        if (omittedMessages > 0) {
            long summaryAllowance = promptBudgetTokens - keptCost - messageCost(new Message("summary", ""));
            if (summaryAllowance > 0) {
                int target = (int) Math.min(summaryAllowance, Integer.MAX_VALUE);
                String text = compressor.compress(List.copyOf(messages.subList(0, omittedMessages)), target);
                if (text != null && !text.isEmpty()) {
                    text = fitText(text, summaryAllowance);
                    if (!text.isEmpty()) {
                        fitted.add(new Message("summary", text));
                    }
                }
            }
        }
        for (int i = keptFrom; i < historicalGroups.size(); i++) {
            Group group = historicalGroups.get(i);
            fitted.addAll(messages.subList(group.start(), group.end()));
        }
        fitted.addAll(currentTurn);

        long estimated = instructionCost + messagesCost(fitted);
        if (estimated > promptBudgetTokens) {
            throw new IllegalStateException("context compressor exceeded prompt budget");
        }
        return new Result(fitted, true, (int) estimated, omittedMessages);
    }

    private long trimCurrentTurn(List<Message> currentTurn, long instructionCost, int budget) {
        long cost = instructionCost + messagesCost(currentTurn);
        for (String role : List.of("tool", "assistant")) {
            for (int i = 1; i < currentTurn.size() && cost > budget; i++) {
                Message message = currentTurn.get(i);
                if (!role.equals(message.role())) {
                    continue;
                }
                long allowance = Math.max(0, textCost(message.content()) - (cost - budget));
                String shortened = fitText(message.content(), allowance);
                currentTurn.set(i, message.withContent(shortened));
                cost = instructionCost + messagesCost(currentTurn);
            }
        }
        return cost;
    }

    private String fitText(String text, long allowance) {
        String value = text == null ? "" : text;
        if (textCost(value) <= allowance) {
            return value;
        }
        if (allowance <= 0) {
            return "";
        }
        String marked = longestPrefix(value, "…", allowance);
        if (!marked.isEmpty()) {
            return marked;
        }
        return longestPrefix(value, "", allowance);
    }

    private String longestPrefix(String value, String suffix, long allowance) {
        int codePoints = value.codePointCount(0, value.length());
        int low = 0;
        int high = codePoints;
        String best = "";
        while (low <= high) {
            int middle = low + (high - low) / 2;
            String candidate = value.substring(0, value.offsetByCodePoints(0, middle)) + suffix;
            if (textCost(candidate) <= allowance) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private long messagesCost(List<Message> messages) {
        long total = 0;
        for (Message message : messages) {
            total += messageCost(message);
        }
        return total;
    }

    private long messageCost(Message message) {
        return MESSAGE_OVERHEAD + textCost(message.role()) + textCost(message.tool())
                + textCost(message.content()) + textCost(message.callId())
                + textCost(message.argumentsBase64()) + (message.success() == null ? 0 : 1);
    }

    private long textCost(String text) {
        int estimate = estimator.estimate(text == null ? "" : text);
        if (estimate < 0) {
            throw new IllegalArgumentException("token estimator returned a negative value");
        }
        return estimate;
    }

    private static List<Group> historicalGroups(List<Message> messages, int latestUser) {
        List<Group> groups = new ArrayList<>();
        int start = 0;
        while (start < latestUser) {
            int end = start + 1;
            while (end < latestUser && !"user".equals(messages.get(end).role())) {
                end++;
            }
            boolean complete = "user".equals(messages.get(start).role())
                    && "assistant".equals(messages.get(end - 1).role());
            groups.add(new Group(start, end, complete));
            start = end;
        }
        return groups;
    }

    private record Group(int start, int end, boolean complete) {}

    private long minimumSummaryTextCost(List<Message> omitted) {
        for (Message message : omitted) {
            if ("summary".equals(message.role())) {
                return textCost("[summary]");
            }
        }
        Message newest = omitted.get(omitted.size() - 1);
        return textCost("[" + (newest.role() == null ? "unknown" : newest.role()) + "]");
    }

    private static String extractiveDemoSummary(List<Message> messages, int targetTokens) {
        if (targetTokens <= 0) {
            return "";
        }
        List<String> selected = new ArrayList<>();
        int usedBytes = 0;
        // Carry an existing summary into the next demo compaction when room permits.
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if ("summary".equals(message.role())) {
                int reserved = Math.min(targetTokens, Math.max(24, targetTokens / 2));
                String prior = compactLine(message, reserved);
                if (!prior.isEmpty()) {
                    selected.add(prior);
                    usedBytes = prior.getBytes(StandardCharsets.UTF_8).length;
                }
                break;
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if ("summary".equals(message.role())) {
                continue;
            }
            int available = targetTokens - usedBytes - (selected.isEmpty() ? 0 : 1);
            String line = compactLine(message, available);
            if (line.isEmpty()) {
                break;
            }
            selected.add(selected.isEmpty() ? 0 : selected.get(0).startsWith("[summary]") ? 1 : 0, line);
            usedBytes += line.getBytes(StandardCharsets.UTF_8).length + (selected.size() == 1 ? 0 : 1);
            if (line.endsWith("…")) {
                break;
            }
        }
        return String.join("\n", selected);
    }

    private static String compactLine(Message message, int maxBytes) {
        String role = message.role() == null ? "unknown" : message.role();
        String tool = message.tool() == null || message.tool().isEmpty() ? "" : " " + message.tool();
        String label = "[" + role + tool + "]";
        int labelBytes = label.getBytes(StandardCharsets.UTF_8).length;
        if (maxBytes < labelBytes) {
            return "";
        }
        String content = message.content() == null ? "" : message.content().replace('\n', ' ').replace('\r', ' ');
        if (content.length() > 160) {
            content = content.substring(0, 160) + "…";
        }
        if (maxBytes == labelBytes) {
            return label;
        }
        int remaining = maxBytes - labelBytes - 1;
        String prefix = "";
        for (int offset = 0; offset < content.length();) {
            int next = offset + Character.charCount(content.codePointAt(offset));
            String candidate = content.substring(0, next);
            if (candidate.getBytes(StandardCharsets.UTF_8).length > remaining) {
                break;
            }
            prefix = candidate;
            offset = next;
        }
        if (prefix.length() < content.length() && remaining - prefix.getBytes(StandardCharsets.UTF_8).length >= 3) {
            prefix += "…";
        }
        return label + " " + prefix;
    }
}
