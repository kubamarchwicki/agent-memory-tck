package org.neo4j.agentmemory;

import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;
import org.neo4j.agentmemory.testsupport.HttpClientUnderTest;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.entity.EntitySearch;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@ParameterizedClass
@EnumSource(HttpClientUnderTest.class)
class MemoryClientContractTest {
    private final HttpClientUnderTest httpClient;

    MemoryClientContractTest(HttpClientUnderTest httpClient) {
        this.httpClient = httpClient;
    }

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private MemoryClient client;

    @BeforeEach
    void startFromACleanContract() {
        client = MemoryClient.create(httpClient.configure(MemoryClientConfiguration.builder()
                .baseUrl(WIRE_MOCK.baseUrl() + "/v1").apiKey("nams_contract-test-key")).build());
    }

    @AfterEach
    void everyExchangeHonouredTheContract() {
        OpenApiContract.assertOnlyDeclaredRequestProperties();
        OpenApiContract.assertEveryExchangeMatchesTheContract();
    }

    @Test
    void createConversation() {
        stubFor(post(urlEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations")));

        var conversation = client.createConversation(new CreateConversation("alice")).join();

        assertThat(conversation.id()).isNotNull();
    }

    @Test
    void listConversations() {
        stubFor(get(urlPathEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations")));

        var conversations = client.listConversations(new ListConversations(25)).join();

        assertThat(conversations).isNotEmpty();
    }

    @Test
    void getConversation() {
        var conversationId = UUID.randomUUID();
        stubFor(get(urlEqualTo("/v1/conversations/" + conversationId))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}")));

        var conversation = client.getConversation(conversationId).join();

        assertThat(conversation.id()).isNotNull();
    }

    @Test
    void deleteConversation() {
        var conversation = conversation();
        stubFor(delete(urlEqualTo("/v1/conversations/" + conversation.id()))
                .willReturn(OpenApiContract.response("delete", "/v1/conversations/{id}")));

        assertThatNoException().isThrownBy(() -> conversation.delete().join());
    }

    @Test
    void addMessage() {
        var conversation = conversation();
        stubFor(post(urlEqualTo("/v1/conversations/" + conversation.id() + "/messages"))
                .willReturn(OpenApiContract.response(
                        "post", "/v1/conversations/{id}/messages")));

        var message = conversation.addMessage(NewMessage.user("hello")).join();

        assertThat(message.id()).isNotNull();
    }

    @Test
    void addMessagesInBulk() {
        var conversation = conversation();
        stubFor(post(urlEqualTo("/v1/conversations/" + conversation.id() + "/messages/bulk"))
                .willReturn(OpenApiContract.response(
                        "post", "/v1/conversations/{id}/messages/bulk")));

        var messages = conversation
                .addMessages(List.of(NewMessage.user("hello"), NewMessage.assistant("hi")))
                .join();

        assertThat(messages).isNotEmpty();
    }

    @Test
    void listMessages() {
        var conversation = conversation();
        stubFor(get(urlPathEqualTo("/v1/conversations/" + conversation.id() + "/messages"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}/messages")));

        assertThat(conversation.messages().join()).isNotEmpty();
    }

    @Test
    void getContext() {
        var conversation = conversation();
        stubFor(get(urlEqualTo("/v1/conversations/" + conversation.id() + "/context"))
                .willReturn(OpenApiContract.response("get", "/v1/conversations/{id}/context")));

        var context = conversation.context().join();

        assertThat(context.recentMessages()).isNotEmpty();
    }

    @Test
    void searchEntities() {
        stubFor(post(urlEqualTo("/v1/entities/search"))
                .willReturn(OpenApiContract.response("post", "/v1/entities/search")));

        assertThat(client.searchEntities(new EntitySearch("neo4j", "person", 5)).join())
                .isNotEmpty();
    }

    @Test
    void recordReasoningStep() {
        stubFor(post(urlEqualTo("/v1/reasoning/steps"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/steps")));

        var step = conversation().recordStep(new NewReasoningStep("why", "what", "done")).join();

        assertThat(step.id()).isNotNull();
    }

    @Test
    void recordToolCall() {
        stubFor(post(urlEqualTo("/v1/reasoning/steps"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/steps")));
        stubFor(post(urlEqualTo("/v1/reasoning/tool-calls"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/tool-calls")));

        var step = conversation().recordStep(new NewReasoningStep("why", "what")).join();
        var call = step.recordToolCall(NewToolCall.builder("search", "{}")
                        .output("{}")
                        .duration(Duration.ofMillis(150))
                        .status(ToolCallStatus.SUCCESS)
                        .build())
                .join();

        assertThat(call.id()).isNotNull();
    }

    @Test
    void getReasoningTrace() {
        var conversation = conversation();
        stubFor(get(urlEqualTo("/v1/reasoning/trace/" + conversation.id()))
                .willReturn(OpenApiContract.response(
                        "get", "/v1/reasoning/trace/{conversationId}")));

        assertThat(conversation.trace().join().steps()).isNotEmpty();
    }

    @Test
    void explainReasoningStep() {
        stubFor(post(urlEqualTo("/v1/reasoning/steps"))
                .willReturn(OpenApiContract.response("post", "/v1/reasoning/steps")));
        var step = conversation().recordStep(new NewReasoningStep("why", "what")).join();
        stubFor(get(urlEqualTo("/v1/reasoning/explain/" + step.id()))
                .willReturn(OpenApiContract.response("get", "/v1/reasoning/explain/{stepId}")));

        assertThat(step.explanation().join().toolCalls()).isNotEmpty();
    }

    private Conversation conversation() {
        stubFor(post(urlEqualTo("/v1/conversations"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations")));
        return client.createConversation(new CreateConversation()).join();
    }
}
