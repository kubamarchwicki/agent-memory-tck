package org.neo4j.agentmemory;

import java.util.Optional;
import java.util.UUID;

public record Entity(
        UUID id,
        String name,
        String type,
        Optional<String> description) {
    public Entity(UUID id, String name, String type, String description) {
        this(id, name, type, Optional.ofNullable(description));
    }

    public Entity {
        description = description == null ? Optional.empty() : description;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Entity that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
