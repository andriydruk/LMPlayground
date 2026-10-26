#!/usr/bin/env python3
"""Generate ios/LMPlayground/Localizable.xcstrings.

Keys are the English strings the Swift compiler extracts from the app
(SWIFT_EMIT_LOC_STRINGS writes *.stringsdata next to the object files).
Translations come from Android's res/values-*/strings.xml wherever the English
text is the same string, so both apps say the same thing in every language;
strings only the iOS app has are translated in ios/Localization/<locale>.json.

Usage:
  generate-strings.py --stringsdata <dir with *.stringsdata> [--check]

--check exits non-zero when a translatable key has no translation in some
locale, or when the catalog on disk is out of date.
"""
import argparse
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ANDROID_RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
IOS = os.path.join(ROOT, "ios")
CATALOG = os.path.join(IOS, "LMPlayground", "Localizable.xcstrings")
OVERRIDES = os.path.join(IOS, "Localization")

# Android resource folder suffix -> iOS locale.
LOCALES = {
    "ar": "ar", "bn": "bn", "cs": "cs", "da": "da", "de": "de", "es": "es", "fi": "fi",
    "fil": "fil", "fr": "fr", "hi": "hi", "in": "id", "it": "it", "iw": "he", "ja": "ja",
    "ko": "ko", "ms": "ms", "nb": "nb", "nl": "nl", "pl": "pl", "pt-rBR": "pt-BR", "ro": "ro",
    "sv": "sv", "th": "th", "tr": "tr", "uk": "uk", "vi": "vi", "zh-rCN": "zh-Hans",
}

# Debug-only screens: never shown in release builds.
DEBUG_SOURCES = {"EngineLabView.swift", "EngineLab.swift", "ChatSelfTest.swift"}
# Keys that read the same in every language.
VERBATIM = {"%@ · %@", "%@ · %.1fs", "me@andriydruk.com", "", "Top P", "Min P", "Top K: %lld"}

# Debug-only keys used outside the debug screens.
DEBUG_KEYS = {"Engine Lab", "Debug"}

# iOS key -> Android string name, where the iOS wording differs only in
# capitalization or phrasing and Android's translation fits.
ALIASES = {
    "System prompts": "system_prompts",
    "Context size": "context_size",
    "Repetition penalty": "repetition_penalty",
    "Chats": "conversations",
    "New chat": "new_conversation",
    "Rename chat": "rename_conversation",
    "Choose a model": "select_model",
    "Copied": "response_copied",
    "With image understanding": "download_with_images",
    "Available in your language": "supports_your_language",
    "Read web pages": "tool_web_fetch_title",
    "Photo Library": "choose_from_library",
}

# iOS key -> Android plurals name, for keys that need plural forms.
PLURALS = {"%lld tokens": "inference_notification_tokens"}


def android_text(raw):
    """An Android string resource value as the user sees it."""
    text = raw.strip()
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        text = text[1:-1]
    text = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), text)
    text = text.replace("\\n", "\n").replace("\\'", "'").replace('\\"', '"').replace("\\@", "@").replace("\\?", "?")
    return text


def to_ios_format(text, positional):
    """Android format specifiers to Foundation ones."""
    def repl(m):
        index, kind = m.group(1), m.group(2)
        spec = "@" if kind == "s" else "lld"
        return f"%{index}${spec}" if (index and positional) else f"%{spec}"
    return re.sub(r"%(?:(\d+)\$)?([sd])", repl, text)


def load_android(folder):
    path = os.path.join(ANDROID_RES, folder, "strings.xml")
    strings, plurals = {}, {}
    if not os.path.exists(path):
        return strings, plurals
    root = ET.parse(path).getroot()
    for el in root:
        name = el.get("name")
        if el.tag == "string" and el.get("translatable") != "false":
            strings[name] = android_text("".join(el.itertext()))
        elif el.tag == "plurals":
            plurals[name] = {item.get("quantity"): android_text("".join(item.itertext())) for item in el}
    return strings, plurals


def load_keys(stringsdata_dir):
    keys = {}
    for path in glob.glob(os.path.join(stringsdata_dir, "*.stringsdata")):
        with open(path) as f:
            data = json.load(f)
        source = os.path.basename(data.get("source", ""))
        for item in data.get("tables", {}).get("Localizable", []):
            keys.setdefault(item["key"], set()).add(source)
    return keys


