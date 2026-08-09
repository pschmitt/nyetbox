# Play Store screenshot automation (POC)

Captures Play Store listing screenshots with [fastlane screengrab][screengrab], driven by the
`StoreScreenshotTest` instrumented test
(`app/src/androidTest/kotlin/dev/pschmitt/nyetbox/StoreScreenshotTest.kt`). Scope is intentionally
narrow for now: **en-US only**, dashboard + device detail + topology + search + settings.

Fastlane regenerates `fastlane/README.md` itself on every run, so this doc lives outside
`fastlane/` to avoid being overwritten.

[screengrab]: https://docs.fastlane.tools/actions/screengrab/

## Why an emulator, and why a disposable NetBox

Nyetbox only has real data to show once it's connected to a NetBox instance, and this project's
physical test devices (Zenfone 10, Mi Pad 4, Pixel 5) are the user's own daily-driver hardware
connected to their real NetBox instance - store screenshots must never show that inventory data.
Rather than inventing a NetBox mocking layer, `just screenshots` reuses the disposable
docker-compose NetBox fixture already built for `.github/workflows/android-e2e.yaml` (see
`ci/netbox/docker-compose.yml`): same throwaway CI-only credentials, with the screenshot-only
Compose overlay adding the pinned `netbox-topology-views` and `netbox-documents` plugins. It's
seeded with its own demo data (`ci/netbox/seed_screenshots.py`, not the E2E workflow's
`seed.py` - see "Demo data" below) and created fresh and torn down
(`docker compose down --volumes`) at the end of every `just screenshots` run, success or
failure, so nothing persists between runs and nothing real is ever at risk.

Screenshots run against a local Android emulator rather than a physical device for the same
reason `android-e2e.yaml` uses one: a scripted, disposable target that starts from a known-clean
state every time, rather than juggling app-data wipes on a device you also use for other testing.
This machine has `/dev/kvm`, so the emulator boots in well under a minute (in the ~35-40s range),
much faster than CI's software-rendered fallback.

## How it fits the existing build split

Per `AGENTS.md`, Gradle/Android SDK work stays on the remote build hosts. `just screenshots`
respects that split - only `adb`/`emulator`/`fastlane` (no Gradle) run locally:

1. Builds and starts the disposable plugin-enabled NetBox fixture (`just screenshots-netbox-up`) and
   seeds it (`just netbox-seed`).
2. Creates the screenshot AVD once if needed (`just screenshots-avd-create`) and boots it
   (`just screenshots-emulator-start`), API 34 google_apis x86_64 - the same profile
   `android-e2e.yaml` uses.
3. `just screenshots-build` builds `app-x86_64-debug.apk` and `app-debug-androidTest.apk`
   remotely (same as `just build`) and fetches both into `./dist/`.
4. Installs the app, clears its data, and re-grants `POST_NOTIFICATIONS` (MainActivity requests it
   at startup on API 33+; an unhandled permission dialog would interrupt the Compose test
   mid-journey - same reason `android-e2e.yaml` grants it before the first launch).
5. `fastlane screengrab` (via `nix develop .#screenshots`) drives `StoreScreenshotTest` over adb
   and pulls the results into `fastlane/metadata/android/`.
6. Always tears the NetBox fixture back down (`just screenshots-netbox-down`, via a shell `trap`), even on
   failure.

Run the whole thing with:

```console
just screenshots
```

Phone output lands in `fastlane/metadata/android/en-US/images/phoneScreenshots/`.

For the tablet layout, manually dispatch the `Screenshots` GitHub Actions workflow.
It reuses the maintained `reactivecircus/android-emulator-runner` action, the plugin-enabled
disposable fixture, and the same `StoreScreenshotTest` journey. Download the resulting artifact;
the images are written to `fastlane/metadata/android/en-US/images/tenInchScreenshots/` so they
cannot overwrite phone screenshots.

## Uploading to Google Play

Capturing screenshots never changes the Play Console listing by itself. When a tagged release
triggers `screenshots.yaml` with `open_pr=true` (see `release.yaml`), the refreshed images land as
a PR with every image embedded inline in the PR body/comment for review - merging that PR is what
publishes them: `play-store-images.yaml` triggers on any push to `main` touching
`fastlane/metadata/android/en-US/images/**` and runs the same `just screenshots-upload` recipe
below in CI, authenticating `gpc` from the `PLAY_SERVICE_ACCOUNT_JSON` repository secret (the same
one `play-store.yaml` uses for bundle publishing). Nothing is uploaded until that PR is merged.

To upload manually instead (e.g. outside the PR flow, or to re-push without a new commit),
authenticate `gpc` for the Play Console service account and run the explicit upload recipe
yourself:

```console
gpc auth login
gpc apps list
just screenshots-upload
```

