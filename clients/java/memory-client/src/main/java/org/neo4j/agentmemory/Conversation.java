package org.neo4j.agentmemory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface Conversation {
    UUID id();

    String userId();

    CompletableFuture<Message> addMessage(NewMessage message);

    CompletableFuture<List<Message>> messages();

    CompletableFuture<ConversationContext> context();

    CompletableFuture<Void> delete();
}
