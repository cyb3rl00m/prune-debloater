# Compatibility

Prune works on any phone running Android 9 (API 28) or newer. How much it knows about a phone depends on its
manufacturer. Prune detects the manufacturer, brand, model, Android version and firmware itself; you never have
to pick your phone.

Open **Home → tap the device card** (or **Settings → Compatibility report**) on your phone to see exactly what
Prune can do there, what it needs, and what has already worked or failed on that device. The report can be
shared as text for bug reports.

## Compatibility levels

| Level | Meaning |
|---|---|
| Fully implemented and tested | Prune's automated tests run in this environment. Today this is only the Android emulator (stock Android 15). |
| Implemented, partially tested | Dedicated rules exist and the debloat engine is tested on stock Android, but not on this brand's hardware yet. |
| Experimental | Dedicated rules and known firmware limitations are built in, tested only with simulated devices. Review every change. |
| Generic compatibility | No dedicated integration. Prune uses its generic Android rules and the community database. |
| Unsupported | Prune cannot work there. |

**No manufacturer has been tested on physical hardware by the project yet.** If you run Prune on your phone,
please share the compatibility report in a GitHub issue: that is how brands move up a level.

## Manufacturers

| Manufacturer | Firmware detected | Level | What is specific | Known limitations |
|---|---|---|---|---|
| Samsung Galaxy | One UI | Partially tested | 18 locked packages, 174 curated entries, 16 Samsung features (Wallet, Pass, Watch/Buds, SmartThings, DeX, Quick Share, Bixby, Health, Secure Folder, S Pen, Galaxy AI...) | Some packages refuse `disable-user`; Prune uninstalls them for the user instead |
| Google Pixel | Stock Android | Partially tested | Pixel-only entries (Pixel Tips, Personal Safety, adaptive connectivity) | Little manufacturer bloat: most removable packages are Google apps |
| Xiaomi, Redmi, POCO | HyperOS, MIUI | Experimental | Security app, permission manager, updater and core MIUI packages locked; ads and analytics services in Minimal; Xiaomi Cloud, Xiaomi Share and Mi Pay features | Needs "USB debugging (Security settings)" for permission, background and animation changes; updates can bring apps back |
| Huawei | EMUI, HarmonyOS 2–4 | Experimental | Phone Manager and updater locked; Google services detected: without them HMS Core and AppGallery are locked | `disable-user` often refused (uninstall-for-user fallback); HarmonyOS NEXT cannot run Android apps |
| Honor | MagicOS, EMUI | Experimental | Updater locked; Honor Mobile Services locked when Google services are missing | `disable-user` often refused |
| OPPO, OnePlus, realme | ColorOS, OxygenOS, realme UI | Experimental | Security Center, permission manager and notification manager locked; telemetry in Minimal; account, share and game features | Needs "Disable permission monitoring" for permission and background changes |
| Motorola | My UX | Experimental | Retail demo, tour and promo apps in Minimal; Moto Actions/Display as a feature; overlays protected | Some overlays cause bootloops (locked or high risk) |
| Sony, Nothing, ASUS, vivo/iQOO, Tecno/Infinix/itel, Nokia/HMD, Lenovo, ZTE/nubia, LG | Partly (vivo Funtouch/OriginOS, Tecno/Infinix HiOS/XOS, Nothing OS) | Generic | Community database entries and a few locked/high-risk system managers | No dedicated testing |
| Any other Android phone | Android version | Generic | Generic Android and Google rules | Unknown system packages are marked "Unknown" and only offered in Custom mode |

Numbers come from `tools/rules/*.json` and the community list at the time of release.

## What works where

The debloat engine uses standard Android commands (`pm`, `cmd appops`, `cmd deviceidle`, `settings`) and is the
same on every brand. What changes is whether a firmware allows them:

| Operation | Needs | Notes |
|---|---|---|
| Scan, classify, explain, duplicates, compatibility report | Nothing | Every device |
| App usage, unused apps, storage per app | Usage access | Every device |
| Disable, uninstall for user, restore | Shizuku or root | Huawei, Honor and Samsung refuse disabling some packages |
| Background restriction, permissions, notifications | Shizuku or root | Xiaomi and ColorOS-based phones need an extra developer option |
| Battery exemptions, cache trimming, app compilation, battery estimates | Shizuku or root | Battery estimate format can differ between firmwares |
| Animation speed | Shizuku, root or WRITE_SECURE_SETTINGS | Xiaomi needs "USB debugging (Security settings)" |
| Everything above without Shizuku/root | A computer with ADB | Export the plan as an ADB script |

## Huawei and Google services

Prune detects Google Mobile Services, Huawei Mobile Services and Honor Mobile Services from the installed packages,
independently of the brand. On a phone without Google services the replacement framework and its app store are
locked, because apps depend on them. Prune runs on EMUI and on HarmonyOS 2.x–4.x, which still run Android apps.
HarmonyOS NEXT does not run Android apps, so Prune cannot be installed there.

## How to help

1. Install Prune, set up Shizuku, and open the compatibility report.
2. Try a Minimal profile and undo it.
3. Open an issue with the shared report and what worked or failed.
