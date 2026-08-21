package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReasoningModelsTest {
    private static final UUID ID =
            UUID.fromString("15c63f73-f00f-45de-b62e-851ea483a552");
    private static final UUID STEP_ID =
            UUID.fromString("673139d8-dd48-48eb-a5a9-cc84e6e938e3");

    @Test
    void supportsReasoningStepsWithAndWithoutResult() {
        assertThat(NewReasoningStep.class.isRecord()).isTrue();
        assertThat(new NewReasoningStep("reason", "act").result()).isEmpty();
        assertThat(new NewReasoningStep("reason", "act", "done").result())
                .contains("done");
    }

    @Test
    void buildsSuccessfulToolCallsByDefault() {
        var request = NewToolCall.builder("memory_search_entities", "{\"query\":\"alice\"}")
                .output("{\"entities\":[]}")
                .duration(Duration.ofMillis(150))
                .build();

        assertThat(NewToolCall.class.isRecord()).isTrue();
        assertThat(request.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(request.output()).contains("{\"entities\":[]}");
        assertThat(request.duration()).contains(Duration.ofMillis(150));
    }

    @Test
    void toolCallsCompareByUuid() {
        var first = new ToolCall(
                ID, STEP_ID, "first", "{}", (String) null,
                ToolCallStatus.SUCCESS, null, null);
        var second = new ToolCall(
                ID, STEP_ID, "renamed", "{\"changed\":true}", "failed",
                ToolCallStatus.ERROR, Duration.ofMillis(1), Instant.EPOCH);

        assertThat(ToolCall.class.isRecord()).isTrue();
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
