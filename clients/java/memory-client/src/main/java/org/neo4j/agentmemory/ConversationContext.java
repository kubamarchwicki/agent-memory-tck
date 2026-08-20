package org.neo4j.agentmemory;

import java.util.List;

public record ConversationContext(
        List<Reflection> reflections,
        List<Observation> observations,
        List<Message> recentMessages) {}
