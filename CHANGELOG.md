# Changelog

## 2.1.0 — 2026-11-24

**Fixed**
- Shizuku: on some phones (reported on Xiaomi HyperOS) Prune's Shizuku user service never connected, so every change waited 15 seconds and failed. Prune now falls back to running commands through Shizuku's own process, the same path other Shizuku apps use.
- Prune checks the connection once before applying anything. If it cannot run commands, it stops and says that nothing was changed, instead of failing every change after a timeout.
- A failed disable no longer claims "Uninstalled for this user instead" when the fallback did not run or did not work. The message now describes what really happened.
- "Authorize Prune in Shizuku" did nothing after the user had tapped Deny once. Prune now detects this and explains how to allow it in Shizuku.
- "Get Shizuku" opened the app store first, and some store clients (Aurora Store) looped on it. Prune now links to Shizuku's GitHub releases first.

**New**
- Set up → Test connection: shows whether Prune can run commands, the uid and which Shizuku path it uses. Useful for bug reports.
- Android TV and Google TV (experimental): Prune appears in the TV launcher, works with the remote's D-pad, detects TVs and has TV rules that lock the home screen, TV Settings, setup and the infrared service.
- Display and effects: refresh rate (adaptive, always maximum, cap at 60 Hz), window blur off, high contrast text.
- Animation speed 0.25×.
- Storage maintenance: runs Android's idle maintenance (fstrim) now.
- Quick Settings tiles: fast animations on/off and clear all app caches.

**Tests**
- CI now starts the real Shizuku app on the emulator and tests Prune's Shizuku code path in debug and release builds, and runs the release build on the Android TV emulator with D-pad input only.

## 2.0.0 — 2026-11-21

Prune becomes a universal Android debloater.

**New**
- Manufacturer-independent architecture: device identity (Build fields and system properties), firmware detection and one integration module per manufacturer, with a generic Android fallback that works on every phone. Unknown manufacturers can never crash Prune.
- Integrations: Samsung (One UI), Google Pixel (stock Android), Xiaomi/Redmi/POCO (HyperOS, MIUI), Huawei (EMUI, HarmonyOS 2–4), Honor (MagicOS), OPPO/OnePlus/realme (ColorOS, OxygenOS, realme UI), Motorola (My UX); detection and firmware names for Sony, Nothing, ASUS, vivo/iQOO (Funtouch OS, OriginOS), Tecno/Infinix/itel (HiOS, XOS), Nokia/HMD, Lenovo, ZTE/nubia, LG.
- Package database split into `core.json` and one rule file per manufacturer (348 curated entries in 9 files), plus the full community manufacturer lists (5,396 entries instead of ~2,000).
- Entries can be limited to Android versions or firmwares, and can describe consequences and recovery.
- Google, Huawei and Honor service frameworks are detected independently of the brand. On a phone without Google services, HMS Core and AppGallery (or Honor's services) are locked.
- Compatibility report: device, firmware, integration, compatibility level, access tier, supported and unsupported operations with firmware-specific caveats, what already succeeded or failed on this device, known limitations. Shareable as text.
- Compatibility levels: fully tested, partially tested, experimental, generic, unsupported. Only the emulator claims "fully tested".
- Duplicate apps: app types installed more than once (browsers, galleries, keyboards, app stores...).
- Installer detection: apps placed by partner preload installers (for example Facebook's or Digital Turbine's) are recognised as preloads.
- New risk level **Unknown** for packages in no database: never pre-selected, Custom only, extra confirmation.
- Region-specific app variants are marked.
- Setup steps for Developer options adapt to the manufacturer, including Xiaomi's "USB debugging (Security settings)" and ColorOS's "Disable permission monitoring".

**Changed**
- Texts are no longer Samsung-specific; the home screen shows the detected brand, model, firmware and compatibility level.
- "Features you use" lists the features of the detected phone's brand only.
- Package labels are cached between scans.

**Upgrade**
- Same signing key as 1.0.0: install over it. History and settings are kept. Feature answers saved by 1.0.0 keep applying; features new in 2.0 count as "used" until you answer.

## 1.0.0 — 2026-11-19

First release: Samsung Galaxy debloater and optimizer with four debloat levels, Shizuku/root/ADB-script support, optimization center, history and restore.
