package org.neo4j.agentmemory.internal.http;

import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.entity.EntitySearch;
import org.neo4j.agentmemory.exception.MemoryClientException;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.exception.MissingJsonCodecException;
import org.neo4j.agentmemory.exception.ResponseDecodingException;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getAllServeEvents;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WireMockTest
class MemoryClientLoggingTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void malformedSuccessIsReportedAsADecodingFailure(WireMockRuntimeInfo wireMock) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=20"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("x-request-id", "req-decode")
                        .withBody("{invalid-json-secret")));
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var client = new HttpMemoryClient(URI.create(wireMock.getHttpBaseUrl() + "/v1"),
                "api-key-secret", new JdkHttpTransport(HttpClient.newHttpClient()), new Jackson3JsonCodec(), new ClientLogging(sink));
        assertThatThrownBy(() -> client.listConversations(new ListConversations(20)).join())
                .hasCauseInstanceOf(ResponseDecodingException.class);
        var events = sink.awaitEvents(3);
        assertThat(events.get(2).message()).contains("outcome=failure", "status=200",
                "phase=decode", "requestId=req-decode", "errorType=ResponseDecodingException");
        assertThat(events.toString()).doesNotContain("outcome=success", "api-key-secret",
                "invalid-json-secret");
        assertThat(events.subList(1, 3).toString()).doesNotContain(wireMock.getHttpBaseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "INFO"})
    void initializationIsLocalAndInfoOnly(String threshold, WireMockRuntimeInfo server) {
        var sink = new RecordingClientLogger(System.Logger.Level.valueOf(threshold));
        client(server, sink);
        assertThat(sink.entries()).containsExactly(new RecordingClientLogger.Entry(
                System.Logger.Level.INFO, "event=client.initialized transport=jdk-http baseUrl=" + server.getHttpBaseUrl() + "/v1"));
        assertThat(getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "INFO"})
    void emptyListCountsAndRespectsThreshold(String threshold, WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=20")).willReturn(okJson("{\"conversations\":[]}")));
        var sink = new RecordingClientLogger(System.Logger.Level.valueOf(threshold));
        assertThat(client(server, sink).listConversations(new ListConversations(20)).join()).isEmpty();
        if (threshold.equals("DEBUG")) terminal(sink, "listConversations", "resultCount=0", "status=200");
        else assertThat(sink.entries()).hasSize(1);
        assertContract();
    }

    @Test
    void bulkMessagesCountsWithoutPayload(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/v1/conversations/" + ID + "/messages/bulk"))
                .willReturn(okJson("{\"messages\":[{\"id\":\"" + ID + "\",\"role\":\"user\",\"content\":\"response-secret\"},"
                        + "{\"id\":\"" + ID + "\",\"role\":\"assistant\",\"content\":\"response-secret\"}]}").withStatus(201)));
        var sink = debug();
        assertThat(client(server, sink).addMessages(ID, List.of(
                NewMessage.user("payload-secret"), NewMessage.assistant("payload-secret"))).join()).hasSize(2);
        terminal(sink, "addMessages", "resultCount=2");
        assertContract();
    }

    @Test
    void structuredResultsReportZeroCounts(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations/" + ID + "/context"))
                .willReturn(okJson("{\"reflections\":[],\"observations\":[],\"recentMessages\":[]}")));
        stubFor(get(urlEqualTo("/v1/reasoning/trace/" + ID))
                .willReturn(okJson("{\"conversationId\":\"" + ID + "\",\"steps\":[],\"toolCalls\":[]}")));
        stubFor(get(urlEqualTo("/v1/reasoning/explain/" + ID))
                .willReturn(okJson("{\"id\":\"" + ID + "\",\"toolCalls\":[],\"influencedEntities\":[]}")));
        var contextSink = debug();
        assertThat(client(server, contextSink).context(ID).join().recentMessages()).isEmpty();
        terminal(contextSink, "context", "reflectionCount=0", "observationCount=0", "messageCount=0");
        var traceSink = debug();
        assertThat(client(server, traceSink).trace(ID).join().steps()).isEmpty();
        terminal(traceSink, "trace", "stepCount=0", "toolCallCount=0");
        var explainSink = debug();
        assertThat(client(server, explainSink).explainReasoningStep(ID).join().toolCalls()).isEmpty();
        terminal(explainSink, "explainReasoningStep", "toolCallCount=0", "entityCount=0");
        assertContract();
    }

    @Test
    void deleteCompletesWithoutDecoding(WireMockRuntimeInfo server) throws Exception {
        stubFor(delete(urlEqualTo("/v1/conversations/" + ID)).willReturn(aResponse().withStatus(204)));
        var sink = debug();
        assertThat(client(server, sink).deleteConversation(ID).join()).isNull();
        assertThat(terminal(sink, "deleteConversation", "status=204")).doesNotContain("Count=", "phase=decode");
        // The published contract declares 200 only; 204 deliberately exercises empty-body compatibility.
        verify(1, deleteRequestedFor(urlEqualTo("/v1/conversations/" + ID)));
    }

    @Test
    void declaredDeleteResponseMatchesContract(WireMockRuntimeInfo server) throws Exception {
        stubFor(delete(urlEqualTo("/v1/conversations/" + ID))
                .willReturn(OpenApiContract.response("delete", "/v1/conversations/{id}")));
        var sink = debug();
        assertThat(client(server, sink).deleteConversation(ID).join()).isNull();
        terminal(sink, "deleteConversation", "outcome=success", "status=200");
        assertContract();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 503})
    void serviceFailuresRetainCallerDiagnosticsAndAwaitIdentity(int status, WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=20"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withHeader("x-secret", "header-secret").withHeader("x-request-id", "req-http")
                        .withBody("body-secret")));
        var sink = debug();
        var future = client(server, sink).listConversations(new ListConversations(20));
        var failure = catchThrowable(future::join).getCause();
        assertThat(failure).isInstanceOf(MemoryServiceException.class);
        var service = (MemoryServiceException) failure;
        assertThat(service.statusCode()).isEqualTo(status);
        assertThat(service.responseHeaders()).containsEntry("x-secret", List.of("header-secret"));
        assertThat(service.responseBodyExcerpt()).isEqualTo("body-secret");
        assertThatThrownBy(() -> MemoryClient.await(future, Duration.ofSeconds(5))).isSameAs(failure);
        terminal(sink, "listConversations", "outcome=failure", "phase=http", "status=" + status,
                "requestId=req-http", "errorType=MemoryServiceException");
        assertThat(sink.entries()).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"conversations\":[{\"id\":\"invalid-uuid-secret\"}]}"})
    void invalidDomainResponseIsDecodingFailure(String body, WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=20")).willReturn(okJson(body)));
        var sink = debug();
        assertThatThrownBy(() -> client(server, sink).listConversations(new ListConversations(20)).join())
                .hasCauseInstanceOf(ResponseDecodingException.class);
        assertThat(terminal(sink, "listConversations", "phase=decode", "outcome=failure"))
                .doesNotContain("outcome=success", "invalid-uuid-secret");
    }

    @Test
    void encodingFailureRemainsAsynchronousAndKeepsCause(WireMockRuntimeInfo server) throws Exception {
        var original = new IllegalArgumentException("encode-secret");
        JsonCodec codec = new JsonCodec() {
            public byte[] encode(Object value) { throw original; }
            public <T> T decode(byte[] json, Class<T> type) { return new Jackson3JsonCodec().decode(json, type); }
        };
        var sink = debug();
        var client = new HttpMemoryClient(URI.create(server.getHttpBaseUrl() + "/v1"),
                "api-key-secret", new JdkHttpTransport(HttpClient.newHttpClient()), codec, new ClientLogging(sink));
        var future = client.createConversation(new CreateConversation());
        assertThat(catchThrowable(future::join).getCause()).isInstanceOf(MemoryClientException.class).hasCause(original);
        terminal(sink, "createConversation", "phase=encode", "outcome=failure");
        assertThat(getAllServeEvents()).isEmpty();
    }

    @Test
    void droppedConnectionReportsTransportFailure(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations?limit=20"))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        var sink = debug();
        var future = client(server, sink).listConversations(new ListConversations(20));
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(MemoryClientException.class);
        assertThat(terminal(sink, "listConversations", "phase=transport", "outcome=failure"))
                .doesNotContain("status=", "requestId=");
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "post", "delete"})
    void transportFailureCompletesTheFutureExceptionally(String method) throws Exception {
        var sink = debug();
        var original = new IllegalStateException("send-secret");
        HttpTransport transport = new HttpTransport() {
            public String name() { return "jdk-http"; }
            public CompletableFuture<HttpResult> send(HttpCall call) { throw original; }
        };
        var client = new HttpMemoryClient(URI.create("https://endpoint-secret/v1"),
                "api-key-secret", transport, new Jackson3JsonCodec(), new ClientLogging(sink));
        record RequestCase(String operation, Supplier<CompletableFuture<?>> invoke) {}
        var requestCase = switch (method) {
            case "post" -> new RequestCase("createConversation",
                    () -> client.createConversation(new CreateConversation()));
            case "delete" -> new RequestCase("deleteConversation", () -> client.deleteConversation(ID));
            default -> new RequestCase("listConversations",
                    () -> client.listConversations(new ListConversations(20)));
        };
        var future = requestCase.invoke().get();
        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MemoryClientException.class);
        assertThat(catchThrowable(future::join).getCause()).hasCause(original);
        terminal(sink, requestCase.operation(), "phase=transport", "outcome=failure",
                "errorType=MemoryClientException");
    }

    @Test
    void invalidLimitDoesNotStartOperation(WireMockRuntimeInfo server) {
        var sink = debug();
        var client = client(server, sink);
        assertThatThrownBy(() -> client.messages(ID, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 200");
        assertThat(sink.entries()).hasSize(1);
        assertThat(getAllServeEvents()).isEmpty();
    }

    @Test
    void factoryLogsOnlySuccessfulConstruction() throws Exception {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var success = probe(classpath, "success");
        assertThat(success).contains("constructed", " INFO ",
                "org.neo4j.agentmemory.MemoryClient - event=client.initialized transport=jdk-http");
        assertThat(success.lines().filter(line -> line.contains("event=client.initialized"))).hasSize(1);
        assertThat(success).contains("baseUrl=https://memory.test/v1").doesNotContain("operation.started", "probe-key");
        assertThat(probe(classpath, "blank-key")).contains("IllegalArgumentException").doesNotContain("client.initialized");
        assertThat(probe(classpath, "null-endpoint")).contains("NullPointerException").doesNotContain("client.initialized");
        var restricted = Path.of(MemoryClient.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(FactoryProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(probe(restricted, "success")).contains("MissingJsonCodecException").doesNotContain("client.initialized");
    }

    @Test
    void createConversationUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations")));
        var sink = debug();
        assertThat(client(server, sink).createConversation(new CreateConversation()).join()).isNotNull();
        terminal(sink, "createConversation", "outcome=success");
        assertContract();
    }

    @Test
    void getConversationUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations/" + ID))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));
        var sink = debug();
        assertThat(client(server, sink).getConversation(ID).join()).isNotNull();
        terminal(sink, "getConversation", "outcome=success");
        assertContract();
    }

    @Test
    void searchEntitiesUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/v1/entities/search"))
                .willReturn(OpenApiContract.response("post", "/v1/entities/search")));
        var sink = debug();
        assertThat(client(server, sink).searchEntities(new EntitySearch("payload-secret")).join()).isNotNull();
        terminal(sink, "searchEntities", "outcome=success");
        assertContract();
    }

    @Test
    void addMessageUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/v1/conversations/" + ID + "/messages"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations/{id}/messages")));
        var sink = debug();
        assertThat(client(server, sink).addMessage(ID, NewMessage.user("payload-secret")).join()).isNotNull();
        terminal(sink, "addMessage", "outcome=success");
        assertContract();
    }

    @Test
    void messagesUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations/" + ID + "/messages"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}/messages")));
        var sink = debug();
        assertThat(client(server, sink).messages(ID).join()).isNotNull();
        terminal(sink, "messages", "outcome=success");
        assertContract();
    }

    @Test
    void limitedMessagesUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(get(urlEqualTo("/v1/conversations/" + ID + "/messages?limit=20"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}/messages")));
        var sink = debug();
        assertThat(client(server, sink).messages(ID, 20).join()).isNotNull();
        terminal(sink, "messages", "outcome=success");
        assertContract();
    }

    @Test
    void recordStepUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/v1/reasoning/steps"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/steps")));
        var sink = debug();
        assertThat(client(server, sink).recordStep(ID, new NewReasoningStep("payload-secret", "payload-secret")).join()).isNotNull();
        terminal(sink, "recordStep", "outcome=success");
        assertContract();
    }

    @Test
    void recordToolCallUsesConstantName(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/v1/reasoning/tool-calls"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/tool-calls")));
        var sink = debug();
        assertThat(client(server, sink).recordToolCall(ID, NewToolCall.builder("payload-secret", "payload-secret").build()).join()).isNotNull();
        terminal(sink, "recordToolCall", "outcome=success");
        assertContract();
    }

    private static RecordingClientLogger debug() { return new RecordingClientLogger(System.Logger.Level.DEBUG); }

    private static HttpMemoryClient client(WireMockRuntimeInfo server, RecordingClientLogger sink) {
        return new HttpMemoryClient(URI.create(server.getHttpBaseUrl() + "/v1"), "api-key-secret",
                new JdkHttpTransport(HttpClient.newHttpClient()), new Jackson3JsonCodec(), new ClientLogging(sink));
    }

    private static String terminal(RecordingClientLogger sink, String operation, String... fields) throws Exception {
        var events = sink.awaitEvents(3);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).level()).isEqualTo(System.Logger.Level.INFO);
        assertThat(events.subList(1, 3)).allSatisfy(event -> {
            assertThat(event.level()).isEqualTo(System.Logger.Level.DEBUG);
            assertThat(event.message()).contains("operation=" + operation + " ");
            assertThat(event.thrown()).isNull();
        });
        assertThat(events.get(1).message()).contains("event=operation.started");
        assertThat(events.get(2).message()).contains("event=operation.completed").contains(fields);
        assertThat(events.toString()).doesNotContain("api-key-secret", "payload-secret", "response-secret",
                "body-secret", "header-secret", "encode-secret", "send-secret", ID.toString());
        assertThat(events.subList(1, 3).toString()).doesNotContain("endpoint-secret");
        return events.get(2).message();
    }

    private static void assertContract() {
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
    }

    private static String probe(String classpath, String mode) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, FactoryProbe.class.getName(), mode).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class FactoryProbe {
        public static void main(String[] args) {
            try {
                if (args[0].equals("blank-key")) {
                    MemoryClient.create(MemoryClientConfiguration.builder()
                            .baseUrl("https://memory.test/v1").apiKey(" ").build());
                } else if (args[0].equals("null-endpoint")) {
                    MemoryClient.create(MemoryClientConfiguration.builder().baseUrl(null).apiKey("probe-key").build());
                } else {
                    MemoryClient.create(MemoryClientConfiguration.builder()
                            .baseUrl("https://memory.test/v1").apiKey("probe-key").build());
                }
                System.out.println("constructed");
            } catch (IllegalArgumentException | NullPointerException | MissingJsonCodecException failure) {
                System.out.println(failure.getClass().getSimpleName());
            }
        }
    }
}
