---
status: accepted
---

# Let applications own the logging backend

The Java 17 client emits diagnostics through JDK `System.Logger` under `org.neo4j.agentmemory`, without a required runtime logging dependency or public logging configuration API. Applications select the backend and thresholds, including an optional SLF4J JDK platform bridge and Logback backend, so the core does not impose a logging stack on framework integrations. The implemented await helper uses `org.neo4j.agentmemory.AwaitSupport`; applications must configure logging before its first default-timeout call because its once-only fallback event is not replayed.

Sources: [await logging design](../java-client-await-design.md#logging), [client dependency boundary](../java-client-concept.md#java-baseline-and-dependencies). The [operation logging design](../java-client-logging-design.md) reuses this choice, but its proposed operation events are recorded separately in [ADR 0020](0020-leave-operation-failure-severity-to-callers.md) and [ADR 0021](0021-observe-operations-without-exposing-content.md).
