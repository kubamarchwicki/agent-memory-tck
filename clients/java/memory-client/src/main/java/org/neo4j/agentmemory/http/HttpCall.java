package org.neo4j.agentmemory.http;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One memory request: a method, an absolute URI template with UUID or Integer
 * variables, the memory headers, and a body.
 */
public sealed interface HttpCall permits HttpCallRecord {
    String method();
    String uriTemplate();
    Map<String, Object> uriVariables();
    Map<String, String> headers();
    Body body();
    URI uri();

    /**
     * A request body. The memory client sends {@link NoBody} or
     * {@link ByteArrayBody}; transports send the bytes of any body other than
     * {@code NoBody}.
     */
    interface Body {
        default byte[] body() {
            return new byte[] {};
        }
    }
    record NoBody() implements Body {}
    record ByteArrayBody(byte[] body) implements Body  {}

    static HttpCall get(String uriTemplate, Map<String, Object> uriVariables,
                               Map<String, String> headers) {
        return new HttpCallRecord("GET", uriTemplate, uriVariables, headers, new NoBody());
    }

    static HttpCall post(String uriTemplate, Map<String, Object> uriVariables,
                                Map<String, String> headers, Body body) {
        return new HttpCallRecord("POST", uriTemplate, uriVariables, headers, body);
    }

    static HttpCall delete(String uriTemplate, Map<String, Object> uriVariables,
                                  Map<String, String> headers) {
        return new HttpCallRecord("DELETE", uriTemplate, uriVariables, headers, new NoBody());
    }
}

/** An HTTP request with an absolute URI template. */
record HttpCallRecord(String method, String uriTemplate, Map<String, Object> uriVariables,
        Map<String, String> headers, Body body) implements HttpCall{
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]+)\\}");

    HttpCallRecord {
        if (!"GET".equals(method) && !"POST".equals(method) && !"DELETE".equals(method)) {
            throw new IllegalArgumentException("unsupported HTTP method: " + method);
        }
        uriVariables.forEach((name, value) -> {
            if (!(value instanceof UUID) && !(value instanceof Integer)) {
                throw new IllegalArgumentException("uri variable " + name + " must be a UUID or Integer");
            }
        });
        uriVariables = Map.copyOf(uriVariables);
        headers = Map.copyOf(headers);
    }

    public URI uri() {
        var matcher = VARIABLE.matcher(uriTemplate);
        var expanded = matcher.replaceAll(match -> {
            var name = match.group(1);
            if (!uriVariables.containsKey(name)) {
                throw new IllegalArgumentException("unbound uri variable: " + name);
            }
            return String.valueOf(uriVariables.get(name));
        });
        return URI.create(expanded);
    }
}
