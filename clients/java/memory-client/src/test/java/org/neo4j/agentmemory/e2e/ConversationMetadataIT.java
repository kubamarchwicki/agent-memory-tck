package org.neo4j.agentmemory.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.conversation.ListConversations;
import org.neo4j.agentmemory.MemoryClient;

class ConversationMetadataIT {
    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void titleSurvivesFreshClientDetailAndListReads() throws Exception {
        var key = System.getenv("MEMORY_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "MEMORY_API_KEY is not set");
        var configured = System.getenv("MEMORY_ENDPOINT");
        var endpoint = URI.create(configured == null || configured.isBlank()
                ? "https://memory.neo4jlabs.com/v1" : configured.trim());
        var creator = MemoryClient.create(endpoint, key);
        var title = "Find hotels in Zermatt";
        var created = creator.createConversation(new CreateConversation(
                "java-metadata-" + UUID.randomUUID(), Map.of("title", title)))
                .get(20, TimeUnit.SECONDS);
        try {
            var reader = MemoryClient.create(endpoint, key);
            var detail = reader.getConversation(created.id()).get(20, TimeUnit.SECONDS);
            var listed = reader.listConversations(new ListConversations(50))
                    .get(20, TimeUnit.SECONDS).stream()
                    .filter(c -> c.id().equals(created.id())).findFirst().orElseThrow();
            assertThat(detail.id()).isEqualTo(created.id());
            assertThat(listed.id()).isEqualTo(created.id());
            assertThat(detail.title()).contains(title);
            assertThat(listed.title()).contains(title);
            assertThat(detail.metadata()).containsEntry("title", title);
            assertThat(listed.metadata()).containsEntry("title", title);
            assertThat(detail.createdAt()).isPresent();
            assertThat(detail.updatedAt()).isPresent();
            assertThat(listed.createdAt()).isEqualTo(detail.createdAt());
            assertThat(listed.updatedAt()).isEqualTo(detail.updatedAt());
        } finally {
            created.delete().get(20, TimeUnit.SECONDS);
        }
    }
}
