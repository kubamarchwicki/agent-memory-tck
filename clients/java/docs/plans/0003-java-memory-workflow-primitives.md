# Java Memory Workflow Primitives Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Java client with the NAMS primitives required for memory-skill steps 1–4 and 7: optional-user conversation creation, entity search, bulk messages, reasoning steps, tool calls, traces, and explanations.

**Architecture:** `MemoryClient` owns cross-conversation entity search, while live `Conversation` and `ReasoningStep` objects own conversation- and step-scoped behavior. Immutable public data carriers use records, including a record-based `NewToolCall` that retains its nested builder; live objects remain classes so their client binding stays hidden. The package-private `JdkMemoryClient` continues to combine domain conversion, REST mapping, and JDK HTTP transport; agent lifecycle orchestration remains outside the core client.

**Tech Stack:** Java 17, Maven 3.9+, `CompletableFuture`, JDK HTTP Client, Jackson Databind 3.1.5, JUnit Jupiter 5.14.4, AssertJ 3.27.7. Retain the existing WireMock 3.13.2 dependency and single test, but add no WireMock coverage.

## Global Constraints

- Keep public types in `org.neo4j.agentmemory`.
- Preserve the asynchronous-only API.
- Keep `JdkMemoryClient` package-private; do not introduce `NamsApi` or an HTTP abstraction.
- Do not add retries, client timeouts, pagination, or a new validation policy.
- Normalize returned collections to immutable, service-ordered snapshots.
- Treat entity types as unrestricted strings.
- Use `Optional` for optional return values. Public convenience constructors and builders accept raw nullable values instead of `Optional`; Java's unavoidable public canonical constructor remains available on records whose components are `Optional`.
- Use records for immutable public data carriers. Add raw-value convenience constructors or builders that initialize optional components with `Optional.empty()` or `Optional.ofNullable(...)`; normal client usage does not invoke record canonical constructors with `Optional` arguments.
- Keep live, client-bound `Conversation` and `ReasoningStep` objects as final classes because records cannot carry hidden non-component client state.
- Use `Duration` publicly and milliseconds on the wire.
- Use `Instant` for exposed timestamps.
- Treat tool input and output as opaque strings.
- Do not expose an unsupported conversation-user update operation or tool-call error property.
- Do not expose step-ID fallbacks on `MemoryClient`; adapters retain live `ReasoningStep` handles.
- Use concrete-type-plus-UUID equality for identified domain objects.
- Preserve the existing WireMock dependency and test without expanding them.
- Do not implement the Java TCK bridge in this plan.
- Extend the credential-gated hosted test to cover every new route.
- Preserve unrelated and untracked workspace changes.
- Never print or commit `clients/java/.env`.

---

## File Structure

- Modify `MemoryClient`, `Conversation`, `CreateConversation`, and `JdkMemoryClient` for the new public operations and wire mappings.
- Modify `Message`, `Observation`, and `Reflection` for UUID identity equality.
- Create `EntitySearch` and `Entity` for cross-conversation search.
- Create `NewReasoningStep`, `ReasoningStep`, `NewToolCall`, `ToolCall`, `ToolCallStatus`, `ReasoningTrace`, and `ReasoningStepExplanation`.
- Add focused request/domain tests and extend `Jackson3JsonCodecTest`.
- Extend `HostedServiceIT` and its assertions without adding local HTTP tests.
- Preserve and commit the glossary and ADR decisions created during grilling.

### Task 1: Optional conversation users and identity equality

**Files:**
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/CreateConversation.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Conversation.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Message.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Observation.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Reflection.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java`
- Modify: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/Jackson3JsonCodecTest.java`
- Modify: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/e2e/assertions/ConversationAssert.java`
- Create: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/ConversationIdentityTest.java`

**Interfaces:**
- Produces: record `CreateConversation` with `CreateConversation()`, `CreateConversation(String)`, and `Optional<String> userId()`; produces `Optional<String> Conversation.userId()`.
- Produces: UUID-based equality for `Conversation`, `Message`, `Observation`, and `Reflection`.

- [ ] **Step 1: Write the failing optional-user and identity tests**

Create `ConversationIdentityTest.java`:

