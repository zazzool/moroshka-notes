# Intelligent knowledge

The AI layer is opt-in and revision pinned. OpenRouter key and models stay on the server; configure them through the app's [processing settings](processing-settings.md), with `/etc/moroshka/keys.env` as migration defaults. `MCP_TOKEN` remains a separate deployment setting. Do not put credentials in the repository; no automatic provider calls on ordinary capture or sync.

- `POST /api/ai/analyze` requires id, revision and consent:true; the server requests a strict JSON schema from a compatible chat model, validates type/title/tags and rechecks the revision. Client applies selected heading/tags/type only after explicit review. No generated body is accepted. Up to 12,000 Unicode code points of note content are sent. Unsupported models/provider failures leave notes unchanged.
- `GET /api/ai/status` returns configuration status and an incremental pending batch. `POST /api/ai/index` accepts 1–8 pinned revisions with consent:true; requests embeddings for missing/stale items only. Finite normalized vectors are stored as atomic JSON in `semantic-v1/<uuid>.json`, tied to SHA-256 revision and model hash. Stale/deleted vectors are ignored and their derived files are removed on successful note edit/deletion (best effort; explicit index clearing remains available without an embedding model). `DELETE /api/ai/index` removes derived index files. Search sends only the explicit query to the embedding provider; related notes use already stored vectors without another external request.
- Ranking uses cosine similarity over a single bounded-prefix vector per note. Positive scores are similarity, not confidence. This is a lightweight first retrieval implementation, not a comprehensive chunked RAG index. Indexing is deliberately manual and batched to control disclosure and costs. Search and related-note results require the current locally synchronized revision; wiki links require a unique matching title and user approval. Server semantic index can be re-created after model change. The index is private derived data and not encrypted at rest.
- `/mcp` is disabled without a distinct MCP_TOKEN. Every request authenticates with that token and validates Host and Origin, using read-only JSON-RPC over Streamable HTTP. Supports discovery and modern headers for 2026-07-28, stateless legacy initialization for 2025-11-25/2025-06-18/2025-03-26. No SSE, sessions, OAuth, tool writes, or client-side registration. Existing Actions endpoint remains independent. MCP tools list/search/read current active notes only, not history or the semantic provider. It is not a published ChatGPT connector; clients need custom bearer headers.

All three external AI operations (summary, analysis, embeddings) explicitly request `provider.data_collection: deny` and `provider.zdr: true`, without a less-private retry. Summary now requires consent:true, uses the same 12,000-code-point bound and rechecks the revision. Provider response bodies are bounded to 4 MiB. These routing constraints are not a guarantee of provider behavior or regulated-data compliance; avoid identifiable confidential data.

## References

- [OpenRouter provider privacy controls](https://openrouter.ai/docs/guides/get-started/sovereign-ai)

- [OpenRouter embeddings API](https://openrouter.ai/docs/api/api-reference/embeddings/create-embeddings)
- [OpenRouter structured outputs](https://openrouter.ai/docs/features/structured-outputs)
- [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- [MCP 2026-07-28 discovery](https://modelcontextprotocol.io/specification/2026-07-28/server/discover)
- [MCP 2025-11-25 transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)

Tests use deterministic fake provider replies; they do not verify real model quality or incur provider charges. Android fixture requires a device/emulator.

The Alpha 9 preview variant has its own application ID `app.moroshka.notes.preview` and a test signature. It does not update or access a previous app's private library; use the portable archive or configured server to transfer notes. The existing compatible-release workflow remains separate and requires the original signing key.
