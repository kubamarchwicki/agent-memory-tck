package org.neo4j.agentmemory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.MessageRole.USER;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class Jackson3JsonCodecTest {
    private static final UUID CONVERSATION_ID =
            UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final UUID MESSAGE_ID =
            UUID.fromString("3b3af5b6-2bff-41fc-bdb2-c72e63822d47");

    private final JsonCodec codec = new Jackson3JsonCodec();

    @Test
    void encodesHostedMessageRequestAndLowercaseRole() {
        var messageJson = new String(codec.encode(new NewMessage(USER, "hello")), UTF_8);

        assertThat(messageJson).isEqualTo("{\"role\":\"user\",\"content\":\"hello\"}");
    }

    @Test
    void decodesRichHostedResponseValuesAndIgnoresUnknownFields() {
        var json = """
                {
                  "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                  "userId": "alice",
                  "workspaceId": "ignored-by-this-slice"
                }
                """;

        var conversation = codec.decode(json.getBytes(UTF_8), ConversationFixture.class);

        assertThat(conversation.id()).isEqualTo(CONVERSATION_ID);
        assertThat(conversation.userId()).isEqualTo("alice");
    }

    @Test
    void decodesHostedContextSnippetThroughFacade() {
        var json = """
                {
                  "reflections": [{
                    "id": "13b46f84-e2b5-4bb5-bce3-77783824196d",
                    "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                    "content": "user values clarity"
                  }],
                  "observations": [{
                    "id": "d37335af-aa1f-4b56-be19-b99c7b7f089e",
                    "conversationId": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                    "content": "long-form messages"
                  }],
                  "recentMessages": [{
                    "id": "3b3af5b6-2bff-41fc-bdb2-c72e63822d47",
                    "role": "user",
                    "content": "hello"
                  }]
                }
                """;

        var context = codec.decode(json.getBytes(UTF_8), ConversationContext.class);

        assertThat(context.reflections()).singleElement()
                .satisfies(reflection -> assertThat(reflection.content()).isEqualTo("user values clarity"));
        assertThat(context.observations()).singleElement()
                .satisfies(observation -> assertThat(observation.content()).isEqualTo("long-form messages"));
        assertThat(context.recentMessages()).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo(MESSAGE_ID);
            assertThat(message.role()).isEqualTo(USER);
            assertThat(message.content()).isEqualTo("hello");
        });
    }

    @Test
    void rejectsMismatchedScalarTypes() {
        var json = """
                {
                  "id": "a2f55d70-838f-4c41-ae7d-dad30fd25720",
                  "userId": 42
                }
                """;
        assertThatThrownBy(() -> codec.decode(json.getBytes(UTF_8), ConversationFixture.class))
                .isInstanceOf(JsonCodecException.class).hasMessage("Could not decode JSON");
    }

    @Test
    void rejectsNumericEnums() {
        var json = """
                {
                  "id": "3b3af5b6-2bff-41fc-bdb2-c72e63822d47",
                  "role": 0,
                  "content": "hello"
                }
                """;
        assertThatThrownBy(() -> codec.decode(json.getBytes(UTF_8), Message.class))
                .isInstanceOf(JsonCodecException.class).hasMessage("Could not decode JSON");
    }

    @Test
    void encodesAndDecodesHostedToolCallStatuses() {
        var encoded = new String(
                codec.encode(new ToolStatusFixture(ToolCallStatus.SUCCESS)), UTF_8);

        assertThat(encoded).isEqualTo("{\"status\":\"success\"}");

        var decoded = codec.decode(
                "{\"status\":\"timeout\"}".getBytes(UTF_8),
                ToolStatusFixture.class);

        assertThat(decoded.status()).isEqualTo(ToolCallStatus.TIMEOUT);
    }

    private record ConversationFixture(UUID id, String userId) {}

    private record ToolStatusFixture(ToolCallStatus status) {}
}
