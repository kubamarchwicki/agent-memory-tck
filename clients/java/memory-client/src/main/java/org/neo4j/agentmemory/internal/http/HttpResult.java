package org.neo4j.agentmemory.internal.http;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** An uninterpreted HTTP response with case-insensitive headers. */
record HttpResult(int status, Map<String, List<String>> headers, byte[] body) {
    HttpResult {
        var copiedHeaders = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copiedHeaders.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copiedHeaders);
        body = body == null ? new byte[0] : body;
    }

    Optional<String> firstHeader(String name) {
        var values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    String contentType() {
        return firstHeader("Content-Type").orElse("");
    }
}
