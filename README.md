<p align="center">
  <img src="docs/images/nyetbox-icon.svg" alt="Nyetbox app icon" width="128">
</p>

<h1 align="center">Nyetbox</h1>

Nyetbox is a native Android companion app for a self-hosted
[NetBox](https://github.com/netbox-community/netbox) instance: an offline-first device browser
with QR/barcode scanning of the device-sticker links NetBox prints for you (e.g.
`https://netbox.example.com/dcim/devices/393/`).

The app was formerly known as **NetBox and Chill**. Version 1.1.0 introduces the Nyetbox name and
Android application ID; Android therefore treats it as a new installation rather than an in-place
update of the former package.

Nyetbox is an independent community project. It is not a NetBox Labs project and is not
affiliated with, sponsored by, maintained by, or endorsed by NetBox Labs, Inc. The NetBox name and
logo are the intellectual property and trademarks of NetBox Labs, Inc.; their use here (in text,
to describe compatibility) does not imply an official relationship. The app's own icon is an
original design and does not reuse NetBox's logo.

## Installation

Not published on Google Play, Amazon Appstore, F-Droid, or IzzyOnDroid. Install and auto-update
via [Obtainium](https://obtainium.imranr.dev/) pointed at this repository, or grab an APK directly
from the [Releases page](https://github.com/pschmitt/nyetbox/releases).

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60">][obtainium-link]

The badge tracks the `release` APK (applicationId `dev.pschmitt.nyetbox`, no `.debug`
suffix, arch auto-selected) from the
["latest"](https://github.com/pschmitt/nyetbox/releases/tag/latest) pre-release build.
Want a debuggable variant instead? Add the app normally in Obtainium via this repo's URL and
adjust the APK filter regex/prerelease settings, or grab the specific APK from the
[Releases page](https://github.com/pschmitt/nyetbox/releases).

[obtainium-link]: https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22dev.pschmitt.nyetbox%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpschmitt%2Fnyetbox%22%2C%22author%22%3A%22pschmitt%22%2C%22name%22%3A%22Nyetbox%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22app-.%2A-release%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22invertAPKFilter%5C%22%3Afalse%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22trackOnly%5C%22%3Afalse%7D%22%7D

Managing apps declaratively with [declaroid](https://github.com/pschmitt/declaroid) instead? Add:

```yaml
- name: Nyetbox
  pkg: dev.pschmitt.nyetbox
  store: github
  repo: pschmitt/nyetbox
```

## Features

- Native Material 3 UI (dynamic color on Android 12+), offline-first: the device inventory is
  cached locally (Room) so browsing/searching works without connectivity
- QR/barcode scanning (CameraX + ZXing, no Google Play Services dependency) of device-sticker
  links - point the camera at a device's sticker to jump straight to its details
- Opening a device-sticker link from any other app (share sheet, or the "Open with" chooser on
  the link itself) deep-links straight into the app
- Background sync via WorkManager, plus manual pull-to-refresh
- Token-only auth against the NetBox REST API - the base URL and token are stored encrypted
  on-device (`EncryptedSharedPreferences`) and never leave your device except to talk to your own
  NetBox instance (see [PRIVACY.md](PRIVACY.md))

## Screenshots

These are the same phone screenshots the `Screenshots` GitHub Actions workflow
generates for the store listing (see `app/src/androidTest/kotlin/dev/pschmitt/nyetbox/StoreScreenshotTest.kt`),
captured from a disposable local NetBox fixture containing only synthetic CI E2E records - no
production inventory, hostnames, tokens, or identifiers are shown. Running that workflow with
`open_pr` refreshes the files below in place, so this section never drifts from what the store
listing actually looks like.

<p>
  <img src="docs/images/readme-dashboard.png" alt="Dashboard with statistics and recent changes" width="180">
  <img src="docs/images/readme-device-detail.png" alt="Device detail page with status, cache, and media sections" width="180">
  <img src="docs/images/readme-topology.png" alt="Network topology graph" width="180">
  <img src="docs/images/readme-search.png" alt="Global search with recently visited items" width="180">
  <img src="docs/images/readme-settings.png" alt="Settings categories" width="180">
</p>

## Setup

You need your own NetBox instance and an API token (NetBox profile → API Tokens). Enter the
instance URL and token on first launch.

For headless provisioning, generate a login QR code from a checkout (or directly from the flake).
Pass a complete token as `--token`:

```console
nix run .#nyetbox-setup -- --url https://netbox.example.com --token 'nbt_...'
```

NetBox also exposes new tokens as separate name and secret fields. The CLI accepts that form too
and composes the current `nbt_<name>.<secret>` token format:

```console
nix run .#nyetbox-setup -- \
  --url https://netbox.example.com \
  --token-name home-phone \
  --token 'secret-value'
```

The QR is rendered in the terminal. Use `--output setup.png` to write an image instead. The QR
contains the API token, so treat the terminal output or image as a secret and scan it only on a
trusted device.

## Releases

APK releases are published through GitHub Releases. A separate, manual Play Store workflow can
build a signed AAB for inspection without publishing; Play publication is guarded by an explicit
workflow input and repository variable. See [docs/releasing.md](docs/releasing.md).

App Link host configuration and the limits of runtime host registration are documented in
[docs/app-links.md](docs/app-links.md).

## License

This project is licensed under [GPLv3](LICENSE).

Android is a trademark of Google LLC. Google Play and the Google Play logo are trademarks of
Google LLC. NetBox is a registered trademark of NetBox Labs, Inc.
