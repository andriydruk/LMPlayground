#!/usr/bin/env python3
"""Check ios/store/<locale>/*.txt against the App Store Connect limits."""
import os
import sys

STORE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "store")
LIMITS = {"name.txt": 30, "subtitle.txt": 30, "promotional_text.txt": 170,
          "keywords.txt": 100, "description.txt": 4000}

problems = 0
for locale in sorted(os.listdir(STORE)):
    folder = os.path.join(STORE, locale)
    if not os.path.isdir(folder):
        continue
    for name, limit in LIMITS.items():
        path = os.path.join(folder, name)
        if not os.path.exists(path):
            print(f"{locale}/{name}: missing")
            problems += 1
            continue
        length = len(open(path, encoding="utf-8").read().strip())
        status = "ok" if 0 < length <= limit else "TOO LONG" if length > limit else "EMPTY"
        if status != "ok":
            problems += 1
        print(f"{locale}/{name}: {length}/{limit} {status}")
sys.exit(1 if problems else 0)
