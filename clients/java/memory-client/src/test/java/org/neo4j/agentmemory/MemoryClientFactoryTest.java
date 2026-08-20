package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class MemoryClientFactoryTest {
    @Test
    void createsClientForExplicitEndpointAndApiKey() {
        assertThat(MemoryClient.create(URI.create("https://memory.test/v1"), "key"))
                .isNotNull();
    }

    @Test
    void rejectsNullOrEmptyExplicitEndpoint() {
        assertThatThrownBy(() -> MemoryClient.create((URI) null, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint must not be null or empty");
        assertThatThrownBy(() -> MemoryClient.create(URI.create(""), "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint must not be null or empty");
    }

    @Test
    void rejectsNullOrBlankApiKey() {
        assertThatThrownBy(() -> MemoryClient.create(URI.create("https://memory.test/v1"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("apiKey must not be blank");
        assertThatThrownBy(() -> MemoryClient.create(URI.create("https://memory.test/v1"), " \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("apiKey must not be blank");
    }
}
