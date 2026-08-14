# AGENTS.md

Repository instructions for AI coding agents working on Nyetbox.

## Task tracking

- `TODO.md` is the running backlog/changelog for this project, one `## NBC-N:` entry per
  feature or fix, numbered sequentially (never reuse or renumber an id). Each entry has a
  checklist of sub-items (`- [ ]`/`- [x]`) and ends with a `Status:` line (`not started` /
  `in progress` / `mostly done` / `**done**`, plus a date and how it was verified).
- Before starting any non-trivial new feature or fix, add (or update) an `NBC-N` entry
  describing it - even if the same conversation immediately goes on to implement it. Update the
  checklist/status as work actually lands, rather than writing the whole entry retroactively once
  everything's finished. This keeps `TODO.md` an accurate record of what's done vs. still open,
  and lets another agent (or a future you) resume the work cold from just this file.
- Trivial one-off asks (a typo, a single-line tweak) don't need their own entry.

## Dev environment

- `nix develop` provides the full toolchain (JDK 21, Android SDK, `just`, `ktfmt`) and installs
  the repo's pre-commit hooks (see `flake.nix`'s `git-hooks.nix` integration - trailing
  whitespace, EOF fixer, merge-conflict/large-file checks, `nixfmt`, `statix`). The generated
  `.pre-commit-config.yaml` is gitignored - it's regenerated from `flake.nix` on every shell
  entry, don't hand-edit it.
- Prefer the `justfile` recipes over raw `./gradlew`/`ssh`/`adb` invocations - run `just --list`
  for the full set.

## Builds

- **Never run Gradle builds locally on this machine** - always build on `rofl-13.brkn.lol` or
  `rofl-14.brkn.lol` instead. The `justfile` automates this:
  - `just sync [host]` - rsync the working tree to the remote build host (excludes `.git`,
    `build/`, `.gradle/`). Namespaced per git worktree so parallel agents don't clobber each
    other's remote sync directory mid-build.
  - `just gradle [host] <tasks...>` - sync, then run arbitrary Gradle tasks remotely.
  - `just build [variant] [host]` - build an APK remotely. `variant` is `debug` (default) or
    `release`. Release builds are signed with the persistent CI keystore, fetched from the rbw
    entry `"NetBox and Chill CI Signing Keystore"`.
  - `just lint` - remote `ktfmtCheck` (mirrors `.github/workflows/lint.yaml`). **Not fully
    trustworthy**: on rofl-13/rofl-14, `ktfmtCheckMain`/`ktfmtFormatMain` report `NO-SOURCE` for
    reasons still unclear, silently skipping the main sourceSet instead of actually checking it -
    a passing `just lint` does not guarantee CI's `Lint` job will also pass.
  - `just test` - remote unit test suite.
  - `just fetch [variant] [host] [abi]` - scp the built APK split back to `./dist/`.
  - `just build-fetch [variant] [host]` - build + fetch in one step.
  - `just format` runs the standalone `ktfmt` CLI locally over tracked `.kt`/`.kts` files - fast,
    but treat it as advisory only (see the flake.nix comment on why there's no ktfmt pre-commit
    hook). It can also disagree with CI outright: the nixpkgs-packaged `ktfmt` binary version can
    drift from the `com.ncorti.ktfmt.gradle`-resolved engine version CI actually uses, so it can
    "fix" formatting that CI's version considers already correct (or vice versa).

**CI is the sole authority on lint/format, full stop - not `just lint`, not `just format`, not
local judgment.** Both of the above discrepancies were discovered the hard way: a change that
passed `just lint` on rofl-13 still failed CI's `Lint` job after being pushed and tagged. If CI's
`Lint` job fails or disagrees with what a local/remote check said:
- `.github/workflows/lint.yaml`'s `Lint` job auto-uploads a `ktfmt-diff-patch` artifact whenever
  `ktfmtCheck` fails - on any trigger, push/PR or manual. It contains exactly what
  `./gradlew ktfmtFormat` would change, computed in the same environment CI's `ktfmtCheck` uses.
  Grab it with `gh run download <run-id> -n ktfmt-diff-patch` and apply it (`git apply`) rather
  than guessing or reformatting by hand. Dispatch it directly (`gh workflow run lint.yaml`) to get
  a patch preemptively, without waiting for a real push/PR to fail first.
- Fix every lint/format violation CI reports before calling a change done, even in files the
  current change didn't touch or author - don't scope a fix to "only the lines I changed" if CI
  flags something adjacent. Never disable, skip, or baseline around a lint failure to make it go
  away; fix the actual violation.
