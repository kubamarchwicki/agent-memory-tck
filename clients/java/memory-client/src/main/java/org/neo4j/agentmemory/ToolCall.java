package org.neo4j.agentmemory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ToolCall(
        UUID id,
        UUID stepId,
        String toolName,
        String input,
        Optional<String> output,
        ToolCallStatus status,
        Optional<Duration> duration,
        Optional<Instant> createdAt) {
    public ToolCall(
            UUID id,
            UUID stepId,
            String toolName,
            String input,
            String output,
            ToolCallStatus status,
            Duration duration,
            Instant createdAt) {
        this(
                id,
                stepId,
                toolName,
                input,
                Optional.ofNullable(output),
                status,
                Optional.ofNullable(duration),
                Optional.ofNullable(createdAt));
    }

    public ToolCall {
        output = output == null ? Optional.empty() : output;
        duration = duration == null ? Optional.empty() : duration;
        createdAt = createdAt == null ? Optional.empty() : createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ToolCall that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
