package org.neo4j.agentmemory;

record ResponseDiagnostics(
        String operation,
        int statusCode,
        String contentType,
        String responseBodyExcerpt) {}
