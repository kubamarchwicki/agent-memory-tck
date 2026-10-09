package org.neo4j.agentmemory.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HttpCallTest {
    @Test void expandsVariablesIntoAnAbsoluteUri() {
        var id = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
        var call = HttpCall.get("https://memory.test/v1/conversations/{conversationId}/messages?limit={limit}",
                Map.of("conversationId", id, "limit", 20), Map.of());
        assertThat(call.uri()).isEqualTo(URI.create("https://memory.test/v1/conversations/" + id + "/messages?limit=20"));
    }

    @Test void rejectsVariablesOtherThanUuidOrInteger() {
        assertThatIllegalArgumentException().isThrownBy(() -> HttpCall.get(
                "https://memory.test/v1/x/{q}", Map.of("q", "a b"), Map.of()));
    }

    @Test void rejectsUnboundTemplateVariables() {
        var call = HttpCall.get("https://memory.test/v1/conversations/{conversationId}", Map.of(), Map.of());
        assertThatIllegalArgumentException().isThrownBy(call::uri);
    }
}
