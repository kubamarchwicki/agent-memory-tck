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

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
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
        var client = JdkMemoryClient.create(
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
}