def placeholders(text):
    """Format arguments, positions dropped, in order of their position."""
    found = re.findall(r"%(?:(\d+)\$)?(@|lld|\.1f|d)", text)
    if any(index for index, _ in found):
        found = sorted(found, key=lambda f: int(f[0] or 0))
    return [kind for _, kind in found]


def unit(value):
    return {"stringUnit": {"state": "translated", "value": value}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--stringsdata", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    keys = load_keys(args.stringsdata)
    if not keys:
        sys.exit(f"no *.stringsdata in {args.stringsdata}; build the app first")
    # A Release build compiles the debug-only code out; keep its keys so
    # Debug and Release builds produce the same catalog.
    for key in DEBUG_KEYS:
        keys.setdefault(key, set())

    en_strings, en_plurals = load_android("values")
    # English text (positions dropped) -> Android string name.
    by_text = {to_ios_format(text, positional=False): name for name, text in en_strings.items()}

    catalog = {"sourceLanguage": "en", "strings": {}, "version": "1.0"}
    missing = {}
    for key in sorted(keys):
        sources = keys[key]
        entry = {}
        if sources <= DEBUG_SOURCES or key in DEBUG_KEYS or key in VERBATIM:
            entry["shouldTranslate"] = False
            catalog["strings"][key] = entry
            continue
        localizations = {}
        if key in PLURALS:
            forms = en_plurals[PLURALS[key]]
            localizations["en"] = {"variations": {"plural": {q: unit(to_ios_format(v, False)) for q, v in forms.items()}}}
        for folder, locale in LOCALES.items():
            strings, plurals = load_android(f"values-{folder}")
            override_path = os.path.join(OVERRIDES, f"{locale}.json")
            overrides = json.load(open(override_path)) if os.path.exists(override_path) else {}
            if key in overrides:
                if sorted(placeholders(overrides[key])) != sorted(placeholders(key)):
                    sys.exit(f"{locale}: placeholders differ for {key!r}: {overrides[key]!r}")
                localizations[locale] = unit(overrides[key])
            elif key in PLURALS and PLURALS[key] in plurals:
                forms = plurals[PLURALS[key]]
                localizations[locale] = {"variations": {"plural": {q: unit(to_ios_format(v, False)) for q, v in forms.items()}}}
            elif key in ALIASES and ALIASES[key] in strings:
                localizations[locale] = unit(to_ios_format(strings[ALIASES[key]], key.count("%") > 1))
            elif key in by_text and by_text[key] in strings:
                positional = key.count("%") > 1
                localizations[locale] = unit(to_ios_format(strings[by_text[key]], positional))
            else:
                missing.setdefault(locale, []).append(key)
        if localizations:
            entry["localizations"] = localizations
        catalog["strings"][key] = entry

    text = json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if missing:
        total = sum(len(v) for v in missing.values())
        print(f"{total} missing translations across {len(missing)} locales", file=sys.stderr)
        for key in sorted({k for v in missing.values() for k in v}):
            locales = [loc for loc, ks in missing.items() if key in ks]
            print(f"  {key[:80]!r} ({len(locales)} locales)", file=sys.stderr)
    if missing and os.environ.get("LMP_MISSING_OUT"):
        with open(os.environ["LMP_MISSING_OUT"], "w") as f:
            json.dump(sorted({k for v in missing.values() for k in v}), f, ensure_ascii=False, indent=1)
    if args.check:
        current = open(CATALOG).read() if os.path.exists(CATALOG) else ""
        if missing or current != text:
            sys.exit("Localizable.xcstrings is out of date or incomplete")
        print("Localizable.xcstrings is up to date")
        return
    with open(CATALOG, "w") as f:
        f.write(text)
    print(f"wrote {CATALOG}: {len(catalog['strings'])} keys")
    write_infoplist_catalog()


def write_infoplist_catalog():
    """InfoPlist.xcstrings from ios/Localization/InfoPlist/translations.json
    (permission prompts; Android has no equivalent strings)."""
    with open(os.path.join(OVERRIDES, "InfoPlist", "translations.json")) as f:
        source = json.load(f)
    expected = {"en", *LOCALES.values()}
    strings = {}
    for key, values in source.items():
        if set(values) != expected:
            sys.exit(f"InfoPlist {key}: locales differ: {sorted(expected ^ set(values))}")
        strings[key] = {"extractionState": "manual",
                        "localizations": {loc: unit(text) for loc, text in values.items()}}
    path = os.path.join(IOS, "LMPlayground", "InfoPlist.xcstrings")
    with open(path, "w") as f:
        f.write(json.dumps({"sourceLanguage": "en", "strings": strings, "version": "1.0"},
                           ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
