package org.neo4j.agentmemory.internal.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.testsupport.HttpClientUnderTest;

@WireMockTest
class LangChain4jHttpTransportTest {
    private static final UUID ID = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final String PATH = "/v1/conversations/" + ID;

    @Test
    void usesTheApplicationHttpClient(WireMockRuntimeInfo server) {
        var delegate = JdkHttpClient.builder().build();
        var calls = new AtomicInteger();
        var httpClient = new HttpClient() {
            public SuccessfulHttpResponse execute(HttpRequest request) {
                calls.incrementAndGet();
                return delegate.execute(request);
            }

            public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                delegate.execute(request, parser, listener);
            }
        };
        stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + ID + "\"}")));
        var client = MemoryClient.create(MemoryClientConfiguration.builder()
                .baseUrl(server.getHttpBaseUrl() + "/v1").apiKey("api-key-secret")
                .langChain4jHttpClient(httpClient, HttpClientUnderTest.TEST_EXECUTOR).build());

        client.getConversation(ID).join();

        assertThat(calls).hasValue(1);
    }

    @Test
    void nonSuccessEventsOmitTheRequestId(WireMockRuntimeInfo server) throws InterruptedException {
        var sink = new RecordingClientLogger(System.Logger.Level.DEBUG);
        var client = new HttpMemoryClient(URI.create(server.getHttpBaseUrl() + "/v1"), "api-key-secret",
                new LangChain4jHttpTransport(JdkHttpClient.builder().build(), HttpClientUnderTest.TEST_EXECUTOR),
                new Jackson3JsonCodec(), new ClientLogging(sink));
        stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404).withHeader("X-Request-ID", "req-404")));

        assertThat(catchThrowable(() -> client.getConversation(ID).join())).hasCauseInstanceOf(MemoryServiceException.class);

        var terminal = sink.awaitEvents(3).stream()
                .filter(entry -> entry.message().contains("event=operation.completed"))
                .findFirst().orElseThrow();
        assertThat(terminal.message()).contains("status=404", "outcome=failure").doesNotContain("requestId=");
    }
}
