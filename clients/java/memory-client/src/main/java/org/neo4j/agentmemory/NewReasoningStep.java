package org.neo4j.agentmemory;

import java.util.Optional;

public record NewReasoningStep(
        String reasoning,
        String actionTaken,
        Optional<String> result) {
    public NewReasoningStep(String reasoning, String actionTaken) {
        this(reasoning, actionTaken, Optional.empty());
    }

    public NewReasoningStep(String reasoning, String actionTaken, String result) {
        this(reasoning, actionTaken, Optional.ofNullable(result));
    }

    public NewReasoningStep {
        result = result == null ? Optional.empty() : result;
    }
}
