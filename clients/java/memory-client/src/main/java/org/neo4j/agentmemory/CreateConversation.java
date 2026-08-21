package org.neo4j.agentmemory;

import java.util.Optional;

public record CreateConversation(Optional<String> userId) {
    public CreateConversation() {
        this(Optional.empty());
    }

    public CreateConversation(String userId) {
        this(Optional.ofNullable(userId));
    }

    public CreateConversation {
        userId = (userId == null ? Optional.<String>empty() : userId)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}
