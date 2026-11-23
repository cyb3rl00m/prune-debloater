#!/usr/bin/env python3
"""Tiny UI driver for CI: reads a `uiautomator dump` XML file and finds a node by text.

Usage: ci_ui.py center REGEX ui.xml   -> prints "x y" of the first node whose text matches
       ci_ui.py text REGEX ui.xml     -> prints the full text of the first matching node
Exit code 1 when nothing matches.
"""
import re
import sys
import xml.etree.ElementTree as ET

mode, pattern, path = sys.argv[1], re.compile(sys.argv[2]), sys.argv[3]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
for node in root.iter("node"):
    text = node.get("text") or ""
    if text and pattern.search(text):
        if mode == "text":
            print(text)
        else:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
