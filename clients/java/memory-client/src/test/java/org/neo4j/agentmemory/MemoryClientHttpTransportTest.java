package org.neo4j.agentmemory;

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
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@WireMockTest
class MemoryClientHttpTransportTest {
    @Test
    void createdJdkMemoryClientTriggersCorrectHttpCall(WireMockRuntimeInfo wireMock) {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                          "userId": "alice"
                        }
                        """)));
        var client = MemoryClient.create(
                URI.create(wireMock.getHttpBaseUrl() + "/v1"), "test-api-key");

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
        var client = MemoryClient.create(
                URI.create(wireMock.getHttpBaseUrl() + "/v1"), "test-api-key");

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
}
