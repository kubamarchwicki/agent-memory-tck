package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordStateTest {
    @Test
    void nullableRecordComponentsUseDomainTypesRatherThanOptional() {
        var records = List.of(
                CreateConversation.class,
                Entity.class,
                NewReasoningStep.class,
                NewToolCall.class,
                ToolCall.class);

        assertThat(records).allSatisfy(record -> {
            assertThat(record.isRecord()).isTrue();
            var componentTypes = Arrays.stream(record.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getType)
                    .toList();
            assertThat(componentTypes)
                    .as("record component types for %s", record.getSimpleName())
                    .doesNotContain(Optional.class);
        });
    }
}
