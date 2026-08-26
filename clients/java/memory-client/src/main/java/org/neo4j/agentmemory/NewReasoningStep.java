package org.neo4j.agentmemory;

import java.util.Optional;

public record NewReasoningStep(
        String reasoning,
        String actionTaken,
        String result) {
    public NewReasoningStep(String reasoning, String actionTaken) {
        this(reasoning, actionTaken, null);
    }

    public Optional<String> getResult() {
        return Optional.ofNullable(result);
    }
}
