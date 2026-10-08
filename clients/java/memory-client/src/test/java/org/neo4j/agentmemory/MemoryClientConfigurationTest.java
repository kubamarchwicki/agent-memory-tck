package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.agentmemory.testsupport.EnvironmentProbe;

class MemoryClientConfigurationTest {
    @Test
    void rejectsNullRestClientArguments() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder()
                .restClient(null, org.neo4j.agentmemory.testsupport.HttpClientUnderTest.TEST_EXECUTOR))
                .isInstanceOf(NullPointerException.class).hasMessage("restClient");
        assertThatThrownBy(() -> MemoryClientConfiguration.builder()
                .restClient(org.springframework.web.client.RestClient.create(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("executor");
    }

    @Test
    void rejectsNullJdkHttpClient() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().jdkHttpClient(null))
                .isInstanceOf(NullPointerException.class).hasMessage("httpClient");
    }

    @Test
    void configurationWithoutHttpClientHasNoTransport() {
        assertThat(MemoryClientConfiguration.builder().baseUrl("https://memory.test/v1")
                .apiKey("key").build().httpTransport()).isNull();
    }

    @Test
    void jdkHttpClientSelectsJdkTransport() {
        assertThat(MemoryClientConfiguration.builder().baseUrl("https://memory.test/v1")
                .apiKey("key").jdkHttpClient(HttpClient.newHttpClient())
                .build().httpTransport().name()).isEqualTo("jdk-http");
    }

    @Test
    void rejectsNullOverridesInSetters() {
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().apiKey(null))
                .isInstanceOf(NullPointerException.class).hasMessage("apiKey");
        assertThatThrownBy(() -> MemoryClientConfiguration.builder().baseUrl(null))
                .isInstanceOf(NullPointerException.class).hasMessage("baseUrl");
    }

    @Test
    void buildsConfigurationWithExplicitValues() {
        var configuration = MemoryClientConfiguration.builder()
                .apiKey("workspace-key")
                .baseUrl("  https://memory.test/v1  ")
                .build();

        assertThat(configuration.apiKey()).isEqualTo("workspace-key");
        assertThat(configuration.baseUrl()).isEqualTo(URI.create("https://memory.test/v1"));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsBlankKeyWhenBuilding(String apiKey) {
        var builder = MemoryClientConfiguration.builder().baseUrl("https://memory.test/v1").apiKey(apiKey);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("apiKey must not be blank");
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "not a URL", "/v1", "ftp://memory.test/v1", "https:///v1",
            "https://user:password@memory.test/v1", "https://memory.test/v1?key=secret",
            "https://memory.test/v1#fragment"})
    void rejectsInvalidBaseUrlWhenBuilding(String baseUrl) {
        var builder = MemoryClientConfiguration.builder().apiKey("workspace-key").baseUrl(baseUrl);
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must be an absolute HTTP(S) URL without user info, query, or fragment");
    }

    @Test
    void emptyBuilderResolvesEnvironmentWhenBuilt() throws Exception {
        assertThat(EnvironmentProbe.run(ConfigurationProbe.class,
                Map.of("NAMS_API_KEY", "environment-key", "NAMS_BASE_URL", "  https://environment.test/v1  ")))
                .contains("https://environment.test/v1", "key-matches=true")
                .doesNotContain(" WARN ");
    }

    @Test
    void defaultsBaseUrlWhenEnvironmentIsAbsentOrBlank() throws Exception {
        for (var environment : java.util.List.of(Map.of("NAMS_API_KEY", "environment-key"),
                Map.of("NAMS_API_KEY", "environment-key", "NAMS_BASE_URL", " \t"))) {
            assertThat(EnvironmentProbe.run(ConfigurationProbe.class, environment))
                    .contains("https://memory.neo4jlabs.com/v1", "key-matches=true", " WARN ",
                            "NAMS_BASE_URL is absent or blank; using default base URL")
                    .doesNotContain("environment-key");
        }
    }

    @Test
    void validatesEnvironmentDuringBuild() throws Exception {
        assertThat(EnvironmentProbe.run(ConfigurationProbe.class, Map.of()))
                .contains("apiKey must not be blank").doesNotContain(" WARN ");
        assertThat(EnvironmentProbe.run(ConfigurationProbe.class, Map.of("NAMS_API_KEY", " \t")))
                .contains("apiKey must not be blank");
        assertThat(EnvironmentProbe.run(ConfigurationProbe.class,
                Map.of("NAMS_API_KEY", "environment-key", "NAMS_BASE_URL", "invalid URL")))
                .contains("baseUrl must be an absolute HTTP(S) URL");
    }

    @Test
    void builtConfigurationDoesNotChangeWhenBuilderIsReused() {
        var builder = MemoryClientConfiguration.builder()
                .apiKey("first-key").baseUrl("https://first.test/v1");
        var first = builder.build();
        var second = builder.apiKey("second-key").baseUrl("https://second.test/v1").build();

        assertThat(first.apiKey()).isEqualTo("first-key");
        assertThat(first.baseUrl()).isEqualTo(URI.create("https://first.test/v1"));
        assertThat(second.apiKey()).isEqualTo("second-key");
        assertThat(second.baseUrl()).isEqualTo(URI.create("https://second.test/v1"));
    }

    public static class ConfigurationProbe {
        public static void main(String[] args) {
            try {
                var configuration = MemoryClientConfiguration.builder().build();
                System.out.println(configuration.baseUrl());
                System.out.println("key-matches=" + configuration.apiKey().equals("environment-key"));
            } catch (IllegalArgumentException failure) {
                System.out.println(failure.getMessage());
            }
        }
    }
}
