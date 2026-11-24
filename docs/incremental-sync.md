# Incremental sync v1 — revision inventory

This is a content-transfer optimization, not a change-event cursor. Existing UUID Markdown files, tombstones, revision history, owner bearer authentication and conditional writes remain unchanged.

## Contract

- `GET /api/sync/manifest` → `{version:1, notes:[{id,revision}], deleted:[{id,revision}]}`. Revisions are SHA-256 of the UTF-8 Markdown text; identifiers are canonical lowercase UUIDs. This is a complete inventory, not a partial list.
- `POST /api/sync/read` with `{notes:[{id,revision}]}` (1–8 distinct records) → `{notes:[{id,revision,text}]}`. Each body must match its requested revision; the entire batch fails with HTTP 409 if a record has changed, disappeared or been deleted. Invalid input fails with 400; files above 512 KiB fail with 413. The raw text budget per batch is 4 MiB; JSON escaping adds overhead.
- Clients request bodies only if the revision differs from the local synchronized base or no base exists. A metadata-only row is never treated as remote Markdown. The new protocol validates response identity, completeness and SHA-256 before reconciliation. Writes and deletions still use the original conditional API.
- If the manifest endpoint returns 404, the client falls back to `GET /api/notes`; other failures, including a stale batch, do not trigger fallback. A failed pull is retried through the existing manual or automatic sync mechanism.
- The API is owner-only. The separate read-only Actions token grants no access to these endpoints. The service worker caches client code, not note responses.

## Trade-offs

The inventory is O(number of live and deleted notes) in metadata, and the server recomputes hashes from files on each request. The client stages requested changed texts before reconciling them; initial sync therefore still needs memory for the initial library. There is no persistent server-side index, cursor or extra migration. Concurrent writes after the manifest are caught by pinned reads or the existing conditional writes; later changes to records not fetched or written arrive on a subsequent exchange. External filesystem modifications remain subject to the existing single-writer operational constraint.

This approach is inspired by revision-based conditional requests ([RFC 9110 §13](https://www.rfc-editor.org/rfc/rfc9110.html#section-13)); a cursor-based event log like [Joplin Server delta sync](https://joplinapp.org/help/dev/spec/server_delta_sync/) could avoid transferring the entire inventory later.

## Tests

`node --test tests/*.test.mjs` covers API, permissions, stale revisions, bounded batches, fallback, malformed responses and digest checks. `python tests/browser.py` verifies no note body transfer on unchanged sync, download of just a remotely modified note, upload of just a local edit and legacy fallback. Android SmokeRunner `-e incrementalFixture 1` checks selection and fallback with a fake API; CI compiles it, but instrumentation requires a device/emulator.
