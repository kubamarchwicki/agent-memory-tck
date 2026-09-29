---
status: proposed
---

# Leave operation failure severity to callers

The operation logging proposal puts ordinary request starts and terminal success, failure, or cancellation summaries at DEBUG, including HTTP 4xx/5xx and encoding, network, and decoding failures. The caller receives those failures and decides their application severity; automatically reporting them at WARNING or ERROR would duplicate application reporting and misclassify failures handled normally. ERROR is reserved for failures owned and handled by the SDK with no caller receiving them, and the current client has no such background failure path.

INFO reports successful local client construction, without asserting service reachability or authentication, and preserves the once-only default-await fallback event; WARNING remains the invalid-await-configuration fallback. `await` does not log propagated failures, wait timeouts, or interruptions again, because it accepts arbitrary futures and cannot reliably identify an underlying client operation.

Source: [logging severity proposal](../java-client-logging-design.md#severity-policy). DEBUG operation summaries are confirmed in that design, but the broader severity policy remains proposed and operation logging is not yet implemented; the existing await configuration events are covered by [ADR 0018](0018-bound-waits-without-mutating-futures.md).
