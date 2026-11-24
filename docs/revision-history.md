# Revision history and trash (first iteration)

Previous distinct contents are archived before overwriting the live note: Android app-private `history/<id>/<sha256>.md`, server vault `history/<id>/<sha256>.md`. SHA-256 filenames deduplicate repeated contents; reads verify integrity. Android uses AtomicFile and a process-wide local access lock; server synchronous compare/replace assumes a single process. A failed snapshot prevents replacement. A failed live write may leave an extra valid snapshot. Timestamps describe first archival, not every edit event.

Server owner-token-only endpoints:
- GET /api/history/:id → revision and archivedAt metadata
- GET /api/history/:id/:revision → exact Markdown
- GET /api/trash → id, title, revision and last modification time
- GET /api/trash/:id → deleted text

No change to the old sync response or ChatGPT Actions scope. Deleted UUIDs remain tombstones; restore means copying content to a new UUID, never replacing active data or reversing an old deletion. Browser deletion requires a clean synced revision and the existing conditional DELETE; stale writes cannot delete newer remote text. Android's local history and trash work offline; browser uses server history and requires the upgraded server. Browser offline version history and cross-device history replication are separate tasks.

Retention: no automatic pruning. Portable archive v1 excludes history/trash. Server full volume backup includes both; Android app-private history is not an uninstall-safe backup. Copies preserve original Markdown, including title; rename as needed to disambiguate title-based wiki links.

Tests: server unit/integration, browser E2E, Android SmokeRunner `-e historyFixture 1`. Android instrumentation requires a disposable emulator/device; compilation alone does not prove execution.

References: [Android AtomicFile](https://developer.android.com/reference/android/util/AtomicFile), [Node.js 24 filesystem API](https://nodejs.org/docs/latest-v24.x/api/fs.html).