```java
package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationIdentityTest {
    private static final UUID ID =
            UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");

    @Test
    void representsKnownAndUnknownConversationUsersExplicitly() {
        assertThat(CreateConversation.class.isRecord()).isTrue();
        assertThat(new CreateConversation().userId()).isEmpty();
        assertThat(new CreateConversation((String) null).userId()).isEmpty();
        assertThat(new CreateConversation(" \t").userId()).isEmpty();
        assertThat(new CreateConversation("alice").userId()).contains("alice");
    }

    @Test
    void conversationsCompareByUuid() {
        var client = client();
        var first = new Conversation(client, ID, "alice");
        var second = new Conversation(client, ID, "bob");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.userId()).isEqualTo(Optional.of("alice"));
    }

    @Test
    void identifiedSnapshotValuesCompareByUuid() {
        var message = new Message(ID, MessageRole.USER, "first");
        var changedMessage = new Message(ID, MessageRole.ASSISTANT, "second");
        var observation = new Observation(ID, "first");
        var changedObservation = new Observation(ID, "second");
        var reflection = new Reflection(ID, "first");
        var changedReflection = new Reflection(ID, "second");

        assertThat(message).isEqualTo(changedMessage);
        assertThat(observation).isEqualTo(changedObservation);
        assertThat(reflection).isEqualTo(changedReflection);
    }

    private static JdkMemoryClient client() {
        return (JdkMemoryClient) MemoryClient.create(
                URI.create("https://memory.test/v1"), "key");
    }
}
```

- [ ] **Step 2: Run the focused test and confirm the public contract is missing**

Run:

```bash
mvn -f clients/java/pom.xml -pl memory-client -am \
  -Dtest=ConversationIdentityTest test
```

Expected: test compilation fails because `CreateConversation()` does not exist and `userId()` does not return `Optional<String>`.

- [ ] **Step 3: Replace `CreateConversation` with an explicit optional-user request**

```java
package org.neo4j.agentmemory;

import java.util.Optional;

public record CreateConversation(Optional<String> userId) {
    public CreateConversation() {
        this(Optional.empty());
    }

    public CreateConversation(String userId) {
        this(Optional.ofNullable(userId));
    }

    public CreateConversation {
        userId = (userId == null ? Optional.<String>empty() : userId)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}
```

- [ ] **Step 4: Change `Conversation` user semantics and equality**

Change its field, constructor, accessor, and equality to:

```java
private final Optional<String> userId;

Conversation(JdkMemoryClient client, UUID id, String userId) {
    this.client = client;
    this.id = id;
    this.userId = Optional.ofNullable(userId)
            .map(String::trim)
            .filter(value -> !value.isEmpty());
}

public Optional<String> userId() {
    return userId;
}

@Override
public boolean equals(Object other) {
    return this == other
            || other instanceof Conversation that
                    && Objects.equals(id, that.id);
}

@Override
public int hashCode() {
    return Objects.hashCode(id);
}
```

Add imports for `Objects` and `Optional`.

- [ ] **Step 5: Give existing identified records UUID equality**

Add the following overrides to `Message`, substituting `Observation` and `Reflection` in the other two records:

```java
@Override
public boolean equals(Object other) {
    return this == other
            || other instanceof Message that
                    && java.util.Objects.equals(id, that.id);
}

@Override
public int hashCode() {
    return java.util.Objects.hashCode(id);
}
```

- [ ] **Step 6: Omit an absent user from create-conversation JSON**

Replace `JdkMemoryClient.createConversation` with:

```java
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
```

Add the `LinkedHashMap` import. Do not add a user-update operation.

- [ ] **Step 7: Remove direct `CreateConversation` JSON binding from the codec test**

The HTTP layer now maps `CreateConversation` to a wire map so `Optional` never reaches Jackson. Replace `encodesHostedRequestRecordsAndLowercaseRole` with:

```java
@Test
void encodesHostedMessageRequestAndLowercaseRole() {
    var messageJson = new String(
            codec.encode(new NewMessage(USER, "hello")), UTF_8);

    assertThat(messageJson).isEqualTo("{\"role\":\"user\",\"content\":\"hello\"}");
}
```

- [ ] **Step 8: Update the fluent conversation assertion**

Use explicit optional semantics in `hasUserId`:

```java
public ConversationAssert hasUserId(String expected) {
    isNotNull();
    if (!actual.userId().equals(Optional.ofNullable(expected))) {
        failWithMessage(
                "Expected conversation user id <%s> but was <%s>",
                expected,
                actual.userId().orElse(null));
    }
    return this;
}
```

Add the `Optional` import. Retain `Objects` because the ID assertion still uses it.

