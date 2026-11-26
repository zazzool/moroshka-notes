# Processing policy / Alpha 12

## Defaults and UI

Menu → Обработка заметок и OpenRouter. Each client persists independent execution routes for metadata (heading/tags/category), summary, expanded search, proposed links, photo OCR: local / server / off. All default to local. A failure, missing OCR pack, lack of connectivity or an unavailable model never silently switches to external inference. Disabling advanced search does not disable ordinary literal search, and disabling processing does not disable capture, editing or synchronization. Settings can be reset to local. No changes to ordinary note capture or storage.

Local text processing is deterministic: rules and frequency-ranked tags, extractive summary, word-frequency cosine ranking, no generated facts or neural embeddings. Server-mode expanded search uses the existing embedding index; per-note/server operations remain revision pinned and explicit. User reviews metadata, links and OCR before accepting. Device routing preferences are intentionally device-specific, not global server commands. OpenRouter credentials/model selections are shared server-wide.

## OpenRouter setup

Owner-token-only GET/PUT /api/ai/config; POST /api/ai/config/test. No returned API key (not even suffix). Edit uses a revision CAS; key action keep/replace/remove. Replacing verifies GET https://openrouter.ai/api/v1/key, never sends notes. Fixed HTTPS destinations, no redirects; generic errors. Existing environment config is read as migration defaults, explicit disconnect persists over it. New installs propose openai/gpt-4.1-mini (text and vision) and openai/text-embedding-3-small; empty model disables corresponding operation. Model IDs remain fully editable; availability, provider routing and pricing vary. Verification checks key acceptance, not model capabilities. Actual note requests remain explicit and use privacy restrictions. Key and models apply without restart; configuration mutation serializes with active AI requests and changes embedding model hash; old index invalidated. No key in portable archives or local client preferences. The server stores its plaintext secret in private `.openrouter-settings-v1.json` mode 0600, inside persistent data volume; full-volume backups need encryption. Disconnect does not revoke the external API key; revoke it at OpenRouter if needed. Avoid sharing screenshots of the input field. Existing server upgrade necessary.

## OCR

Android uses Tesseract4Android 4.9.0 / Tesseract 5 (Apache-2.0; upstream license and dependency notices). User explicitly downloads Russian + English tessdata_fast 4.1.0 (~7.97 MB) from immutable Git revision 65727574dfcd264acbb0c3e07860e4e9e9b22185. Length and SHA-256 checked before AtomicFile write. Photos processed on a worker with no provider request. Browser uses Tesseract.js 6.0.1 and core 6.0.0: build-time pinned SHA-512 npm tarballs, fixed selected assets, no install scripts; traineddata pinned by SHA-256. Assets served on exact allowlist at /ocr-v1, cached on explicit installation; WASM runs in a same-origin worker with narrow CSP, no photo goes to external sites. Browser eviction can require reinstall. Recognition is printed-text OCR (not handwriting guarantee); review result. New Android dependency is restricted to its specific JitPack group. Model first download requires network, later recognition doesn't. Offline OCR fixture uses exact traineddata and synthetic Russian/English text.

## References

- [OpenRouter key validation](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key)
- [OpenRouter models](https://openrouter.ai/docs/api/api-reference/models/get-models)
- [GPT-4.1 Mini](https://openrouter.ai/openai/gpt-4.1-mini), [text embedding 3 small](https://openrouter.ai/openai/text-embedding-3-small)
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0)
- [Tesseract model data](https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0)
- [Tesseract.js API and worker configuration](https://github.com/naptha/tesseract.js/blob/v6.0.1/docs/api.md)
- [Tesseract.js license](https://github.com/naptha/tesseract.js/blob/v6.0.1/LICENSE.md)

Local OCR engine quality on real handwritten documents and actual OpenRouter model availability are not guaranteed by fixture tests.
