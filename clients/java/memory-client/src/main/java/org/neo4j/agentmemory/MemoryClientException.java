package org.neo4j.agentmemory;

public class MemoryClientException extends RuntimeException {
    public MemoryClientException(String message) {
        super(message);
    }

    public MemoryClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