- [ ] **Step 9: Run the Java unit tests**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am test
```

Expected: all unit tests pass, including the existing WireMock test.

- [ ] **Step 10: Commit the optional-user and identity slice**

```bash
git add \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/CreateConversation.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Conversation.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Message.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Observation.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Reflection.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/ConversationIdentityTest.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/Jackson3JsonCodecTest.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/e2e/assertions/ConversationAssert.java
git commit -m "feat(java): support optional conversation users"
```

### Task 2: Cross-conversation entity search

**Files:**
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/EntitySearch.java`
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Entity.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/MemoryClient.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java`
- Create: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/EntitySearchTest.java`

**Interfaces:**
- Produces: `MemoryClient.searchEntities(String)` and `MemoryClient.searchEntities(EntitySearch)`.
- Produces: four `EntitySearch` constructors covering typed/untyped and default/explicit limits.
- Produces: immutable `Entity` records with UUID identity and optional descriptions.

- [ ] **Step 1: Write the failing entity-model tests**

```java
package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntitySearchTest {
    private static final UUID ID =
            UUID.fromString("2bdc3216-d03b-44ca-a335-6dfad86390d8");

    @Test
    void offersTypedAndUntypedSearchConstructors() {
        assertThat(new EntitySearch("alice"))
                .extracting(EntitySearch::query, EntitySearch::type, EntitySearch::limit)
                .containsExactly("alice", null, 10);
        assertThat(new EntitySearch("alice", 3).limit()).isEqualTo(3);
        assertThat(new EntitySearch("alice", "person").type()).isEqualTo("person");
        assertThat(new EntitySearch("alice", "custom-type", 4).limit()).isEqualTo(4);
    }

    @Test
    void keepsEntityTypesOpenAndDescriptionsOptional() {
        var entity = new Entity(ID, "Apollo", "future-type", (String) null);

        assertThat(Entity.class.isRecord()).isTrue();
        assertThat(entity.type()).isEqualTo("future-type");
        assertThat(entity.description()).isEmpty();
    }

    @Test
    void entitiesCompareByUuid() {
        var first = new Entity(ID, "Apollo", "project", "first");
        var second = new Entity(ID, "Renamed", "custom", (String) null);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
```

- [ ] **Step 2: Run the focused test and confirm the types are missing**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am -Dtest=EntitySearchTest test
```

Expected: test compilation fails because `EntitySearch` and `Entity` do not exist.

- [ ] **Step 3: Add the search request**

```java
package org.neo4j.agentmemory;

public record EntitySearch(String query, String type, int limit) {
    private static final int DEFAULT_LIMIT = 10;

    public EntitySearch(String query) {
        this(query, null, DEFAULT_LIMIT);
    }

    public EntitySearch(String query, int limit) {
        this(query, null, limit);
    }

    public EntitySearch(String query, String type) {
        this(query, type, DEFAULT_LIMIT);
    }
}
```

- [ ] **Step 4: Add the minimal entity model**

```java
package org.neo4j.agentmemory;

import java.util.Optional;
import java.util.UUID;

