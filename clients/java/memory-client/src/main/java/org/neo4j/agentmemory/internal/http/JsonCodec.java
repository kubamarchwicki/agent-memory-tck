package org.neo4j.agentmemory.internal.http;

interface JsonCodec {
    String name();

    byte[] encode(Object wireValue);

    <T> T decode(byte[] json, Class<T> wireType);
}
