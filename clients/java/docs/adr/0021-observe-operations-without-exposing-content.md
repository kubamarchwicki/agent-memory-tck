---
status: proposed
---

# Observe operation outcomes without exposing content

The logging proposal records selected operation metadata rather than payloads: a local call ID shared across client instances correlates start and terminal events, with elapsed time, outcome, observed HTTP status, result counts, and an optional service request ID. It excludes credentials, raw URLs, resource IDs, content, response excerpts, and exception messages or stack traces; failures expose only their phase and unwrapped exception class name. Service request IDs must match `[A-Za-z0-9._:-]{1,128}`, allowing bounded correlation metadata without arbitrary header dumps, and formatted log text remains a diagnostic convention rather than a public event API.

Instrumentation observes the original returned future through a side-effect callback whose dependent future is ignored, preserving future identity, cancellation behavior, synchronous failures, and exception identity. Start timing at shared request-helper entry, include POST encoding, and report success only after status validation, decoding, and domain conversion; DELETE needs only status validation. Record one terminal outcome even when a cancelled future's exchange continues, and correlate through captured operation state rather than thread-local context; cancellation describes the caller's future outcome, not confirmed transport cancellation, and success does not assert extraction or enrichment readiness.

Formatting and backend calls guard against ordinary `RuntimeException` failures so diagnostics cannot change client results, without swallowing fatal errors or recursively logging a broken logger. Returning the original future means a caller may observe completion before logging finishes, and changing logger thresholds can suppress either event in a pair; these limits are preferable to inserting a new completion or cancellation boundary for observability.

Source: [logging design](../java-client-logging-design.md#logger-names-and-fields), including Operation boundary and asynchronous behavior. This instrumentation remains proposed; [ADR 0019](0019-use-application-owned-jdk-logging.md) records the existing backend choice and [ADR 0020](0020-leave-operation-failure-severity-to-callers.md) records the proposed severity policy.
