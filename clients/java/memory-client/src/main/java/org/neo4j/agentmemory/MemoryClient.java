package org.neo4j.agentmemory;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MemoryClient {
    static MemoryClient create(String apiKey) {
        var configuredEndpoint = System.getenv("MEMORY_ENDPOINT");
        var endpoint = URI.create(configuredEndpoint == null || configuredEndpoint.isBlank()
                ? "https://memory.neo4jlabs.com/v1"
                : configuredEndpoint.trim());
        return create(endpoint, apiKey);
    }

    static MemoryClient create(URI endpoint, String apiKey) {
        return JdkMemoryClient.create(endpoint, apiKey);
    }

    CompletableFuture<Conversation> createConversation(CreateConversation request);

    CompletableFuture<List<Conversation>> listConversations(ListConversations request);

    CompletableFuture<Conversation> getConversation(UUID conversationId);

    default CompletableFuture<List<Entity>> searchEntities(String query) {
        return searchEntities(new EntitySearch(query));
    }

    CompletableFuture<List<Entity>> searchEntities(EntitySearch search);
}
