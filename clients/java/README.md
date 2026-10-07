# Neo4j Agent Memory Java client

This Java 17 client accesses the hosted Neo4j Agent Memory Service. The core artifact is `org.neo4j:agent-memory-client:0.1.0-SNAPSHOT`. It uses the JDK HTTP client and an optional Jackson 3 JSON adapter; applications must provide Jackson 3 at runtime. It has no application framework dependency.

## Public API

MemoryClient.create(String apiKey) uses MEMORY_ENDPOINT if set, otherwise
https://memory.neo4jlabs.com/v1. MemoryClient.create(URI endpoint, String apiKey)
selects an explicit endpoint. Both factories construct the default JDK HTTP
adapter without checking remote reachability or authentication.

MemoryClient defines all supported domain operations, including those addressed
by Conversation and Reasoning Step UUIDs. Applications can use these operations
directly or through the live Conversation and ReasoningStep handles. Every
returned handle is bound to the client that produced it. Alternate adapters
implement MemoryClient and can construct those same handles from domain values.

The method Javadoc on MemoryClient defines the shared operation behavior,
including result order, immutable collections, validation timing, and failures.
The handle methods link to those contracts. Successful writes do not establish
that extraction or enrichment is ready.

| Package | Contents |
| --- | --- |
| org.neo4j.agentmemory | MemoryClient and its default factories and await helpers |
| org.neo4j.agentmemory.conversation | Conversation, requests, Messages, and Conversation Context |
| org.neo4j.agentmemory.reasoning | Reasoning Steps, traces, explanations, and Tool Calls |
| org.neo4j.agentmemory.entity | Entity values and search inputs |
| org.neo4j.agentmemory.exception | Client-owned failures |
| org.neo4j.agentmemory.internal.http | Unsupported implementation details of the default HTTP adapter |

Public domain types have moved from the root package to the packages above.
Update source imports when adopting this version. The artifact coordinates
remain org.neo4j:agent-memory-client:0.1.0-SNAPSHOT.

`CreateConversation` has `userId` and `Map<String, String> metadata` components, plus no-argument and user-only constructors. A user ID is optional; there is no caller-supplied conversation ID. Null or empty metadata becomes an immutable empty map and is omitted from the create request. The `title` metadata entry is sent as `metadata.title` and can be read from a returned conversation.

`Conversation` exposes its `UUID id()`, `Optional<String> userId()`, direct immutable `Map<String, String> metadata()`, `Optional<String> title()`, `Optional<Instant> createdAt()` and `updatedAt()`, `Optional<String> firstMessageSnippet()`, and `Optional<Long> messageCount()`. Missing count is distinct from zero. The title uses the list response title when supplied, otherwise `metadata.title`; the client generates no title or snippet. These fields describe the response snapshot, so fetch the conversation again to observe later remote changes. `addMessage(NewMessage)`, `addMessages(List<NewMessage>)`, `messages()`, `messages(int)`, `context()`, `recordStep(NewReasoningStep)`, `trace()`, and `delete()` are asynchronous operations on a live conversation.

`Message` has UUID, role, and content. `NewMessage.user(String)` and `NewMessage.assistant(String)` create message input. `ConversationContext` exposes observations, reflections, and recent messages. Entity search types are in `org.neo4j.agentmemory.entity`; reasoning trace types are in `org.neo4j.agentmemory.reasoning`. Public request and value records retain their semantic component types; nullable scalar components use null for absence, with optional views where supplied. Optional collection components normalize missing values to direct immutable maps or lists, never `Optional` containers. Conversation-list and message-read responses require their respective `conversations` and `messages` arrays; missing or null arrays fail with `ResponseDecodingException`, while explicit empty arrays return empty lists. Returned collections are immutable snapshots.

`messages()` sends no limit and uses the hosted default of 50. `messages(int limit)` sends a limit from 1 through 200 and throws `IllegalArgumentException` synchronously outside that range. The client preserves the service's newest-first message order. It does not sort, reverse, deduplicate, paginate, or trim results locally. A caller that needs chronological model input can reverse an application-owned copy:

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.neo4j.agentmemory.conversation.CreateConversation;
import org.neo4j.agentmemory.MemoryClient;
import org.neo4j.agentmemory.conversation.NewMessage;

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

## Blocking helper and logging

`MemoryClient.await(future)` returns a future's value, including null for a successful void operation. `MemoryClient.await(future, Duration)` supplies a timeout for that call. Both methods are static; importing `org.neo4j.agentmemory.MemoryClient.await` statically lets you write `await(...)` as shown below. Both throw the underlying client exception directly. Other runtime exceptions and errors propagate; checked failures are wrapped in `MemoryClientException` with their cause retained.

`NAMS_AWAIT_TIMEOUT_SECONDS` configures the default wait in positive whole seconds. It is read once on first use of `await(future)` and defaults to 30 seconds when absent, blank, malformed, nonpositive, or too large. An explicit positive `Duration` bypasses it and supports subsecond waits. Null or invalid explicit durations throw `IllegalArgumentException`.

The first use of the built-in fallback emits one event shared by all callers: INFO when the setting is absent or blank, or WARNING when it is invalid. Both identify the variable and the 30-second timeout. An invalid setting emits only the warning. A valid setting or explicit-duration call emits no fallback event.

Timeout and interruption throw `MemoryClientException`, retaining `TimeoutException` or `InterruptedException` as the cause. Interruption restores the thread's interrupt flag. The timeout measures this wait, not the operation's total lifetime. Both outcomes leave the supplied future running, so a timed-out write can still complete.

