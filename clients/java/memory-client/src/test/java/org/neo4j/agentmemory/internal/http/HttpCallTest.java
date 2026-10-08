package org.neo4j.agentmemory.internal.http;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HttpCallTest {
    @Test void expandsVariablesIntoAnAbsoluteUri() {
        var id = UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
        var call = new HttpCall("GET", "https://memory.test/v1/conversations/{conversationId}/messages?limit={limit}",
                Map.of("conversationId", id, "limit", 20), Map.of(), null);
        assertThat(call.uri()).isEqualTo(URI.create("https://memory.test/v1/conversations/" + id + "/messages?limit=20"));
    }

    @Test void rejectsVariablesOtherThanUuidOrInteger() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HttpCall("GET",
                "https://memory.test/v1/x/{q}", Map.of("q", "a b"), Map.of(), null));
    }

    @Test void rejectsUnboundTemplateVariables() {
        var call = new HttpCall("GET", "https://memory.test/v1/conversations/{conversationId}", Map.of(), Map.of(), null);
        assertThatIllegalArgumentException().isThrownBy(call::uri);
    }

    @Test void rejectsUnsupportedMethods() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HttpCall("PUT",
                "https://memory.test/v1/conversations", Map.of(), Map.of(), null));
    }
}
