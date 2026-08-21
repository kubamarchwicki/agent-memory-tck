package org.neo4j.agentmemory;

import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

final class JdkMemoryClient implements MemoryClient {
    private static final int BODY_EXCERPT_LIMIT = 1024;

    private final URI endpoint;
    private final String apiKey;
    private final HttpClient httpClient;
    private final JsonCodec jsonCodec;

    private JdkMemoryClient(URI endpoint, String apiKey, JsonCodec jsonCodec) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.jsonCodec = jsonCodec;
    }

    static MemoryClient create(URI endpoint, String apiKey) {
        if (endpoint == null || endpoint.toString().isEmpty()) {
            throw new IllegalArgumentException("endpoint must not be null or empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        return new JdkMemoryClient(endpoint, apiKey, JsonCodecs.jackson3());
    }

    @Override
    public CompletableFuture<Conversation> createConversation(CreateConversation request) {
        var body = new LinkedHashMap<String, Object>();
        request.userId().ifPresent(userId -> body.put("userId", userId));
        return post(
                "createConversation",
                "/conversations",
                body,
                ConversationResponse.class,
                this::conversation);
    }

    @Override
    public CompletableFuture<List<Conversation>> listConversations(ListConversations request) {
        var path = new StringBuilder("/conversations?limit=").append(request.limit());
        return get(
                "listConversations",
                path.toString(),
                ConversationsResponse.class,
                response -> nullToEmpty(response.conversations()).stream()
                        .map(this::conversation)
                        .toList());
    }

    @Override
    public CompletableFuture<Conversation> getConversation(UUID conversationId) {
        return get(
                "getConversation",
                "/conversations/" + conversationId,
                ConversationResponse.class,
                this::conversation);
    }

    @Override
    public CompletableFuture<List<Entity>> searchEntities(EntitySearch search) {
        var body = new LinkedHashMap<String, Object>();
        body.put("query", search.query());
        body.put("limit", search.limit());
        if (search.type() != null) {
            body.put("type", search.type());
        }
        return post(
                "searchEntities",
                "/entities/search",
                body,
                EntitiesResponse.class,
                response -> nullToEmpty(response.entities()).stream()
                        .map(this::entity)
                        .toList());
    }

    CompletableFuture<Message> addMessage(UUID conversationId, NewMessage message) {
        return post(
                "addMessage",
                "/conversations/" + conversationId + "/messages",
                message,
                Message.class,
                response -> response);
    }

    CompletableFuture<List<Message>> messages(UUID conversationId) {
        return get(
                "messages",
                "/conversations/" + conversationId + "/messages",
                MessagesResponse.class,
                response -> List.copyOf(nullToEmpty(response.messages())));
    }

    CompletableFuture<ConversationContext> context(UUID conversationId) {
        return get(
                "context",
                "/conversations/" + conversationId + "/context",
                ConversationContext.class,
                response -> response);
    }

    CompletableFuture<Void> deleteConversation(UUID conversationId) {
        var operation = "deleteConversation";
        var request = request("/conversations/" + conversationId)
                .DELETE()
                .build();
        return exchange(operation, request).thenApply(response -> {
            requireSuccess(operation, response);
            return null;
        });
    }

    private <W, T> CompletableFuture<T> get(
            String operation,
            String path,
            Class<W> wireType,
            Function<W, T> transform) {
        var request = request(path).GET().build();
        return exchangeAndDecode(operation, request, wireType, transform);
    }

    private <W, T> CompletableFuture<T> post(
            String operation,
            String path,
            Object wireRequest,
            Class<W> wireType,
            Function<W, T> transform) {
        final byte[] body;
        try {
            body = jsonCodec.encode(wireRequest);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new MemoryClientException(
                    operation + " could not encode request JSON", failure));
        }

        var request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return exchangeAndDecode(operation, request, wireType, transform);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(endpoint.toString() + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
    }

    private <W, T> CompletableFuture<T> exchangeAndDecode(
            String operation,
            HttpRequest request,
            Class<W> wireType,
            Function<W, T> transform) {
        return exchange(operation, request).thenApply(response -> {
            requireSuccess(operation, response);
            try {
                return transform.apply(jsonCodec.decode(response.body(), wireType));
            } catch (RuntimeException failure) {
                throw new ResponseDecodingException(
                        operation,
                        response.statusCode(),
                        contentType(response),
                        excerpt(response.body()),
                        failure);
            }
        });
    }

    private CompletableFuture<HttpResponse<byte[]>> exchange(
            String operation, HttpRequest request) {
        try {
            return httpClient.sendAsync(request, ofByteArray()).handle((response, failure) -> {
                if (failure != null) {
                    throw new MemoryClientException(
                            operation + " HTTP request failed", unwrap(failure));
                }
                return response;
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new MemoryClientException(
                    operation + " HTTP request failed", failure));
        }
    }

    private static void requireSuccess(
            String operation, HttpResponse<byte[]> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MemoryServiceException(
                    operation,
                    response.statusCode(),
                    response.headers().map(),
                    contentType(response),
                    excerpt(response.body()));
        }
    }

    private Conversation conversation(ConversationResponse response) {
        return new Conversation(this, response.id(), response.userId());
    }

    private Entity entity(EntityResponse response) {
        return new Entity(
                response.id(),
                response.name(),
                response.type(),
                response.description());
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private static String excerpt(byte[] body) {
        var text = new String(body, UTF_8);
        return text.length() <= BODY_EXCERPT_LIMIT
                ? text
                : text.substring(0, BODY_EXCERPT_LIMIT) + "…";
    }

    private record ConversationResponse(UUID id, String userId) {}

    private record ConversationsResponse(List<ConversationResponse> conversations) {}

    private record MessagesResponse(List<Message> messages) {}

    private record EntityResponse(
            UUID id,
            String name,
            String type,
            String description) {}

    private record EntitiesResponse(
            List<EntityResponse> entities,
            String searchType) {}
}
