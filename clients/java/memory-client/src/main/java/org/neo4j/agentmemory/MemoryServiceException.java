package org.neo4j.agentmemory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryServiceException extends MemoryClientException {
    private final String operation;
    private final int statusCode;
    private final Map<String, List<String>> responseHeaders;
    private final String contentType;
    private final String responseBodyExcerpt;

    public MemoryServiceException(
            String operation,
            int statusCode,
            Map<String, List<String>> responseHeaders,
            String contentType,
            String responseBodyExcerpt) {
        super(operation + " failed with HTTP " + statusCode);
        this.operation = operation;
        this.statusCode = statusCode;
        this.responseHeaders = immutableHeaders(responseHeaders);
        this.contentType = contentType;
        this.responseBodyExcerpt = responseBodyExcerpt;
    }

    public String operation() {
        return operation;
    }

    public int statusCode() {
        return statusCode;
    }

    public Map<String, List<String>> responseHeaders() {
        return responseHeaders;
    }

    public String contentType() {
        return contentType;
    }

    public String responseBodyExcerpt() {
        return responseBodyExcerpt;
    }

    private static Map<String, List<String>> immutableHeaders(
            Map<String, List<String>> headers) {
        var copy = new LinkedHashMap<String, List<String>>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }
}