- `just format` runs the local `ktfmt` CLI over *every* tracked `.kt`/`.kts` file in the repo, not
  just the ones a change touched - combined with the local/CI ktfmt version drift above, this can
  silently reformat (and sometimes visibly worsen the formatting of) dozens of unrelated files in
  one run. Diff-review its output before committing; `git checkout -- <file>` anything it touched
  outside the actual change, don't assume every file it modified was an intended fix.
- **Releasing a new version is exactly this procedure, in order - never skip or reorder a step:**
  1. Land every change for the release on `main` first (including the version bump below) and
     confirm `git status` is clean before tagging anything.
  2. Bump `configuredVersionCode`/`configuredVersionName`'s defaults in `app/build.gradle.kts` to
     the new version, as its own `chore: bump versionCode/versionName defaults to N/X.Y.Z` commit.
     These gradle-property defaults are what both the tag-triggered `Release` workflow and
     `just build release` actually embed - skipping this step was the exact root cause of a real
     failure (v1.5.5's Play Store job rejected the build with "Version code 24 has already been
     used" because the defaults still said 24/1.5.4).
  3. Push to `main`, then confirm **on that exact commit's SHA** - not an assumption, not an older
     green run - that `Lint`, `Build`, and `Release` (the workflows that trigger on a push to
     `main`) all succeeded: `gh run list --branch main --limit 5` and check the commit message/SHA
     column, or `gh run list --commit <sha>`.
  4. Only then tag it (`vX.Y.Z`, matching the bumped `versionName`) and push the tag - this is what
     fires the permanent (non-prerelease) `Release` workflow run for that tag.
  5. If a tag was already pushed and CI on it fails, delete the tag
     (`git push origin :refs/tags/<tag>`) and recreate it once a fixed commit is fully green on
     `main` - never leave a released tag pointing at a red build.

## Physical test devices

- **Zenfone 10** (`arm64-v8a`), connected directly over USB to this machine's adb. Recipes:
  `just zenfone-install <apk>`, `just zenfone-uninstall [pkg]`, `just zenfone-logcat [filter]`,
  `just deploy-zenfone [variant]` (build + fetch + install in one step).
- **Mi Pad 4** (`arm64-v8a`, rooted), reachable via SSH at `mi-pad-4.lan` port `8022` (Termux).
  Recipes mirror the Zenfone ones but go through `just mipad-connect` first (finds the port
  `adbd` is listening on via a root SSH shell, `adb connect`s to it): `just mipad-install <apk>`,
  `just mipad-uninstall [pkg]`, `just mipad-logcat [filter]`, `just deploy-mipad [variant]`.
- **Pixel 5** (`arm64-v8a`, codename `redfin`), wireless adb at `px5.lan` - not always listening,
  enabled on demand via `zhj adb::connect px5.lan` (triggers wireless debugging through Home
  Assistant/Tasker on the phone). The port changes every time it's (re)enabled, so
  `just px5-connect` always re-discovers it from `adb devices` rather than assuming a fixed one.
  `just px5-install <apk>`, `just px5-uninstall [pkg]`, `just px5-logcat [filter]`,
  `just deploy-px5 [variant]`.
- **Always deploy to every attached adb device**, not just one: after landing a verified change
  (compiled, tested, lint-checked remotely), run `just deploy-all [variant]` rather than a
  single-device recipe. It builds, fetches, and installs on the Zenfone 10, Mi Pad 4, and Pixel 5 in
  one step - install onto whatever's connected by default, not just whichever device happens to be
  handy. Only target a single device (`just deploy-zenfone`/`deploy-mipad`/`deploy-px5`) when
  there's a specific reason to, e.g. reproducing a device-specific bug.
- Signature mismatch gotcha: if a device already has a build signed with a different key than the
  one you're installing, install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Fix is
  `just zenfone-uninstall`/`just mipad-uninstall`/`just px5-uninstall` then install fresh - this
  wipes local app data (Room DB cache, stored token). Confirm with the user before doing this if
  it's not their own throwaway data.

## Architecture

- Single `:app` Gradle module (Kotlin, Jetpack Compose + Material 3, Hilt DI) - no
  multi-module split, this app doesn't need one.
- NetBox API access via Retrofit + kotlinx.serialization, with a dynamic base-URL interceptor so
  the user's configured NetBox instance can change at runtime without rebuilding the Retrofit
  client (see `data/api/DynamicBaseUrlInterceptor.kt`).
- **Before writing JSON-parsing code against a NetBox endpoint whose exact response shape you
  don't already have confirmed in this repo, use the `netbox` skill to query the user's real
  instance (`netbox.brkn.lol`) and look at the actual response first**, rather than relying on
  NetBox's public docs/source on GitHub. This bit a real feature: the NBC-383 cable-trace tab was
  implemented against field names (`termination`, `TracedCableSerializer`'s field list) pulled from
  a web-fetched summary of NetBox's GitHub source, which turned out to be wrong for the live
  instance's actual API version - the real field is `object`, not `termination` - and the bug
  (`cableTraceStartTarget` silently returning null, tab showing "No cable path to trace" for every
  cable) only surfaced once the user tested it for real. A few `curl -H "Authorization: Token ..."`
  calls against the real API up front would have caught it before it ever compiled.
- Offline cache via Room (`data/db`). `DeviceRepository` is cache-first: reads come from Room,
  writes/refreshes come from the API and upsert into Room.
- **Offline-first is a hard requirement of this app, not a nice-to-have.** It must stay fully
  usable with zero connectivity for anything already synced. Any new read path - a screen, a
  ViewModel, a repository - has to follow the same shape as `DeviceRepository`/
  `GenericObjectRepository`: reads come from a Room `Flow` first, a network call is only ever a
  best-effort *refresh* that upserts into Room, and its failure surfaces as a friendly message
  (or is silently skipped) rather than blocking or replacing what's already cached. A feature that
  only works while NetBox is reachable, with no cached fallback, is a regression - not a reasonable
  first-pass scope-down. This bit a real review: NBC-13's global search first shipped as a live-
  only network fan-out with explicitly "transient" (not cached) results, flagged and reworked to be
  cache-first the same day. See also NBC-18 (cached data must render immediately even when a
  refresh at launch fails).
- **Prefer computing a value once at sync time over re-deriving it on every screen open.** If a
  screen has to decide something from data sync already fetched (does this device have any
  interfaces, which tab should show, what's the count for a badge), and that decision could
  instead be computed once - while the data is written into Room during sync - and stored for a
  cheap indexed read later, do that instead of recomputing it live every time the screen opens. Live
  re-derivation that scans/decodes cached rows on every open doesn't just cost latency; it can be
  real, repeated CPU work that scales with total data size (see NBC-390: per-device tab visibility
  was answered by decoding every cached row of an endpoint *across every device in the install*, on
  every device-detail screen open, because there was no indexed way to ask "which of these rows
  belong to device 42" - fixed by precomputing a `relatedObjectId` column at sync/write time instead
  of at read time). When in doubt, push the work upstream to sync and leave the read path a plain,
  fast lookup.
- The whole point of the app is scanning the device-sticker QR codes (public NetBox device URLs
  like `https://<netbox-host>/dcim/devices/<id>/`) with the in-app CameraX/ZXing scanner, and via
  the `/dcim/devices/*` deep-link intent-filter when such a link is opened from another app. Both
  paths funnel through `scanner/DeviceUrlParser.kt`.

## UI conventions

- Use an icon wherever there's a labeled action or a labeled piece of information: every `Button`/
  `OutlinedButton`/`IconButton`, every overflow/dropdown menu item, and every `ListItem` that names
  a distinct thing (a setting, a section, a row in a list) should carry a leading icon, not just a
  text label. `material-icons-extended` is already a project dependency specifically so this is
  never a reason to settle for a plain-text-only control - reach for a fitting icon (extended set
  first, then core) rather than skipping it.
  - `material.icons.extended` is already wired into `app/build.gradle.kts` - use its full icon set
    freely (`Icons.Default.*`/`Icons.AutoMirrored.Filled.*`), not just the small core subset.
  - `ui/directory/AppIcons.kt` maps NetBox app namespaces (`dcim`, `ipam`, `plugins/<name>`, ...)
    to an icon - reuse `AppIcons.forAppKey(...)` for anything rendering a row/section for a NetBox
    object type, instead of picking an ad hoc icon per screen, so the same object type reads with
    the same icon everywhere (sidebar, list rows, elsewhere).
  - `contentDescription` should be a real accessibility label when the icon is the only affordance
    (e.g. an `IconButton`); pass `null` when the icon is purely decorative next to a text label
    that already says the same thing (e.g. a `ListItem` leading icon next to its own headline).
