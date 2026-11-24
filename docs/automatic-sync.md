# Automatic sync — first iteration

The user opts in per client. Server protocol and existing Markdown files are unchanged.

## Android

Use native JobScheduler (min API 26), persistent periodic job (30 minutes) and one-shot job on exit (min 5 seconds). Constraints: network, battery and storage not low. No secrets in job extras; read connection settings locally at execution time. Platform controls actual timing and can defer or stop jobs. The JobService runs network work on a worker, tracks and disconnects the current connection on cancellation.

SyncAccess holds a process-wide network exchange lock and a separate local mutation lock. Background jobs require a generation lease with no visible activity. A new activity invalidates the lease, even if the app later exits again. Before and after HTTP and before each local write, recheck the lease and the saved connection settings. Local writes and foreground registration share a lock; never hold it during HTTP. If an acknowledged server request cannot be marked locally due to cancellation, existing revision reconciliation handles the next attempt. The editor reloads changed content on return. The manual exchange still uses the existing dedicated screen. Jobs are not scheduled by default.

## Web

Opt-in stored separately from credentials. Debounced local edits, online/visibility events, 120-second foreground polling, exponential backoff on failures. A single exchange is allowed, manual or automatic. Automatic requests do not make the editor inert. After an HTTP PUT, compare the latest locally persisted text to the submitted text; update only its base revision and keep a newer edit dirty. Defer remote changes/deletions to the open note in automatic mode, apply on close; preserve conflicting content. Remote server changes to closed notes apply normally. Auth failure pauses automatic retries until connection settings change. Browser tabs must remain open; no promise of background sync with closed browser.

No incremental fetch yet; automatic full snapshots are bounded by the current server/client storage limits. No cancellation can retract a request already processed by the server.

Tests: browser E2E covers in-flight editing, reconnection, conflicts, opt-out persistence. Android SmokeRunner `-e syncGateFixture 1` covers lease invalidation and checkpoints; compilation is not instrumentation execution.

References:
- [Android JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder)
- [Android JobService](https://developer.android.com/reference/android/app/job/JobService)
- [MDN Navigator.onLine](https://developer.mozilla.org/en-US/docs/Web/API/Navigator/onLine)
