package org.neo4j.agentmemory;

import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ClientLogging logging;

    JdkMemoryClient(URI endpoint, String apiKey, JsonCodec jsonCodec, ClientLogging logging) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.jsonCodec = jsonCodec;
        this.logging = logging;
        logging.initialized();
    }

    static MemoryClient create(URI endpoint, String apiKey) {
        if (endpoint == null || endpoint.toString().isEmpty()) {
            throw new IllegalArgumentException("endpoint must not be null or empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        return new JdkMemoryClient(endpoint, apiKey, JsonCodecs.jackson3(), new ClientLogging());
    }

    @Override
    public CompletableFuture<Conversation> createConversation(CreateConversation request) {
        var body = new LinkedHashMap<String, Object>();
        request.getUserId().ifPresent(userId -> body.put("userId", userId));
        if (!request.metadata().isEmpty()) {
            body.put("metadata", request.metadata());
        }
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
                response -> Objects.requireNonNull(
                                response.conversations(), "conversations array is required")
                        .stream()
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

    CompletableFuture<List<Message>> addMessages(
            UUID conversationId, List<NewMessage> messages) {
        var snapshot = List.copyOf(messages);
        return post(
                "addMessages",
                "/conversations/" + conversationId + "/messages/bulk",
                new AddMessagesRequest(snapshot),
                MessagesResponse.class,
                response -> List.copyOf(nullToEmpty(response.messages())));
    }

    CompletableFuture<List<Message>> messages(UUID conversationId) {
        return readMessages("/conversations/" + conversationId + "/messages");
    }

    CompletableFuture<List<Message>> messages(UUID conversationId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return readMessages("/conversations/" + conversationId + "/messages?limit=" + limit);
    }

    private CompletableFuture<List<Message>> readMessages(String path) {
        return get("messages", path, MessagesResponse.class,
                response -> List.copyOf(Objects.requireNonNull(
                        response.messages(), "messages array is required")));
    }

    CompletableFuture<ConversationContext> context(UUID conversationId) {
        return get(
                "context",
                "/conversations/" + conversationId + "/context",
                ConversationContext.class,
                response -> response);
    }

    CompletableFuture<ReasoningStep> recordStep(
            UUID conversationId, NewReasoningStep step) {
        var body = new LinkedHashMap<String, Object>();
        body.put("conversationId", conversationId);
        body.put("reasoning", step.reasoning());
        body.put("actionTaken", step.actionTaken());
        step.getResult().ifPresent(result -> body.put("result", result));
        return post(
                "recordStep",
                "/reasoning/steps",
                body,
                RecordReasoningStepResponse.class,
                this::recordedReasoningStep);
    }

    CompletableFuture<ToolCall> recordToolCall(UUID stepId, NewToolCall call) {
        var body = new LinkedHashMap<String, Object>();
        body.put("stepId", stepId);
        body.put("toolName", call.toolName());
        body.put("input", call.input());
        body.put("status", call.status());
        call.getOutput().ifPresent(output -> body.put("output", output));
        call.getDuration().ifPresent(duration -> body.put("durationMs", duration.toMillis()));
        return post(
                "recordToolCall",
                "/reasoning/tool-calls",
                body,
                RecordToolCallResponse.class,
                response -> new ToolCall(
                        response.id(),
                        response.stepId(),
                        response.toolName(),
                        call.input(),
                        call.output(),
                        response.status(),
                        call.duration(),
                        null));
    }

    CompletableFuture<ReasoningTrace> trace(UUID conversationId) {
        return get(
                "trace",
                "/reasoning/trace/" + conversationId,
                ReasoningTraceResponse.class,
                response -> reasoningTrace(conversationId, response));
    }

    CompletableFuture<ReasoningStepExplanation> explainReasoningStep(UUID stepId) {
        return get(
                "explainReasoningStep",
                "/reasoning/explain/" + stepId,
                ReasoningStepExplanationResponse.class,
                this::reasoningStepExplanation);
    }

    CompletableFuture<Void> deleteConversation(UUID conversationId) {
        var operation = "deleteConversation";
        return logging.call(operation, log -> {
            var request = request("/conversations/" + conversationId).DELETE().build();
            return exchange(operation, request, log).thenApply(response -> {
                requireSuccess(operation, response);
                return null;
            });
        });
    }

    private <W, T> CompletableFuture<T> get(
            String operation, String path, Class<W> wireType, Function<W, T> transform) {
        return logging.call(operation, log -> {
            var request = request(path).GET().build();
            return exchangeAndDecode(operation, request, wireType, transform, log);
        });
    }

    private <W, T> CompletableFuture<T> post(
            String operation, String path, Object wireRequest,
            Class<W> wireType, Function<W, T> transform) {
        return logging.call(operation, log -> {
            log.phase(ClientLogging.Phase.ENCODE);
            final byte[] body;
            try {
                body = jsonCodec.encode(wireRequest);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(new MemoryClientException(
                        operation + " could not encode request JSON", failure));
            }
            log.phase(ClientLogging.Phase.REQUEST);
            var request = request(path).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            return exchangeAndDecode(operation, request, wireType, transform, log);
        });
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
            Function<W, T> transform, ClientLogging.Operation log) {
        return exchange(operation, request, log).thenApply(response -> {
            requireSuccess(operation, response);
            log.phase(ClientLogging.Phase.DECODE);
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
            String operation, HttpRequest request, ClientLogging.Operation log) {
        log.phase(ClientLogging.Phase.TRANSPORT);
        try {
            return httpClient.sendAsync(request, ofByteArray()).handle((response, failure) -> {
                if (failure != null) {
                    throw new MemoryClientException(
                            operation + " HTTP request failed", unwrap(failure));
                }
                log.response(response.statusCode(), response.headers());
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
        return new Conversation(this, response.id(), response.userId(), response.metadata(),
                instant(response.createdAt()), instant(response.updatedAt()), response.title(),
                response.firstMessageSnippet(), response.messageCount());
    }

    private Entity entity(EntityResponse response) {
        return new Entity(
                response.id(),
                response.name(),
                response.type(),
                response.description());
    }

    private ReasoningStep recordedReasoningStep(RecordReasoningStepResponse response) {
        return new ReasoningStep(
                this,
                response.id(),
                response.conversationId(),
                response.reasoning(),
                response.actionTaken(),
                response.result(),
                null);
    }

    private ReasoningTrace reasoningTrace(
            UUID requestedConversationId, ReasoningTraceResponse response) {
        var conversationId = response.conversationId() == null
                ? requestedConversationId
                : response.conversationId();
        var steps = nullToEmpty(response.steps()).stream()
                .map(step -> new ReasoningStep(
                        this,
                        step.id(),
                        conversationId,
                        step.reasoning(),
                        step.actionTaken(),
                        step.result(),
                        instant(step.createdAt())))
                .toList();
        var toolCalls = nullToEmpty(response.toolCalls()).stream()
                .map(this::toolCall)
                .toList();
        return new ReasoningTrace(conversationId, steps, toolCalls);
    }

    private ReasoningStepExplanation reasoningStepExplanation(
            ReasoningStepExplanationResponse response) {
        var step = new ReasoningStep(
                this,
                response.id(),
                response.conversationId(),
                response.reasoning(),
                response.actionTaken(),
                response.result(),
                instant(response.createdAt()));
        var calls = nullToEmpty(response.toolCalls()).stream()
                .map(this::toolCall)
                .toList();
        var entities = nullToEmpty(response.influencedEntities()).stream()
                .map(entity -> new Entity(
                        entity.id(), entity.name(), entity.type(), (String) null))
                .toList();
        return new ReasoningStepExplanation(step, calls, entities);
    }

    private ToolCall toolCall(ToolCallResponse response) {
        return new ToolCall(
                response.id(),
                response.stepId(),
                response.toolName(),
                response.input(),
                response.output(),
                response.status(),
                response.durationMs() == null
                        ? null
                        : Duration.ofMillis(response.durationMs()),
                instant(response.createdAt()));
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
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

    private record ConversationResponse(
            UUID id, String userId, Map<String, String> metadata,
            String createdAt, String updatedAt, String title,
            String firstMessageSnippet, Long messageCount) {}

    private record ConversationsResponse(List<ConversationResponse> conversations) {}

    private record MessagesResponse(List<Message> messages) {}

    private record AddMessagesRequest(List<NewMessage> messages) {}

    private record EntityResponse(
            UUID id,
            String name,
            String type,
            String description) {}

    private record EntitiesResponse(
            List<EntityResponse> entities,
            String searchType) {}

    private record RecordReasoningStepResponse(
            UUID id,
            UUID conversationId,
            String reasoning,
            String actionTaken,
            String result) {}

    private record ReasoningStepResponse(
            UUID id,
            String reasoning,
            String actionTaken,
            String result,
            String createdAt) {}

    private record RecordToolCallResponse(
            UUID id,
            UUID stepId,
            String toolName,
            ToolCallStatus status) {}

    private record ToolCallResponse(
            UUID id,
            UUID stepId,
            String toolName,
            String input,
            String output,
            ToolCallStatus status,
            Long durationMs,
            String createdAt) {}

    private record ReasoningTraceResponse(
            UUID conversationId,
            List<ReasoningStepResponse> steps,
            List<ToolCallResponse> toolCalls) {}

    private record InfluencedEntityResponse(
            UUID id,
            String name,
            String type) {}

    private record ReasoningStepExplanationResponse(
            UUID id,
            UUID conversationId,
            String reasoning,
            String actionTaken,
            String result,
            String createdAt,
            List<ToolCallResponse> toolCalls,
            List<InfluencedEntityResponse> influencedEntities) {}
}
