# Java client await design

Status: implemented on 2026-09-29.

## Purpose and API

Synchronous consumers need a value or an exception when calling the asynchronous Java client. Add two generic static methods to `MemoryClient`, implemented through one package-private `AwaitSupport` class:

```java
static <T> T await(CompletableFuture<T> future) {
    return AwaitSupport.await(future);
}

static <T> T await(CompletableFuture<T> future, Duration timeout) {
    return AwaitSupport.await(future, timeout);
}
```

All operations continue to return `CompletableFuture`. The helper accepts futures from `MemoryClient`, `Conversation`, `ReasoningStep`, and application-composed work. It returns the same value, including null for a successful `CompletableFuture<Void>`. Waiting depends only on the supplied future and shared timeout configuration, so it requires no client instance. Call `MemoryClient.await(...)` directly or statically import `await`:

```java
import static org.neo4j.agentmemory.MemoryClient.await;

import java.time.Duration;

// Inside a method with an existing client and conversation ID:
var conversation = await(client.getConversation(id));
var messages = await(conversation.messages(), Duration.ofSeconds(5));
await(conversation.delete());
```

This adds an optional wait adapter to the asynchronous core described in [ADR 0001](adr/0001-completablefuture-for-java-client.md). It does not change the network operations' return types or the meaning of their futures.

## Waiting and failures

Use `CompletableFuture.get(long, TimeUnit)` with nanosecond precision. Map execution failures through `private static RuntimeException mapped(Throwable failure)` in `AwaitSupport`. The timeout measures waiting inside the helper; it is not an HTTP request deadline. Java evaluates the operation before passing its future to `await`, so even invalid helper arguments cannot undo an operation that already started.

| Outcome | Behavior |
| --- | --- |
| Success | Return the original value, including null. |
| Exceptional completion | Unwrap only `ExecutionException` and `CompletionException` while they have a cause. |
| Client exception | Throw the original instance, retaining its concrete type, diagnostics, and cause chain. |
| Other runtime exception or `Error` | Throw the original instance. |
| Checked failure | Throw `MemoryClientException` retaining the unwrapped failure as its cause. |
| Wait timeout | Throw `MemoryClientException` with the `TimeoutException` as its cause and the effective duration in its message. |
| Wait interruption | Restore the waiting thread's interrupt flag, then throw `MemoryClientException` with the `InterruptedException` as its cause. |
| Cancellation | Preserve `CancellationException`, including cancellation reached through an async wrapper. |

Only an interruption of the actual wait restores the current thread's interrupt flag. An `InterruptedException` stored as another computation's failure is a checked failure, not evidence that this thread was interrupted.

Timeout and interruption leave the supplied future alone: no `cancel`, `orTimeout`, exceptional completion, retry, or retry of the wait. The operation can finish later, and another caller can still consume its result. A timed-out write may still have been applied. Caller-side validation that throws before an operation returns its future remains synchronous.

## Configuration

Precedence: explicit `Duration`, then `NAMS_AWAIT_TIMEOUT_SECONDS`, then 30 seconds.

- `await(future)` lazily reads and caches the environment setting on its first use. The default configuration is shared across all calls to the static helper.
- `NAMS_AWAIT_TIMEOUT_SECONDS` accepts trimmed positive whole seconds. Missing or blank values select 30 seconds.
- Malformed, nonpositive, or out-of-range values log a warning and select 30 seconds. Invalid configuration must not poison class initialization.
- `await(future, timeout)` bypasses the environment setting and fallback logging.
- Explicit durations must be positive and convertible to a positive `long` number of nanoseconds. Null, zero, negative, and overflowing durations throw `IllegalArgumentException`. A null future throws `NullPointerException`.
- The environment range uses the same nanosecond limit as explicit durations: at most 9,223,372,036 whole seconds. Oversized values fall back as invalid configuration.

## Logging

Use `System.getLogger("org.neo4j.agentmemory.AwaitSupport")`. The core adds no runtime logging dependency.

Emit one fallback event per process on the first use of the default-timeout overload, shared across all callers, including concurrent calls:

```text
INFO: NAMS_AWAIT_TIMEOUT_SECONDS is unset or blank; using default await timeout of 30 seconds.
WARNING: NAMS_AWAIT_TIMEOUT_SECONDS is invalid; expected positive whole seconds within the supported range; using default await timeout of 30 seconds.
```

The warning itself reports the fallback; do not emit a second INFO event for the same resolution. A valid environment setting and explicit-duration calls emit no fallback event. Resolve configuration through a lazy static holder so resolution and its event happen once for the loaded library. In containers that load independent copies of the library, each class loader owns its configuration and event; the helper introduces no JVM-global registry.

Configure application logging before the first default-timeout call, because the fallback event is not replayed. The [Java README](../README.md#blocking-helper-and-logging) documents await usage and application-side Logback dependencies and configuration. SLF4J's `slf4j-jdk-platform-logging` bridge routes `System.Logger` to SLF4J, and `logback-classic` supplies the backend. Applications choose their logging backend; the client does not bundle one.

## Implementation and verification

Implemented by [plan 0007](plans/0007-java-client-await.md). Keep transport, live handles, public exception classes, and their async operations available. The change adds two static methods, one package-private helper, focused unit/process tests, and public documentation. No hosted service is needed to verify waiting on supplied futures.

Verification must cover static calls without client construction; original value/exception identity; nested async wrappers; checked, unchecked and fatal failures; successful null; direct and dependent cancellation; timeout and interruption without future mutation; restored interrupt status; explicit-duration validation; valid, absent, blank and invalid environment settings; independent-process default resolution; once-only fallback logging across concurrent calls; and explicit-duration bypass.

The Logback example must be checked in a separate Java process with its documented dependencies, logger name, and XML configuration. That smoke check is documentation validation, not a logging dependency in the client artifact.

## Sources

- [Java 17 CompletableFuture API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html): timed get, exceptional completion, cancellation, and mutating timeout methods.
- [Java 17 System.LoggerFinder](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.LoggerFinder.html): application-selectable JDK logging implementation.
- [SLF4J JDK Platform Logging support](https://www.slf4j.org/manual.html#jep264): bridge artifact.
- [Logback configuration](https://logback.qos.ch/manual/configuration.html): application classpath XML configuration and logger levels.
