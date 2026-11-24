# Alpha 8 — release request

The request file `deploy/alpha-release-request.json` is a deliberately narrow push trigger on main for the alpha release and existing production deployment workflows. Ordinary source commits do not trigger either deployment. Both workflows can also be launched manually.

The alpha workflow verifies server and browser tests, builds Android and the installer, and publishes only if the APK has the previous package name, a higher version code and the exact signing certificate of alpha 7. The previous APK is pinned by commit and SHA-256. The test build artifact is **not** an upgrade APK; do not uninstall a working app to install it.

The original alpha debug keystore is not stored in the repository. In the `android-release` environment configure `ALPHA_KEYSTORE_BASE64`, `ALPHA_KEY_ALIAS`, `ALPHA_STORE_PASSWORD` and `ALPHA_KEY_PASSWORD` using the same original key. Do not generate a replacement key for an in-place upgrade. Keep stable release secrets separate. If the key is unavailable, the workflow deliberately refuses publication.

Production deployment is independent of APK signing and uses the existing `production` environment: `DEPLOY_TARGET`, `DOMAIN`, `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS`. Existing server tests, pinned SSH identity and the installer health check remain required.