public record Entity(
        UUID id,
        String name,
        String type,
        Optional<String> description) {
    Entity(UUID id, String name, String type, String description) {
        this(id, name, type, Optional.ofNullable(description));
    }

    public Entity {
        description = description == null ? Optional.empty() : description;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Entity that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
```

- [ ] **Step 5: Add entity search to `MemoryClient`**

```java
default CompletableFuture<List<Entity>> searchEntities(String query) {
    return searchEntities(new EntitySearch(query));
}

CompletableFuture<List<Entity>> searchEntities(EntitySearch search);
```

- [ ] **Step 6: Map `/entities/search` in `JdkMemoryClient`**

```java
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

private Entity entity(EntityResponse response) {
    return new Entity(
            response.id(),
            response.name(),
            response.type(),
            response.description());
}
```

Add nested wire records:

```java
private record EntityResponse(
        UUID id,
        String name,
        String type,
        String description) {}

private record EntitiesResponse(
        List<EntityResponse> entities,
        String searchType) {}
```

- [ ] **Step 7: Run focused and complete unit verification**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am -Dtest=EntitySearchTest test
mvn -f clients/java/pom.xml -pl memory-client -am test
```

Expected: both commands pass.

- [ ] **Step 8: Commit entity search**

```bash
git add \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/EntitySearch.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Entity.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/MemoryClient.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/EntitySearchTest.java
git commit -m "feat(java): add entity search"
```

### Task 3: Ordered bulk message persistence

**Files:**
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Conversation.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java`
- Modify: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/e2e/HostedServiceIT.java`

**Interfaces:**
- Produces: `CompletableFuture<List<Message>> Conversation.addMessages(List<NewMessage>)`.
- Preserves: `Conversation.addMessage(NewMessage)`.
- Maps: `POST /conversations/{id}/messages/bulk`.

- [ ] **Step 1: Change the hosted workflow to require bulk messages**

In `retrievesConversationAndRoundTripsMessagesAndContext`, replace the initial two singular writes with:

```java
var firstUser = conversation
        .addMessages(List.of(user("java e2e user " + marker)))
        .join()
        .get(0);
var firstAssistant = conversation
        .addMessage(assistant("java e2e assistant " + marker))
        .join();

var exchange = conversation
        .addMessages(List.of(
                user("java e2e follow-up user " + marker),
                assistant("java e2e follow-up assistant " + marker)))
        .join();

assertThat(exchange)
        .extracting(Message::role)
        .containsExactly(USER, ASSISTANT);
```

Update the remaining assertions to use `firstUser` and `firstAssistant`, and import `java.util.List`.

- [ ] **Step 2: Run test compilation and confirm the bulk method is missing**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am test
```

Expected: test compilation fails because `Conversation.addMessages` is undefined.

- [ ] **Step 3: Add the live conversation method**

```java
public CompletableFuture<List<Message>> addMessages(List<NewMessage> messages) {
    return client.addMessages(id, messages);
}
```

- [ ] **Step 4: Add the bulk HTTP mapping**

```java
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
```

Add the nested request record:

```java
private record AddMessagesRequest(List<NewMessage> messages) {}
```

Do not reject empty lists or lists over 100 locally. `List.copyOf` provides defensive copying and null rejection only.

- [ ] **Step 5: Run unit verification**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am test
```

Expected: all unit tests compile and pass.

- [ ] **Step 6: Commit bulk message support**

```bash
git add \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Conversation.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/e2e/HostedServiceIT.java
git commit -m "feat(java): add bulk conversation messages"
```

### Task 4: Reasoning request and tool-call value models

**Files:**
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/NewReasoningStep.java`
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ToolCallStatus.java`
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/NewToolCall.java`
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ToolCall.java`
- Create: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/ReasoningModelsTest.java`
- Modify: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/Jackson3JsonCodecTest.java`

**Interfaces:**
- Produces: `NewReasoningStep` record constructors with and without result.
- Produces: hosted `ToolCallStatus` values.
- Produces: record-based `NewToolCall.builder(toolName, input)` with its nested builder retained.
- Produces: immutable UUID-identified `ToolCall` records.

- [ ] **Step 1: Write the failing reasoning-model tests**

```java
package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReasoningModelsTest {
    private static final UUID ID =
            UUID.fromString("15c63f73-f00f-45de-b62e-851ea483a552");
    private static final UUID STEP_ID =
            UUID.fromString("673139d8-dd48-48eb-a5a9-cc84e6e938e3");

    @Test
    void supportsReasoningStepsWithAndWithoutResult() {
        assertThat(NewReasoningStep.class.isRecord()).isTrue();
        assertThat(new NewReasoningStep("reason", "act").result()).isEmpty();
        assertThat(new NewReasoningStep("reason", "act", "done").result())
                .contains("done");
    }

    @Test
    void buildsSuccessfulToolCallsByDefault() {
        var request = NewToolCall.builder("memory_search_entities", "{\"query\":\"alice\"}")
                .output("{\"entities\":[]}")
                .duration(Duration.ofMillis(150))
                .build();

        assertThat(NewToolCall.class.isRecord()).isTrue();
        assertThat(request.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(request.output()).contains("{\"entities\":[]}");
        assertThat(request.duration()).contains(Duration.ofMillis(150));
    }

    @Test
    void toolCallsCompareByUuid() {
        var first = new ToolCall(
                ID, STEP_ID, "first", "{}", (String) null,
                ToolCallStatus.SUCCESS, null, null);
        var second = new ToolCall(
                ID, STEP_ID, "renamed", "{\"changed\":true}", "failed",
                ToolCallStatus.ERROR, Duration.ofMillis(1), Instant.EPOCH);

        assertThat(ToolCall.class.isRecord()).isTrue();
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
```

- [ ] **Step 2: Run the focused test and confirm the types are missing**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am -Dtest=ReasoningModelsTest test
```

Expected: test compilation fails for the four missing reasoning types.

- [ ] **Step 3: Add `NewReasoningStep`**

```java
package org.neo4j.agentmemory;

import java.util.Optional;

public record NewReasoningStep(
        String reasoning,
        String actionTaken,
        Optional<String> result) {
    public NewReasoningStep(String reasoning, String actionTaken) {
        this(reasoning, actionTaken, Optional.empty());
    }

    public NewReasoningStep(String reasoning, String actionTaken, String result) {
        this(reasoning, actionTaken, Optional.ofNullable(result));
    }

    public NewReasoningStep {
        result = result == null ? Optional.empty() : result;
    }
}
```

- [ ] **Step 4: Add the hosted status enum**

```java
package org.neo4j.agentmemory;

public enum ToolCallStatus {
    PENDING,
    SUCCESS,
    FAILURE,
    ERROR,
    TIMEOUT,
    CANCELLED
}
```

Do not add `COMPLETED`; integrations own any translation to `SUCCESS`.

- [ ] **Step 5: Add the builder-based request**

```java
package org.neo4j.agentmemory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record NewToolCall(
        String toolName,
        String input,
        Optional<String> output,
        ToolCallStatus status,
        Optional<Duration> duration) {
    private NewToolCall(Builder builder) {
        this(
                builder.toolName,
                builder.input,
                Optional.ofNullable(builder.output),
                builder.status,
                Optional.ofNullable(builder.duration));
    }

    public NewToolCall {
        output = output == null ? Optional.empty() : output;
        duration = duration == null ? Optional.empty() : duration;
    }

    public static Builder builder(String toolName, String input) {
        return new Builder(toolName, input);
    }

    public static final class Builder {
        private final String toolName;
        private final String input;
        private String output;
        private ToolCallStatus status = ToolCallStatus.SUCCESS;
        private Duration duration;

        private Builder(String toolName, String input) {
            this.toolName = toolName;
            this.input = input;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder status(ToolCallStatus status) {
            this.status = Objects.requireNonNull(status, "status");
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public NewToolCall build() {
            return new NewToolCall(this);
        }
    }
}
```

- [ ] **Step 6: Add the immutable recorded tool call**

```java
package org.neo4j.agentmemory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ToolCall(
        UUID id,
        UUID stepId,
        String toolName,
        String input,
        Optional<String> output,
        ToolCallStatus status,
        Optional<Duration> duration,
        Optional<Instant> createdAt) {
    ToolCall(
            UUID id,
            UUID stepId,
            String toolName,
            String input,
            String output,
            ToolCallStatus status,
            Duration duration,
            Instant createdAt) {
        this(
                id,
                stepId,
                toolName,
                input,
                Optional.ofNullable(output),
                status,
                Optional.ofNullable(duration),
                Optional.ofNullable(createdAt));
    }

    public ToolCall {
        output = output == null ? Optional.empty() : output;
        duration = duration == null ? Optional.empty() : duration;
        createdAt = createdAt == null ? Optional.empty() : createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ToolCall that
                        && java.util.Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(id);
    }
}
```

- [ ] **Step 7: Add codec coverage for lowercase hosted statuses**

Add to `Jackson3JsonCodecTest`:

```java
@Test
void encodesAndDecodesHostedToolCallStatuses() {
    var encoded = new String(
            codec.encode(new ToolStatusFixture(ToolCallStatus.SUCCESS)), UTF_8);

    assertThat(encoded).isEqualTo("{\"status\":\"success\"}");

    var decoded = codec.decode(
            "{\"status\":\"timeout\"}".getBytes(UTF_8),
            ToolStatusFixture.class);

    assertThat(decoded.status()).isEqualTo(ToolCallStatus.TIMEOUT);
}

private record ToolStatusFixture(ToolCallStatus status) {}
```

- [ ] **Step 8: Run reasoning-model and codec tests**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am \
  -Dtest=ReasoningModelsTest,Jackson3JsonCodecTest test
```

Expected: all selected tests pass.

- [ ] **Step 9: Commit reasoning request and value models**

```bash
git add \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/NewReasoningStep.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ToolCallStatus.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/NewToolCall.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ToolCall.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/ReasoningModelsTest.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/Jackson3JsonCodecTest.java
git commit -m "feat(java): add reasoning request models"
```

### Task 5: Live reasoning steps, traces, and explanations

**Files:**
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ReasoningStep.java`
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ReasoningTrace.java`
- Create: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ReasoningStepExplanation.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Conversation.java`
- Modify: `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java`
- Create: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/ReasoningSnapshotsTest.java`

**Interfaces:**
- Produces: `Conversation.recordStep(NewReasoningStep)` and `Conversation.trace()`.
- Produces: live `ReasoningStep.recordToolCall(NewToolCall)` and `ReasoningStep.explanation()`.
- Produces: immutable `ReasoningTrace` and `ReasoningStepExplanation`.

- [ ] **Step 1: Write the failing snapshot-model test**

```java
package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReasoningSnapshotsTest {
    private static final UUID CONVERSATION_ID =
            UUID.fromString("a2f55d70-838f-4c41-ae7d-dad30fd25720");
    private static final UUID STEP_ID =
            UUID.fromString("673139d8-dd48-48eb-a5a9-cc84e6e938e3");
    private static final UUID CALL_ID =
            UUID.fromString("15c63f73-f00f-45de-b62e-851ea483a552");

    @Test
    void traceCopiesCollectionsAndGroupsToolCallsByStep() {
        var step = step();
        var call = new ToolCall(
                CALL_ID, STEP_ID, "search", "{}", "[]",
                ToolCallStatus.SUCCESS, null, null);
        var source = new ArrayList<>(List.of(step));
        var trace = new ReasoningTrace(CONVERSATION_ID, source, List.of(call));

        source.clear();

        assertThat(trace.steps()).containsExactly(step);
        assertThat(trace.toolCalls(step)).containsExactly(call);
        assertThatThrownBy(() -> trace.toolCalls().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void explanationsCopyToolCallsAndInfluencedEntities() {
        var explanation = new ReasoningStepExplanation(
                step(),
                List.of(),
                List.of(new Entity(
                        UUID.randomUUID(), "Alice", "person", (String) null)));

        assertThat(explanation.step().conversationId()).isEqualTo(CONVERSATION_ID);
        assertThatThrownBy(() -> explanation.influencedEntities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ReasoningStep step() {
        return new ReasoningStep(
                client(), STEP_ID, CONVERSATION_ID,
                "reason", "act", null, null);
    }

    private static JdkMemoryClient client() {
        return (JdkMemoryClient) MemoryClient.create(
                URI.create("https://memory.test/v1"), "key");
    }
}
```

- [ ] **Step 2: Run test compilation and confirm the live types are missing**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am -Dtest=ReasoningSnapshotsTest test
```

Expected: test compilation fails for `ReasoningStep`, `ReasoningTrace`, and `ReasoningStepExplanation`.

- [ ] **Step 3: Add the live reasoning step**

```java
package org.neo4j.agentmemory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ReasoningStep {
    private final JdkMemoryClient client;
    private final UUID id;
    private final UUID conversationId;
    private final String reasoning;
    private final String actionTaken;
    private final Optional<String> result;
    private final Optional<Instant> createdAt;

    ReasoningStep(
            JdkMemoryClient client,
            UUID id,
            UUID conversationId,
            String reasoning,
            String actionTaken,
            String result,
            Instant createdAt) {
        this.client = client;
        this.id = id;
        this.conversationId = conversationId;
        this.reasoning = reasoning;
        this.actionTaken = actionTaken;
        this.result = Optional.ofNullable(result);
        this.createdAt = Optional.ofNullable(createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public String reasoning() {
        return reasoning;
    }

    public String actionTaken() {
        return actionTaken;
    }

    public Optional<String> result() {
        return result;
    }

    public Optional<Instant> createdAt() {
        return createdAt;
    }

    public CompletableFuture<ToolCall> recordToolCall(NewToolCall call) {
        return client.recordToolCall(id, call);
    }

    public CompletableFuture<ReasoningStepExplanation> explanation() {
        return client.explainReasoningStep(id);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ReasoningStep that && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
```

- [ ] **Step 4: Add trace and explanation snapshots**

`ReasoningTrace.java`:

```java
package org.neo4j.agentmemory;

import java.util.List;
import java.util.UUID;

public record ReasoningTrace(
        UUID conversationId,
        List<ReasoningStep> steps,
        List<ToolCall> toolCalls) {
    public ReasoningTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public List<ToolCall> toolCalls(ReasoningStep step) {
        return toolCalls.stream()
                .filter(call -> java.util.Objects.equals(call.stepId(), step.id()))
                .toList();
    }
}
```

`ReasoningStepExplanation.java`:

```java
package org.neo4j.agentmemory;

import java.util.List;

public record ReasoningStepExplanation(
        ReasoningStep step,
        List<ToolCall> toolCalls,
        List<Entity> influencedEntities) {
    public ReasoningStepExplanation {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        influencedEntities =
                influencedEntities == null ? List.of() : List.copyOf(influencedEntities);
    }
}
```

- [ ] **Step 5: Add conversation-scoped reasoning methods**

```java
public CompletableFuture<ReasoningStep> recordStep(NewReasoningStep step) {
    return client.recordStep(id, step);
}

public CompletableFuture<ReasoningTrace> trace() {
    return client.trace(id);
}
```

- [ ] **Step 6: Add reasoning request mappings to `JdkMemoryClient`**

Add imports for `Duration` and `Instant`, then add:

```java
CompletableFuture<ReasoningStep> recordStep(
        UUID conversationId, NewReasoningStep step) {
    var body = new LinkedHashMap<String, Object>();
    body.put("conversationId", conversationId);
    body.put("reasoning", step.reasoning());
    body.put("actionTaken", step.actionTaken());
    step.result().ifPresent(result -> body.put("result", result));
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
    call.output().ifPresent(output -> body.put("output", output));
    call.duration().ifPresent(duration -> body.put("durationMs", duration.toMillis()));
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
                    call.output().orElse(null),
                    response.status(),
                    call.duration().orElse(null),
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
```

- [ ] **Step 7: Add response conversion helpers**

```java
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
```

- [ ] **Step 8: Add nested response records**

```java
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
```

- [ ] **Step 9: Run snapshot and complete unit verification**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am \
  -Dtest=ReasoningSnapshotsTest,ReasoningModelsTest,Jackson3JsonCodecTest test
mvn -f clients/java/pom.xml -pl memory-client -am test
```

Expected: both commands pass.

- [ ] **Step 10: Commit the live reasoning model**

```bash
git add \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ReasoningStep.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ReasoningTrace.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/ReasoningStepExplanation.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/Conversation.java \
  clients/java/memory-client/src/main/java/org/neo4j/agentmemory/JdkMemoryClient.java \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/ReasoningSnapshotsTest.java
git commit -m "feat(java): add live reasoning traces"
```

### Task 6: Hosted workflow verification and design-document handoff

**Files:**
- Modify: `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/e2e/HostedServiceIT.java`
- Preserve: `CONTEXT.md`
- Preserve: `clients/java/docs/adr/0002-domain-oriented-java-memory-client.md`
- Preserve: `clients/java/docs/adr/0007-keep-agent-workflow-orchestration-outside-core.md`
- Preserve: `clients/java/docs/adr/0008-keep-entity-types-open.md`
- Preserve: `clients/java/docs/adr/0009-represent-optional-conversation-user-explicitly.md`
- Preserve: `clients/java/docs/adr/0010-keep-tool-call-payloads-opaque.md`
- Preserve: `clients/java/docs/adr/0011-use-domain-identity-equality.md`

- [ ] **Step 1: Add the complete hosted skill-primitives workflow**

Add this credential-gated test to `HostedServiceIT`:

```java
@Test
void supportsMemorySkillPrimitivesAndReasoningTrace() {
    var marker = UUID.randomUUID().toString();
    var client = client();
    var conversation = client.createConversation(new CreateConversation()).join();

    assertThat(conversation.userId()).isEmpty();

    var priorEntities = client.searchEntities(marker).join();
    assertThatThrownBy(priorEntities::clear)
            .isInstanceOf(UnsupportedOperationException.class);

    var firstUser = conversation
            .addMessages(List.of(user("first user " + marker)))
            .join()
            .get(0);
    var firstAssistant = conversation
            .addMessage(assistant("first assistant " + marker))
            .join();
    var exchange = conversation
            .addMessages(List.of(
                    user("next user " + marker),
                    assistant("next assistant " + marker)))
            .join();

    assertThat(exchange)
            .extracting(Message::role)
            .containsExactly(USER, ASSISTANT);

    var step = conversation
            .recordStep(new NewReasoningStep(
                    "Application supplied reasoning " + marker,
                    "Search prior entities",
                    "Search completed"))
            .join();

    var call = step.recordToolCall(NewToolCall
                    .builder(
                            "memory_search_entities",
                            "{\"query\":\"" + marker + "\"}")
                    .output("{\"entities\":[]}")
                    .status(ToolCallStatus.SUCCESS)
                    .duration(Duration.ofMillis(25))
                    .build())
            .join();

    assertThat(call.stepId()).isEqualTo(step.id());
    assertThat(call.input()).contains(marker);
    assertThat(call.duration()).contains(Duration.ofMillis(25));

    var trace = conversation.trace().join();

    assertThat(trace.conversationId()).isEqualTo(conversation.id());
    assertThat(trace.steps()).contains(step);
    assertThat(trace.toolCalls(step)).contains(call);

    var explanation = step.explanation().join();

    assertThat(explanation.step()).isEqualTo(step);
    assertThat(explanation.toolCalls()).contains(call);
    assertThat(explanation.influencedEntities()).isNotNull();

    assertThat(conversation.messages().join())
            .extracting(Message::id)
            .contains(
                    firstUser.id(),
                    firstAssistant.id(),
                    exchange.get(0).id(),
                    exchange.get(1).id());
}
```

Add imports for `Duration`, `List`, `NewReasoningStep`, `NewToolCall`, `ToolCallStatus`, and `assertThatThrownBy`.

- [ ] **Step 2: Run credential-free verification**

```bash
env -u MEMORY_API_KEY \
  mvn -f clients/java/pom.xml -pl memory-client -am verify
```

Expected: unit tests pass and hosted tests are skipped only because `MEMORY_API_KEY` is absent.

- [ ] **Step 3: Run hosted verification when credentials are available**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am verify
```

Expected with `MEMORY_API_KEY` configured: all hosted workflows pass within their 30-second timeout. Do not print the key.

- [ ] **Step 4: Check formatting and the complete diff**

```bash
mvn -f clients/java/pom.xml -pl memory-client -am test
git diff --check
git status --short
```

Expected: Maven succeeds, `git diff --check` prints nothing, and status contains only intended Java and documentation changes plus pre-existing unrelated workspace files.

- [ ] **Step 5: Commit hosted verification and agreed design documents**

```bash
git add \
  CONTEXT.md \
  clients/java/docs/adr/0002-domain-oriented-java-memory-client.md \
  clients/java/docs/adr/0007-keep-agent-workflow-orchestration-outside-core.md \
  clients/java/docs/adr/0008-keep-entity-types-open.md \
  clients/java/docs/adr/0009-represent-optional-conversation-user-explicitly.md \
  clients/java/docs/adr/0010-keep-tool-call-payloads-opaque.md \
  clients/java/docs/adr/0011-use-domain-identity-equality.md \
  clients/java/docs/plans/0003-java-memory-workflow-primitives.md \
  clients/java/memory-client/src/test/java/org/neo4j/agentmemory/e2e/HostedServiceIT.java
git commit -m "test(java): verify memory workflow primitives"
```

## Acceptance Criteria

- Skill Step 1 can create a conversation with or without a user, retain its UUID, and never advertise an unsupported user update.
- Skill Step 2 can search entities before the first response with optional type and configurable limit.
- Skill Step 3 can bulk-store the first user message.
- Skill Step 4 can bulk-store complete later exchanges without duplicate first-message persistence.
- Skill Step 7 can create a reasoning step, record opaque tool-call data, retrieve a trace, and explain the live step.
- All new collections are immutable and preserve hosted order.
- All identified domain objects use concrete-type-plus-UUID equality.
- Existing unit and WireMock tests remain passing; no additional WireMock test is added.
- The hosted workflow exercises all new routes.
- The Java conformance bridge remains out of scope.

## Self-Review Results

- Spec coverage: tasks map directly to steps 1–4 and 7; the unsupported user-update sentence is explicitly excluded because no hosted route exists.
- Placeholder scan: no deferred implementation markers or unspecified code steps remain.
- Type consistency: `EntitySearch`, `NewReasoningStep`, `NewToolCall`, `ReasoningStep`, `ToolCall`, `ReasoningTrace`, and `ReasoningStepExplanation` signatures are consistent across production and test tasks.
- Record shape: immutable requests and snapshots are records with raw-value convenience construction paths; the builder remains nested in `NewToolCall`, while live `Conversation` and `ReasoningStep` objects remain classes. Public canonical constructors taking `Optional` are an unavoidable Java record property and are not used by the planned client mappings or examples.
- Wire contract: the live NAMS Swagger definitions were rechecked on 2026-08-21; camelCase fields, six canonical tool-call statuses, sparse write acknowledgements, flat trace tool calls, and minimal influenced entities match the planned wire records.
- JSON boundary: `CreateConversation` is converted to a wire map before encoding, so Jackson never serializes `Optional` directly.
- Source compatibility: the intentional `Conversation.userId()` breaking change includes all known Java test updates.
- Verification limitation: TCK coverage is excluded by decision; credential-free runs cannot execute hosted routes.

## Assumptions

- The live NAMS OpenAPI remains authoritative for camelCase request and response fields.
- `POST /reasoning/tool-calls` continues returning a sparse acknowledgment; the client merges that response with the immutable request.
- Trace and explanation reads supply `createdAt`; immediate write responses may omit it.
- Agent/framework integrations retain live `ReasoningStep` objects when translating step-ID-shaped tool protocols.
- Existing WireMock 3.13.2 configuration and its current single test remain unchanged.
