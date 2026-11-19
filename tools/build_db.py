#!/usr/bin/env python3
"""Builds Prune's package database assets.

Inputs
  tools/uad_lists.json   community data from Universal Android Debloater Next Generation (GPL-3.0)
  tools/prune_rules.json Prune's own safety rules (always win over UAD data)

Outputs (app/src/main/assets/)
  packages_uad.json      Samsung, Google, AOSP, carrier and common preload entries, compact keys
  prune_rules.json       validated copy of the rules

Run from the repository root:  python3 tools/build_db.py
Update the UAD data first with: curl -o tools/uad_lists.json \
  https://raw.githubusercontent.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/main/resources/assets/uad_lists.json
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$|^android$")

# Oem entries are kept only when they belong to Samsung or to software Samsung preloads.
SAMSUNG_PREFIXES = (
    "com.samsung", "com.sec.", "com.sem.", "com.knox", "com.skms", "com.wsomacp", "com.wssyncmldm",
    "com.osp.", "com.monotype", "com.mygalaxy", "com.dsi.ant", "com.policydm", "com.android.samsung",
    "com.hiya", "com.gd.mobicore", "com.visionobjects", "com.diotek", "com.vlingo", "com.ws.dm",
    "com.swiftkey", "com.touchtype", "com.qualcomm", "com.fingerprints", "com.samsungmobile",
    "com.enhance.gameservice", "com.trustonic", "com.sktelecom", "com.kt.", "com.lguplus",
)
KEEP_LISTS = {"Google", "Aosp", "Carrier", "Misc"}
LIST_CODE = {"Oem": "o", "Google": "g", "Aosp": "a", "Carrier": "c", "Misc": "m", "Pending": "p"}
REMOVAL_CODE = {"Recommended": "r", "Advanced": "a", "Expert": "e", "Unsafe": "u"}
RISKS = {"SAFE", "LOW", "MODERATE", "HIGH"}


def fail(msg: str) -> None:
    print("ERROR:", msg, file=sys.stderr)
    sys.exit(1)


def clean_description(text: str) -> str:
    text = text.replace("\r", "").strip()
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text[:1200]


def build_uad() -> dict:
    data = json.loads((ROOT / "tools" / "uad_lists.json").read_text(encoding="utf-8"))
    out = {}
    for pkg, v in data.items():
        if not PKG_RE.match(pkg):
            continue
        lst = v.get("list", "")
        if lst == "Oem" and not pkg.startswith(SAMSUNG_PREFIXES):
            continue
        if lst != "Oem" and lst not in KEEP_LISTS:
            continue
        removal = v.get("removal", "")
        if removal not in REMOVAL_CODE:
            continue
        entry = {
            "l": LIST_CODE[lst],
            "r": REMOVAL_CODE[removal],
            "d": clean_description(v.get("description", "")),
        }
        deps = [d for d in v.get("dependencies", []) if PKG_RE.match(d)]
        needed = [d for d in v.get("neededBy", []) if PKG_RE.match(d)]
        if deps:
            entry["dep"] = deps
        if needed:
            entry["nb"] = needed
        out[pkg] = entry
    return out


def validate_rules(rules: dict) -> None:
    locked = {e["id"] for e in rules["locked"]}
    for e in rules["locked"]:
        if not PKG_RE.match(e["id"]) or not e.get("reason"):
            fail(f"bad locked entry {e}")
    for e in rules["lockedPrefixes"]:
        if not e["prefix"] or not e.get("reason"):
            fail(f"bad locked prefix {e}")
    keys = set()
    for eco in rules["ecosystems"] + rules["featureTags"]:
        if eco["key"] in keys:
            fail(f"duplicate group key {eco['key']}")
        keys.add(eco["key"])
        if not eco.get("warning") or not eco.get("name"):
            fail(f"group {eco['key']} needs name and warning")
        for p in eco.get("packages", []):
            if not PKG_RE.match(p):
                fail(f"bad package {p} in {eco['key']}")
            if p in locked:
                fail(f"{p} is both locked and in group {eco['key']}")
    for pkg, e in rules["entries"].items():
        if not PKG_RE.match(pkg):
            fail(f"bad entry id {pkg}")
        if pkg in locked:
            fail(f"{pkg} is both locked and a selectable entry")
        if any(pkg.startswith(p["prefix"]) for p in rules["lockedPrefixes"]):
            fail(f"{pkg} matches a locked prefix but has an entry")
        if e.get("level") not in (1, 2, 3, 4):
            fail(f"{pkg}: level must be 1-4")
        if e.get("risk") not in RISKS:
            fail(f"{pkg}: risk must be one of {RISKS}")
        if e["risk"] == "HIGH" and e["level"] != 4:
            fail(f"{pkg}: HIGH risk packages may only be selected in Custom (level 4)")
    for e in rules["prefixEntries"]:
        if e.get("level") not in (1, 2, 3, 4) or e.get("risk") not in RISKS:
            fail(f"bad prefix entry {e}")


def main() -> None:
    rules = json.loads((ROOT / "tools" / "prune_rules.json").read_text(encoding="utf-8"))
    validate_rules(rules)
    uad = build_uad()
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "packages_uad.json").write_text(
        json.dumps(uad, ensure_ascii=False, separators=(",", ":"), sort_keys=True), encoding="utf-8")
    (ASSETS / "prune_rules.json").write_text(
        json.dumps(rules, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"packages_uad.json: {len(uad)} entries; prune_rules.json: {len(rules['entries'])} curated, "
          f"{len(rules['locked'])} locked, {len(rules['ecosystems'])} ecosystems")


if __name__ == "__main__":
    main()
