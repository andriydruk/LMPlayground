#!/usr/bin/env python3
"""Generate docs/i18n/<lang>.js — the website's translations for all 28 languages.

Sources, in order of precedence (later wins):
  1. English, read straight from the pages: every element marked with
     data-i18n / data-i18n-html / data-i18n-attr in docs/index.html and
     docs/models/index.html, plus the script-only strings in JS_EN below.
  2. Strings carried over from the previous design (legacy_i18n.py): the
     app-mirrored FAQ, privacy policy and phone chat (professional
     translations from the Android strings.xml), and any website copy whose
     English is unchanged.
  3. docs/tools/i18n/<lang>.json — translations of the current website copy
     (best-effort; PRs welcome).

Keys missing in a language fall back to English at runtime. Run with
--todo to write docs/tools/i18n/_todo.json listing what each language lacks.

Run:  python3 docs/tools/build_i18n.py [--todo]
"""
import html, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import legacy_i18n as legacy  # noqa: E402  (builds the old bundle in memory)

OLD = legacy.BUNDLE
PAGES = ['index.html', os.path.join('models', 'index.html')]

# Strings that only exist in the page scripts.
JS_EN = {
    "meta.title": "LM Playground — Private AI on your phone",
    "mp.meta.title": "Models — LM Playground",
    "ui.prev": "Previous",
    "ui.next": "Next",
    "ui.theme": "Toggle light or dark theme",
    "card.vision.alt": "A sheep grazing in a field",
    "tp.o1": "Ocean Blue.",
    "tp.o2": "Harbor Mist — a soft, cool gray-blue.",
    "tp.o3": "Midnight Orchard: deep plum with a hint of green.",
    "tp.o4": "Thunderstorm Lavender, flecked with copper like rain on a tin roof.",
    "tp.o5": "Velvet Static — the color a radio hums at 3 a.m. when nobody is listening.",
    "flow.q1": "What’s 15% of 240?",
    "flow.a1": "15% of 240 is <b>36</b>.",
    "flow.q2": "Translate “see you tomorrow” into Japanese.",
    "flow.a2": "また明日 — <i>mata ashita</i>.",
    "flow.q3": "Summarize this email in one line.",
    "flow.a3": "Thursday’s sync moves to Friday at 10am.",
    "flow.q4": "Is 391 a prime number?",
    "flow.a4": "No — 391 = <b>17 × 23</b>.",
    "how.dl.wait": "Waiting…",
    "how.dl.downloading": "Downloading · Wi‑Fi",
    "how.dl.eta": "{speed} MB/s · {time} left",
    "how.dl.saved": "Saved on device",
    "phone.qD": "When can I terminate my rental agreement?",
    "phone.tD": "Searching document",
    "phone.rD": "The excerpts say the lease can be ended after the initial six-month term with 60 days’ written notice, and Section 8.2 covers the deposit: returned within 14 days of move-out. Answer with both.",
    "phone.aD": "You can terminate with **60 days’ written notice** after the initial six-month period. Your deposit is returned within **14 days** of move-out (Section 8.2).",
    "mp.count.models": "models",
    "mp.count.publishers": "publishers",
    "mp.count.vision": "see images",
    "mp.count.tools": "can use tools",
    "mp.badge.new": "New",
    "mp.downloads": "{n} downloads / month",
    "mp.shown.all": "Showing all {n} models",
    "mp.shown.some": "Showing {n} of {total} models",
    "mp.empty": "No models match. Try another filter — or bring your own GGUF.",
}

# Carried over as-is from the previous design (app-mirrored or still used).
CARRY_PREFIXES = ('faq.', 'privacy.')
CARRY_KEYS = {
    'phone.q', 'phone.r0', 'phone.a', 'phone.qV', 'phone.rV', 'phone.aV',
    'phone.q1', 'phone.r1', 'phone.a1', 'phone.q2', 'phone.r2', 'phone.a2',
    'phone.q3', 'phone.r3', 'phone.a3', 'phone.thinking', 'phone.input', 'phone.send',
    'tools.t1.title', 'tools.t2.title', 'tools.t3.title', 'units.tokens', 'prompts.new',
}

