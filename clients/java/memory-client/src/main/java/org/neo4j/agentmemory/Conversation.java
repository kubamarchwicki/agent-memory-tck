package org.neo4j.agentmemory;

import java.util.UUID;

public interface Conversation {
    UUID id();

    String userId();
}
