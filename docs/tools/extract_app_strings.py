#!/usr/bin/env python3
"""Extract FAQ + screenshot chat strings from all values-*/strings.xml files.

Usage: python3 extract_app_strings.py [repo_root]
       (defaults to two levels up from this script: docs/tools/ → repo root)

Prints a JSON object {lang_code: {string_name: value}} to stdout.
"""
import os, re, json, sys
from xml.etree import ElementTree as ET

if len(sys.argv) > 1:
    REPO = sys.argv[1]
else:
    REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASE = os.path.join(REPO, 'app', 'src', 'main', 'res')

# Map android resource folder -> our website lang code.
# values         -> en (default)
# values-pt-rBR  -> pt
# values-zh-rCN  -> zh
# values-iw      -> iw (Hebrew)
# values-in      -> in (Indonesian)
# values-fil     -> fil
# everything else: trim "values-" prefix.
def folder_to_lang(folder):
    if folder == 'values':
        return 'en'
    code = folder.replace('values-', '')
    if code in ('night',):
        return None
    if '-' in code:
        # values-pt-rBR -> "pt-rBR" -> pt
        code = code.split('-')[0]
    return code

WANT = [
    'faq_q1','faq_q2','faq_q3','faq_q4','faq_q5',
    'faq_q6','faq_q7','faq_q8','faq_q9','faq_q10',
    'faq_q11','faq_q12','faq_q13','faq_q14','faq_q15',
    'faq_a1','faq_a2','faq_a3','faq_a4','faq_a5',
    'faq_a6','faq_a7','faq_a8','faq_a9','faq_a10',
    'faq_a11','faq_a12','faq_a13','faq_a14','faq_a15',
    # Privacy policy — the website hosts it too, mirroring the app copy so it
    # localizes as the Android resources get translated.
    'privacy_policy',
    'privacy_policy_effective_date',
    'privacy_policy_intro',
    'privacy_policy_s1_title','privacy_policy_s2_title','privacy_policy_s3_title',
    'privacy_policy_s4_title','privacy_policy_s5_title','privacy_policy_s6_title',
    'privacy_policy_s7_title',
    'privacy_policy_s1_body','privacy_policy_s2_body','privacy_policy_s3_body',
    'privacy_policy_s4_body','privacy_policy_s5_body','privacy_policy_s6_body',
    'privacy_policy_s7_body',
    'screenshot_chat_question',
    'screenshot_chat_response',
    'screenshot_chat',
    'screenshot_chat_sub',
    # Token-count unit ("%d tokens") — reused for the hero chat stats so the
    # word is localized (and keeps each locale's word order).
    'tokens_value',
    # Section headers used when the models list is split by language support.
    'supports_your_language',
    'other_models',
    # System-prompts + tools sections (website mirrors the app copy so it
    # localizes as the Android resources get translated).
    'system_prompts',
    'system_prompt_new',
    'tools',
    'tools_section_intro',
    'tool_run_javascript_title',
    'tool_run_javascript_desc',
    'tool_web_search_title',
    'tool_web_search_desc',
    'tool_web_fetch_title',
    'tool_web_fetch_desc',
]

def unescape(s):
    if s is None:
        return ''
    # Decode Java-style \uXXXX (Android strings.xml uses this)
    s = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s)
    # Android XML escapes
    s = s.replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')
    return s

out = {}
for folder in sorted(os.listdir(BASE)):
    if not folder.startswith('values'):
        continue
    lang = folder_to_lang(folder)
    if not lang:
        continue
    path = os.path.join(BASE, folder, 'strings.xml')
    if not os.path.exists(path):
        continue
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        print(f"WARN: parse error {path}: {e}", file=sys.stderr)
        continue
    root = tree.getroot()
    bag = {}
    for el in root.findall('string'):
        name = el.get('name')
        if name in WANT:
            # ET strips XML escapes but preserves Android \\n etc.
            text = el.text or ''
            # collect inner text including any nested tags (none expected in these keys)
            for child in el:
                text += ET.tostring(child, encoding='unicode')
                if child.tail:
                    text += child.tail
            bag[name] = unescape(text).strip()
    out[lang] = bag

print(json.dumps(out, ensure_ascii=False, indent=2))
