#!/usr/bin/env python3
"""Render docs/banner.png (the 1200×630 link preview image) from docs/tools/banner.html.

Serves docs/ locally, captures the page with headless Chrome at 2× and scales it
down to 1200×630 so text stays crisp. Needs Google Chrome and Pillow.

Run:  python3 docs/tools/render_banner.py [--theme dark] [--out PATH]
"""
import argparse, functools, http.server, os, shutil, subprocess, tempfile, threading
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.dirname(HERE)
CHROME = next((p for p in (
    os.environ.get('CHROME'),
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    shutil.which('google-chrome'), shutil.which('chromium'), shutil.which('chrome'),
) if p and os.path.exists(p)), None)


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--theme', choices=['light', 'dark'], default='light')
    ap.add_argument('--out', default=os.path.join(DOCS, 'banner.png'))
    args = ap.parse_args()
    if not CHROME:
        raise SystemExit('Google Chrome not found; set CHROME=/path/to/chrome')

    srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Quiet, directory=DOCS))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    url = f'http://127.0.0.1:{srv.server_port}/tools/banner.html?theme={args.theme}'
    with tempfile.TemporaryDirectory() as tmp:
        shot = os.path.join(tmp, 'shot.png')
        subprocess.run([CHROME, '--headless=new', '--hide-scrollbars', '--force-device-scale-factor=2',
                        '--window-size=1200,630', '--virtual-time-budget=4000',
                        f'--user-data-dir={tmp}/profile', f'--screenshot={shot}', url],
                       check=True, capture_output=True)
        srv.shutdown()
        im = Image.open(shot).convert('RGB')
        im = im.crop((0, 0, 2400, 1260)).resize((1200, 630), Image.LANCZOS)
        im.save(args.out, optimize=True)
    print(f'wrote {args.out} ({os.path.getsize(args.out) // 1024} KB)')


if __name__ == '__main__':
    main()
