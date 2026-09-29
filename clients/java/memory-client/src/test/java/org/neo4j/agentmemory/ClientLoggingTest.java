package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClientLoggingTest {
    @Test
    void observesTheOriginalFutureAndCountsOnlyTheResult() throws Exception {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var operation = new ClientLogging(sink).start("messages");
        var future = new CompletableFuture<List<String>>();
        assertThat(operation.observe(future)).isSameAs(future);
        operation.response(200, headers(Map.of("x-request-id", List.of("req-123"))));
        future.complete(List.of("private-content-sentinel"));
        var events = sink.awaitEvents(2);
        assertThat(events).extracting(RecordingClientLogger.Entry::level)
                .containsExactly(System.Logger.Level.DEBUG, System.Logger.Level.DEBUG);
        assertThat(events.get(1).message()).contains("outcome=success", "status=200",
                "requestId=req-123", "resultCount=1", "durationMs=");
        assertThat(events.toString()).doesNotContain("private-content-sentinel");
        assertSafe(events);
    }

    @Test
    void infoSuppressesOperationSummaries() {
        var sink = new RecordingClientLogger(System.Logger.Level.INFO);
        var logging = new ClientLogging(sink);
        logging.initialized();
        logging.start("messages").observe(CompletableFuture.completedFuture(List.of()));
        assertThat(sink.entries()).containsExactly(new RecordingClientLogger.Entry(
                System.Logger.Level.INFO, "event=client.initialized transport=jdk-http"));
    }

    @Test
    void cancellationProducesOneTerminalOutcome() throws Exception {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var future = new CompletableFuture<String>();
        new ClientLogging(sink).start("getConversation").observe(future);
        future.cancel(false);
        assertThat(future.complete("later")).isFalse();
        assertThat(sink.awaitEvents(2).get(1).message()).contains("outcome=cancelled");
        assertThat(sink.entries()).hasSize(2);
        assertSafe(sink.entries());
    }

    @Test
    void serviceFailureRetainsFutureAndExceptionIdentityWithoutDiagnostics() throws Exception {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var failure = new MemoryServiceException(
                "messages", 503, Map.of(), "application/json", "body-secret");
        var future = new CompletableFuture<Object>();
        var operation = new ClientLogging(sink).start("messages");
        operation.response(503, headers(Map.of()));
        assertThat(operation.observe(future)).isSameAs(future);
        future.completeExceptionally(failure);
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class).hasCause(failure);
        var events = sink.awaitEvents(2);
        assertThat(events).extracting(RecordingClientLogger.Entry::level)
                .containsExactly(System.Logger.Level.DEBUG, System.Logger.Level.DEBUG);
        assertThat(events.get(1).message()).contains("outcome=failure", "status=503",
                "phase=http", "errorType=MemoryServiceException");
        assertThat(events.toString()).doesNotContain("body-secret", failure.getMessage());
        assertSafe(events);
    }

    @Test
    void wrappedCancellationOmitsExceptionMessages() throws Exception {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var future = new CompletableFuture<Object>();
        new ClientLogging(sink).start("messages").observe(future);
        future.completeExceptionally(new CompletionException(new CancellationException("secret")));
        var events = sink.awaitEvents(2);
        assertThat(events.get(1).message()).contains("outcome=cancelled", "errorType=CancellationException");
        assertThat(events.toString()).doesNotContain("secret");
        assertSafe(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-request-id", "request-id", "x-amzn-requestid"})
    void filtersEveryRequestIdHeader(String header) throws Exception {
        for (var value : List.of("req-123", "bad value", "x".repeat(129), "", "x".repeat(128))) {
            var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
            var operation = new ClientLogging(sink).start("messages");
            operation.response(200, headers(Map.of(header, List.of(value))));
            operation.observe(CompletableFuture.completedFuture(List.of()));
            var events = sink.awaitEvents(2);
            var message = events.get(1).message();
            assertThat(message).contains("status=200");
            if (value.equals("req-123") || value.length() == 128) {
                assertThat(message).contains("requestId=" + value);
            } else {
                assertThat(message).doesNotContain("requestId=");
            }
            assertSafe(events);
        }
    }

    @Test
    void missingHeadersOmitRequestIdAndPrecedenceUsesFirstPresentHeader() throws Exception {
        var cases = List.of(
                Map.<String, List<String>>of(),
                Map.of("x-request-id", List.of("first"), "request-id", List.of("second"),
                        "x-amzn-requestid", List.of("third")),
                Map.of("request-id", List.of("second"), "x-amzn-requestid", List.of("third")),
                Map.of("x-request-id", List.of("bad value"), "request-id", List.of("second")));
        var expected = List.of("", "first", "second", "");
        for (int index = 0; index < cases.size(); index++) {
            var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
            var operation = new ClientLogging(sink).start("messages");
            operation.response(200, headers(cases.get(index)));
            operation.observe(CompletableFuture.completedFuture(List.of()));
            var events = sink.awaitEvents(2);
            var message = events.get(1).message();
            if (expected.get(index).isEmpty()) assertThat(message).doesNotContain("requestId=");
            else assertThat(message).contains("requestId=" + expected.get(index));
            assertSafe(events);
        }
    }

    @Test
    void emptyDomainResultsExposeOnlyExactZeroCounts() throws Exception {
        var values = List.of(List.of(), new ConversationContext(List.of(), List.of(), List.of()),
                new ReasoningTrace(null, List.of(), List.of()),
                new ReasoningStepExplanation(null, List.of(), List.of()));
        var counts = List.of(" resultCount=0", " reflectionCount=0 observationCount=0 messageCount=0",
                " stepCount=0 toolCallCount=0", " toolCallCount=0 entityCount=0");
        for (int index = 0; index < values.size(); index++) {
            var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
            new ClientLogging(sink).start("messages")
                    .observe(CompletableFuture.completedFuture(values.get(index)));
            var events = sink.awaitEvents(2);
            assertThat(events.get(1).message()).endsWith(counts.get(index));
            assertSafe(events);
        }
    }

    @Test
    void reverseCompletionCorrelatesEachFutureAcrossClients() throws Exception {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var first = new CompletableFuture<String>();
        var second = new CompletableFuture<String>();
        new ClientLogging(sink).start("messages").observe(first);
        new ClientLogging(sink).start("messages").observe(second);
        var starts = sink.awaitEvents(2);
        assertThat(starts).allSatisfy(event -> assertThat(event.message())
                .startsWith("event=operation.started operation=messages callId="));
        var firstId = field(starts.get(0).message(), "callId");
        var secondId = field(starts.get(1).message(), "callId");
        assertThat(firstId).isNotEqualTo(secondId);
        second.complete("second-secret");
        first.complete("first-secret");
        assertThat(first.cancel(false)).isFalse();
        assertThat(second.complete("again")).isFalse();
        var events = sink.awaitEvents(4);
        assertThat(events).hasSize(4);
        assertThat(field(events.get(2).message(), "callId")).isEqualTo(secondId);
        assertThat(field(events.get(3).message(), "callId")).isEqualTo(firstId);
        assertThat(events.subList(2, 4)).allSatisfy(event -> {
            assertThat(event.message()).contains("event=operation.completed", "outcome=success");
            assertThat(Long.parseLong(field(event.message(), "durationMs"))).isGreaterThanOrEqualTo(0);
        });
        assertThat(events.toString()).doesNotContain("first-secret", "second-secret", "again");
        assertSafe(events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void brokenLoggerPreservesSuccessFailureAndSynchronousReporting(boolean throwsOnCheck) {
        System.Logger logger = new System.Logger() {
            public String getName() { return "broken"; }
            public boolean isLoggable(Level level) {
                if (throwsOnCheck) throw new IllegalStateException("logger-failure");
                return true;
            }
            public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
                throw new IllegalStateException("logger-failure");
            }
            public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
                throw new IllegalStateException("logger-failure");
            }
        };
        var logging = new ClientLogging(logger);
        logging.initialized();
        var success = new CompletableFuture<String>();
        assertThat(logging.start("messages").observe(success)).isSameAs(success);
        success.complete("result");
        assertThat(success.join()).isEqualTo("result");
        var failure = new IllegalArgumentException("original-failure");
        var failed = new CompletableFuture<String>();
        assertThat(logging.start("messages").observe(failed)).isSameAs(failed);
        failed.completeExceptionally(failure);
        assertThatThrownBy(failed::join).isInstanceOf(CompletionException.class).hasCause(failure);
        logging.start("messages").failedBeforeFuture(failure);
    }

    @Test
    void synchronousFailureProducesOneSafeTerminalOutcome() throws Exception {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var operation = new ClientLogging(sink).start("messages");
        operation.failedBeforeFuture(new IllegalArgumentException("synchronous-secret"));
        operation.failedBeforeFuture(new IllegalStateException("second-secret"));
        operation.observe(CompletableFuture.completedFuture("third-secret"));
        var events = sink.awaitEvents(2);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).message()).contains("outcome=failure", "phase=request",
                "errorType=IllegalArgumentException");
        assertThat(events.toString()).doesNotContain("synchronous-secret", "second-secret", "third-secret");
        assertSafe(events);
    }

    @ParameterizedTest
    @EnumSource(ClientLogging.Phase.class)
    void failuresCaptureTheLastPhaseAndUnwrapExecutionExceptions(ClientLogging.Phase phase) throws Exception {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var operation = new ClientLogging(sink).start("messages");
        operation.phase(phase);
        operation.observe(CompletableFuture.failedFuture(new CompletionException(
                new ExecutionException(new IllegalArgumentException("wrapped-secret")))));
        var events = sink.awaitEvents(2);
        assertThat(events.get(1).message()).contains("phase=" + phase.name().toLowerCase(java.util.Locale.ROOT),
                "errorType=IllegalArgumentException", "outcome=failure");
        assertThat(events.toString()).doesNotContain("wrapped-secret");
        assertSafe(events);
    }

    private static String field(String message, String name) {
        return java.util.Arrays.stream(message.split(" "))
                .filter(part -> part.startsWith(name + "="))
                .findFirst().orElseThrow().substring(name.length() + 1);
    }

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (name, value) -> true);
    }

    private static void assertSafe(List<RecordingClientLogger.Entry> events) {
        assertThat(events).extracting(RecordingClientLogger.Entry::thrown).containsOnlyNulls();
    }
}
