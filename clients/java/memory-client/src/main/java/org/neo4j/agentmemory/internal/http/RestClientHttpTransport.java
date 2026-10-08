package org.neo4j.agentmemory.internal.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

/**
 * Sends blocking exchanges through an application-owned Spring client. The
 * client's own default headers are dropped so credentials or tenancy headers
 * meant for other services are never sent to the memory service.
 */
public final class RestClientHttpTransport implements HttpTransport {
    private final RestClient restClient;
    private final Executor executor;

    public RestClientHttpTransport(RestClient restClient, Executor executor) {
        this.restClient = Objects.requireNonNull(restClient, "restClient")
                .mutate().defaultHeaders(HttpHeaders::clear).build();
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public String name() {
        return "spring-rest-client";
    }

    @Override
    public CompletableFuture<HttpResult> send(HttpCall call) {
        return CompletableFuture.supplyAsync(() -> {
            var request = restClient.method(HttpMethod.valueOf(call.method()))
                    .uri(call.uriTemplate(), call.uriVariables())
                    .headers(headers -> call.headers().forEach(headers::set));
            if (call.body() != null) {
                request.body(call.body());
            }
            return request.exchange((sentRequest, response) -> {
                var headers = new LinkedHashMap<String, List<String>>();
                response.getHeaders().forEach(headers::put);
                return new HttpResult(response.getStatusCode().value(), headers,
                        response.getBody().readAllBytes());
            });
        }, executor);
    }
}
