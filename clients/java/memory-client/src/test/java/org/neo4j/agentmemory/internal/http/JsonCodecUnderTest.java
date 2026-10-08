package org.neo4j.agentmemory.internal.http;

import java.net.http.HttpClient;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.MemoryClientConfiguration;

public enum JsonCodecUnderTest {
    JACKSON_3,
    JACKSON_2;

    /** A client on the default JDK transport that uses this codec instead of automatic selection. */
    public MemoryClient client(MemoryClientConfiguration configuration) {
        return new HttpMemoryClient(configuration.baseUrl(), configuration.apiKey(),
                new JdkHttpTransport(HttpClient.newHttpClient()), codec(), new ClientLogging());
    }

    JsonCodec codec() {
        return switch (this) {
            case JACKSON_3 -> new Jackson3JsonCodec();
            case JACKSON_2 -> new Jackson2JsonCodec();
        };
    }
}
