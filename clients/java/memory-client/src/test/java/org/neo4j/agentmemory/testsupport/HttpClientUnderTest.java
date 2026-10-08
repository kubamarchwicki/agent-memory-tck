package org.neo4j.agentmemory.testsupport;

import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.neo4j.agentmemory.MemoryClientConfiguration;

/** Configures the clients exercised by the shared HTTP suites. */
public enum HttpClientUnderTest {
    JDK_DEFAULT,
    JDK_CONFIGURED,
    REST_CLIENT;

    public static final Executor TEST_EXECUTOR = Executors.newCachedThreadPool(command -> {
        var thread = new Thread(command, "memory-client-test");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryClientConfiguration.Builder configure(MemoryClientConfiguration.Builder builder) {
        return configure(builder, TEST_EXECUTOR);
    }

    public MemoryClientConfiguration.Builder configure(
            MemoryClientConfiguration.Builder builder, Executor executor) {
        return switch (this) {
            case JDK_DEFAULT -> builder;
            case JDK_CONFIGURED -> builder.jdkHttpClient(HttpClient.newHttpClient());
            case REST_CLIENT -> builder.restClient(RestClient.builder()
                    .requestFactory(new JdkClientHttpRequestFactory()).build(), executor);
        };
    }

    public boolean takesExecutor() {
        return this == REST_CLIENT;
    }

    public boolean keepsFailureHeaders() {
        return true;
    }
}
