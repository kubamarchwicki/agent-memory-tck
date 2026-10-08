package org.neo4j.agentmemory.internal.http;

import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import org.neo4j.agentmemory.testsupport.HttpClientUnderTest;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.conversation.MessageRole;
import org.neo4j.agentmemory.exception.MemoryServiceException;
import org.neo4j.agentmemory.exception.MemoryClientException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@WireMockTest
@ParameterizedClass
@EnumSource(HttpClientUnderTest.class)
class MemoryClientHttpTransportTest {
    private final HttpClientUnderTest httpClient;

    MemoryClientHttpTransportTest(HttpClientUnderTest httpClient) {
        this.httpClient = httpClient;
    }

    @Test
    void sendsAuthorizationAcceptAndJsonBody(WireMockRuntimeInfo wireMock) {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                          "userId": "alice"
                        }
                        """)));
        var client = MemoryClient.create(httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(wireMock.getHttpBaseUrl() + "/v1").apiKey("test-api-key")).build());

        client.createConversation(new CreateConversation("alice")).join();

        verify(1, postRequestedFor(urlEqualTo("/v1/conversations"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"userId":"alice"}
                        """)));
    }

    @Test
    void omitsAbsentRecordValuesFromRequests(WireMockRuntimeInfo wireMock) {
        var conversationId = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
        var stepId = UUID.fromString("673139d8-dd48-48eb-a5a9-cc84e6e938e3");
        var callId = UUID.fromString("15c63f73-f00f-45de-b62e-851ea483a552");
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/v1/conversations"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720"
                                }
                                """)));
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/v1/reasoning/steps"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "673139d8-dd48-48eb-a5a9-cc84e6e938e3",
                                  "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                                  "reasoning": "reason",
                                  "actionTaken": "act"
                                }
                                """)));
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlEqualTo("/v1/reasoning/tool-calls"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "id": "15c63f73-f00f-45de-b62e-851ea483a552",
                                  "stepId": "673139d8-dd48-48eb-a5a9-cc84e6e938e3",
                                  "toolName": "search",
                                  "status": "success"
                                }
                                """)));
        var client = MemoryClient.create(httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(wireMock.getHttpBaseUrl() + "/v1").apiKey("test-api-key")).build());

        var conversation = client.createConversation(new CreateConversation()).join();
        var step = conversation
                .recordStep(new NewReasoningStep("reason", "act"))
                .join();
        var call = step.recordToolCall(NewToolCall.builder("search", "{}").build()).join();

        verify(1, postRequestedFor(urlEqualTo("/v1/conversations"))
                .withRequestBody(equalToJson("{}")));
        verify(1, postRequestedFor(urlEqualTo("/v1/reasoning/steps"))
                .withRequestBody(equalToJson("""
                        {
                          "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                          "reasoning": "reason",
                          "actionTaken": "act"
                        }
                        """)));
        verify(1, postRequestedFor(urlEqualTo("/v1/reasoning/tool-calls"))
                .withRequestBody(equalToJson("""
                        {
                          "stepId": "673139d8-dd48-48eb-a5a9-cc84e6e938e3",
                          "toolName": "search",
                          "input": "{}",
                          "status": "success"
                        }
                        """)));

        assertThat(conversation.id()).isEqualTo(conversationId);
        assertThat(step.id()).isEqualTo(stepId);
        assertThat(call.id()).isEqualTo(callId);
    }
    @Test
    void roundTripsNonAsciiContent(WireMockRuntimeInfo wireMock) {
        var id = UUID.randomUUID();
        var content = "Zażółć gęślą jaźń 🧠";
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                urlEqualTo("/v1/conversations/" + id + "/messages"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"15c63f73-f00f-45de-b62e-851ea483a552\","
                                + "\"role\":\"user\",\"content\":\"" + content + "\"}")));

        var message = client(wireMock).addMessage(id,
                new NewMessage(
                        MessageRole.USER, content)).join();

        assertThat(message.content()).isEqualTo(content);
        verify(postRequestedFor(urlEqualTo("/v1/conversations/" + id + "/messages"))
                .withRequestBody(equalToJson("{\"role\":\"user\",\"content\":\"" + content + "\"}")));
    }

    @Test
    void serviceFailureKeepsDiagnostics(WireMockRuntimeInfo wireMock) {
        var id = UUID.randomUUID();
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                urlEqualTo("/v1/conversations/" + id))
                .willReturn(aResponse().withStatus(503).withHeader("X-Request-ID", "req-503")
                        .withHeader("content-type", "application/problem+json").withBody("{\"detail\":\"busy\"}")));

        var failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> client(wireMock).getConversation(id).join());
        assertThat(failure.getCause()).isInstanceOf(MemoryServiceException.class);
        var serviceFailure = (MemoryServiceException) failure.getCause();
        assertThat(serviceFailure.statusCode()).isEqualTo(503);
        assertThat(serviceFailure.responseBodyExcerpt()).contains("busy");
        if (httpClient.keepsFailureHeaders()) {
            assertThat(serviceFailure.contentType()).isEqualTo("application/problem+json");
            assertThat(serviceFailure.responseHeaders().keySet())
                    .anyMatch(key -> key.equalsIgnoreCase("x-request-id"));
        } else {
            assertThat(serviceFailure.contentType()).isEmpty();
            assertThat(serviceFailure.responseHeaders()).isEmpty();
        }
    }

    @Test
    void droppedConnectionFailsTheFuture(WireMockRuntimeInfo wireMock) {
        var id = UUID.randomUUID();
        stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                urlEqualTo("/v1/conversations/" + id))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));

        var failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> client(wireMock).getConversation(id).join());
        assertThat(failure.getCause()).isInstanceOf(MemoryClientException.class)
                .satisfies(clientFailure -> assertThat(clientFailure.getCause()).isNotNull());
    }

    @Test
    void rejectedExecutorFailsTheFuture(WireMockRuntimeInfo wireMock) {
        org.junit.jupiter.api.Assumptions.assumeTrue(httpClient.takesExecutor());
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var client = MemoryClient.create(httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(wireMock.getHttpBaseUrl() + "/v1").apiKey("test-api-key"), executor).build());

        var future = client.getConversation(UUID.randomUUID());
        var failure = org.assertj.core.api.Assertions.catchThrowable(future::join);
        assertThat(failure.getCause()).isInstanceOf(MemoryClientException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        verify(0, com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(anyUrl()));
    }

    private MemoryClient client(WireMockRuntimeInfo wireMock) {
        return MemoryClient.create(httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(wireMock.getHttpBaseUrl() + "/v1").apiKey("test-api-key")).build());
    }
}
