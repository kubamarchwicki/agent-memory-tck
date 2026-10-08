package org.neo4j.agentmemory.internal.http;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Sends exchanges through an application-owned JDK HTTP client. */
public final class JdkHttpTransport implements HttpTransport {
    private final HttpClient httpClient;

    public JdkHttpTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String name() { return "jdk-http"; }

    @Override
    public CompletableFuture<HttpResult> send(HttpCall call) {
        var publisher = call.body() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(call.body());
        var request = HttpRequest.newBuilder(call.uri()).method(call.method(), publisher);
        call.headers().forEach(request::header);
        return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> new HttpResult(response.statusCode(), response.headers().map(), response.body()));
    }
}
