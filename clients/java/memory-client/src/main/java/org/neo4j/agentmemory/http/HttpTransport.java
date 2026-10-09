package org.neo4j.agentmemory.http;

import java.util.concurrent.CompletableFuture;

/** Internal asynchronous HTTP exchange seam. */
public interface HttpTransport {
    String name();
    CompletableFuture<HttpResult> send(HttpCall call);
}
