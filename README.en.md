# 75Play

**Wireless CarPlay for the Changan CS75 Plus 2020 head unit.**

An open-source wireless CarPlay receiver for the factory head unit (`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`). Three wireless modes: Wi‑Fi Direct (default), car hotspot (SoftAP), and existing Wi‑Fi / same LAN; built-in MFi authentication — no jailbreak, dongle, or account.

> Independent implementation, **not** Apple-certified, not affiliated with Apple or Changan. Set up while safely parked.
>
> Modified and optimized from [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android). Current release **v2.0.1** — see [CHANGELOG](CHANGELOG.md).

[Download APK](https://github.com/chansLam/75Play/releases) · [Project site](https://chansLam.github.io/75Play/en/) · [中文](README.md)

---

## Highlights

- **Wireless-only CarPlay** — Wi‑Fi Direct / car hotspot (SoftAP) / existing Wi‑Fi (LAN); USB wired removed (this head unit has no TUN)
- **One-tap connect** — Wi‑Fi Direct / car hotspot auto-prepare the link on switch; LAN starts CarPlay after Wi‑Fi joins
- **Immersive display** — wide-screen layout; launch head-unit apps from CarPlay; floating ball to return
- **Glass dual-theme UI** — home and settings grouped by driver goals
- **Languages** — zh-Hans / zh-Hant / en / ar / es / ru / uk
- **Android terminal** (experimental)

## Quick install

1. Uninstall any **differently signed** 75Play build first.
2. Download the APK from [Releases](https://github.com/chansLam/75Play/releases) and install it **on the head unit** (not the iPhone).
3. Open 75Play, keep Bluetooth on, and pick a mode on the home screen:
   - **Wi‑Fi Direct** / **car hotspot**: switching auto-enables Wi‑Fi or configures the hotspot; then tap **Connect CarPlay**
   - **LAN**: pick the Wi‑Fi and enter the password; CarPlay starts **automatically** after Wi‑Fi connects
4. Allow CarPlay on the iPhone.

Full guide (Chinese): [docs/INSTALL.md](docs/INSTALL.md).

## Docs

| Doc | About |
| --- | --- |
| [Install & use](docs/INSTALL.md) | Target unit, setup, connection, settings |
| [Build & release](docs/BUILD.md) | Environment, signing, Actions, in-app updates |
| [Security & privacy](SECURITY.md) | Keys, diagnostics, privacy |
| [Contributing](CONTRIBUTING.md) | Issues / PRs and local checks |
| [Third-party notices](docs/THIRD_PARTY_NOTICES.md) | Upstream tips and licenses |
| [Changelog](CHANGELOG.md) | Release history |

## Scope

| Supported | Not supported |
| --- | --- |
| CS75 Plus **2020** · `OS_S311-WT` | Other head units / model years |
| Wireless (Wi‑Fi Direct, SoftAP, existing Wi‑Fi / LAN) | USB wired CarPlay, TUN |
| `armeabi-v7a` | x86 / arm64 head-unit packages |

## Feedback

Open an [Issue](https://github.com/chansLam/75Play/issues) with:

1. A diagnostic report from **Settings → Diagnostics** (`.txt`)
2. Head-unit firmware and iPhone / iOS versions
3. Reproduction steps

**Do not** attach hotspot passwords or pairing material. See [SECURITY.md](SECURITY.md).

## License & trademarks

- Source: **GPL-3.0** ([`LICENSE`](LICENSE))
- Some UI derives from DiAuto (**AGPL-3.0**); see [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)
- CarPlay belongs to Apple Inc.; use of this software does not imply Apple certification
