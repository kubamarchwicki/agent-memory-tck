package org.neo4j.agentmemory;

import java.util.Optional;

public record CreateConversation(String userId) {
    public CreateConversation() {
        this((String) null);
    }

    public CreateConversation {
        if (userId != null) {
            userId = userId.trim();
            if (userId.isEmpty()) {
                userId = null;
            }
        }
    }

    public Optional<String> getUserId() {
        return Optional.ofNullable(userId);
    }
}
