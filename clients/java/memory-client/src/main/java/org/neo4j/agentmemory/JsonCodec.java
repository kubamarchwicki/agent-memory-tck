package org.neo4j.agentmemory;

interface JsonCodec {
    byte[] encode(Object wireValue);

    <T> T decode(byte[] json, Class<T> wireType);
}
