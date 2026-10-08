package org.neo4j.agentmemory.internal.http;

import java.util.concurrent.CompletableFuture;

/** Internal asynchronous HTTP exchange seam. */
public interface HttpTransport {
    String name();
    CompletableFuture<HttpResult> send(HttpCall call);
}
