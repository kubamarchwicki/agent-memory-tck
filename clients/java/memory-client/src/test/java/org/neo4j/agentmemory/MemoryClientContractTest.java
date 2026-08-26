package org.neo4j.agentmemory;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.wiremock.junit5.OpenApiValidator;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MemoryClientContractTest {
    private static final OpenApiValidator VALIDATION =
            new OpenApiValidator(OpenApiContract.spec());

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().extensions(VALIDATION))
            .configureStaticDsl(true)
            .build();

    private MemoryClient client;

    @BeforeEach
    void startFromACleanContract() {
        VALIDATION.reset();
        client = MemoryClient.create(
                URI.create(WIRE_MOCK.baseUrl() + "/v1"), "nams_contract-test-key");
    }

    @AfterEach
    void everyExchangeHonouredTheContract() {
        OpenApiContract.assertOnlyDeclaredRequestProperties();
        VALIDATION.assertValidationPassed();
    }

    @Test
    void createConversation() {
        stubFor(post(urlEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations")));

        var conversation = client.createConversation(new CreateConversation("alice")).join();

        assertThat(conversation.id()).isNotNull();
    }
}
