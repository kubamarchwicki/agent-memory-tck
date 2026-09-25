# Neo4j Agent Memory Java Client: Conceptual Foundation

**Status:** Conceptual validation
**Recorded:** 2026-08-19

## Objective

Build a thin Java client for the hosted [Neo4j Agent Memory Service (NAMS)](https://memory.neo4jlabs.com/) over its [OpenAPI REST interface](https://memory.neo4jlabs.com/openapi.json).

The client should expose only operations genuinely backed by NAMS. It is not intended to reproduce the full self-hosted Python implementation or become a general Neo4j agent-memory engine.

Implementation examples and framework integrations will eventually validate the client design, but they will not define or drive the core API.

## Decisions

### Product boundary

- The product is a Java client for the hosted NAMS service.
- Communication is HTTP-only.
- The client will not include a Bolt-based or other self-hosted memory implementation.
- It will not perform client-side extraction, embedding, enrichment, entity resolution, or other work performed by NAMS.
- The public API must not advertise operations that cannot actually be executed against NAMS.

### Java baseline and dependencies

- Target Java 17 for compatibility with langchain4j and spring framework (spring-ai).
- Use `org.neo4j.agentmemory` for the public client and domain types.
- The core client has no required third-party runtime dependencies.
- JSON object binding is supplied by internal adapters for optional mapper dependencies. The first adapter targets Jackson 3; Jackson 2 support may be added without changing the public domain API.
- JDK facilities provide the baseline networking, TLS, and concurrency primitives.
- Build-time and test dependencies are acceptable as long as they do not become transitive runtime dependencies of the core artifact.
- Framework integration artifacts may depend on their respective frameworks, but those dependencies remain isolated from the core client.

### HTTP transport

> The HTTP transport is a minimal, library-neutral extension seam; NAMS protocol mapping remains internal.

- The default transport is based on the Java `HttpClient`.
- `MemoryClient.create(String apiKey)` uses a non-blank `MEMORY_ENDPOINT` environment value when present and otherwise falls back to the hardcoded `https://memory.neo4jlabs.com/v1` endpoint.
- `MemoryClient.create(URI endpoint, String apiKey)` lets applications target an explicit NAMS endpoint without exposing or abstracting the underlying HTTP client.
- The explicit endpoint factory rejects only a null or empty URI. The initial client otherwise uses the endpoint as supplied, without validating its scheme, host, query, or fragment and without normalizing trailing slashes.
- Both factories reject null or blank API keys synchronously but do not require the deployment-specific `nams_` prefix; a nonblank key rejected by the service fails through `MemoryServiceException`.
- The client captures that API key at construction and sends it as `Authorization: Bearer <apiKey>` on every request. This milestone has no token provider, refresh flow, custom headers, or per-request credentials.
- The initial client creates and uses JDK `HttpClient` internally. It exposes neither a transport extension interface nor a public injection point for a configured HTTP client.
- The internal client is the unconfigured result of `HttpClient.newHttpClient()`. The core client adds no connection timeout, executor, redirect policy, protocol preference, proxy, authenticator, retry policy, or per-request timeout.
- A transport seam will be reconsidered only after concrete Spring and LangChain4j integrations establish whether framework-native clients, rather than low-level HTTP clients, need to be plugged in.

### Domain language

- Use **conversation** terminology in the public Java API.
- Treat TCK `session_id` terminology as a translation concern at the TCK boundary; do not propagate it into the public client vocabulary.
- Organize the conceptual domain around conversation memory, entity/graph memory, and reasoning memory.
- Names such as `ConversationMemory`, `EntityMemory`, and `ReasoningMemory` describe the present conceptual grouping, not finalized Java type names.
- Records are transparent data carriers: components and generated accessors use semantic domain types, with `null` representing an absent nullable value.
- `Optional` is limited to explicit method-return views of absence; it is not used as a record component or stored instance-field type.
- Nullable record components expose `getXxx()` methods returning `Optional.ofNullable(component)` when callers benefit from an optional view, while the generated `xxx()` accessor continues to reveal the raw state.

### Framework integrations

- Spring AI and LangChain4j integrations are downstream consumers and design validators.
- They should live in separate integration packages or artifacts.
- A framework-neutral conversation abstraction can belong in the client where it represents the NAMS domain.
- Framework-owned abstractions such as a particular `ChatMemory` interface should be implemented in the relevant integration rather than imposed on the core model.
- Example applications are premature and are not part of the current design focus.

**References**:
- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [Spring AI Session (community project)](https://spring-ai-community.github.io/spring-ai-session/latest/)
- [Langchain4j Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory/)

## Initial NAMS capability boundary

The first client surface should concentrate on the hosted memory data plane exposed by NAMS.

### In scope

- Conversations and messages
- Conversation search and context retrieval
- Observations and reflections
- Extraction status
- Entities and relationships
- Entity search, graph retrieval/expansion, merge, feedback, history, and provenance
- Reasoning steps, tool calls, traces, and explanations

This is a capability boundary, not a commitment to expose every corresponding endpoint in the first release.

### Deferred

- Authentication and API-key administration
- Workspace and membership administration
- Database provisioning or copying
- Ontology administration and migration
- Skills and reviews
- Raw query/Cypher access
- Self-hosted-only capabilities

The current NAMS specification is significantly broader than the intended Java client. At the inspected snapshot, it is a Swagger 2.0 document with 88 paths and 146 definitions, spanning memory operations as well as authentication, workspace, ontology, review, and skill administration. This confirms that the OpenAPI document should be treated as the hosted capability catalog, not as an instruction to expose the entire service.

### First implementation milestone

Implement these hosted operations first:

- createConversation
- listConversations
- getConversation
- addMessage
- listMessages
- getContext
- deleteConversation

Together they exercise POST, GET, DELETE, path parameters, query parameters, request bodies, response envelopes, and 204 responses.

The models needed are only:

- Conversation
- Message
- MessageRole
- ConversationContext
- Observation
- Reflection

Model IDs as UUID, timestamps as Instant, and conversation metadata as Map<String, String>. Bind public request and value records directly when their shape matches hosted camelCase JSON; keep only response envelopes and live-conversation response data as private records local to the HTTP implementation.

Keep the public domain modules deep and HTTP implementation details internal:

MemoryClient public interface
├── create, list, and get conversations
└── returns live Conversation modules

Conversation public final class
├── exposes conversation details
└── adds and lists messages, retrieves context, and deletes itself

Internal HTTP implementation
└── JDK HttpClient

The public surface is vertical and domain-oriented rather than a flat mirror of HTTP endpoints. `MemoryClient.getConversation` returns a final live `Conversation` with its details; the conversation's `messages()` and `context()` methods perform their respective remote reads, while `addMessage(...)` and `delete()` perform its mutations. Endpoint-shaped code, JSON binding, and JDK HTTP details remain internal.

The initial `MemoryClient` interface has no lifecycle method. Deterministic shutdown belongs to a lower-level client or transport only when that implementation owns resources that require it; such ownership is not part of the domain interface.

The initial core API is asynchronous-only. Every operation returns `CompletableFuture<T>` (or `CompletableFuture<Void>` when it has no result). A synchronous facade can be added later if Java consumers demonstrably need it.

Failures use a small public hierarchy rooted at `MemoryClientException`. Non-success HTTP responses use `MemoryServiceException`, incompatible success payloads use `ResponseDecodingException`, and an unavailable Jackson 3 runtime fails client construction with `MissingJsonCodecException`; JDK network failures are wrapped once with their original cause retained.

The initial client performs no automatic retries. In particular, mutating POST requests are not replayed without an idempotency contract; callers and later framework integrations may apply an explicit retry policy outside the core client.

`listConversations` sends one request with the supplied limit, unwraps that response's `conversations` array, and performs no automatic pagination. Page or cursor types are deferred until the hosted contract exposes stable pagination semantics.

Collections returned by `listConversations`, `Conversation.messages`, and `Conversation.context` are immutable snapshots. `ConversationContext` defensively copies all three list inputs so callers cannot mutate remote state or client-owned values through returned collections.

The `conversations` array in conversation-list responses and the `messages` array in message-read responses are required. Missing or null arrays fail with `ResponseDecodingException`; explicit empty arrays return empty immutable lists. Optional context collections such as reflections, observations, and recent messages still normalize missing or null values to empty immutable lists.

The client preserves collection order exactly as returned by NAMS and performs no client-side sorting, particularly for domain-significant message order.

The seven-operation walking skeleton uses handwritten, package-private route mappings and wire records. OpenAPI generation is deferred because its POJO output and generator configuration have not been accepted; the internal placement keeps that choice revisitable without changing the public domain interface.

Focused unit tests feed representative hosted JSON snippets through the package-private `JsonCodec` interface to verify Jackson 3 request encoding, direct rich-type decoding, unknown-property tolerance, and disabled scalar coercion. HTTP operation tests are deferred until they can use WireMock; the credential-gated hosted test remains the walking end-to-end check.

Apart from the agreed factory checks for API key and endpoint, the initial client performs no explicit validation of operation arguments or request-record fields. The later `Conversation.messages(int limit)` overload checks 1–200 synchronously and throws `IllegalArgumentException` outside that range. Other request validation is deferred rather than duplicating the evolving NAMS contract.

## Source authority

When sources disagree or evolve, use this order:

1. The Agent Memory specification and TCK define domain meaning and behavioral expectations.
2. The NAMS OpenAPI contract and verified hosted behavior define which capabilities the hosted client can honestly support.
3. The Python and TypeScript implementations are design references, not normative APIs for Java.
4. Spring AI and LangChain4j integrations validate usability downstream; they do not determine the core model.

## TCK and validation strategy

The [Agent Memory TCK](https://github.com/neo4j-labs/agent-memory-tck/) uses a cross-language HTTP bridge. Its Python test suite can exercise a language-specific conformance server, which in turn invokes the language client. The reviewed Makefile includes Bronze bridge tests and conformance-server targets for TypeScript and Go; the repository has since expanded its cross-language client set.

Java should follow the same staged model:

1. Reach Bronze conformance through a Java conformance server against the TCK reference bridge.
2. Run hosted end-to-end validation against the actual NAMS REST data plane.
3. Pursue higher conformance tiers only where their semantics are applicable to the hosted client.
4. Validate Spring AI and LangChain4j adapters separately after the core client boundary is stable.

Two conceptual profiles keep the result honest:

- **Hosted profile:** genuine NAMS-backed memory operations exposed by the Java client.
- **Conformance profile:** the adapter surface necessary for TCK execution, initially Bronze.

The profiles can share domain concepts where they overlap, but TCK-only behavior must not leak into or inflate the hosted public API.

## Findings from existing implementations

The [Neo4j Agent Memory repository](https://github.com/neo4j-labs/agent-memory/) contains Python and TypeScript implementations that share the memory model and support NAMS. It also contains functionality beyond the planned Java client, including self-hosted operation and model/provider integrations. Consequently:

- Existing SDKs are useful for naming, behavior, and workflow comparisons.
- Their complete feature sets should not be copied into Java.
- The Java client should preserve the hosted service boundary rather than emulate local engine behavior.
- The earlier evolution of the NAMS plugin provides supporting precedent for separating a domain-facing service from lower-level HTTP mechanics.

## Explicitly undecided

The discussion has not yet selected:

- Additional public model fields beyond those required by the initial hosted workflows
- Any specific optional third-party HTTP adapter
- Artifact and module layout
- Release, compatibility, and NAMS API-versioning policy
- Detailed mappings to Spring AI or LangChain4j
- Example applications
- Whether future pagination or cursor types are needed for message history. `Conversation.messages()` sends no limit and uses the hosted default of 50; `Conversation.messages(int limit)` sends a caller-supplied 1–200 limit, validated synchronously. Both return immutable newest-first snapshots in service order without client-side sorting, reversing, deduplication, or pagination. The hosted contract states the range and default only in prose, with no `minimum`, `maximum`, or `default` in the schema, so contract verification cannot detect a drift in those limits.

These questions belong to the next design phase. They should be resolved only after the conceptual boundary above remains stable under further review.

## Reference material

- [NAMS](https://memory.neo4jlabs.com/)
- [NAMS OpenAPI contract](https://memory.neo4jlabs.com/openapi.json)
- [Neo4j Agent Memory](https://github.com/neo4j-labs/agent-memory/)
- [Agent Memory TCK](https://github.com/neo4j-labs/agent-memory-tck/)
- [Agent Memory TCK Makefile](https://github.com/neo4j-labs/agent-memory-tck/blob/main/Makefile)

## Inspection snapshot

The repository observations in this document were made on 2026-08-19 against local snapshots of:

- `neo4j-labs/agent-memory` at commit `4eb4445`
- `neo4j-labs/agent-memory-tck` at commit `4603b91`

The live services and repositories may evolve after this record.
