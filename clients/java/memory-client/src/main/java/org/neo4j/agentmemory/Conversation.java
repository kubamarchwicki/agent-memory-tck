package org.neo4j.agentmemory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class Conversation {
    private final JdkMemoryClient client;
    private final UUID id;
    private final String userId;

    Conversation(JdkMemoryClient client, UUID id, String userId) {
        this.client = client;
        this.id = id;
        this.userId = userId;
    }

    public UUID id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public CompletableFuture<Message> addMessage(NewMessage message) {
        return client.addMessage(id, message);
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
}
