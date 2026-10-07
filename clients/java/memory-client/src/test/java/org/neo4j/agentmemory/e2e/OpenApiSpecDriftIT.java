package org.neo4j.agentmemory.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenApiSpecDriftIT {
    private static final URI PUBLISHED =
            URI.create("https://memory.neo4jlabs.com/openapi.json");

    @Test
    @Timeout(30)
    void vendoredContractMatchesThePublishedContract() throws Exception {
        var published = fetchPublished();
        var vendored = vendored();

        assertThat(published)
                .as("memory-client/src/test/resources/openapi.json has fallen behind %s; re-vendor it with "
                        + "curl -o memory-client/src/test/resources/openapi.json %s", PUBLISHED, PUBLISHED)
                .isEqualTo(vendored);
    }

    private static String fetchPublished() throws InterruptedException {
        var request = HttpRequest.newBuilder(PUBLISHED)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        var http = HttpClient.newHttpClient();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return abort("published contract returned " + response.statusCode());
            }
            return response.body();
        } catch (IOException unreachable) {
            return abort("published contract is unreachable: " + unreachable.getMessage());
        }
    }

    private static String vendored() throws IOException {
        try (var stream = OpenApiSpecDriftIT.class.getResourceAsStream("/openapi.json")) {
            assertThat(stream).as("openapi.json is missing from the test classpath").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
