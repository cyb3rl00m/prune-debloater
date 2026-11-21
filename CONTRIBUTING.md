# Contributing to Prune

Thanks for helping. The most valuable contributions right now are **reports from real phones** and
**better manufacturer rules**.

## Report how Prune works on your phone

1. Install the latest release and set up Shizuku (see the README).
2. Open **Home → tap the device card** (the compatibility report) and tap the share icon.
3. Try a Minimal profile, then undo it in **Restore**.
4. Open an issue with the report and what worked or failed. Include error messages exactly as shown.

## Improve a manufacturer's rules

Rules live in `tools/rules/`:

| File | Covers |
|---|---|
| `core.json` | Android core, Google apps, third-party preloads, cross-brand features. Loaded on every device. |
| `samsung.json`, `google_pixel.json`, `xiaomi.json`, `huawei.json`, `honor.json`, `oplus.json`, `motorola.json` | One manufacturer (or a family sharing one firmware, like Xiaomi/Redmi/POCO). |
| `others.json` | Known critical apps of brands without a dedicated file. |

Each entry:

```jsonc
"com.example.bloat": {
  "level": 2,                 // 1 Minimal, 2 Balanced, 3 Aggressive, 4 Custom only
  "risk": "LOW",              // SAFE, LOW, MODERATE, HIGH (HIGH must be level 4)
  "note": "What it is, in one sentence.",
  "minSdk": 31,               // optional: only on these Android versions
  "firmware": ["HyperOS"],    // optional: only on these firmwares
  "consequences": "What stops working.",   // optional
  "recovery": "How to get it back."        // optional, a generic text is used otherwise
}
```

Rules for rules:

- **Be conservative.** If you are not sure what a package does, leave it out: Prune marks unknown packages *Unknown*, which is safer than a wrong "safe".
- Anything that can bootloop, break calls, notifications, permissions, updates, security or connectivity goes in `locked`.
- Packages behind a feature people may use (cloud, pay, watch, share) belong in an `ecosystems` group, so users can keep them.
- A package may have an entry in only one file. `python3 tools/build_db.py` validates everything and fails on conflicts.

Then run `python3 tools/build_db.py`, add or update a unit test in `app/src/test`, and open a pull request.

## Add a manufacturer integration

Integrations are in `app/src/main/java/io/github/cyb3rl00m/prune/core/oem/Integrations.kt`. An integration only
adds knowledge (detection, firmware version, setup steps, limitations, dynamic protections); the debloat engine
stays the same for every brand. Add a simulated device for it in `OemIntegrationTest.kt` with the real
`ro.*` properties of that firmware. Never mark an integration higher than `EXPERIMENTAL` without hardware reports.

## Code

- Kotlin, Jetpack Compose, no new dependencies without a good reason.
- Every privileged command goes through `core/shell/Commands.kt` (argv lists, validated inputs).
- Every change must be verifiable by reading state back from Android, and undoable through `Inverse`.
- `./gradlew testDebugUnitTest` must pass; CI also runs the emulator tests.

By contributing you agree that your contribution is licensed under GPL-3.0-or-later.
