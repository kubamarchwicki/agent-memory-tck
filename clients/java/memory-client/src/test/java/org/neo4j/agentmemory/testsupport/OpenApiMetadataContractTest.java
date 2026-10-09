package org.neo4j.agentmemory.testsupport;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class OpenApiMetadataContractTest {
    @Test
    void stillRejectsMisspelledTopLevelFields(WireMockRuntimeInfo server) throws Exception {
        stubFor(post(urlEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations")));
        var request = HttpRequest.newBuilder(URI.create(server.getHttpBaseUrl() + "/v1/conversations"))
                .header("Authorization", "Bearer key")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"metdata\":{\"title\":\"test\"}}"))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        assertThatThrownBy(OpenApiContract::assertOnlyDeclaredRequestProperties)
                .isInstanceOf(AssertionError.class).hasMessageContaining("metdata");
    }
}
