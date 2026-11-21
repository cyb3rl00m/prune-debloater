#!/usr/bin/env python3
"""Builds Prune's package database assets.

Inputs
  tools/uad_lists.json   community data from Universal Android Debloater Next Generation (GPL-3.0)
  tools/rules/*.json     Prune's own safety rules: core.json for every device plus one file per manufacturer.
                         Prune's rules always win over the community data.

Outputs (app/src/main/assets/)
  packages_uad.json      manufacturer, Google, AOSP, carrier and common preload entries, compact keys
  rules/*.json           validated copies of the rule files
  rules/index.json       list of rule files, core first

Run from the repository root:  python3 tools/build_db.py
Update the community data first with: curl -o tools/uad_lists.json \
  https://raw.githubusercontent.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/main/resources/assets/uad_lists.json
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
RULES_SRC = ROOT / "tools" / "rules"
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$|^android$")

LIST_CODE = {"Oem": "o", "Google": "g", "Aosp": "a", "Carrier": "c", "Misc": "m"}
REMOVAL_CODE = {"Recommended": "r", "Advanced": "a", "Expert": "e", "Unsafe": "u"}
RISKS = {"SAFE", "LOW", "MODERATE", "HIGH"}
REQUIRED_KEYS = {"schema", "manufacturer", "updated", "locked", "lockedPrefixes", "ecosystems", "featureTags",
                 "entries", "prefixEntries"}


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
        removal = v.get("removal", "")
        if lst not in LIST_CODE or removal not in REMOVAL_CODE:
            continue
        entry = {"l": LIST_CODE[lst], "r": REMOVAL_CODE[removal], "d": clean_description(v.get("description", ""))}
        deps = [d for d in v.get("dependencies", []) if PKG_RE.match(d)]
        needed = [d for d in v.get("neededBy", []) if PKG_RE.match(d)]
        if deps:
            entry["dep"] = deps
        if needed:
            entry["nb"] = needed
        out[pkg] = entry
    return out


def validate_file(name: str, rules: dict) -> None:
    missing = REQUIRED_KEYS - rules.keys()
    if missing:
        fail(f"{name}: missing keys {sorted(missing)}")
    for e in rules["locked"]:
        if not PKG_RE.match(e["id"]) or not e.get("reason"):
            fail(f"{name}: bad locked entry {e}")
    for e in rules["lockedPrefixes"]:
        if not e["prefix"] or not e.get("reason"):
            fail(f"{name}: bad locked prefix {e}")
    for g in rules["ecosystems"] + rules["featureTags"]:
        if not g.get("warning") or not g.get("name") or not g.get("key"):
            fail(f"{name}: group {g.get('key')} needs key, name and warning")
        for p in g.get("packages", []):
            if not PKG_RE.match(p):
                fail(f"{name}: bad package {p} in {g['key']}")
    for pkg, e in rules["entries"].items():
        if not PKG_RE.match(pkg):
            fail(f"{name}: bad entry id {pkg}")
        if e.get("level") not in (1, 2, 3, 4):
            fail(f"{name}: {pkg}: level must be 1-4")
        if e.get("risk") not in RISKS:
            fail(f"{name}: {pkg}: risk must be one of {RISKS}")
        if e["risk"] == "HIGH" and e["level"] != 4:
            fail(f"{name}: {pkg}: HIGH risk packages may only be selected in Custom (level 4)")
        for k in ("minSdk", "maxSdk"):
            if k in e and not isinstance(e[k], int):
                fail(f"{name}: {pkg}: {k} must be an integer")
    for e in rules["prefixEntries"]:
        if e.get("level") not in (1, 2, 3, 4) or e.get("risk") not in RISKS:
            fail(f"{name}: bad prefix entry {e}")


def validate_all(files: dict) -> None:
    locked = {e["id"] for r in files.values() for e in r["locked"]}
    prefixes = [e["prefix"] for r in files.values() for e in r["lockedPrefixes"]]
    seen_entries = {}
    eco_keys = {}
    for name, r in files.items():
        for g in r["ecosystems"]:
            if g["key"] in eco_keys and eco_keys[g["key"]] != name:
                fail(f"ecosystem {g['key']} defined in both {eco_keys[g['key']]} and {name}")
            eco_keys[g["key"]] = name
            for p in g.get("packages", []):
                if p in locked:
                    fail(f"{p} is both locked and in group {g['key']} ({name})")
        for pkg in r["entries"]:
            if pkg in locked:
                fail(f"{pkg} is locked but has a selectable entry in {name}")
            if any(pkg.startswith(p) for p in prefixes):
                fail(f"{pkg} matches a locked prefix but has an entry in {name}")
            if pkg in seen_entries:
                fail(f"{pkg} has entries in both {seen_entries[pkg]} and {name}")
            seen_entries[pkg] = name


def main() -> None:
    names = sorted(p.name for p in RULES_SRC.glob("*.json"))
    if "core.json" not in names:
        fail("tools/rules/core.json is required")
    names.remove("core.json")
    names.insert(0, "core.json")
    files = {}
    for n in names:
        rules = json.loads((RULES_SRC / n).read_text(encoding="utf-8"))
        validate_file(n, rules)
        files[n] = rules
    validate_all(files)
    uad = build_uad()

    out_rules = ASSETS / "rules"
    out_rules.mkdir(parents=True, exist_ok=True)
    for old in out_rules.glob("*.json"):
        old.unlink()
    for n, rules in files.items():
        (out_rules / n).write_text(json.dumps(rules, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_rules / "index.json").write_text(json.dumps({"files": names}, indent=1), encoding="utf-8")
    (ASSETS / "packages_uad.json").write_text(
        json.dumps(uad, ensure_ascii=False, separators=(",", ":"), sort_keys=True), encoding="utf-8")
    legacy = ASSETS / "prune_rules.json"
    if legacy.exists():
        legacy.unlink()
    total = sum(len(r["entries"]) for r in files.values())
    print(f"packages_uad.json: {len(uad)} entries; {len(files)} rule files, {total} curated entries")
    for n, r in files.items():
        print(f"  {n:20} {r['manufacturer']:28} locked {len(r['locked']):3}  entries {len(r['entries']):3}")


if __name__ == "__main__":
    main()
