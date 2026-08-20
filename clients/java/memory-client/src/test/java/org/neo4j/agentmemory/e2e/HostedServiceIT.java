package org.neo4j.agentmemory.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.neo4j.agentmemory.MessageRole.ASSISTANT;
import static org.neo4j.agentmemory.MessageRole.USER;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.neo4j.agentmemory.Conversation;
import org.neo4j.agentmemory.CreateConversation;
import org.neo4j.agentmemory.ListConversations;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.Message;
import org.neo4j.agentmemory.NewMessage;
import org.neo4j.agentmemory.e2e.assertions.ConversationAssert;
import org.neo4j.agentmemory.e2e.assertions.ConversationContextAssert;
import org.neo4j.agentmemory.e2e.assertions.MessageAssert;

/**
 * Focused end-to-end tests against the live hosted Neo4j Agent Memory Service.
 *
 * <p>The suite exercises only the initial conversation workflows. Maven Failsafe discovers it
 * during {@code verify}; every workflow is marked skipped when {@code MEMORY_API_KEY} is absent or
 * blank. Each workflow creates uniquely identified data and owns all state it uses. Workflows are
 * parallel-safe but run sequentially by default.
 *
 * <p>There is deliberately no cleanup harness or provenance tagging. Conversation deletion is a
 * scenario in its own right, not teardown for other scenarios.
 */
@Timeout(value = 30, unit = SECONDS)
class HostedServiceIT {
    private static final String API_KEY = environment("MEMORY_API_KEY", "").trim();

    @BeforeEach
    void requireApiKey() {
        assumeFalse(API_KEY.isBlank(), "MEMORY_API_KEY is not set");
    }

    @Test
    void createsAndListsConversation() {
        var userId = uniqueUserId();
        var client = client();

        var created = client.createConversation(new CreateConversation(userId)).join();

        ConversationAssert.assertThat(created)
                .hasId()
                .hasUserId(userId);

        var conversations = client.listConversations(new ListConversations(userId, 200)).join();

        assertThat(conversations)
                .extracting(Conversation::id)
                .contains(created.id());
    }

    @Test
    void retrievesConversationAndRoundTripsMessagesAndContext() {
        var userId = uniqueUserId();
        var marker = UUID.randomUUID().toString();
        var client = client();
        var created = client.createConversation(new CreateConversation(userId)).join();

        var conversation = client.getConversation(created.id()).join();

        ConversationAssert.assertThat(conversation)
                .hasId(created.id())
                .hasUserId(userId);

        var userMessage = conversation
                .addMessage(new NewMessage(USER, "java e2e user " + marker))
                .join();
        var assistantMessage = conversation
                .addMessage(new NewMessage(ASSISTANT, "java e2e assistant " + marker))
                .join();

        MessageAssert.assertThat(userMessage)
                .hasId()
                .hasRole(USER)
                .hasContent("java e2e user " + marker);
        MessageAssert.assertThat(assistantMessage)
                .hasId()
                .hasRole(ASSISTANT)
                .hasContent("java e2e assistant " + marker);

        assertThat(conversation.messages().join())
                .extracting(Message::id)
                .contains(userMessage.id(), assistantMessage.id());

        var context = conversation.context().join();

        ConversationContextAssert.assertThat(context)
                .hasThreeTiers()
                .containsRecentMessages(userMessage.id(), assistantMessage.id());
    }

    @Test
    void deletesConversation() {
        var client = client();
        var created = client.createConversation(new CreateConversation(uniqueUserId())).join();

        assertThatCode(() -> created.delete().join()).doesNotThrowAnyException();
    }

    private static MemoryClient client() {
        return MemoryClient.create(API_KEY);
    }

    private static String uniqueUserId() {
        return "tck-e2e-java-" + UUID.randomUUID();
    }

    private static String environment(String name, String fallback) {
        var value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
