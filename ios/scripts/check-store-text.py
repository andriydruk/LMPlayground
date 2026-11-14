#!/usr/bin/env python3
"""Check ios/store against the App Store Connect limits.

Layout (fastlane deliver): store/<locale>/*.txt is the per-language listing,
store/copyright.txt applies to every language, and store/review_information/
holds the App Review notes. Contact details for App Review are entered in App
Store Connect, not kept in the repo.
"""
import os
import sys

STORE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "store")
LIMITS = {"name.txt": 30, "subtitle.txt": 30, "promotional_text.txt": 170,
          "keywords.txt": 100, "description.txt": 4000}
URLS = ("privacy_url.txt", "support_url.txt", "marketing_url.txt")
# Folders deliver reads that are not languages.
NON_LOCALE = {"review_information", "trade_representative_contact_information"}
SHARED = {"copyright.txt": 200, os.path.join("review_information", "notes.txt"): 4000}

problems = 0


def read(path):
    return open(path, encoding="utf-8").read().strip() if os.path.exists(path) else None


def report(label, ok, detail):
    global problems
    if not ok:
        problems += 1
    print(f"{label}: {detail}")


for name, limit in SHARED.items():
    text = read(os.path.join(STORE, name))
    if text is None:
        report(name, False, "missing")
    else:
        status = "ok" if 0 < len(text) <= limit else "TOO LONG" if text else "EMPTY"
        report(name, status == "ok", f"{len(text)}/{limit} {status}")

for locale in sorted(os.listdir(STORE)):
    folder = os.path.join(STORE, locale)
    if not os.path.isdir(folder) or locale in NON_LOCALE:
        continue
    for name, limit in LIMITS.items():
        text = read(os.path.join(folder, name))
        if text is None:
            report(f"{locale}/{name}", False, "missing")
            continue
        status = "ok" if 0 < len(text) <= limit else "TOO LONG" if text else "EMPTY"
        report(f"{locale}/{name}", status == "ok", f"{len(text)}/{limit} {status}")
    for name in URLS:
        text = read(os.path.join(folder, name))
        if text is None:
            report(f"{locale}/{name}", False, "missing")
            continue
        ok = text.startswith("https://") and len(text.split()) == 1
        report(f"{locale}/{name}", ok, text if ok else f"not a single https URL: {text!r}")

sys.exit(1 if problems else 0)
