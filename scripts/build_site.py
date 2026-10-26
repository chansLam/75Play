#!/usr/bin/env python3
"""Generate the static GitHub Pages editions from site/content.json + CHANGELOG.md."""
from __future__ import annotations

import json
import os
import re
import subprocess
from html import escape as e
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
CHANGELOG = ROOT / 'CHANGELOG.md'


def detect_repository() -> str:
    env = os.environ.get('GITHUB_REPOSITORY', '').strip()
    if env:
        return env
    config_path = SITE / 'config.json'
    if config_path.is_file():
        configured = json.loads(config_path.read_text(encoding='utf-8')).get('github_repository', '').strip()
        if configured:
            return configured
    try:
        url = subprocess.check_output(
            ['git', 'remote', 'get-url', 'origin'],
            cwd=ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        url = ''
    match = re.search(r'github\.com[:/]([^/]+)/([^/.]+?)(?:\.git)?$', url)
    if match:
        return f'{match.group(1)}/{match.group(2)}'
    raise SystemExit(
        'Cannot detect GitHub repository. Set GITHUB_REPOSITORY, site/config.json '
        'github_repository, or configure git remote origin.'
    )


def parse_changelog(path: Path) -> list[tuple[str, list[str]]]:
    """Return [(version, bullets)] from CHANGELOG.md `## X.Y.Z` sections."""
    if not path.is_file():
        return []
    versions: list[tuple[str, list[str]]] = []
    current: str | None = None
    bullets: list[str] = []
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.startswith('## '):
            if current is not None:
                versions.append((current, bullets))
            current = line[3:].strip()
            bullets = []
        elif current is not None and line.startswith('- '):
            bullets.append(line[2:].strip())
    if current is not None:
        versions.append((current, bullets))
    return versions


def md_inline(text: str) -> str:
    """Render a small safe subset of inline Markdown: escape first, then format."""
    text = e(text)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    text = re.sub(r'~~([^~]+)~~', r'<s>\1</s>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)
    text = re.sub(r'\*([^*]+)\*', r'<em>\1</em>', text)
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', text)
    return text


def render_changelog(entries: list[tuple[str, list[str]]], title: str, more: str, changelog_url: str) -> str:
    if not entries:
        return ''
    blocks = []
    for version, bullets in entries[:3]:
        label = version if version.lower().startswith('v') else f'v{version}'
        items = ''.join(f'<li>{md_inline(b)}</li>' for b in bullets[:8]) or '<li>—</li>'
        blocks.append(
            f'<article class="release"><h3><bdi>{e(label)}</bdi></h3><ul>{items}</ul></article>'
        )
    return (
        f'<section class="band changelog" id="changelog">'
        f'<div class="band-inner"><h2>{e(title)}</h2>'
        f'{"".join(blocks)}'
        f'<p class="fine"><a href="{e(changelog_url)}">{e(more)}</a></p></div></section>'
    )


config = json.loads((SITE / 'config.json').read_text(encoding='utf-8-sig'))
data = json.loads((SITE / 'content.json').read_text(encoding='utf-8-sig'))
REPO_SLUG = detect_repository()
OWNER, REPO_NAME = REPO_SLUG.split('/', 1)
BASE = f'https://{OWNER}.github.io/{REPO_NAME}/'
REPO = f'https://github.com/{REPO_SLUG}'
VERSION = config['version']
TAG = config.get('tag') or f'v{VERSION}'
APK = config.get('apk_asset') or f'75Play-v{VERSION}-release.apk'
RELEASE = f'{REPO}/releases/tag/{TAG}'
DOWNLOAD = f'{REPO}/releases/download/{TAG}/{APK}'
CHANGELOG_URL = f'{REPO}/blob/main/CHANGELOG.md'
INSTALL_DOC = f'{REPO}/blob/main/docs/INSTALL.md'
MIRRORS = [
    'https://ghproxy.net/' + DOWNLOAD,
    'https://gh-proxy.com/' + DOWNLOAD,
]
CHANGELOG_ENTRIES = parse_changelog(CHANGELOG)

for lang, d in data.items():
    folder = SITE if lang == 'zh-Hans' else SITE / lang
    folder.mkdir(exist_ok=True)
    prefix = './' if lang == 'zh-Hans' else '../'
    url = BASE + ('' if lang == 'zh-Hans' else lang + '/')
    nav = ''.join(
        f'<a href="{prefix}{"" if code == "zh-Hans" else code + "/"}" lang="{code}" hreflang="{code}" dir="auto"'
        + (' aria-current="page"' if code == lang else '')
        + f'>{e(v["name"])}</a>'
        for code, v in data.items()
    )
    alternates = ''.join(
        f'<link rel="alternate" hreflang="{code}" href="{BASE}{"" if code == "zh-Hans" else code + "/"}">'
        for code in data
    ) + f'<link rel="alternate" hreflang="x-default" href="{BASE}">'
    feature_items = ''.join(
        f'<li><strong>{e(item["title"])}</strong><span>{e(item["text"])}</span></li>'
        if isinstance(item, dict) else f'<li>{e(item)}</li>'
        for item in (d.get('features') or [])
    )
    step_items = ''.join(
        f'<li><span class="step-n" aria-hidden="true">{i}</span><span>{e(item)}</span></li>'
        for i, item in enumerate(d.get('steps') or [], start=1)
    )
    mirror_links = ' · '.join(
        f'<a href="{e(mirror)}">{e(d["mirrors"])} {i + 1}</a>' for i, mirror in enumerate(MIRRORS)
    )
    history_btn = (
        f'<a class="btn ghost" href="#changelog">{e(d.get("history", "Changelog"))}</a>'
        if CHANGELOG_ENTRIES else ''
    )
    changelog_html = render_changelog(
        CHANGELOG_ENTRIES,
        d.get('changelogTitle', 'Version history'),
        d.get('changelogMore', 'Full changelog'),
        CHANGELOG_URL,
    )
    page_title = f'75Play · {d["download"]}'
    (folder / 'index.html').write_text(
        f'''<!doctype html>
<html lang="{lang}" dir="{d['dir']}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{e(page_title)}</title>
<meta name="description" content="{e(d['intro'])}">
<meta name="theme-color" content="#0b1412">
<link rel="icon" href="{prefix}assets/icon.png?v={e(VERSION)}">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700&family=Syne:wght@600;700;800&display=swap" rel="stylesheet">
<link rel="stylesheet" href="{prefix}assets/site.css?v={e(VERSION)}">
<link rel="canonical" href="{url}">{alternates}
<meta property="og:title" content="75Play">
<meta property="og:description" content="{e(d['intro'])}">
<meta property="og:image" content="{BASE}assets/icon.png?v={e(VERSION)}">
<meta property="og:url" content="{url}">
<meta property="og:type" content="website">
</head>
<body>
<div class="atmosphere" aria-hidden="true"></div>
<header class="top">
  <a class="mark" href="{prefix}"><img src="{prefix}assets/icon.png?v={e(VERSION)}" width="40" height="40" alt=""><span>75Play</span></a>
  <nav class="languages" aria-label="Language">{nav}</nav>
</header>
<main>
  <section class="hero">
    <p class="brand">75Play</p>
    <p class="kicker"><bdi>{e(TAG)}</bdi> · {e(d['tag'])}</p>
    <h1>{e(d['title']).replace(chr(10), '<br>')}</h1>
    <p class="lede">{e(d['intro'])}</p>
    <div class="actions">
      <a class="btn primary" href="{DOWNLOAD}">{e(d['download'])}</a>
      <a class="btn ghost" href="#install">{e(d['install'])}</a>
      {history_btn}
    </div>
    <p class="fine mirrors">{mirror_links}</p>
    <p class="fine">{e(d['requires'])}</p>
  </section>

  <section class="band features" id="features">
    <div class="band-inner">
      <h2>{e(d['whats'])}</h2>
      <ul class="feature-list">{feature_items}</ul>
      <p class="fine scope">{e(d['supportScope'])}</p>
    </div>
  </section>

  <section class="band install" id="install">
    <div class="band-inner">
      <h2>{e(d['setup'])}</h2>
      <ol class="steps">{step_items}</ol>
      <p class="fine">{e(d['bssid'])} <a href="{INSTALL_DOC}">{e(d.get('adb', 'Install guide'))}</a></p>
    </div>
  </section>

  <section class="band shots" id="shots">
    <div class="band-inner">
      <h2>{e(d.get('shotsTitle', 'Screenshots'))}</h2>
      <p class="fine">{e(d.get('shotsSub', '75Play v2.0.0 real-device screenshots.'))}</p>
      <div class="shot-grid">
        <a href="{prefix}assets/screenshots/carplay-home.png"><img loading="lazy" src="{prefix}assets/screenshots/carplay-home.png" alt="{e(d.get('shotCarplay','CarPlay home'))}"></a>
        <a href="{prefix}assets/screenshots/home.png"><img loading="lazy" src="{prefix}assets/screenshots/home.png" alt="{e(d.get('shotHome','Home'))}"></a>
        <a href="{prefix}assets/screenshots/connecting.png"><img loading="lazy" src="{prefix}assets/screenshots/connecting.png" alt="{e(d.get('shotConnecting','Connecting'))}"></a>
        <a href="{prefix}assets/screenshots/settings-display.png"><img loading="lazy" src="{prefix}assets/screenshots/settings-display.png" alt="{e(d.get('shotDisplay','Display / audio settings'))}"></a>
        <a href="{prefix}assets/screenshots/settings-vehicle.png"><img loading="lazy" src="{prefix}assets/screenshots/settings-vehicle.png" alt="{e(d.get('shotVehicle','Vehicle / system settings'))}"></a>
        <a href="{prefix}assets/screenshots/connected-manage.png"><img loading="lazy" src="{prefix}assets/screenshots/connected-manage.png" alt="{e(d.get('shotConnected','Connected & manage apps'))}"></a>
        <a href="{prefix}assets/screenshots/app-manage.png"><img loading="lazy" src="{prefix}assets/screenshots/app-manage.png" alt="{e(d.get('shotApps','App management'))}"></a>
      </div>
    </div>
  </section>

  {changelog_html}

  <section class="band meta" id="updates">
    <div class="band-inner split">
      <div>
        <h2>{e(d['update'])}</h2>
        <p>{e(d['updateText'])}</p>
      </div>
      <div>
        <h2>{e(d['diagnosticsTitle'])}</h2>
        <p>{e(d['diagnosticsText'])}</p>
        <p class="fine"><a href="{REPO}/issues">{e(d['feedback'])}</a> · <a href="{REPO}/issues/new">{e(d['newIssue'])}</a></p>
      </div>
    </div>
  </section>
</main>
<footer class="foot">
  <nav>
    <a href="{REPO}/blob/main/SECURITY.md">{e(d['privacy'])}</a>
    <a href="{REPO}">{e(d['source'])}</a>
    <a href="{CHANGELOG_URL}">{e(d.get('changelogTitle', d['notes']))}</a>
    <a href="{RELEASE}">{e(d['notes'])}</a>
    <a href="{REPO}/issues">{e(d['feedback'])}</a>
  </nav>
  <p>{e(d['footer'])}</p>
</footer>
</body>
</html>
''',
        encoding='utf-8',
    )

print(f'Generated {len(data)} language pages for {REPO_SLUG} ({TAG})')