For example, pass an existing conversation UUID as the first command-line argument and provide `MEMORY_API_KEY` in the environment. This example prints its recent messages in the service's newest-first order:

```java
import static org.neo4j.agentmemory.MemoryClient.await;

import java.time.Duration;
import java.util.UUID;
import org.neo4j.agentmemory.MemoryClient;

public final class ReadConversationMessages {
    public static void main(String[] args) {
        var client = MemoryClient.create(System.getenv("MEMORY_API_KEY"));
        var conversationId = UUID.fromString(args[0]);

        // Uses NAMS_AWAIT_TIMEOUT_SECONDS, falling back to 30 seconds.
        var conversation = await(client.getConversation(conversationId));

        // Overrides the default for this call and returns List<Message> directly.
        var messages = await(conversation.messages(20), Duration.ofSeconds(5));
        messages.forEach(message -> System.out.println(message.content()));
    }
}
```

The client uses JDK `System.Logger`: `org.neo4j.agentmemory.JdkMemoryClient` for initialization and operations, and `org.neo4j.agentmemory.AwaitSupport` for await configuration. To capture these events with Logback, add the JDK Platform Logging bridge and Logback backend to the **application's** dependencies. The bridge routes `System.Logger` through SLF4J. [SLF4J documents the bridge here](https://www.slf4j.org/manual.html#jep264).

```xml
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-jdk-platform-logging</artifactId>
  <version>2.0.20</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>ch.qos.logback</groupId>
  <artifactId>logback-classic</artifactId>
  <version>1.5.38</version>
  <scope>runtime</scope>
</dependency>
```

These are concrete example versions; use your application's dependency management to align its SLF4J 2.0 components. If the application already provides Logback, keep that backend and add the bridge. Select one SLF4J backend; for example, replace `slf4j-simple` when using Logback.

Place this in the application's `src/main/resources/logback.xml`, or merge the logger entry into its existing configuration:

```xml
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger - %msg%n</pattern>
    </encoder>
  </appender>
  <logger name="org.neo4j.agentmemory" level="INFO"/>
  <root level="WARN">
    <appender-ref ref="CONSOLE"/>
  </root>
</configuration>
```

The package entry admits initialization and fallback INFO events even with a WARN root. Change this single entry to `level="DEBUG"` to expose ordinary operation summaries across the package. `System.Logger.Level.WARNING` appears as WARN in Logback. Configure the bridge and backend at application startup; once-only await fallback events are not replayed. See the [Logback configuration manual](https://logback.qos.ch/manual/configuration.html).

| Level | Core events |
| --- | --- |
| DEBUG | Operation starts and terminal success, failure, or cancellation, including HTTP 4xx/5xx and encoding, transport, and decoding failures |
| INFO | Successful local client construction; once-only absent or blank default-await setting fallback |
| WARNING | Once-only invalid default-await setting fallback |
| ERROR | None currently; applications own reporting of returned exceptions |

`event=client.initialized transport=jdk-http` means local construction succeeded. It does not establish service reachability or authentication. Operation timing starts at shared request-helper entry, includes POST encoding, and ends after HTTP status validation, decoding, and domain conversion (status validation alone for DELETE). Success does not establish extraction or enrichment readiness. Synchronous validation before helper entry emits no operation event.

For example, at DEBUG the client can emit these diagnostic summaries (values are illustrative):

```text
event=operation.started operation=listConversations callId=7
event=operation.completed operation=listConversations callId=7 outcome=success durationMs=12 status=200 requestId=req-123 resultCount=2
event=operation.started operation=getConversation callId=8
event=operation.completed operation=getConversation callId=8 outcome=failure durationMs=9 status=404 phase=http errorType=MemoryServiceException
```

`callId` is a local counter shared across client instances in the loaded client classes; it is not a durable or distributed identifier. Terminal events carry elapsed `durationMs`, outcome, observed HTTP `status` when available, and optional result counts. Lists use `resultCount`; context uses `reflectionCount`, `observationCount`, and `messageCount`; traces use `stepCount` and `toolCallCount`; explanations use `toolCallCount` and `entityCount`. Failures and cancellations carry a phase (`encode`, `request`, `transport`, `http`, or `decode`) and the unwrapped exception's simple class name in `errorType`.

An optional `requestId` comes from the first available `x-request-id`, `request-id`, or `x-amzn-requestid` response header, in that order, and is emitted only when it matches `[A-Za-z0-9._:-]{1,128}`. The client excludes credentials, raw URLs, resource IDs, message and reasoning content, tool payloads, response excerpts, and exception messages or stack traces. Field text is a diagnostic convention, not a public event API.

Cancellation describes the returned future's outcome, without confirming transport cancellation. An await timeout or interruption leaves the supplied future running and emits no extra operation failure; `await` also avoids reporting propagated failures again. Operations retain the original returned future and exception behavior. Ordinary logger runtime failures are isolated from operation results, while fatal errors are not swallowed. A caller can observe future completion before its terminal logging callback finishes: `join()` does not flush logging. Threshold changes can suppress one event of a pair, and configuring a backend does not guarantee delivery.

You can verify package inheritance independently of a client operation:

```java
var clientLogger = System.getLogger("org.neo4j.agentmemory.JdkMemoryClient");
clientLogger.log(System.Logger.Level.INFO, "probe-initialized");
clientLogger.log(System.Logger.Level.DEBUG, "probe-completed");
System.getLogger("org.neo4j.agentmemory.AwaitSupport")
        .log(System.Logger.Level.WARNING, "probe-invalid-setting");
```

With the package at INFO and root at WARN, only the INFO and WARNING probes appear. In a fresh JVM with the package changed to DEBUG, all three appear.

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