TAG_RE = re.compile(r'<(\w+)([^<>]*?)\sdata-i18n(-html)?="([^"]+)"([^<>]*)>(.*?)</\1>', re.S)
ATTR_RE = re.compile(r'<\w+[^<>]*\sdata-i18n-attr="([^"]+)"[^<>]*>')


def norm(text):
    return re.sub(r'\s+', ' ', text).strip()


def page_strings():
    """English for every key used in the pages, read from the markup."""
    out = {}
    for rel in PAGES:
        src = open(os.path.join(DOCS, rel), encoding='utf-8').read()
        for m in TAG_RE.finditer(src):
            key, is_html, body = m.group(4), m.group(3), m.group(6)
            val = norm(body) if is_html else html.unescape(norm(body))
            if key in out and out[key] != val:
                print(f'warning: {key} has two English versions:\n  {out[key]!r}\n  {val!r}', file=sys.stderr)
            out.setdefault(key, val)
        for m in ATTR_RE.finditer(src):
            tag = m.group(0)
            for pair in m.group(1).split(';'):
                attr, key = pair.split(':')
                am = re.search(r'\s' + re.escape(attr) + r'="([^"]*)"', tag)
                if am:
                    out.setdefault(key, html.unescape(am.group(1)))
    return out


def build():
    en = page_strings()
    for k, v in JS_EN.items():
        en.setdefault(k, v)
    for k, v in OLD['en'].items():
        if k in CARRY_KEYS or k.startswith(CARRY_PREFIXES):
            en[k] = v  # the app's own English wins for mirrored strings

    # Any key whose English matches an old string (same key or not) can reuse
    # that string's translations.
    by_text = {}
    for k, v in OLD['en'].items():
        by_text.setdefault(v, k)

    langs = sorted(OLD.keys())
    bundle, missing = {'en': en}, {}
    for code in langs:
        if code == 'en':
            continue
        old = OLD[code]
        out = {}
        for k, v in en.items():
            if k in CARRY_KEYS or k.startswith(CARRY_PREFIXES):
                if k in old:
                    out[k] = old[k]
                continue
            src_key = k if OLD['en'].get(k) == v else by_text.get(v)
            if src_key and src_key in old and old[src_key] != OLD['en'][src_key]:
                out[k] = old[src_key]
        path = os.path.join(HERE, 'i18n', f'{code}.json')
        if os.path.exists(path):
            for k, v in json.load(open(path, encoding='utf-8')).items():
                if k in en:
                    out[k] = v
        # Drop entries identical to English — the runtime falls back anyway.
        out = {k: v for k, v in out.items() if v != en[k] or k in CARRY_KEYS}
        bundle[code] = out
        missing[code] = {k: en[k] for k in en if k not in out and not (k in CARRY_KEYS or k.startswith(CARRY_PREFIXES))}
    return en, bundle, missing


def main():
    en, bundle, missing = build()
    out_dir = os.path.join(DOCS, 'i18n')
    os.makedirs(out_dir, exist_ok=True)
    total = 0
    for code, strings in bundle.items():
        path = os.path.join(out_dir, f'{code}.js')
        with open(path, 'w', encoding='utf-8') as f:
            f.write('// Auto-generated by docs/tools/build_i18n.py — do not edit by hand.\n')
            f.write(f'(window.LMP_I18N = window.LMP_I18N || {{}})[{json.dumps(code)}] = ')
            f.write(json.dumps(strings, ensure_ascii=False, sort_keys=True, separators=(',', ':')))
            f.write(';\n')
        total += os.path.getsize(path)
    print(f'Wrote {len(bundle)} files to {out_dir} ({total // 1024} KB total, '
          f'en {os.path.getsize(os.path.join(out_dir, "en.js")) // 1024} KB), {len(en)} English keys', file=sys.stderr)
    for code in sorted(missing):
        if missing[code]:
            print(f'  {code}: {len(missing[code])} untranslated', file=sys.stderr)
    if '--todo' in sys.argv:
        todo = os.path.join(HERE, 'i18n', '_todo.json')
        os.makedirs(os.path.dirname(todo), exist_ok=True)
        json.dump(missing, open(todo, 'w', encoding='utf-8'), ensure_ascii=False, indent=1, sort_keys=True)
        print(f'Wrote {todo}', file=sys.stderr)


if __name__ == '__main__':
    main()
