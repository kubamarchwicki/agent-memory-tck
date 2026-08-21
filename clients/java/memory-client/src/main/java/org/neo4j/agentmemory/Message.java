package org.neo4j.agentmemory;

import java.util.UUID;

public record Message(UUID id, MessageRole role, String content) {
    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Message that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
