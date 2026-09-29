---
status: accepted
---

# Keep the Java client within the hosted service boundary

The Java client exposes NAMS-backed memory operations over HTTP, leaving extraction, embedding, enrichment, and entity resolution to the service; it does not include a Bolt-based or other self-hosted engine. The OpenAPI document is a capability catalog rather than a mandate to mirror every endpoint, so authentication administration, workspace and database administration, ontology administration, skills, reviews, and raw query access remain deferred. This keeps the public API honest about what the hosted client can execute instead of inheriting the broader scope of the Python and TypeScript implementations.

The Agent Memory specification and TCK define domain meaning, while the NAMS contract and verified hosted behavior determine supported capabilities. TCK-specific behavior stays in the conformance adapter, including translating `session_id` into the public conversation vocabulary; Spring AI and LangChain4j integrations remain downstream consumers in separate packages or artifacts, with their dependencies and framework-owned abstractions outside the Java 17 core. Conformance and framework usability therefore validate the client without expanding its hosted domain boundary.

Complements [ADR 0007](0007-keep-agent-workflow-orchestration-outside-core.md).
