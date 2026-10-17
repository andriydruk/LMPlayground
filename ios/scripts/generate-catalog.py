#!/usr/bin/env python3
"""Generate the iOS model catalog from the Android one.

Android's ModelInfoProvider.kt is the source of truth for every catalog model
(name, files, URLs, languages, capability flags, measured thinking modes).
ios/catalog/ios-catalog.json adds what is iOS-specific: which models were
verified on the reference device (the iPhone 16 Pro) and what was measured.

    ios/scripts/generate-catalog.py           write ModelCatalog+Generated.swift
    ios/scripts/generate-catalog.py --check   fail if it is out of date

The --check mode also fails when ios-catalog.json names a model that the
Android catalog no longer has.
"""

import json
import re
import sys
from pathlib import Path

IOS_DIR = Path(__file__).resolve().parent.parent
REPO = IOS_DIR.parent
PROVIDER = REPO / "android/app/src/main/java/com/druk/lmplayground/models/ModelInfoProvider.kt"
IOS_CATALOG = IOS_DIR / "catalog/ios-catalog.json"
OUTPUT = IOS_DIR / "LMPlayground/Models/ModelCatalog+Generated.swift"

STATUSES = {"verified", "candidate", "excluded"}


def kotlin_string(literal: str) -> str:
    """Decode a Kotlin string literal body (\\uXXXX, \\", \\\\, \\$)."""
    out = []
    i = 0
    while i < len(literal):
        c = literal[i]
        if c == "\\" and i + 1 < len(literal):
            n = literal[i + 1]
            if n == "u":
                out.append(chr(int(literal[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"n": "\n", "t": "\t"}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strings_in(text: str) -> list[str]:
    return [kotlin_string(s) for s in re.findall(r'"((?:[^"\\]|\\.)*)"', text)]


def balanced(text: str, start: int) -> str:
    """The text inside the parentheses opening at text[start]."""
    assert text[start] == "("
    depth = 0
    in_string = False
    i = start
    while i < len(text):
        c = text[i]
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
        elif c == '"':
            in_string = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    raise ValueError("unbalanced parentheses")


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$", "", text)


def parse_language_lists(src: str) -> dict[str, list[str]]:
    lists: dict[str, list[str]] = {}
    for m in re.finditer(r"private val (\w+) = (listOf\(|(\w+)\s*$)", src, flags=re.M):
        name = m.group(1)
        if m.group(2).startswith("listOf("):
            lists[name] = strings_in(balanced(src, m.end() - 1))
        else:
            lists[name] = lists[m.group(3)]
    return lists


def parse_set(src: str, name: str) -> set[str]:
    m = re.search(rf"private val {name} = setOf\(", src)
    return set(strings_in(balanced(src, m.end() - 1)))


def parse_thinking_modes(src: str) -> dict[str, str]:
    m = re.search(r"private val THINKING_MODE = mapOf\(", src)
    body = balanced(src, m.end() - 1)
    return {kotlin_string(f): mode for f, mode in re.findall(r'"((?:[^"\\]|\\.)*)" to ThinkingMode\.(\w+)', body)}


def parse_model(body: str, languages: dict[str, list[str]]) -> dict:
    def field(name: str):
        m = re.search(rf"\b{name}\s*=\s*", body)
        if not m:
            return None
        rest = body[m.end():]
        if rest.startswith('"'):
            return kotlin_string(re.match(r'"((?:[^"\\]|\\.)*)"', rest).group(1))
        if rest.startswith("Uri.parse(") or rest.startswith("LocalDate.parse("):
            return kotlin_string(re.match(r'\w+\.parse\("((?:[^"\\]|\\.)*)"\)', rest).group(1))
        if rest.startswith("R.drawable."):
            return re.match(r"R\.drawable\.(\w+)", rest).group(1)
        if rest.startswith("listOf("):
            return strings_in(balanced(rest, len("listOf")))
        if rest.startswith("true") or rest.startswith("false"):
            return rest.startswith("true")
        ident = re.match(r"(\w+)", rest).group(1)
        if ident in languages:
            return languages[ident]
        raise ValueError(f"unsupported value for {name}: {rest[:40]!r}")

    return {
        "name": field("name"),
        "filename": field("filename"),
        "remoteURL": field("remoteUri"),
        "releaseDate": field("releaseDate"),
        "description": field("description"),
        "logo": field("logoRes") or "penrose_triangle",
        "languages": field("supportedLanguages") or [],
        "deprecated": field("deprecated") or False,
        "mmprojFilename": field("mmprojFilename"),
        "mmprojURL": field("mmprojUri"),
    }


def parse_catalog() -> dict:
    src = strip_comments(PROVIDER.read_text())
    languages = parse_language_lists(src)
    tools = parse_set(src, "TOOL_CAPABLE")
    thinking = parse_set(src, "THINKING_CAPABLE")
    modes = parse_thinking_modes(src)

    raw_start = re.search(r"private val rawModels: List<ModelInfo> = listOf\(", src)
    raw = balanced(src, raw_start.end() - 1)
    models = []
    for m in re.finditer(r"ModelInfo\(", raw):
        # Only top-level entries: skip matches nested inside another entry.
        if models and m.start() < models[-1][1]:
            continue
        body = balanced(raw, m.end() - 1)
        models.append((parse_model(body, languages), m.end() + len(body)))
    chat = [m for m, _ in models]
    for m in chat:
        m["supportsTools"] = m["filename"] in tools
        m["supportsThinking"] = m["filename"] in thinking
        m["thinkingMode"] = modes.get(m["filename"], "UNKNOWN").lower()

    def single(name: str) -> dict:
        m = re.search(rf"val {name}: ModelInfo = ModelInfo\(", src)
        model = parse_model(balanced(src, m.end() - 1), languages)
        model.update(supportsTools=False, supportsThinking=False, thinkingMode="none")
        return model

    return {"chat": chat, "embedding": single("embeddingModel"), "dictation": single("dictationModel")}


def swift_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def swift_optional(value) -> str:
    return "nil" if value is None else swift_string(value)


def swift_model(m: dict, ios: dict, kind: str) -> str:
    entry = ios.get(m["filename"], {})
    status = entry.get("status", "candidate")
    measured = entry.get("measured")
    measured_swift = "nil"
    if measured:
        measured_swift = (
            "MeasuredPerformance("
            f"footprintMB: {int(measured['footprintMB'])}, "
            f"tokensPerSecond: {float(measured['tokensPerSecond'])}, "
            f"device: {swift_string(measured['device'])})"
        )
    langs = "[" + ", ".join(swift_string(l) for l in m["languages"]) + "]"
    return f"""        CatalogModel(
            kind: .{kind},
            name: {swift_string(m["name"])},
            filename: {swift_string(m["filename"])},
            remoteURL: {swift_optional(m["remoteURL"])},
            releaseDate: {swift_optional(m["releaseDate"])},
            summary: {swift_string(m["description"])},
            logo: {swift_string(m["logo"])},
            supportedLanguages: {langs},
            supportsTools: {str(m["supportsTools"]).lower()},
            supportsThinking: {str(m["supportsThinking"]).lower()},
            thinkingMode: .{m["thinkingMode"]},
            deprecated: {str(m["deprecated"]).lower()},
            mmprojFilename: {swift_optional(m["mmprojFilename"])},
            mmprojURL: {swift_optional(m["mmprojURL"])},
            iosStatus: .{status},
            maxContext: {entry.get("maxContext", "nil")},
            measured: {measured_swift}
        )"""


def render(catalog: dict, ios: dict) -> str:
    chat = ",\n".join(swift_model(m, ios, "chat") for m in catalog["chat"])
    return f"""// GENERATED by ios/scripts/generate-catalog.py from
// android/.../models/ModelInfoProvider.kt and ios/catalog/ios-catalog.json.
// Do not edit; change those files and re-run the script.

extension ModelCatalog {{

    static let chatModels: [CatalogModel] = [
{chat}
    ]

    static let embeddingModel =
{swift_model(catalog["embedding"], ios, "embedding")}

    static let dictationModel =
{swift_model(catalog["dictation"], ios, "dictation")}
}}
"""


def main() -> int:
    check = "--check" in sys.argv[1:]
    catalog = parse_catalog()
    ios_json = json.loads(IOS_CATALOG.read_text())
    ios = ios_json.get("models", {})

    known = {m["filename"] for m in catalog["chat"]} | {catalog["embedding"]["filename"], catalog["dictation"]["filename"]}
    errors = [f"ios-catalog.json names {f}, which the Android catalog doesn't have" for f in ios if f not in known]
    errors += [f"{f}: unknown status {e.get('status')!r}" for f, e in ios.items() if e.get("status") not in STATUSES]
    if errors:
        print("\n".join(f"error: {e}" for e in errors), file=sys.stderr)
        return 1

    output = render(catalog, ios)
    if check:
        if not OUTPUT.exists() or OUTPUT.read_text() != output:
            print(f"error: {OUTPUT.relative_to(REPO)} is out of date; run ios/scripts/generate-catalog.py", file=sys.stderr)
            return 1
        print(f"catalog up to date ({len(catalog['chat'])} chat models)")
        return 0
    OUTPUT.write_text(output)
    verified = sum(1 for e in ios.values() if e.get("status") == "verified")
    print(f"wrote {OUTPUT.relative_to(REPO)}: {len(catalog['chat'])} chat models, {verified} verified on device")
    return 0


if __name__ == "__main__":
    sys.exit(main())
