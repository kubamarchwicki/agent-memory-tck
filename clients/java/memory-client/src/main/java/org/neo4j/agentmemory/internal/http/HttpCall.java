package org.neo4j.agentmemory.internal.http;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** An HTTP request with an absolute URI template. */
record HttpCall(String method, String uriTemplate, Map<String, Object> uriVariables,
        Map<String, String> headers, byte[] body) {
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]+)\\}");

    HttpCall {
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
