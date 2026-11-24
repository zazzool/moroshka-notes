# Moroshka portable backup v1

UTF-8 JSON: `{"format":"moroshka-notes-backup","version":1,"notes":[{"id":"<uuid>","text":"<Markdown>","sha256":"<lowercase SHA-256 of UTF-8 text>"}]}`.

The archive includes active notes only, in plaintext. No keys, tokens, user settings, revisions, deleted notes or draft. Maximum 32 MiB encoded, 10,000 records, 512 KiB UTF-8 per record. UUIDs are lowercase canonical hex with hyphens; duplicates and mismatched checksums reject the entire input before writes. SHA-256 detects corruption, not malicious replacement of both text and hash.

Restore is add-only. Identical ID/text is skipped; a conflicting or locally deleted ID is restored under a deterministic alternate UUID (SHA-256 of `moroshka-backup-v1\n<id>\n<text-sha256>\n<attempt>`, formatted with version/variant nibbles 8). Reserve all incoming IDs and avoid occupied/deleted IDs. No original file is overwritten. A write error can leave successfully imported notes; retrying skips them. Imported notes have no base revision until the existing sync protocol reconciles them.

Android uses Storage Access Framework with user-selected files; export/import run on the existing worker. Web uses local downloads, file picker, the existing note store and offline shell. Browser quota remains a limitation. Tests: `node --test tests/*.test.mjs`, `python tests/browser.py`, Android instrumentation with `-e backupFixture 1`.

References: [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files), [MDN storage quotas](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria).
