package org.neo4j.agentmemory.internal.http;

public enum JsonCodecUnderTest {
    JACKSON_3,
    JACKSON_2;

    JsonCodec codec() {
        return switch (this) {
            case JACKSON_3 -> new Jackson3JsonCodec();
            case JACKSON_2 -> new Jackson2JsonCodec();
        };
    }
}
