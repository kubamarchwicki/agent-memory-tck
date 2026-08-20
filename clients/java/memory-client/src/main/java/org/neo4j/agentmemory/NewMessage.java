package org.neo4j.agentmemory;

public record NewMessage(MessageRole role, String content) {
    public static NewMessage user(String content) {
        return new NewMessage(MessageRole.USER, content);
    }
    public static NewMessage assistant(String content) {
        return new NewMessage(MessageRole.ASSISTANT, content);
    }
}
