# Neo4j Agent Memory Java client

This Java 17 client accesses the hosted Neo4j Agent Memory Service. The core artifact is `org.neo4j:agent-memory-client:0.1.0-SNAPSHOT`. It uses the JDK HTTP client and an optional Jackson 3 JSON adapter; applications must provide Jackson 3 at runtime. It has no application framework dependency.

## Public API

`MemoryClient.create(String apiKey)` uses `MEMORY_ENDPOINT` if set, otherwise `https://memory.neo4jlabs.com/v1`. `MemoryClient.create(URI endpoint, String apiKey)` selects an explicit endpoint. The client provides `createConversation(CreateConversation)`, `listConversations(ListConversations)`, `getConversation(UUID)`, and `searchEntities(String)` / `searchEntities(EntitySearch)`.

`CreateConversation` has `userId` and `Map<String, String> metadata` components, plus no-argument and user-only constructors. A user ID is optional; there is no caller-supplied conversation ID. Null or empty metadata becomes an immutable empty map and is omitted from the create request. The `title` metadata entry is sent as `metadata.title` and can be read from a returned conversation.

`Conversation` exposes its `UUID id()`, `Optional<String> userId()`, direct immutable `Map<String, String> metadata()`, `Optional<String> title()`, `Optional<Instant> createdAt()` and `updatedAt()`, `Optional<String> firstMessageSnippet()`, and `Optional<Long> messageCount()`. Missing count is distinct from zero. The title uses the list response title when supplied, otherwise `metadata.title`; the client generates no title or snippet. These fields describe the response snapshot, so fetch the conversation again to observe later remote changes. `addMessage(NewMessage)`, `addMessages(List<NewMessage>)`, `messages()`, `messages(int)`, `context()`, `recordStep(NewReasoningStep)`, `trace()`, and `delete()` are asynchronous operations on a live conversation.

`Message` has UUID, role, and content. `NewMessage.user(String)` and `NewMessage.assistant(String)` create message input. `ConversationContext` exposes observations, reflections, and recent messages. Entity search and reasoning trace types are also in `org.neo4j.agentmemory`. Public request and value records retain their semantic component types; nullable scalar components use null for absence, with optional views where supplied. Collection components normalize missing values to direct immutable maps or lists, never `Optional` containers. Returned collections are immutable snapshots.

`messages()` sends no limit and uses the hosted default of 50. `messages(int limit)` sends a limit from 1 through 200 and throws `IllegalArgumentException` synchronously outside that range. The client preserves the service's newest-first message order. It does not sort, reverse, deduplicate, paginate, or trim results locally. A caller that needs chronological model input can reverse an application-owned copy:

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.neo4j.agentmemory.CreateConversation;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.NewMessage;

// Inside a method that declares throws Exception:
var client = MemoryClient.create(System.getenv("MEMORY_API_KEY"));
var conversation = client.createConversation(new CreateConversation(
        null, Map.of("title", "Find hotels in Zermatt"))).get(20, TimeUnit.SECONDS);
conversation.addMessage(NewMessage.user("Find hotels in Zermatt")).get(20, TimeUnit.SECONDS);
var recent = conversation.messages(20).get(20, TimeUnit.SECONDS);
var chronological = new ArrayList<>(recent);
Collections.reverse(chronological); // Application-owned conversion for model input.
var latest = conversation.messages(1).get(20, TimeUnit.SECONDS);
```

Caller-side timed waits do not establish transport cancellation. The client performs no automatic write retries.

## Dependency and source delivery

```xml
<dependency>
  <groupId>org.neo4j</groupId>
  <artifactId>agent-memory-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>tools.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>3.1.5</version>
</dependency>
```

These coordinates alone do not imply a published artifact. The supported handoff is a reachable, exact Git commit and a source build before downstream CI resolves the snapshot. From a clean checkout of the supplied commit, with Java 17+ and Maven available:

```bash
git clone <reachable-repository-url> agent-memory-tck
cd agent-memory-tck
git checkout --detach <delivery-commit-id>
mvn -f clients/java/pom.xml -pl memory-client -am clean install
```

Downstream CI should run that build in its own Maven repository before resolving `org.neo4j:agent-memory-client:0.1.0-SNAPSHOT`. Delivery evidence must include the source commit ID, SHA-256 hashes of the built JAR and installed POM, Java and Maven versions, and hosted test counts. The credential-gated persistence check is `mvn -f clients/java/pom.xml -pl memory-client -Dit.test=ConversationMetadataIT verify`; a skip without `MEMORY_API_KEY` is not hosted persistence evidence. Use `MEMORY_ENDPOINT` to select an isolated hosted test workspace. The check deletes only the UUID it creates.