The upload recipe includes both phone and tablet screenshot buckets. The launcher icon and the
1024x500 feature graphic (the banner at the top of the store listing) are each composed from
`docs/images/nyetbox-icon.svg` and uploaded separately, since neither is locale-scoped:

```console
just play-icon-upload
just play-feature-graphic-upload
```

The recipe uploads each Fastlane output to the **release** package `dev.pschmitt.nyetbox`; the
screenshot test itself runs the separate debug package. It refuses to run when the generated
phone-screenshot directory is empty, and replaces each bucket's existing Play Console images with
the local set rather than appending to it - confirmed live that appending silently produces
duplicates, or outright exceeds Play's 8-screenshot-per-language cap. It verifies the target
package through `gpc apps list`; `gpc doctor` is not used as a publishing gate because its
package/credential diagnostics can be misleading when the package is supplied via
flags or another working authentication context.
Generated images are ignored by Git: this checkout currently has four older POC outputs, but no
topology or tablet capture yet. The four phone images and the flattened icon have been uploaded;
the tablet bucket remains empty until the manual workflow is run and its artifact is reviewed.

## Running it more than once against the same emulator

`just screenshots` clears the app's data before every run (`adb shell pm clear`), so re-running it
against an emulator that's still up from a previous capture starts from onboarding again rather
than failing because the app is already connected. Keeping the emulator running between runs (it's
only started if nothing is already listed under `emulator-*` in `adb devices`) is what makes
iterating on `StoreScreenshotTest` fast - only `screenshots-build` and the fastlane step need to
re-run, not the ~40s emulator boot.

## Multiple devices attached

If other Android devices/emulators are also attached (the physical Zenfone/Mi Pad/Pixel 5, or a
stray leftover emulator), `just screenshots` still targets the right one: it discovers the
`emulator-*` serial itself and passes it to every `adb` call and to fastlane via
`SCREENGRAB_SPECIFIC_DEVICE`.

## Demo data

`ci/netbox/seed_screenshots.py` seeds a small realistic-looking (but obviously fake) rack rather
than reusing `android-e2e.yaml`'s `ci/netbox/seed.py` - that script's exact-match assertions
(`CI E2E Device`, `CI E2E Manufacturer`, ...) exist for deterministic E2E test assertions, not to
look good in a store listing. `seed_screenshots.py` creates one manufacturer (Acme Networks), one
site (Berlin Data Center), one rack (Rack A1), and four devices (`core-sw-01`/`-02`, `edge-rtr-01`,
`fw-01`) with distinct roles and device types, three connected interface cables, and a named demo
document attached to `core-sw-01`. This gives the dashboard richer stats (3 device types, 4 devices,
1 rack) and gives the topology screen a real graph rather than a collection of isolated nodes.

## Search screenshot synchronization

`StoreScreenshotTest` waits for a fact that's only true once each screen's real data has rendered,
not a generic app-bar title, an asset tag also shown on the list row we just left, or (for search)
the search field's own typed text - all of those render before the underlying fetch/search
actually completes and can make the test capture a loading/empty placeholder instead of real
content. This was fixed for the dashboard, device detail, and settings screenshots, which
reliably show real content, and the device detail screen additionally needed an explicit
"Refresh" click (via its overflow menu) to work around what looks like a race between navigating
in and that screen's own per-device fetch actually starting - the NetBox API itself responds in
well under a second even right after `just netbox-up`, so this isn't a NetBox performance problem.

The search screenshot waits for the `e2e-search-result` semantics tag attached to a real result card,
so a slow or empty search cannot silently produce a bad listing asset. If that wait times out, the
capture fails and the test diagnostics should be inspected rather than publishing the empty state.

## Extending beyond the POC

- Running the updated result-card wait end to end again on the local emulator.
- More screens: add further `Screengrab.screenshot("...")` calls to `StoreScreenshotTest`,
  applying the same "wait on a fact unique to the loaded screen, not one already visible on the
  screen you're leaving" rule documented in the code comments.
- More locales: add entries to `locales(...)` in `fastlane/Screengrabfile` - screengrab switches
  the device locale for each one via `LocaleTestRule`, which is already wired into the test.
- Tablet screenshot buckets: the manual `Screenshots` workflow uses a tablet AVD and
  the Play Console `tenInchScreenshots` bucket.
- Uploading is intentionally an explicit `just screenshots-upload` step after reviewing the
  generated images.

## Verified POC run, 2026-08-04

Ran end to end on this machine (KVM-accelerated Pixel 2 profile, API 34 google_apis x86_64):

```console
just screenshots
```

`01_dashboard`, `02_device_detail`, and `05_settings` were repeatedly verified showing real
seeded content, not loading placeholders. `03_topology` waits for the seeded four-node,
three-connection graph, and `04_search` fails instead of silently accepting an empty state when no
result card renders. The disposable NetBox fixture was confirmed torn down
(`docker compose ... down --volumes`) after every run, including failed ones.
