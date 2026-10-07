---
status: accepted
---

# Organize packages around memory operations

MemoryClient defines the complete domain-operation seam, including operations
addressed by Conversation and Reasoning Step UUIDs. Conversation and ReasoningStep
remain final live handles bound to a MemoryClient; their public constructors
allow adapters in other packages to create them from domain values. Construction
binds an existing identity and response snapshot without performing remote I/O.

The root package contains MemoryClient and its package-private AwaitSupport.
The conversation package owns Conversation, its creation and listing requests,
Messages, and Conversation Context values. The reasoning package owns
ReasoningStep, its request and trace types, explanations, and Tool Calls.
The entity package owns Entity and EntitySearch. The exception package owns
the public failure hierarchy and package-private ResponseDiagnostics.

The default JDK HTTP adapter, codecs, private wire records, and operation
logging live together in internal.http. JdkMemoryClient and its static factory
are public solely so the root default factory can call them across packages.
Its constructor and the other implementation types remain package-private.
The internal package name communicates an unsupported implementation surface;
it does not enforce Java access restrictions.

MemoryClient Javadoc records the shared operation behavior: live-handle binding,
immutable result collections, service order, validation timing, asynchronous
failures, and write and wait semantics. The default adapter owns HTTP envelope
details. Live-handle methods link to the corresponding operation contract.
The default factories still select the JDK implementation; alternate adapters
implement MemoryClient directly.

Package names do not become new artifact or deployment divisions. The core
remains Java 17 with an optional Jackson dependency, and the conformance
artifact remains separate. Logger names stay org.neo4j.agentmemory.JdkMemoryClient
and org.neo4j.agentmemory.AwaitSupport despite the implementation move.

This decision refines [ADR 0002](0002-domain-oriented-java-memory-client.md):
the root interface now includes raw-ID operations so live handles can use
alternate adapters. The final-handle model and private wire records remain.
It preserves [ADR 0003](0003-optional-internal-json-adapters.md) and
[ADR 0005](0005-use-jdk-http-without-a-transport-extension.md): this operation
seam adds no configurable HttpClient injection or separate transport extension.
