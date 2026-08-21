package org.neo4j.agentmemory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class Conversation {
    private final JdkMemoryClient client;
    private final UUID id;
    private final Optional<String> userId;

    Conversation(JdkMemoryClient client, UUID id, String userId) {
        this.client = client;
        this.id = id;
        this.userId = Optional.ofNullable(userId)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    public UUID id() {
        return id;
    }

    public Optional<String> userId() {
        return userId;
    }

    public CompletableFuture<Message> addMessage(NewMessage message) {
        return client.addMessage(id, message);
    }

    public CompletableFuture<List<Message>> addMessages(List<NewMessage> messages) {
        return client.addMessages(id, messages);
    }

    public CompletableFuture<List<Message>> messages() {
        return client.messages(id);
    }

    public CompletableFuture<ConversationContext> context() {
        return client.context(id);
    }

    public CompletableFuture<Void> delete() {
        return client.deleteConversation(id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Conversation that
                        && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
