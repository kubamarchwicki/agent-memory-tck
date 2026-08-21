package org.neo4j.agentmemory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record NewToolCall(
        String toolName,
        String input,
        Optional<String> output,
        ToolCallStatus status,
        Optional<Duration> duration) {
    private NewToolCall(Builder builder) {
        this(
                builder.toolName,
                builder.input,
                Optional.ofNullable(builder.output),
                builder.status,
                Optional.ofNullable(builder.duration));
    }

    public NewToolCall {
        output = output == null ? Optional.empty() : output;
        duration = duration == null ? Optional.empty() : duration;
    }

    public static Builder builder(String toolName, String input) {
        return new Builder(toolName, input);
    }

    public static final class Builder {
        private final String toolName;
        private final String input;
        private String output;
        private ToolCallStatus status = ToolCallStatus.SUCCESS;
        private Duration duration;

        private Builder(String toolName, String input) {
            this.toolName = toolName;
            this.input = input;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder status(ToolCallStatus status) {
            this.status = Objects.requireNonNull(status, "status");
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public NewToolCall build() {
            return new NewToolCall(this);
        }
    }
}
