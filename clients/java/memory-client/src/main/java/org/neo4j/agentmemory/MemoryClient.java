package org.neo4j.agentmemory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MemoryClient {
    static MemoryClient create(String apiKey) {
        if (apiKey == null) {
            throw new NullPointerException("apiKey");
        }
        var configuredEndpoint = System.getenv("MEMORY_ENDPOINT");
        var endpoint = URI.create(configuredEndpoint == null || configuredEndpoint.isBlank()
                ? "https://memory.neo4jlabs.com/v1"
                : configuredEndpoint.trim());
        throw new UnsupportedOperationException("No NAMS endpoint client is available for " + endpoint);
    }

    CompletableFuture<Conversation> createConversation(CreateConversation request);

    CompletableFuture<List<Conversation>> listConversations(ListConversations request);
}
