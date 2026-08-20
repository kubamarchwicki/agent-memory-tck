package org.neo4j.agentmemory;

import java.util.UUID;

public record Message(UUID id, MessageRole role, String content) {}
