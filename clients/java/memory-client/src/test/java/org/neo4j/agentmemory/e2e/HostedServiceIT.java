package org.neo4j.agentmemory.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.neo4j.agentmemory.conversation.NewMessage.user;
import static org.neo4j.agentmemory.conversation.NewMessage.assistant;
import static org.neo4j.agentmemory.conversation.MessageRole.ASSISTANT;
import static org.neo4j.agentmemory.conversation.MessageRole.USER;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.neo4j.agentmemory.conversation.Conversation;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.Message;
import org.neo4j.agentmemory.reasoning.NewReasoningStep;
import org.neo4j.agentmemory.reasoning.NewToolCall;
import org.neo4j.agentmemory.reasoning.ToolCallStatus;
import org.neo4j.agentmemory.e2e.assertions.ConversationAssert;
import org.neo4j.agentmemory.e2e.assertions.ConversationContextAssert;
import org.neo4j.agentmemory.e2e.assertions.MessageAssert;

/**
 * Focused end-to-end tests against the live hosted Neo4j Agent Memory Service.
 *
 * <p>
 * The suite exercises only the initial conversation workflows. Maven Failsafe
 * discovers it
 * during {@code verify}; every workflow is marked skipped when
 * {@code MEMORY_API_KEY} is absent or
 * blank. Each workflow creates uniquely identified data and owns all state it
 * uses. Workflows are
 * parallel-safe but run sequentially by default.
 *
 * <p>
 * There is deliberately no cleanup harness or provenance tagging. Conversation
 * deletion is a
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

                var conversations = client.listConversations(new ListConversations(200)).join();

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

                var firstUser = conversation
                                .addMessages(List.of(user("java e2e user " + marker)))
                                .join()
                                .get(0);
                var firstAssistant = conversation
                                .addMessage(assistant("java e2e assistant " + marker))
                                .join();
                var exchange = conversation
                                .addMessages(List.of(
                                                user("java e2e follow-up user " + marker),
                                                assistant("java e2e follow-up assistant " + marker)))
                                .join();

                assertThat(exchange)
                                .extracting(Message::role)
                                .containsExactly(USER, ASSISTANT);

                MessageAssert.assertThat(firstUser)
                                .hasId()
                                .hasRole(USER)
                                .hasContent("java e2e user " + marker);
                MessageAssert.assertThat(firstAssistant)
                                .hasId()
                                .hasRole(ASSISTANT)
                                .hasContent("java e2e assistant " + marker);

                assertThat(conversation.messages().join())
                                .extracting(Message::id)
                                .contains(firstUser.id(), firstAssistant.id());

                var context = conversation.context().join();

                ConversationContextAssert.assertThat(context)
                                .hasThreeTiers()
                                .containsRecentMessages(firstUser.id(), firstAssistant.id());
        }

        @Test
        void deletesConversation() {
                var client = client();
                var created = client.createConversation(new CreateConversation(uniqueUserId())).join();

                assertThatCode(() -> created.delete().join()).doesNotThrowAnyException();
        }

        @Test
        void supportsMemorySkillPrimitivesAndReasoningTrace() {
                var marker = UUID.randomUUID().toString();
                var client = client();
                var conversation = client.createConversation(new CreateConversation()).join();

                assertThat(conversation.userId()).isEmpty();

                var priorEntities = client.searchEntities(marker).join();
                assertThatThrownBy(priorEntities::clear)
                                .isInstanceOf(UnsupportedOperationException.class);

                var firstUser = conversation
                                .addMessages(List.of(user("first user " + marker)))
                                .join()
                                .get(0);
                var firstAssistant = conversation
                                .addMessage(assistant("first assistant " + marker))
                                .join();
                var exchange = conversation
                                .addMessages(List.of(
                                                user("next user " + marker),
                                                assistant("next assistant " + marker)))
                                .join();

                assertThat(exchange)
                                .extracting(Message::role)
                                .containsExactly(USER, ASSISTANT);

                var step = conversation
                                .recordStep(new NewReasoningStep(
                                                "Application supplied reasoning " + marker,
                                                "Search prior entities",
                                                "Search completed"))
                                .join();

                var call = step.recordToolCall(NewToolCall
                                .builder(
                                                "memory_search_entities",
                                                "{\"query\":\"" + marker + "\"}")
                                .output("{\"entities\":[]}")
                                .status(ToolCallStatus.SUCCESS)
                                .duration(Duration.ofMillis(25))
                                .build())
                                .join();

                assertThat(call.stepId()).isEqualTo(step.id());
                assertThat(call.input()).contains(marker);
                assertThat(call.duration()).isEqualTo(Duration.ofMillis(25));
                assertThat(call.getDuration()).contains(Duration.ofMillis(25));

                var trace = conversation.trace().join();

                assertThat(trace.conversationId()).isEqualTo(conversation.id());
                assertThat(trace.steps()).contains(step);
                assertThat(trace.toolCalls(step)).contains(call);

                var explanation = step.explanation().join();

                assertThat(explanation.step()).isEqualTo(step);
                assertThat(explanation.toolCalls()).contains(call);
                assertThat(explanation.influencedEntities()).isNotNull();

                assertThat(conversation.messages().join())
                                .extracting(Message::id)
                                .contains(
                                                firstUser.id(),
                                                firstAssistant.id(),
                                                exchange.get(0).id(),
                                                exchange.get(1).id());
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
