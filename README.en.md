# 75Play

**CarPlay for the Changan CS75 Plus 2020 head unit.**

An open-source CarPlay receiver for the factory head unit (`OS_S311-WT` · Android 4.4.2 / API 19 · `armeabi-v7a`). Wireless is the default: Wi‑Fi Direct, car hotspot (SoftAP), and existing Wi‑Fi / same LAN, plus wired USB. Built-in MFi authentication — no jailbreak, dongle, or account.

> Independent implementation, **not** Apple-certified, not affiliated with Apple or Changan. Set up while safely parked.
>
> Modified and optimized from [DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android). Current release **v2.0.6-beta** — see [CHANGELOG](CHANGELOG.md). In-app updates do not offer this preview.

[Download APK](https://github.com/chansLam/75Play/releases) · [Project site](https://chansLam.github.io/75Play/en/) · [中文](README.md)

---

## Highlights

- **Three wireless modes** — Wi‑Fi Direct is the default and uses IPv4 only; car hotspot and existing Wi‑Fi stay available. A failed direct link resets once
- **Wired USB** — plug a data cable into the head unit, then connect. If an iPhone is already on USB, the session switches to wired and does not need a kernel TUN
- **Shell wallpaper** — default background, local custom image, daily Bing/Wallhaven downloads, and multi-source online search with fullscreen paging preview
- **Steering-wheel keys** — voice wakes Siri, previous / next track, answer / hang up. A short volume press changes the head-unit volume; holding volume zooms the map. SRC cycles the map view. A generic wheel map can be turned on in Settings (off by default) to learn key codes
- **Fullscreen picture** — always fullscreen, SurfaceView, H.264. Frame rate 15 / 30 / 45 / 60, default 30. Medium and small sizes can show Smart Display Zoom on the iPhone
- **Cluster and temperature** — CarPlay turn guidance is sent to the factory cluster. When the head unit has a cluster display, a left swipe with the same fingers as the return gesture (three by default) draws the navigation map there after guidance has started, and it stays for the rest of that connection. A single-screen head unit still receives only the turn guidance. A top bar shows outside, cabin, and setpoint temperatures, and can be turned off under Display
- **Settings stay on the shell** — Display & audio and About are three columns, and they are not edited on the CarPlay picture
- **Languages** — zh-Hans / zh-Hant / en / ar / es / ru / uk
- **Android terminal** (experimental)

## Quick install

1. Uninstall any **differently signed** 75Play build first.
2. Download the APK from [Releases](https://github.com/chansLam/75Play/releases) and install it **on the head unit** (not the iPhone).
3. Open 75Play, keep Bluetooth on, and pick a mode on the home screen:
   - **Wi‑Fi Direct** / **car hotspot**: switching auto-enables Wi‑Fi or configures the hotspot; then tap **Connect CarPlay**
   - **LAN**: pick the Wi‑Fi and enter the password; CarPlay starts **automatically** after Wi‑Fi connects
   - **Wired USB**: plug a data cable into the head unit, tap **Connect CarPlay**, and allow the USB permission
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
| Wireless (Wi‑Fi Direct, SoftAP, existing Wi‑Fi / LAN) and wired USB | Kernel TUN, BYD HUD |
| `armeabi-v7a` | x86 / arm64 head-unit packages |

## Feedback

Open an [Issue](https://github.com/chansLam/75Play/issues) with:

1. A diagnostic report from **About → Diagnostic report → Export** (`.txt`)
2. Head-unit firmware and iPhone / iOS versions
3. Reproduction steps

**Do not** attach hotspot passwords or pairing material. See [SECURITY.md](SECURITY.md).

## License & trademarks

- Source: **GPL-3.0** ([`LICENSE`](LICENSE))
- CarPlay belongs to Apple Inc.; use of this software does not imply Apple certification
