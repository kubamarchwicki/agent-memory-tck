package org.neo4j.agentmemory;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.neo4j.agentmemory.conversation.NewMessage;
import org.neo4j.agentmemory.exception.MissingJsonCodecException;
import org.neo4j.agentmemory.testsupport.EnvironmentProbe;
import org.neo4j.agentmemory.testsupport.OpenApiContract;

@WireMockTest
class JsonCodecSelectionTest {
    @Test
    void jackson3Only(WireMockRuntimeInfo server) throws Exception {
        assertThat(readAndWrite(server, "jdk", "jackson-databind-2", "jackson-core-2"))
                .contains("json=jackson3");
    }

    @Test
    void jackson2OnlyWithLangChain4j(WireMockRuntimeInfo server) throws Exception {
        assertThat(readAndWrite(server, "langchain4j", "jackson-databind-3", "jackson-core-3"))
                .contains("json=jackson2", "transport=langchain4j-http");
    }

    @Test
    void bothMajorsPreferJackson3(WireMockRuntimeInfo server) throws Exception {
        assertThat(readAndWrite(server, "jdk")).contains("json=jackson3");
    }

    @Test
    void brokenJackson3FallsBackToJackson2(WireMockRuntimeInfo server) throws Exception {
        assertThat(readAndWrite(server, "jdk", "jackson-core-3"))
                .contains("json=jackson2");
    }

    @Test
    void neitherMajorFailsWithBothCoordinates(WireMockRuntimeInfo server) throws Exception {
        var output = EnvironmentProbe.runOnClasspath(
                EnvironmentProbe.classpathWithout("jackson-databind-", "jackson-core-"),
                SelectionProbe.class, environment(server), "create");

        assertThat(output.lines()).contains(
                "MissingJsonCodecException: No supported JSON codec is available; add tools.jackson.core:jackson-databind 3.1.4+ or com.fasterxml.jackson.core:jackson-databind 2.19.0+",
                "suppressed=2");
        assertThat(output).doesNotContain("client.initialized");
    }

    private static String readAndWrite(WireMockRuntimeInfo server, String mode, String... excluded)
            throws Exception {
        var response = OpenApiContract.response("get", "/v1/conversations/{id}");
        var id = UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.build().getBody()).get("id").asString());
        var path = "/v1/conversations/" + id;
        stubFor(get(urlEqualTo(path)).willReturn(response));
        stubFor(post(urlEqualTo(path + "/messages"))
                .willReturn(OpenApiContract.response("post", "/v1/conversations/{id}/messages")));

        var output = EnvironmentProbe.runOnClasspath(EnvironmentProbe.classpathWithout(excluded),
                SelectionProbe.class, environment(server), mode, id.toString());

        assertThat(output.lines()).contains("read=" + id, "written=user");
        verify(getRequestedFor(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer workspace-key")));
        verify(postRequestedFor(urlEqualTo(path + "/messages"))
                .withHeader("Authorization", equalTo("Bearer workspace-key"))
                .withRequestBody(matchingJsonPath("$.content", equalTo("zażółć"))));
        OpenApiContract.assertEveryExchangeMatchesTheContract();
        OpenApiContract.assertOnlyDeclaredRequestProperties();
        return output;
    }

    private static Map<String, String> environment(WireMockRuntimeInfo server) {
        return Map.of("NAMS_API_KEY", "workspace-key", "NAMS_BASE_URL", server.getHttpBaseUrl() + "/v1");
    }

    public static class SelectionProbe {
        public static void main(String[] args) throws Exception {
            try {
                var builder = MemoryClientConfiguration.builder();
                if (args[0].equals("langchain4j")) {
                    builder.langChain4jHttpClient(JdkHttpClient.builder().build(),
                            Executors.newCachedThreadPool(task -> {
                                var thread = new Thread(task);
                                thread.setDaemon(true);
                                return thread;
                            }));
                }
                var client = MemoryClient.create(builder.build());
                if (args[0].equals("create")) return;
                var conversation = client.getConversation(UUID.fromString(args[1])).get(5, TimeUnit.SECONDS);
                System.out.println("read=" + conversation.id());
                var message = conversation.addMessage(NewMessage.user("zażółć")).get(5, TimeUnit.SECONDS);
                System.out.println("written=" + message.role().name().toLowerCase(Locale.ROOT));
            } catch (MissingJsonCodecException failure) {
                if (!args[0].equals("create")) throw failure;
                System.out.println(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                System.out.println("suppressed=" + failure.getSuppressed().length);
            }
        }
    }
}
