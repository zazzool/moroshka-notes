# Alpha 9 reliability audit / Alpha 10 candidate

Scope: the merged Alpha 9 implementation, not a certification of the live VPS. Corrected in this branch:

- Recovery: a missing server UUID without a tombstone is a conditional create (base=null), including a clean cached note. Explicit tombstones still win; a concurrent remote write is rejected. External SAF deletion records become archival copies, never pending remote deletions. Folder restore uses the same deterministic add-only plan as portable archives; legacy live/trash UUID collisions are repaired before network sync.
- Metadata: common top-level fence scanner on Kotlin and JS (shared by browser/server); protects same-line inline code and indented code examples. Only selected metadata is edited, managed category tags are mutually exclusive when reclassified. Titles, tags, links and task lists use the scanner. It is not a full CommonMark parser; complex nested block constructs remain a limitation.
- AI: explicit summary consent, bounded prompt and response, pinned revision, privacy routing flags and best-effort cleanup of derived vectors. No external model calls during ordinary note capture/sync. Actual provider behavior/model quality requires separate tests.
- MCP: canonical Host / Origin checks in addition to separate bearer authentication; loopback-only host fallback when PUBLIC_URL is absent.
- Packaging: distinguish Android variants by launcher labels, disable debuggability of the preview distribution, require original signing certificate for a same-package preview release. New CI artifacts include the server installer and instrumentation APK. Android runtime workflow runs smoke fixtures on a disposable API 35 emulator; the fixture result must explicitly be successful. No VPS deployment or private key provisioned by this branch.

References: [Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile), [CommonMark fenced code](https://spec.commonmark.org/current/#fenced-code-blocks), [Android signing](https://developer.android.com/studio/publish/app-signing), [MCP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [OpenRouter privacy routing](https://openrouter.ai/docs/guides/get-started/sovereign-ai).

Remaining risks: browser localStorage quota and unencrypted local/remote Markdown; complete inventory scanning per exchange; title-based wiki links on renames; single-note-prefix semantic retrieval, manual indexing and no OAuth. No full security audit, large-vault benchmark or real-model quality claim.
