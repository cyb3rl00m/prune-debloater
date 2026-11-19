# Releasing Prune

The release signing key is **not** stored on GitHub. CI builds and tests the release APK (signed with a throwaway
debug key), then the APK is re-signed outside CI with the real key and published by a workflow.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. Update the release badge and file names in `README.md`.
2. Write `docs/release-notes/v<version>.md`.
3. Push to `main`. Wait for the `build` workflow: unit tests, lint, emulator tests and the release smoke test must pass.
   The APK of that commit is pushed to the `ci-results` branch (`apk/Prune-<version>.apk`, `COMMIT` names the commit).
4. Sign it with the release key ([uber-apk-signer](https://github.com/patrickfav/uber-apk-signer) or `apksigner`):

   ```
   java -jar uber-apk-signer-1.3.0.jar -a Prune-<version>.apk --allowResign \
     --ks prune-release.jks --ksAlias prune --ksPass <password> --ksKeyPass <password> -o out
   cp out/*-aligned-signed.apk Prune-<version>.apk
   sha256sum Prune-<version>.apk > SHA256SUMS.txt
   ```

5. Optional but recommended: push a branch `release-check` containing `apk/Prune-<version>.apk`. The
   `verify-signed-apk` workflow installs and launches it on an emulator and pushes the result to `release-check-results`.
6. Push a branch `release-publish` containing `apk/Prune-<version>.apk` and `apk/SHA256SUMS.txt`. The
   `publish-release` workflow checks the checksum and the signer (`CN=cyb3rl00m`), creates tag and release
   `v<version>` on `main`, attaches both files, and deletes the branch.

Every future release must be signed with the same key, otherwise Android refuses to install it over the old version.
Keep `prune-release.jks` and its password backed up in two places, and never commit them.
