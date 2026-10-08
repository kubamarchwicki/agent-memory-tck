package org.neo4j.agentmemory.testsupport;

import java.net.http.HttpClient;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.neo4j.agentmemory.MemoryClientConfiguration;

/** Configures the clients exercised by the shared HTTP suites. */
public enum HttpClientUnderTest {
    JDK_DEFAULT,
    JDK_CONFIGURED;

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
        return this == JDK_DEFAULT ? builder : builder.jdkHttpClient(HttpClient.newHttpClient());
    }

    public boolean takesExecutor() {
        return false;
    }

    public boolean keepsFailureHeaders() {
        return true;
    }
}
