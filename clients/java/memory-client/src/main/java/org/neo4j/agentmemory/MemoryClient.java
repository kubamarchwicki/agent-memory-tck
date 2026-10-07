package org.neo4j.agentmemory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MemoryClient {
    /**
     * Waits for a supplied future using NAMS_AWAIT_TIMEOUT_SECONDS, or 30 seconds.
     * The environment default is resolved once on first use. Missing or invalid
     * configuration logs its fallback through System.Logger.
     *
     * @param future the operation to await; may already have started
     * @param <T> the result type
     * @return the result, including null for successful void operations
     * @throws NullPointerException if future is null
     * @throws MemoryClientException on timeout, interruption, or a checked failure
     * @throws java.util.concurrent.CancellationException if the operation was cancelled
     * @see #await(CompletableFuture, Duration)
     */
    static <T> T await(CompletableFuture<T> future) {
        return AwaitSupport.await(future);
    }

    /**
     * Waits interruptibly for a supplied future with an explicit timeout.
     * This overload bypasses environment configuration and default-timeout logging.
     * Waiting starts here, rather than when the operation started. Timeout and
     * interruption leave the future running; interruption restores the thread's flag.
     * Async wrappers are removed, preserving client exceptions, other runtime
     * exceptions, and errors. Checked failures become MemoryClientException causes.
     *
     * @param future the operation to await; may already have started
     * @param timeout a positive duration representable as a long number of nanoseconds
     * @param <T> the result type
     * @return the result, including null for successful void operations
     * @throws NullPointerException if future is null
     * @throws IllegalArgumentException if timeout is null, nonpositive, or too large
     * @throws MemoryClientException on timeout, interruption, or a checked failure
     * @throws java.util.concurrent.CancellationException if the operation was cancelled
     */
    static <T> T await(CompletableFuture<T> future, Duration timeout) {
        return AwaitSupport.await(future, timeout);
    }

    static MemoryClient create(String apiKey) {
        var configuredEndpoint = System.getenv("MEMORY_ENDPOINT");
        var endpoint = URI.create(configuredEndpoint == null || configuredEndpoint.isBlank()
                ? "https://memory.neo4jlabs.com/v1"
                : configuredEndpoint.trim());
        return create(endpoint, apiKey);
    }

    static MemoryClient create(URI endpoint, String apiKey) {
        return JdkMemoryClient.create(endpoint, apiKey);
    }

    CompletableFuture<Conversation> createConversation(CreateConversation request);

    CompletableFuture<List<Conversation>> listConversations(ListConversations request);

    CompletableFuture<Conversation> getConversation(UUID conversationId);

    default CompletableFuture<List<Entity>> searchEntities(String query) {
        return searchEntities(new EntitySearch(query));
    }

    CompletableFuture<List<Entity>> searchEntities(EntitySearch search);

    CompletableFuture<Message> addMessage(UUID conversationId, NewMessage message);

    CompletableFuture<List<Message>> addMessages(
            UUID conversationId, List<NewMessage> messages);

    CompletableFuture<List<Message>> messages(UUID conversationId);

    CompletableFuture<List<Message>> messages(UUID conversationId, int limit);

    CompletableFuture<ConversationContext> context(UUID conversationId);

    CompletableFuture<ReasoningStep> recordStep(
            UUID conversationId, NewReasoningStep step);

    CompletableFuture<ToolCall> recordToolCall(UUID stepId, NewToolCall call);

    CompletableFuture<ReasoningTrace> trace(UUID conversationId);

    CompletableFuture<ReasoningStepExplanation> explainReasoningStep(UUID stepId);

    CompletableFuture<Void> deleteConversation(UUID conversationId);
}
