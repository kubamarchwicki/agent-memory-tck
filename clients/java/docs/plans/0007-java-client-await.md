# Java Client Await Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add interruptible, bounded `MemoryClient.await` helpers that return a value or throw a useful exception, with an environment-configured default and observable fallback.

**Architecture:** Two generic static methods on `MemoryClient` delegate to a package-private `AwaitSupport`. That helper centralizes timed waiting and exception mapping; a lazy static holder resolves the default duration once. Application logging captures JDK `System.Logger` events through its chosen backend.

**Tech Stack:** Java 17, Maven, `CompletableFuture`, `Duration`, `System.Logger`, existing JUnit Jupiter 5.14.4 and AssertJ 3.27.7. The application-side Logback example uses SLF4J 2.0.20 and Logback 1.5.38.

**Spec:** [Agreed await design](../java-client-await-design.md). Read the [Java README](../../README.md) for the current API; the [README content below](#readme-content) is added as part of implementation. Inspected baseline: `06c26bf9a7c022529461f8127fd07fe2ab97c33d`.

## Global Constraints

- All operations continue to return `CompletableFuture`.
- Precedence: explicit `Duration`, then `NAMS_AWAIT_TIMEOUT_SECONDS`, then 30 seconds.
- Timeout and interruption leave the supplied future alone: no `cancel`, `orTimeout`, exceptional completion, retry, or retry of the wait.
- Use `System.getLogger("org.neo4j.agentmemory.AwaitSupport")`. The core adds no runtime logging dependency.
- Malformed, nonpositive, or out-of-range values log a warning and select 30 seconds. Invalid configuration must not poison class initialization.
- Only an interruption of the actual wait restores the current thread's interrupt flag.
- Target Java 17; preserve unrelated and untracked files. This checkout contains existing untracked design and configuration files. Stage only named task files when committing.

---

## File responsibilities

| File | Responsibility |
| --- | --- |
| `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/MemoryClient.java` | Public static methods and their Javadoc |
| `clients/java/memory-client/src/main/java/org/neo4j/agentmemory/AwaitSupport.java` | Waiting, exception mapping, duration validation, lazy environment configuration and logging |
| `clients/java/memory-client/src/test/java/org/neo4j/agentmemory/MemoryClientAwaitTest.java` | Public behavior, parser boundaries, and fresh-process logging/configuration checks |
| `clients/java/README.md` | Usage, timeout/error semantics, and application-side logging setup |
| `.env.example` | Commented Java await setting |
| `clients/java/docs/java-client-await-design.md` | Agreed contract and implementation status |

This is one cohesive API change with one review gate. Do not introduce synchronous versions of `Conversation` or `ReasoningStep`, new public exception classes, or a logging framework in the core POM.

## Task 1: Implement and document bounded waiting

**Files:** Create `AwaitSupport.java` and `MemoryClientAwaitTest.java`; modify the other files in the responsibility table.

**Interfaces:**

- Consumes the current `MemoryClient` and `MemoryClientException(String, Throwable)`.
- Produces public `static <T> T await(CompletableFuture<T>)` and `static <T> T await(CompletableFuture<T>, Duration)` methods. Call them as `MemoryClient.await(...)` or through `import static org.neo4j.agentmemory.MemoryClient.await;`.
- Produces package-private `AwaitSupport.await` overloads and `resolveDefaultTimeout(String, System.Logger)` for testing environment parsing without mutating process environment.

- [x] **Step 1: Verify the baseline and add the failing contract tests.**

Run the existing unit suite from the repository root:

```bash
mvn -f clients/java/pom.xml -pl memory-client -am test
```

Create `MemoryClientAwaitTest.java` with this code. It uses the static helpers with in-memory futures without constructing a client. The subprocess cases ensure that environment resolution and class initialization are independently exercised. Their five-second process deadline also proves that a one-second environment timeout takes effect instead of falling back to 30 seconds.

```java
package org.neo4j.agentmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.agentmemory.MemoryClient.await;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MemoryClientAwaitTest {
    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void returnsTheOriginalValueAndSuccessfulNull() {
        var value = new Object();
        assertThat(MemoryClient.await(CompletableFuture.completedFuture(value), SECOND))
                .isSameAs(value);
        assertThat(await(CompletableFuture.<Void>completedFuture(null), SECOND))
                .isNull();
    }

    @Test
    void preservesClientDiagnosticsAndOtherUncheckedFailures() {
        var service = new MemoryServiceException("read", 503,
                Map.of("retry-after", List.of("1")), "application/json", "unavailable");
        var decode = new ResponseDecodingException("read", 200, "application/json",
                "invalid", new IOException("bad payload"));
        for (Throwable original : List.of(service, decode,
                new IllegalStateException("application"), new AssertionError("fatal"))) {
            var wrapped = new CompletionException(new ExecutionException(original));
            assertThatThrownBy(() -> await(CompletableFuture.failedFuture(wrapped), SECOND))
                    .isSameAs(original);
        }
    }

    @Test
    void wrapsCheckedFailuresWithoutInterruptingTheWaitingThread() {
        var failure = new InterruptedException("another computation was interrupted");
        assertThatThrownBy(() -> await(CompletableFuture.failedFuture(failure), SECOND))
                .isInstanceOf(MemoryClientException.class).hasCause(failure);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void preservesDirectAndDependentCancellation() {
        var source = new CompletableFuture<String>();
        var dependent = source.thenApply(String::length);
        source.cancel(false);
        assertThatThrownBy(() -> await(source, SECOND))
                .isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> await(dependent, SECOND))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void timedOutFutureCanStillCompleteNormally() {
        var source = new CompletableFuture<String>();
        assertThatThrownBy(() -> await(source, Duration.ofMillis(1)))
                .isInstanceOf(MemoryClientException.class)
                .hasCauseInstanceOf(TimeoutException.class)
                .hasMessageContaining("PT0.001S");
        assertThat(source.isDone()).isFalse();
        assertThat(source.complete("later")).isTrue();
        assertThat(await(source, SECOND)).isEqualTo("later");
    }

    @Test
    void interruptionRestoresFlagAndLeavesFutureUsable() {
        var source = new CompletableFuture<String>();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> await(source, SECOND))
                    .isInstanceOf(MemoryClientException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(source.isDone()).isFalse();
        } finally {
            Thread.interrupted();
        }
        assertThat(source.complete("later")).isTrue();
        assertThat(await(source, SECOND)).isEqualTo("later");
    }

    @Test
    void rejectsInvalidExplicitArgumentsEvenForCompletedFutures() {
        var source = CompletableFuture.completedFuture("ready");
        assertThatThrownBy(() -> await(source, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (var duration : List.of(Duration.ZERO, Duration.ofNanos(-1),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> await(source, duration))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> await(null, SECOND))
                .isInstanceOf(NullPointerException.class);
        assertThat(await(source, Duration.ofNanos(1))).isEqualTo("ready");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentSettingLogsInfoAndUsesThirtySeconds(String raw) {
        var logger = new RecordingLogger();
        assertThat(AwaitSupport.resolveDefaultTimeout(raw, logger)).isEqualTo(Duration.ofSeconds(30));
        assertThat(logger.events).hasSize(1);
        assertThat(logger.events.get(0)).startsWith("INFO:")
                .contains("NAMS_AWAIT_TIMEOUT_SECONDS", "30 seconds");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "0", "-1", "1.5", "9223372037", "999999999999999999999"})
    void invalidSettingLogsWarningAndUsesThirtySeconds(String raw) {
        var logger = new RecordingLogger();
        assertThat(AwaitSupport.resolveDefaultTimeout(raw, logger)).isEqualTo(Duration.ofSeconds(30));
        assertThat(logger.events).hasSize(1);
        assertThat(logger.events.get(0)).startsWith("WARNING:")
                .contains("NAMS_AWAIT_TIMEOUT_SECONDS", "30 seconds");
    }

    @Test
    void acceptsTrimmedPositiveWholeSecondsWithoutFallbackLogging() {
        var logger = new RecordingLogger();
        assertThat(AwaitSupport.resolveDefaultTimeout(" 2 ", logger)).isEqualTo(Duration.ofSeconds(2));
        assertThat(AwaitSupport.resolveDefaultTimeout("9223372036", logger))
                .isEqualTo(Duration.ofSeconds(9223372036L));
        assertThat(logger.events).isEmpty();
    }

    @Test
    void defaultConfigurationLogsOnceAcrossConcurrentCalls() throws Exception {
        var missing = probe(null, "default");
        assertThat(missing.lines().filter(line -> line.contains("using default await timeout")))
                .hasSize(1);
        assertThat(missing).contains("INFO:", "NAMS_AWAIT_TIMEOUT_SECONDS", "30 seconds");
        var invalid = probe("invalid", "default");
        assertThat(invalid.lines().filter(line -> line.contains("using default await timeout")))
                .hasSize(1);
        assertThat(invalid).contains("WARNING:", "30 seconds");
    }

    @Test
    void explicitDurationBypassesEnvironmentAndDefaultLogging() throws Exception {
        assertThat(probe("invalid", "explicit")).doesNotContain("NAMS_AWAIT_TIMEOUT_SECONDS");
        assertThat(probe(null, "explicit")).doesNotContain("NAMS_AWAIT_TIMEOUT_SECONDS");
    }

    @Test
    void environmentControlsTheActualDefaultWait() throws Exception {
        assertThat(probe("1", "pending")).contains("timed out").doesNotContain("using default");
    }

    private static String probe(String setting, String mode) throws Exception {
        var command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), mode).redirectErrorStream(true);
        if (setting == null) command.environment().remove("NAMS_AWAIT_TIMEOUT_SECONDS");
        else command.environment().put("NAMS_AWAIT_TIMEOUT_SECONDS", setting);
        var process = command.start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            if (args[0].equals("pending")) {
                try {
                    await(new CompletableFuture<>());
                    throw new AssertionError("expected a timed wait");
                } catch (MemoryClientException failure) {
                    if (!(failure.getCause() instanceof TimeoutException)) throw failure;
                    System.out.println("timed out");
                }
                return;
            }
            IntStream.range(0, 16).parallel().forEach(index -> {
                var future = CompletableFuture.completedFuture("ready");
                var value = args[0].equals("explicit")
                        ? await(future, SECOND) : await(future);
                if (!value.equals("ready")) throw new AssertionError(value);
            });
        }
    }

    private static class RecordingLogger implements System.Logger {
        final List<String> events = new ArrayList<>();
        public String getName() { return "await-test"; }
        public boolean isLoggable(Level level) { return true; }
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            events.add(level + ":" + message);
        }
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            events.add(level + ":" + java.text.MessageFormat.format(format, params));
        }
    }
}
```

- [x] **Step 2: Run the focused tests and confirm the missing API failure.**

```bash
mvn -f clients/java/pom.xml -pl memory-client -Dtest=MemoryClientAwaitTest test
```

Expected: test compilation fails because `MemoryClient.await` and `AwaitSupport` do not exist. An unrelated dependency or environment error does not establish this failing baseline.

- [x] **Step 3: Implement the helper and public methods.**

Create `AwaitSupport.java`:

```java
package org.neo4j.agentmemory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AwaitSupport {
    private static final String ENV = "NAMS_AWAIT_TIMEOUT_SECONDS";
    private static final Duration FALLBACK = Duration.ofSeconds(30);
    private static final System.Logger LOGGER = System.getLogger(AwaitSupport.class.getName());

    private AwaitSupport() {}

    static <T> T await(CompletableFuture<T> future) {
        Objects.requireNonNull(future, "future must not be null");
        return await(future, DefaultTimeout.VALUE);
    }

    static <T> T await(CompletableFuture<T> future, Duration timeout) {
        Objects.requireNonNull(future, "future must not be null");
        long nanos = positiveNanos(timeout);
        try {
            return future.get(nanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MemoryClientException("Interrupted while awaiting completion", interrupted);
        } catch (TimeoutException timedOut) {
            throw new MemoryClientException("Await timed out after " + timeout, timedOut);
        } catch (ExecutionException failure) {
            throw mapped(failure);
        }
    }

    private static RuntimeException mapped(Throwable failure) {
        while ((failure instanceof ExecutionException || failure instanceof CompletionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new MemoryClientException("Asynchronous operation failed", failure);
    }

    private static long positiveNanos(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be a positive Duration");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("timeout exceeds the supported nanosecond range", overflow);
        }
    }

    static Duration resolveDefaultTimeout(String raw, System.Logger logger) {
        if (raw == null || raw.isBlank()) {
            logger.log(System.Logger.Level.INFO,
                    ENV + " is unset or blank; using default await timeout of 30 seconds.");
            return FALLBACK;
        }
        try {
            var timeout = Duration.ofSeconds(Long.parseLong(raw.trim()));
            positiveNanos(timeout);
            return timeout;
        } catch (IllegalArgumentException invalid) {
            logger.log(System.Logger.Level.WARNING,
                    ENV + " is invalid; expected positive whole seconds within the supported range; "
                            + "using default await timeout of 30 seconds.");
            return FALLBACK;
        }
    }

    private static final class DefaultTimeout {
        private static final Duration VALUE = resolveDefaultTimeout(System.getenv(ENV), LOGGER);
    }
}
```

Import `java.time.Duration` in `MemoryClient.java` and add these methods:

```java
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
```

- [x] **Step 4: Run the focused tests and full unit suite.**

```bash
mvn -f clients/java/pom.xml -pl memory-client -Dtest=MemoryClientAwaitTest test
mvn -f clients/java/pom.xml -pl memory-client -am test
```

Expected: all new tests and existing tests pass. Examine test reports; report actual counts. These commands do not claim hosted-service conformance or write-cancellation guarantees.

- [x] **Step 5: Add README documentation, usage and logging examples, and the environment setting.**

Once the helper is implemented, insert the complete section below into `clients/java/README.md`, immediately before `Dependency and source delivery`. It documents both overloads and includes a runnable class that reads an existing conversation with the default timeout, then reads messages with an explicit timeout. The logging dependencies and XML belong in that same README update.

### README content

````markdown
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

The logger is `org.neo4j.agentmemory.AwaitSupport`, using JDK `System.Logger`. To capture these events with Logback, add the JDK Platform Logging bridge and Logback backend to the **application's** dependencies. The bridge routes `System.Logger` through SLF4J. [SLF4J documents the bridge here](https://www.slf4j.org/manual.html#jep264).

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
  <logger name="org.neo4j.agentmemory.AwaitSupport" level="INFO"/>
  <root level="WARN">
    <appender-ref ref="CONSOLE"/>
  </root>
</configuration>
```

The explicit logger level admits the fallback INFO event even with a WARN root. `System.Logger.Level.WARNING` appears as WARN in Logback. Configure the bridge and backend at startup, before the first default-timeout call; the once-only event is not replayed. See the [Logback configuration manual](https://logback.qos.ch/manual/configuration.html).

You can verify the logging route independently of a client operation:

```java
System.getLogger("org.neo4j.agentmemory.AwaitSupport")
        .log(System.Logger.Level.INFO, "NAMS logging configuration check");
```
````

Append the following commented setting to `.env.example`:

```dotenv
# Optional Java client await timeout in positive whole seconds (default: 30).
# Export into the Java process environment; the core does not load .env files.
# NAMS_AWAIT_TIMEOUT_SECONDS=30
```

Change the design status to `Implemented` only after the verification gate. At that point, update its logging documentation link from this plan to `../README.md#blocking-helper-and-logging`.

- [x] **Step 6: Verify the logging instructions and review the final diff.**

Use a temporary Maven application with the exact two runtime dependencies and XML from Step 5, now added to the README, plus this class:

```java
public final class LoggingProbe {
    public static void main(String[] args) {
        var logger = System.getLogger("org.neo4j.agentmemory.AwaitSupport");
        logger.log(System.Logger.Level.INFO, "NAMS logging configuration check");
        logger.log(System.Logger.Level.WARNING, "NAMS warning configuration check");
    }
}
```

Resolve its runtime classpath with `mvn dependency:build-classpath`, compile with `javac --release 17`, and launch it in a fresh JVM. Both messages must appear exactly once through the configured Logback console appender, including INFO with the root set to WARN. The setup uses application dependencies; do not add either dependency to the core POM. Keep the probe outside the repository.

Run:

```bash
git diff --check
git diff -- clients/java/memory-client/src/main/java/org/neo4j/agentmemory/MemoryClient.java clients/java/README.md .env.example
git status --short
```

Read the newly created helper and test files explicitly; unstaged new files do not appear in an ordinary `git diff`. Confirm all configuration and examples use `NAMS_AWAIT_TIMEOUT_SECONDS`, errors preserve causes and identity, and no API path mutates the supplied future. Verify documentation matches behavior before marking the design implemented.

- [x] **Step 7: Commit the reviewed change when executing this plan.**

```bash
git add clients/java/memory-client/src/main/java/org/neo4j/agentmemory/MemoryClient.java clients/java/memory-client/src/main/java/org/neo4j/agentmemory/AwaitSupport.java clients/java/memory-client/src/test/java/org/neo4j/agentmemory/MemoryClientAwaitTest.java clients/java/README.md .env.example clients/java/docs/java-client-await-design.md clients/java/docs/plans/0007-java-client-await.md
git commit -m "feat(java): add bounded await helpers with configurable timeout"
```

## Plan review checklist

- [x] All agreed behavior is represented in the implementation and tests above.
- [x] Environment spelling is `NAMS_AWAIT_TIMEOUT_SECONDS` throughout.
- [x] Default configuration is lazy and shared; explicit durations bypass it.
- [x] Invalid configuration emits a single warning containing the fallback duration.
- [x] Public methods preserve values and client exception objects without cancelling futures.
- [x] Both public await methods are static; examples use a static import, and tests exercise them without constructing a client.
- [x] Planned README content includes both await overloads, a complete usage example, and the application-side bridge, backend and logger configuration.
- [x] Code steps contain concrete code and validation commands.
- [x] Validate the README logging dependencies and XML in a separate Java process.
- [x] Record implementation test results during execution.

## Documentation validation evidence

On 2026-09-29, a temporary Maven application resolved the exact documentation dependencies (`slf4j-jdk-platform-logging:2.0.20` and `logback-classic:1.5.38`). A probe compiled with `javac --release 17` and ran on OpenJDK 17.0.20.1 using the documented `logback.xml`. These dependency and configuration examples now reside in the README. During implementation, the documented setup was re-resolved, compiled with `javac --release 17`, and launched in a fresh JVM. The probe's INFO and WARNING calls each produced one correctly named Logback event, despite the WARN root level:

```text
INFO  org.neo4j.agentmemory.AwaitSupport - NAMS logging configuration check
WARN  org.neo4j.agentmemory.AwaitSupport - NAMS warning configuration check
```

README XML parsing, local document links, code-fence pairing, environment spelling and `git diff --check` passed during design review. The current implementation and test results are recorded below.


## Implementation results (2026-09-29)

- Baseline command `mvn -f clients/java/pom.xml -pl memory-client -am test` failed before changes: existing `RecordStateTest` AssertJ generic assertions did not compile (two constructor errors; 65 tests reported, 0 failures).
- The exact focused-test command first encountered Maven Compiler Plugin 3.1's Java 5 default because it does not honor the inherited `maven.compiler.release` property. With `-Dmaven.compiler.source=17 -Dmaven.compiler.target=17`, the red run failed on missing `MemoryClient.await` and `AwaitSupport`, as required.
- After implementation, `mvn -f clients/java/pom.xml -pl memory-client -Dtest=MemoryClientAwaitTest -Dmaven.compiler.source=17 -Dmaven.compiler.target=17 test` passed: 21 tests, 0 failures, 0 errors, 0 skipped.
- Full suite command `mvn -f clients/java/pom.xml -pl memory-client -am -Dmaven.compiler.source=17 -Dmaven.compiler.target=17 test` passed: 86 tests, 0 failures, 0 errors, 0 skipped.
- Logging smoke used a temporary Maven app outside the repository with README dependencies and XML, resolved via `mvn dependency:build-classpath`, compiled with `javac --release 17`, then launched in a separate JVM. It emitted exactly one INFO and one WARN event from `org.neo4j.agentmemory.AwaitSupport`, including INFO while root level was WARN.
- `git diff --check` passed. Self-review confirmed both API methods are static and do not require a client instance, explicit waits bypass configuration, fallback is lazily shared, failures preserve identity/cause semantics, and no path mutates the supplied future.
- Concern: the project POM's compiler plugin is too old to use its configured `maven.compiler.release` property; this task used command-line Java 17 source/target overrides and did not change that unrelated build configuration. The baseline also has a separate pre-existing AssertJ compile defect.
