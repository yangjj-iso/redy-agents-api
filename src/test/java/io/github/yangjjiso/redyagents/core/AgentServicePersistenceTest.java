package io.github.yangjjiso.redyagents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AgentServicePersistenceTest {
    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @Timeout(10)
    void externalFunctionCanResumeAfterRestartAndToolResultIsIdempotent() throws Exception {
        Path snapshots = directory.resolve("sessions");
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> {
            if (modelCalls.incrementAndGet() == 1) {
                return Decision.toolCall("looking up customer",
                        new ToolCall("lookup", bytes("{\"id\":\"123\"}"), "call_123"));
            }
            Message result = messages.get(messages.size() - 1);
            assertEquals("tool", result.role());
            assertEquals("call_123", result.callId());
            assertEquals(Boolean.TRUE, result.success());
            assertEquals("found", result.content());
            return Decision.finalMessage("Customer found");
        };
        ExternalFunctionTool lookup = new ExternalFunctionTool() {};
        LoopRunner runner = new LoopRunner(model, Map.of("lookup", lookup), 5);
        String sessionId;
        String turnId;

        try (AgentService first = new AgentService(runner, new FileSessionStore(snapshots))) {
            Session session = first.createSession(new AgentConfig("test", "demo", ""));
            sessionId = session.id();
            Turn turn = first.startTurn(sessionId, "Find customer");
            turnId = turn.id();
            Session waiting = awaitSessionStatus(first, sessionId, "requires_action");
            assertEquals("waiting", first.getTurn(sessionId, turnId).status());
            assertEquals(turnId, waiting.activeTurnId());
            assertEquals(1, waiting.requiredActions().size());
            RequiredAction action = waiting.requiredActions().get(0);
            assertEquals("function_call", action.type());
            assertEquals(turnId, action.turnId());
            assertEquals("call_123", action.callId());
            assertEquals("lookup", action.name());
            assertEquals(Map.of("id", "123"), action.arguments());
            assertEquals(1, modelCalls.get());
        }

        try (AgentService restarted = new AgentService(runner, new FileSessionStore(snapshots))) {
            Session waiting = restarted.getSession(sessionId);
            assertEquals("requires_action", waiting.status());
            assertEquals("waiting", restarted.getTurn(sessionId, turnId).status());
            assertEquals("call_123", waiting.requiredActions().get(0).callId());
            assertEquals(1, modelCalls.get(), "restoring a pending call must not call the model");

            restarted.submitToolResult(sessionId, turnId, "call_123", true, "found", null);
            Turn completed = awaitTurnStatus(restarted, sessionId, turnId, "completed");
            assertEquals("Customer found", completed.output());
            assertEquals(2, modelCalls.get());

            restarted.submitToolResult(sessionId, turnId, "call_123", true, "found", null);
            assertEquals(2, modelCalls.get(), "identical tool results must not rerun the model");
            AgentException conflict = assertThrows(AgentException.class,
                    () -> restarted.submitToolResult(sessionId, turnId, "call_123",
                            true, "different", null));
            assertEquals(AgentException.Reason.CONFLICT, conflict.reason());
        }

        try (AgentService restartedAgain = new AgentService(runner, new FileSessionStore(snapshots))) {
            restartedAgain.submitToolResult(sessionId, turnId, "call_123", true, "found", null);
            assertEquals("completed", restartedAgain.getTurn(sessionId, turnId).status());
            assertEquals(2, modelCalls.get(), "result deduplication must survive another restart");
        }
    }

    @Test
    @Timeout(10)
    void interruptedExecutingTurnFailsOnRecoveryWithoutRerunningTheModel() throws Exception {
        Path liveSnapshots = directory.resolve("live");
        Path recoveredSnapshots = directory.resolve("recovered");
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        Model blockedModel = (cancellation, agent, messages) -> {
            modelEntered.countDown();
            releaseModel.await();
            cancellation.throwIfCancelled();
            return Decision.finalMessage("should not be recovered as completed");
        };
        AtomicInteger recoveryModelCalls = new AtomicInteger();
        Model recoveryModel = (cancellation, agent, messages) -> {
            recoveryModelCalls.incrementAndGet();
            return Decision.finalMessage("unexpected replay");
        };
        AgentService original = new AgentService(
                new LoopRunner(blockedModel), new FileSessionStore(liveSnapshots));
        String sessionId = null;
        String turnId = null;
        try {
            Session session = original.createSession(new AgentConfig("test", "demo", ""));
            sessionId = session.id();
            Turn turn = original.startTurn(sessionId, "Do work");
            turnId = turn.id();
            assertTrue(modelEntered.await(2, TimeUnit.SECONDS), "model did not start");
            assertEquals("in_progress", original.getTurn(sessionId, turnId).status());

            // Copy the durable state at the crash boundary. The live worker remains blocked.
            Files.createDirectories(recoveredSnapshots);
            Files.copy(liveSnapshots.resolve(sessionId + ".snapshot"),
                    recoveredSnapshots.resolve(sessionId + ".snapshot"));
            try (AgentService recovered = new AgentService(
                    new LoopRunner(recoveryModel), new FileSessionStore(recoveredSnapshots))) {
                Turn failed = recovered.getTurn(sessionId, turnId);
                assertEquals("failed", failed.status());
                assertNotNull(failed.completedAt());
                assertTrue(failed.error().contains("restarted during execution"));
                assertEquals("idle", recovered.getSession(sessionId).status());
                assertEquals(0, recoveryModelCalls.get());
                List<Event> events = recovered.eventsAfter(sessionId, 0);
                Event recoveredFailure = events.stream().filter(event -> "turn.failed".equals(event.type()))
                        .findFirst().orElseThrow();
                assertEquals(Boolean.TRUE, recoveredFailure.data().get("recovered"));
                assertTrue(events.stream().anyMatch(event -> "agent.session.turn.failed".equals(event.type())));
                assertEquals("agent.session.idle", events.get(events.size() - 1).type());
            }
        } finally {
            releaseModel.countDown();
            if (sessionId != null && turnId != null) {
                try {
                    awaitTurnStatus(original, sessionId, turnId, "completed");
                } catch (AssertionError ignored) {
                    // Keep the cleanup bounded if an assertion above exposed a worker failure.
                }
            }
            original.close();
        }
    }

    @Test
    @Timeout(10)
    void safeCheckpointAfterExternalResultContinuesOnRecovery() throws Exception {
        Path snapshots = directory.resolve("safe-checkpoint");
        FileSessionStore store = new FileSessionStore(snapshots);
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> {
            if (modelCalls.incrementAndGet() == 1) {
                return Decision.toolCall("lookup",
                        new ToolCall("lookup", bytes("{\"id\":\"123\"}"), "call_safe"));
            }
            Message last = messages.get(messages.size() - 1);
            assertEquals("tool", last.role());
            assertEquals("call_safe", last.callId());
            assertEquals("found", last.content());
            return Decision.finalMessage("recovered answer");
        };
        LoopRunner runner = new LoopRunner(model, Map.of("lookup", new ExternalFunctionTool() {}), 5);
        String sessionId;
        String turnId;
        try (AgentService original = new AgentService(runner, store)) {
            Session session = original.createSession(new AgentConfig("test", "demo", ""));
            sessionId = session.id();
            turnId = original.startTurn(sessionId, "Find customer").id();
            awaitSessionStatus(original, sessionId, "requires_action");
        }

        AgentService.Snapshot waiting = json.readValue(store.loadAll().get(sessionId),
                AgentService.Snapshot.class);
        assertNotNull(waiting.checkpoint().pendingExternal());
        ToolResult supplied = new ToolResult("call_safe", true, "found", null);
        LoopCheckpoint resumed = runner.resumeExternal(waiting.checkpoint(), supplied,
                (type, data) -> {});
        assertNull(resumed.pendingExternal());
        Turn pending = waiting.turns().get(0);
        Turn executing = new Turn(pending.id(), pending.sessionId(), pending.input(),
                "in_progress", "", "", pending.createdAt(), null);
        Session active = new Session(waiting.session().id(), waiting.session().agent(),
                "in_progress", turnId, waiting.session().createdAt(), Instant.now());
        List<SessionItem> items = new ArrayList<>(waiting.items());
        Message output = resumed.messages().get(resumed.messages().size() - 1);
        items.add(new SessionItem("item_safe_result", turnId, "function_call_output",
                output.role(), output.content(), output.tool(), output.callId(),
                output.argumentsBase64(), output.success(), Instant.now()));
        AgentService.Snapshot safe = new AgentService.Snapshot(active, List.of(executing),
                waiting.context(), resumed.messages(), waiting.events(), items, resumed,
                Map.of(turnId + ":call_safe", supplied), waiting.steering(), true);
        store.save(sessionId, json.writeValueAsBytes(safe));

        try (AgentService recovered = new AgentService(runner, new FileSessionStore(snapshots))) {
            Turn completed = awaitTurnStatus(recovered, sessionId, turnId, "completed");
            assertEquals("recovered answer", completed.output());
            assertEquals(2, modelCalls.get());
            assertTrue(recovered.eventsAfter(sessionId, 0).stream()
                    .noneMatch(event -> "turn.failed".equals(event.type())));
        }
    }

    @Test
    @Timeout(10)
    void cancellingWaitingFunctionCreatesPairedFailureItemAndSurvivesRestart() throws Exception {
        Path snapshots = directory.resolve("waiting-cancel");
        AtomicInteger modelCalls = new AtomicInteger();
        Model model = (cancellation, agent, messages) -> {
            modelCalls.incrementAndGet();
            return Decision.toolCall("external",
                    new ToolCall("lookup", bytes("{\"id\":\"123\"}"), "call_cancel"));
        };
        LoopRunner runner = new LoopRunner(model, Map.of("lookup", new ExternalFunctionTool() {}), 5);
        String sessionId;
        String turnId;
        try (AgentService service = new AgentService(runner, new FileSessionStore(snapshots))) {
            Session session = service.createSession(new AgentConfig("test", "demo", ""));
            sessionId = session.id();
            turnId = service.startTurn(sessionId, "Find customer").id();
            awaitSessionStatus(service, sessionId, "requires_action");
            SessionItem call = service.items(sessionId).stream()
                    .filter(item -> "function_call".equals(item.type())).findFirst().orElseThrow();

            Turn cancelled = service.cancelTurn(sessionId, turnId);
            assertEquals("cancelled", cancelled.status());
            assertEquals("idle", service.getSession(sessionId).status());
            SessionItem result = service.items(sessionId).stream()
                    .filter(item -> "function_call_output".equals(item.type())).findFirst().orElseThrow();
            assertEquals(call.callId(), result.callId());
            assertEquals(call.argumentsBase64(), result.argumentsBase64());
            assertEquals(Boolean.FALSE, result.success());
            assertTrue(result.content().contains("cancelled"));
            assertEquals(1, modelCalls.get());
        }

        try (AgentService recovered = new AgentService(runner, new FileSessionStore(snapshots))) {
            assertEquals("cancelled", recovered.getTurn(sessionId, turnId).status());
            assertEquals("idle", recovered.getSession(sessionId).status());
            assertEquals(1, modelCalls.get());
            List<Event> events = recovered.eventsAfter(sessionId, 0);
            assertTrue(events.stream().anyMatch(event -> "turn.cancelled".equals(event.type())));
            assertEquals("agent.session.idle", events.get(events.size() - 1).type());
        }
    }

    @Test
    @Timeout(10)
    void reusedCallIdInLaterTurnStillGetsItsOwnRecoveryResult() throws Exception {
        Path snapshots = directory.resolve("reused-call-id");
        FileSessionStore store = new FileSessionStore(snapshots);
        Model model = (cancellation, agent, messages) -> {
            if ("tool".equals(messages.get(messages.size() - 1).role())) {
                return Decision.finalMessage("done");
            }
            return Decision.toolCall("external",
                    new ToolCall("lookup", bytes("{\"id\":\"123\"}"), "call_shared"));
        };
        LoopRunner runner = new LoopRunner(model,
                Map.of("lookup", new ExternalFunctionTool() {}), 5);
        String sessionId;
        String secondTurn;
        try (AgentService service = new AgentService(runner, store)) {
            sessionId = service.createSession(new AgentConfig("test", "demo", "")).id();
            String firstTurn = service.startTurn(sessionId, "First").id();
            awaitSessionStatus(service, sessionId, "requires_action");
            service.submitToolResult(sessionId, firstTurn, "call_shared", true, "found", null);
            awaitTurnStatus(service, sessionId, firstTurn, "completed");

            secondTurn = service.startTurn(sessionId, "Second").id();
            awaitSessionStatus(service, sessionId, "requires_action");
        }
        AgentService.Snapshot waiting = json.readValue(store.loadAll().get(sessionId),
                AgentService.Snapshot.class);
        List<Turn> turns = new ArrayList<>(waiting.turns());
        Turn pending = turns.get(turns.size() - 1);
        turns.set(turns.size() - 1, new Turn(pending.id(), pending.sessionId(), pending.input(),
                "in_progress", "", "", pending.createdAt(), null));
        Session active = new Session(waiting.session().id(), waiting.session().agent(),
                "in_progress", secondTurn, waiting.session().createdAt(), Instant.now());
        AgentService.Snapshot interrupted = new AgentService.Snapshot(active, turns,
                waiting.context(), waiting.executionMessages(), waiting.events(), waiting.items(),
                waiting.checkpoint(), waiting.toolResults(), waiting.steering(), false);
        store.save(sessionId, json.writeValueAsBytes(interrupted));

        try (AgentService recovered = new AgentService(runner, new FileSessionStore(snapshots))) {
            assertEquals("failed", recovered.getTurn(sessionId, secondTurn).status());
            List<SessionItem> secondItems = recovered.items(sessionId).stream()
                    .filter(item -> secondTurn.equals(item.turnId())).toList();
            assertEquals(1, secondItems.stream()
                    .filter(item -> "function_call".equals(item.type())).count());
            List<SessionItem> outputs = secondItems.stream()
                    .filter(item -> "function_call_output".equals(item.type())).toList();
            assertEquals(1, outputs.size());
            assertEquals("call_shared", outputs.get(0).callId());
            assertEquals(Boolean.FALSE, outputs.get(0).success());
        }
    }

    @Test
    @Timeout(10)
    void interruptedCancellationPersistsTerminalSnapshotForRestart() throws Exception {
        Path snapshots = directory.resolve("interrupted-cancel");
        FileSessionStore store = new FileSessionStore(snapshots);
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        Model blocking = (cancellation, agent, messages) -> {
            modelEntered.countDown();
            blocked.await();
            return Decision.finalMessage("unexpected");
        };
        String sessionId;
        String turnId;
        try (AgentService service = new AgentService(new LoopRunner(blocking), store)) {
            Session session = service.createSession(new AgentConfig("test", "demo", ""));
            sessionId = session.id();
            turnId = service.startTurn(sessionId, "Block").id();
            assertTrue(modelEntered.await(2, TimeUnit.SECONDS));
            assertEquals("cancelling", service.cancelTurn(sessionId, turnId).status());
            assertEquals("cancelled", awaitTurnStatus(service, sessionId, turnId, "cancelled").status());

            // The worker is interrupted here; a failed file write would leave the prior snapshot.
            AgentService.Snapshot durable = json.readValue(store.loadAll().get(sessionId),
                    AgentService.Snapshot.class);
            assertEquals("cancelled", durable.turns().get(0).status());
            assertEquals("idle", durable.session().status());
            assertTrue(durable.events().stream().anyMatch(event -> "turn.cancelled".equals(event.type())));
            assertEquals("agent.session.idle", durable.events().get(durable.events().size() - 1).type());
        }

        AtomicInteger recoveryModelCalls = new AtomicInteger();
        Model recoveryModel = (cancellation, agent, messages) -> {
            recoveryModelCalls.incrementAndGet();
            return Decision.finalMessage("unexpected replay");
        };
        try (AgentService recovered = new AgentService(new LoopRunner(recoveryModel),
                new FileSessionStore(snapshots))) {
            assertEquals("cancelled", recovered.getTurn(sessionId, turnId).status());
            assertEquals(0, recoveryModelCalls.get());
        }
    }

    @Test
    @Timeout(10)
    void cancelledTurnDoesNotReplaySteeringAlreadySentToTheModel() throws Exception {
        Path snapshots = directory.resolve("steering-cancel");
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstModel = new CountDownLatch(1);
        CountDownLatch secondModelEntered = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        Map<Integer, List<Message>> observed = new ConcurrentHashMap<>();
        Model model = (cancellation, agent, messages) -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) {
                firstModelEntered.countDown();
                releaseFirstModel.await();
                return Decision.finalMessage("partial");
            }
            if (call == 2) {
                secondModelEntered.countDown();
                new CountDownLatch(1).await();
                return Decision.finalMessage("unexpected");
            }
            observed.put(call, List.copyOf(messages));
            return Decision.finalMessage("done " + call);
        };

        try (AgentService service = new AgentService(new LoopRunner(model),
                new FileSessionStore(snapshots))) {
            String sessionId = service.createSession(new AgentConfig("test", "demo", "")).id();
            String originalTurnId = service.startTurn(sessionId, "original").id();
            assertTrue(firstModelEntered.await(2, TimeUnit.SECONDS));
            service.submitMessage(sessionId, "steer one");
            service.submitMessage(sessionId, "steer two");
            releaseFirstModel.countDown();
            assertTrue(secondModelEntered.await(2, TimeUnit.SECONDS));
            assertEquals("cancelling", service.cancelTurn(sessionId, originalTurnId).status());

            awaitTurnStatus(service, sessionId, originalTurnId, "cancelled");
            assertEquals(1, service.listTurns(sessionId).size());
            assertEquals(2, modelCalls.get());
            service.submitMessage(sessionId, "next");
            awaitTerminalTurns(service, sessionId, 2);
            assertEquals(1, countUser(observed.get(3), "steer one"));
            assertEquals(1, countUser(observed.get(3), "steer two"));
            assertEquals(1, countUser(observed.get(3), "next"));
            assertEquals(1, service.items(sessionId).stream()
                    .filter(item -> originalTurnId.equals(item.turnId())
                            && "user".equals(item.role()) && "steer one".equals(item.content()))
                    .count(), "the cancelled turn keeps its audit item");
            AgentService.Snapshot durable = json.readValue(
                    new FileSessionStore(snapshots).loadAll().get(sessionId), AgentService.Snapshot.class);
            assertTrue(durable.claimedSteering().isEmpty());
            assertTrue(durable.steering().isEmpty());
        } finally {
            releaseFirstModel.countDown();
        }
    }

    @Test
    @Timeout(10)
    void cancellationRequeuesOnlySteeringClaimedBeforeTheNextModelBoundary() throws Exception {
        Path snapshots = directory.resolve("unconsumed-cancel");
        CountDownLatch runnerEntered = new CountDownLatch(1);
        CountDownLatch allowDrain = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Map<String, List<Message>> observed = new ConcurrentHashMap<>();
        Runner runner = (cancellation, session, history, input, emit) -> {
            if (runs.incrementAndGet() == 1) {
                runnerEntered.countDown();
                allowDrain.await();
                assertEquals(List.of("steer one", "steer two"), emit.drainSteering());
                drained.countDown();
                new CountDownLatch(1).await();
                return "unexpected";
            }
            List<Message> modelView = new ArrayList<>(history);
            modelView.add(new Message("user", input));
            observed.put(input, modelView);
            return "done";
        };

        try (AgentService service = new AgentService(runner, new FileSessionStore(snapshots))) {
            String sessionId = service.createSession(new AgentConfig("test", "demo", "")).id();
            String originalTurnId = service.startTurn(sessionId, "original").id();
            assertTrue(runnerEntered.await(2, TimeUnit.SECONDS));
            service.submitMessage(sessionId, "steer one");
            service.submitMessage(sessionId, "steer two");
            allowDrain.countDown();
            assertTrue(drained.await(2, TimeUnit.SECONDS));

            service.cancelTurn(sessionId, originalTurnId);
            List<Turn> turns = awaitTerminalTurns(service, sessionId, 3);
            assertEquals(List.of("original", "steer one", "steer two"),
                    turns.stream().map(Turn::input).toList());
            assertEquals(List.of("cancelled", "completed", "completed"),
                    turns.stream().map(Turn::status).toList());
            assertEquals(1, countUser(observed.get("steer one"), "steer one"));
            assertEquals(0, countUser(observed.get("steer one"), "steer two"));
            assertEquals(1, countUser(observed.get("steer two"), "steer two"));
            assertEquals(1, service.items(sessionId).stream()
                    .filter(item -> originalTurnId.equals(item.turnId())
                            && "user".equals(item.role()) && "steer one".equals(item.content()))
                    .count(), "the cancelled turn keeps its audit item");
        } finally {
            allowDrain.countDown();
        }
    }

    @Test
    @Timeout(10)
    void failedTurnDoesNotReplaySteeringAlreadySentToTheModel() throws Exception {
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstModel = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<List<Message>> retryContext = new AtomicReference<>();
        Model model = (cancellation, agent, messages) -> switch (modelCalls.incrementAndGet()) {
            case 1 -> {
                firstModelEntered.countDown();
                releaseFirstModel.await();
                yield Decision.finalMessage("partial");
            }
            case 2 -> throw new IllegalStateException("model failed after steering");
            default -> {
                retryContext.set(List.copyOf(messages));
                yield Decision.finalMessage("recovered");
            }
        };
        try (AgentService service = new AgentService(new LoopRunner(model),
                new FileSessionStore(directory.resolve("steering-failure")))) {
            String sessionId = service.createSession(new AgentConfig("test", "demo", "")).id();
            service.startTurn(sessionId, "original");
            assertTrue(firstModelEntered.await(2, TimeUnit.SECONDS));
            service.submitMessage(sessionId, "steer after failure");
            releaseFirstModel.countDown();

            List<Turn> failed = awaitTerminalTurns(service, sessionId, 1);
            assertEquals("failed", failed.get(0).status());
            assertEquals(2, modelCalls.get());
            service.submitMessage(sessionId, "next");
            List<Turn> turns = awaitTerminalTurns(service, sessionId, 2);
            assertEquals("next", turns.get(1).input());
            assertEquals(1, countUser(retryContext.get(), "steer after failure"));
            assertEquals(1, countUser(retryContext.get(), "next"));
        } finally {
            releaseFirstModel.countDown();
        }
    }

    @Test
    @Timeout(10)
    void crashedTurnDoesNotReplaySteeringAlreadySentToTheModel() throws Exception {
        Path live = directory.resolve("steering-live");
        Path copied = directory.resolve("steering-recovered");
        CountDownLatch firstModelEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstModel = new CountDownLatch(1);
        CountDownLatch secondModelEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondModel = new CountDownLatch(1);
        AtomicInteger originalCalls = new AtomicInteger();
        Model originalModel = (cancellation, agent, messages) -> {
            if (originalCalls.incrementAndGet() == 1) {
                firstModelEntered.countDown();
                releaseFirstModel.await();
                return Decision.finalMessage("partial");
            }
            secondModelEntered.countDown();
            releaseSecondModel.await();
            return Decision.finalMessage("original process finished");
        };
        AgentService original = new AgentService(new LoopRunner(originalModel),
                new FileSessionStore(live));
        String sessionId = null;
        String firstTurnId = null;
        try {
            sessionId = original.createSession(new AgentConfig("test", "demo", "")).id();
            firstTurnId = original.startTurn(sessionId, "original").id();
            assertTrue(firstModelEntered.await(2, TimeUnit.SECONDS));
            original.submitMessage(sessionId, "steer after crash");
            releaseFirstModel.countDown();
            assertTrue(secondModelEntered.await(2, TimeUnit.SECONDS));

            Files.createDirectories(copied);
            Files.copy(live.resolve(sessionId + ".snapshot"),
                    copied.resolve(sessionId + ".snapshot"));
            AgentService.Snapshot crashed = json.readValue(
                    new FileSessionStore(copied).loadAll().get(sessionId), AgentService.Snapshot.class);
            assertEquals(false, crashed.safeToResume());
            assertEquals(1, crashed.claimedSteering().size());
            assertEquals("steer after crash", crashed.claimedSteering().get(0).input());
            assertTrue(crashed.claimedSteering().get(0).consumed());

            AtomicInteger recoveryCalls = new AtomicInteger();
            AtomicReference<List<Message>> retryContext = new AtomicReference<>();
            Model recoveryModel = (cancellation, agent, messages) -> {
                recoveryCalls.incrementAndGet();
                retryContext.set(List.copyOf(messages));
                return Decision.finalMessage("recovered");
            };
            try (AgentService recovered = new AgentService(new LoopRunner(recoveryModel),
                    new FileSessionStore(copied))) {
                assertEquals("failed", awaitTurnStatus(recovered, sessionId, firstTurnId, "failed").status());
                assertEquals(1, recovered.listTurns(sessionId).size());
                assertEquals(0, recoveryCalls.get());
                recovered.submitMessage(sessionId, "next");
                List<Turn> turns = awaitTerminalTurns(recovered, sessionId, 2);
                assertEquals(List.of("failed", "completed"),
                        turns.stream().map(Turn::status).toList());
                assertEquals("next", turns.get(1).input());
                assertEquals(1, recoveryCalls.get());
                assertEquals(1, countUser(retryContext.get(), "steer after crash"));
                assertEquals(1, countUser(retryContext.get(), "next"));
                String crashedTurnId = firstTurnId;
                assertEquals(1, recovered.items(sessionId).stream()
                        .filter(item -> crashedTurnId.equals(item.turnId())
                                && "user".equals(item.role())
                                && "steer after crash".equals(item.content()))
                        .count(), "the crashed turn keeps its audit item");
            }
        } finally {
            releaseFirstModel.countDown();
            releaseSecondModel.countDown();
            if (sessionId != null && firstTurnId != null) {
                try {
                    awaitTurnStatus(original, sessionId, firstTurnId, "completed");
                } catch (AssertionError ignored) {
                    // Keep cleanup bounded if an assertion above exposed a worker failure.
                }
            }
            original.close();
        }
    }

    @Test
    @Timeout(10)
    void crashRecoveryRequeuesClaimThatNeverReachedTheNextModelBoundary() throws Exception {
        Path live = directory.resolve("unconsumed-live");
        Path copied = directory.resolve("unconsumed-recovered");
        CountDownLatch runnerEntered = new CountDownLatch(1);
        CountDownLatch allowDrain = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        CountDownLatch releaseOriginal = new CountDownLatch(1);
        Runner originalRunner = (cancellation, session, history, input, emit) -> {
            runnerEntered.countDown();
            allowDrain.await();
            assertEquals(List.of("unconsumed steering"), emit.drainSteering());
            drained.countDown();
            releaseOriginal.await();
            return "original process finished";
        };
        AgentService original = new AgentService(originalRunner, new FileSessionStore(live));
        String sessionId = null;
        String firstTurnId = null;
        try {
            sessionId = original.createSession(new AgentConfig("test", "demo", "")).id();
            firstTurnId = original.startTurn(sessionId, "original").id();
            assertTrue(runnerEntered.await(2, TimeUnit.SECONDS));
            original.submitMessage(sessionId, "unconsumed steering");
            allowDrain.countDown();
            assertTrue(drained.await(2, TimeUnit.SECONDS));

            Files.createDirectories(copied);
            Files.copy(live.resolve(sessionId + ".snapshot"),
                    copied.resolve(sessionId + ".snapshot"));
            AgentService.Snapshot crashed = json.readValue(
                    new FileSessionStore(copied).loadAll().get(sessionId), AgentService.Snapshot.class);
            assertEquals(1, crashed.claimedSteering().size());
            assertEquals(false, crashed.claimedSteering().get(0).consumed());

            AtomicInteger recoveryCalls = new AtomicInteger();
            AtomicReference<List<Message>> recoveryView = new AtomicReference<>();
            Runner recoveredRunner = (cancellation, session, history, input, emit) -> {
                recoveryCalls.incrementAndGet();
                List<Message> view = new ArrayList<>(history);
                view.add(new Message("user", input));
                recoveryView.set(view);
                return "recovered";
            };
            try (AgentService recovered = new AgentService(recoveredRunner,
                    new FileSessionStore(copied))) {
                List<Turn> turns = awaitTerminalTurns(recovered, sessionId, 2);
                assertEquals(List.of("failed", "completed"),
                        turns.stream().map(Turn::status).toList());
                assertEquals("unconsumed steering", turns.get(1).input());
                assertEquals(1, recoveryCalls.get());
                assertEquals(1, countUser(recoveryView.get(), "unconsumed steering"));
                String crashedTurnId = firstTurnId;
                assertEquals(1, recovered.items(sessionId).stream()
                        .filter(item -> crashedTurnId.equals(item.turnId())
                                && "user".equals(item.role())
                                && "unconsumed steering".equals(item.content()))
                        .count());
            }
        } finally {
            allowDrain.countDown();
            releaseOriginal.countDown();
            if (sessionId != null && firstTurnId != null) {
                try {
                    awaitTurnStatus(original, sessionId, firstTurnId, "completed");
                } catch (AssertionError ignored) {
                    // Keep cleanup bounded if an assertion above exposed a worker failure.
                }
            }
            original.close();
        }
    }

    private static List<Turn> awaitTerminalTurns(AgentService service, String sessionId, int count)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            List<Turn> turns = service.listTurns(sessionId);
            if (turns.size() == count && turns.stream().allMatch(turn ->
                    "completed".equals(turn.status()) || "failed".equals(turn.status())
                            || "cancelled".equals(turn.status()))) {
                return turns;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("session did not reach " + count + " terminal turns: "
                + service.listTurns(sessionId).stream()
                        .map(turn -> turn.input() + "=" + turn.status()).toList());
    }

    private static long countUser(List<Message> messages, String text) {
        assertNotNull(messages, "model did not receive the expected retry");
        return messages.stream().filter(message ->
                "user".equals(message.role()) && text.equals(message.content())).count();
    }

    private static Session awaitSessionStatus(AgentService service, String sessionId, String status)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Session session = service.getSession(sessionId);
            if (status.equals(session.status())) {
                return session;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("session did not reach " + status);
    }

    private static Turn awaitTurnStatus(AgentService service, String sessionId, String turnId, String status)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Turn turn = service.getTurn(sessionId, turnId);
            if (status.equals(turn.status())) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("turn did not reach " + status);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
