# Prompt: performance audit of Nyetbox → TODO.md backlog

Copy everything below the `---` into the task given to the inspecting agent (Fable 5).

---

The user's real-world complaint is: **the app feels sluggish**. Your job is to find out *why* —
both raw performance (slow operations, janky frames, wasted work) and perceived
performance/UX (missing loading feedback, slow transitions, anything that makes the app *feel*
slow even when the underlying work is fast) — and turn each root cause into a fix another agent can
implement. This is Nyetbox, a native Android app (Kotlin, Jetpack Compose + Material 3, Hilt, Room,
Retrofit, WorkManager, Glance widgets).

**Your job is diagnosis and ticket-writing only — not implementation.** You will not fix, patch,
refactor, or otherwise change a single line of app source, no matter how small or obvious the fix
looks once you've found it. That temptation is real — you're being asked to do deep, hands-on
investigation (including live device testing below), and once you've root-caused something it will
often feel faster to just fix it than to write it up. Don't. Every issue you confirm becomes a
`TODO.md` entry, full stop; a separate, less capable/less context-aware agent does the actual
implementation later, working from your write-up alone. Your entries are that agent's *entire*
brief — write each one as if the implementing agent will never see this conversation, never talk to
you, and never re-investigate: it needs to be immediately actionable, unambiguous, and correct
enough to implement cold from the text alone.

**The target is a smooth, consistent 60fps or better everywhere** — scrolling lists, screen
transitions, opening a device, dismissing the keyboard, widget interactions. 60fps (16.6ms/frame)
is the floor, not the ceiling: on a device with a higher refresh-rate display, the app should keep
up with that panel's native rate rather than capping the experience at 60. Judge every finding
**against the lower/mid-tier physical devices this project actually tests on**, not an idealized
flagship — the Zenfone 10 and Mi Pad 4 are not top-of-the-line hardware (see `AGENTS.md`'s
"Physical test devices" section). A finding that only matters on a high-refresh-rate flagship is
much lower priority than one that drops frames on the weakest device available. If you can measure
or infer an actual frame-time/jank signal, note it against that bar explicitly.

**Prefer investigating over guessing.** Reading the code for obviously-wasteful patterns is a
start, but the strongest findings come from actually reproducing sluggishness on real hardware —
the app is already installed on the Mi Pad 4 and Zenfone 10, so use them: use the app the way a
real user would (browse the device directory, search, open a device detail, scroll a long list,
trigger a sync, add/view a widget, open/dismiss the keyboard), and correlate what feels slow with
hard evidence (frame-timing dumps, dropped-frame warnings, ANRs, StrictMode violations) and the
code path behind it. See "Live investigation" below before falling back to static-only analysis.

## Before you start

1. Read `AGENTS.md` in full — it defines this repo's architecture conventions (offline-first,
   cache-first reads, "compute once at sync time instead of on every screen open"), the build/
   deploy workflow (`just` recipes, remote build hosts, physical test devices), and the exact
   `TODO.md` entry format. Your output must follow that format exactly.
2. Read `TODO.md` end to end (it's long — use search, not just the tail) so you don't propose a
   fix for something already fixed, already tracked as an open `NBC-N` entry, or explicitly
   rejected in a past entry's notes. Pay particular attention to entries about caching,
   `relatedObjectId`/precomputation, and sync (`NBC-390` is the canonical example of the
   "full-table-scan-decode-on-every-screen-open" anti-pattern this codebase has already been bitten
   by once — assume there are more instances of it, not fewer), and to `NBC-416` (a recent
   IME/keyboard-dismissal bug) as an example of the kind of small interaction-latency issue that's
   in scope here.
3. Note the numbering rule: entries are `## NBC-N`, sequential, never reused or renumbered. Find
   the current highest `NBC-N` in `TODO.md` yourself (`grep -oE '^## NBC-[0-9]+' TODO.md`) and
   number your new entries continuing from there — don't hardcode a number from this prompt, since
   more entries may have landed since it was written.

## Codebase orientation (read this first — you're expensive, don't re-derive this)

Single `:app` Gradle module, Kotlin, Jetpack Compose + Material 3, Hilt DI, Room, Retrofit +
kotlinx.serialization, WorkManager, Coil 3. Everything lives under
`app/src/main/kotlin/dev/pschmitt/nyetbox/`:

- `data/api/` — Retrofit service + DTOs for the NetBox REST API.
- `data/db/` — Room: `DeviceDao`/`DeviceEntity` (the device-specific fast path) and
  `NetBoxObjectDao`/`NetBoxObjectEntity` (the generic cache for every *other* NetBox object type —
  racks, sites, cables, etc., keyed by `endpointPath`).
- `data/repository/` — `DeviceRepository` (devices), `GenericObjectRepository` (everything else,
  641 lines — the biggest single surface for the NBC-390-style full-scan pattern, see below),
  `DirectoryRepository` (sidebar app/model metadata), `GlobalSearchRepository`,
  `SettingsRepository`, `SyncStatusRepository`, plus smaller single-purpose ones.
- `sync/` — `SyncWorker` (the `CoroutineWorker` WorkManager runs), `SyncScheduler` (enqueues
  periodic/startup/manual work), `SyncNotifier`, `OfflineSyncRepository` (the actual sync
  orchestration `SyncWorker` delegates to).
- `ui/` — one subpackage per screen/feature (`directory`, `dashboard`, `search`, `settings`,
  `navigation`, `gestures`, `theme`, `common`, ...), each typically a `*ViewModel.kt` +
  `*Screen.kt` pair.
- `widget/` and `shortcuts/` — **new, uncommitted at the time this prompt was written, and have had
  zero performance review yet.** Treat these as a blank slate rather than assuming past scrutiny:
  `widget/` = two Glance home-screen widgets (`NyetboxGlanceWidget`, `RackViewGlanceWidget`) plus
  `WidgetRefreshWorker`/`WidgetUpdater`/`*ConfigStore`/`*ConfigActivity`; `shortcuts/` =
  `ShortcutSyncer` (publishes launcher long-press shortcuts) + `GestureIcons`.
- `scanner/`, `printing/`, `qrsetup/`, `image/`, `crash/`, `di/` — smaller, more self-contained;
  lower priority unless live testing points you there.

**My own hunches — unverified, treat as leads to check, not confirmed findings:**

1. `NyetboxApp.onCreate()` (`NyetboxApp.kt:31-44`) runs five calls back-to-back, synchronously, on
   the main thread, on every cold start: `syncNotifier.createChannel()`,
   `syncScheduler.schedulePeriodic()`, `syncScheduler.scheduleStartup()`,
   `backupScheduler.schedule()`, `shortcutSyncer.sync()` — all before `Application.onCreate()`
   returns and `MainActivity` even starts. `ShortcutSyncer.sync()` in particular
   (`shortcuts/ShortcutSyncer.kt:30`) calls `ShortcutManagerCompat.setDynamicShortcuts()`, a Binder
   IPC to the system `ShortcutManager` service, plus builds an `IconCompat` per configured
   shortcut. None of this looks async. Worth measuring whether this stack adds meaningful cold-start
   latency, especially on the Mi Pad 4.
2. That same `ShortcutSyncer.sync()` is called *again* from a Compose `LaunchedEffect(shortcutItems)`
   in `MainActivity.kt:104`, which by default runs on the Main dispatcher — so the same
   Binder-IPC-plus-icon-building work can fire on the UI thread any time the user's shortcut list
   changes, not just at startup. Check whether it's actually hopping off Main anywhere, and whether
   it can be triggered more often than the user would expect.
3. `MainActivity.onCreate()` (`MainActivity.kt:81`) calls
   `CrashReportStore(applicationContext).takePending()` synchronously, before `setContent`, on
   *every* cold start (not only after a real crash) — a `SharedPreferences` read plus a
   commit-mode write when a report is pending (`crash/CrashReport.kt:22,28`, both `commit = true`,
   deliberately synchronous so a crash report survives process death). Cheap in isolation, but it's
   one more synchronous main-thread hop stacked in front of first frame alongside #1 — worth
   checking cumulatively, not just individually.
4. `NetBoxObjectDao.observeAllObjects()`/`observeAll(endpointPath)` (`data/db/NetBoxObjectDao.kt`)
   are the full-table-scan queries `NBC-390` introduced `observeByRelatedObjectId()` as a faster
   alternative to — but only for the three call sites `NBC-390` actually touched. Per its own
   write-up, "anything else... is untouched and still falls back to the original full-scan
   behavior." `GenericObjectRepository.kt` (641 lines) is the place to hunt for more call sites
   still doing the pre-`NBC-390` full-scan-then-filter-in-Kotlin pattern that could take the same
   fix.
5. `DeviceRepository.observeDevices()` (`DeviceRepository.kt:30-37`) — when a search query has
   structured filters (e.g. `status:active`) — maps over *every* currently-loaded device on *every*
   Room emission, calling `createSearchFields()` (allocates a new `Map` per device,
   `GlobalSearchRepository.kt:638`) and `matchesFilters()` in Kotlin rather than in SQL. Likely
   cheap at realistic device counts, but confirm rather than assume, particularly if live testing
   shows search-while-typing jank.

Use these as a starting shortlist, not the full audit — the "What to audit" categories below still
apply in full, and live testing may well turn up something none of these five predict.

## Live investigation

**Do real, hands-on performance testing on the physical devices — this is expected, not optional
if a device is reachable.** Nyetbox is already installed on both:

- **Mi Pad 4** (SSH at `mi-pad-4.lan:8022`, via `just mipad-connect` then the `mipad-*` recipes) —
  **this is by far the slowest device the project has, and therefore the most important one.** If
  the app is smooth here, it's smooth everywhere; if it's sluggish anywhere, it'll show up worst
  here. Prioritize it as your primary test target.
- **Zenfone 10** — wired directly over USB adb to this machine, no discovery step needed
  (`just zenfone-logcat`, `just zenfone-install`, etc.). Use it as a faster secondary device to
  confirm whether a given jank source is universal or specific to weaker hardware.

**Do not touch the Pixel 5 (`px5.lan`) without asking first.** It's also reachable via
`just px5-connect`/`px5-*` and has the app installed, but it's the user's actual daily-driver
phone — it may be in active use (in their pocket, on their lap, mid-call) when you'd run adb
against it. Any `px5-*` recipe (install, uninstall, logcat, an interactive shell, anything that
touches the screen or reinstalls the app) needs an explicit go-ahead from the user first, each
time — don't treat an earlier yes as blanket permission for the rest of the session. Mi Pad 4 and
Zenfone 10 give you everything you need (worst-case and mid-tier hardware); only ask to bring in
the Pixel 5 if you have a specific reason those two can't cover.

Since a working build is already installed on the Mi Pad 4 and Zenfone 10, you can start driving
the app immediately without rebuilding anything. Only rebuild/redeploy (`just build debug <remote-host>` +
`just mipad-install`/`just zenfone-install`, or `just deploy-mipad`/`just deploy-zenfone`) if you
need a change that isn't in the currently-installed build (e.g. temporary added logging) — never
run Gradle locally.

**The currently-installed builds are debug builds** (package id `dev.pschmitt.nyetbox.debug`,
confirmed via `adb -s <serial> shell pm list packages | grep nyetbox` at prompt-writing time —
re-verify this yourself rather than assuming it's still true, since a device's install can change
between sessions). This matters for how you weigh findings: per `app/build.gradle.kts`, only the
`release` buildType sets `isMinifyEnabled`/`isShrinkResources` — debug builds ship unminified,
unshrunk, un-R8-optimized bytecode, which can itself cause some sluggishness that a real user (who
only ever gets a release build, via Obtainium/GitHub Releases) would never see. When a finding
looks like generic "unoptimized debug bytecode is slow" rather than an actual algorithmic/
architectural problem in the app's own code, don't write it up as a `TODO.md` entry — there's
nothing to fix. If you're unsure whether a jank source is real or a debug-build artifact, and a
release-signed APK is feasible to produce (`just build release <remote-host>` — this still works
outside CI, since the release buildType falls back to the regular auto-generated debug keystore
when the CI keystore env vars aren't set), build and install one to cross-check before writing that
finding up. Treat this as an extra-confidence step for surprising or borderline findings, not a
requirement for every single one.

- Drive the app through its heaviest real paths: cold start, the device directory list (scroll
  fast, scroll a long way), global search while typing, opening a device detail with lots of
  fields/interfaces/images, pull-to-refresh/manual sync, a background sync while the app is open,
  adding a home-screen widget and letting it refresh, onboarding's keyboard show/dismiss flow.
- While doing that, gather concrete evidence, not just impressions:
  - Tail logcat (`just mipad-logcat`/`zenfone-logcat`, optionally filtered) for `Choreographer`
    "Skipped ... frames" warnings (note by how much — 1-2 skipped frames is noise, dozens is a real
    stutter), `ANR in dev.pschmitt...`, StrictMode violations (disk/network on main thread), Room
    "query took Nms" warnings if present, and GC churn suggestive of allocation pressure in a hot
    path (e.g. per-frame allocations in a composable or a `LazyColumn` item).
  - Pull real frame-timing numbers via `adb -s <serial> shell dumpsys gfxinfo
    dev.pschmitt.nyetbox.debug reset`, then reproduce the interaction (e.g. scroll the directory
    for a few seconds), then `adb -s <serial> shell dumpsys gfxinfo dev.pschmitt.nyetbox.debug
    framestats` for per-frame timing you can compare against the 16.6ms (60fps) budget directly,
    rather than relying on logcat alone. (Use `just mipad-connect`'s printed `host:port` as the
    `-s` target for the Mi Pad 4; the Zenfone 10 shows up directly in `adb devices`.)
  - `adb -s <serial> shell dumpsys meminfo dev.pschmitt.nyetbox.debug` and/or `top -m 10` can
    surface memory pressure or CPU hogs correlated with a sluggish interaction.
- If neither device is reachable in your environment, say so plainly in your final report and fall
  back to static analysis only — don't fabricate measurements or claim you observed something you
  didn't.
- Tie every live observation back to the actual code path before writing an entry — "scrolling the
  directory list drops frames on the Mi Pad 4 (framestats showed several 40+ms frames)" is an
  observation, not a finding; the finding is *why* (e.g. "each row recomposes fully because
  `DeviceListItem` reads an unstable `List<Tag>` param directly instead of a stable, remembered
  snapshot"). Note which device(s) you reproduced each issue on.

Once you've root-caused something this way, **stop and write it up** — don't reach for an editor.
Diagnosing on-device is in scope; touching the code to confirm your fix works is not. If you're
tempted to "just quickly patch it to check," that's the sign to write the `TODO.md` entry instead
and let the fix be verified by whoever implements it.

## What to audit

Cover these areas, but don't force a finding into a category that doesn't fit — if a category is
clean, say nothing about it rather than inventing a marginal issue for the sake of coverage:

- **Main-thread / cold-start cost**: work in `Application.onCreate` (`NyetboxApp.kt`),
  `MainActivity.kt`, and any `ViewModel` `init {}` block that does synchronous I/O, blocking calls,
  or heavy computation before first frame.
- **Room query patterns**: any DAO query or repository method that reads a whole table
  (`observeAll`, unbounded `SELECT *`) and then filters/decodes rows in Kotlin, where an indexed
  `WHERE` clause or a precomputed column (the `NBC-390` pattern) could do the same job in the
  database instead. Check `data/db/` DAOs and every repository in `data/repository/` for this.
  Also check for missing indices on columns used in `WHERE`/`ORDER BY` in the `@Entity` definitions
  and `MIGRATION_*` steps.
- **Compose recomposition and frame budget**: unstable parameters (raw `List`/`Map`/lambdas
  without `remember`) passed into composables that render lists (device directory, search results,
  rack view), missing/wrong `key =` in `LazyColumn`/`LazyRow` items, expensive work (formatting,
  sorting, filtering, string building) done inline in a `@Composable` body on every recomposition
  instead of hoisted to the `ViewModel`/`remember`/`derivedStateOf`, heavy `Modifier` chains or
  nested layouts in list-item composables that blow the 16ms (60fps) frame budget once multiplied
  across a long list.
- **Perceived latency / UX polish**: places where the underlying work may be fine but the
  experience still *feels* slow or unresponsive — missing loading/skeleton states (a blank screen
  or frozen UI while data loads instead of immediate feedback), taps/buttons with no immediate
  visual response before the async result lands, screen transition or dialog-open/close animations
  that are noticeably slow or stutter, search-as-you-type without debouncing (janking on every
  keystroke) or with too much debounce (feels laggy), the keyboard show/dismiss flow (recent
  history here, see `NBC-416`) anywhere else it appears (search, edit forms), any place a
  long-running action blocks input entirely instead of staying interactive.
- **Sync (`sync/SyncWorker.kt`, `SyncScheduler.kt`, repositories' refresh paths)**: redundant
  network calls, sequential requests that could be parallelized (or vice versa — unbounded
  parallelism that could overwhelm a small self-hosted NetBox instance), full re-syncs where an
  incremental/`?last_updated__gte` sync would do, JSON decoding done more than once for the same
  payload, and whether a sync running in the background visibly competes with the UI thread for
  CPU/DB access while the user is actively using the app.
  - **Widgets are new (uncommitted at audit time) and unaudited — give them real attention**:
    `widget/WidgetRefreshWorker.kt`, `widget/WidgetUpdater.kt`, and both Glance widgets
    (`NyetboxGlanceWidget.kt`, `RackViewGlanceWidget.kt`). Check refresh interval/frequency,
    whether a widget update re-derives data that sync already computed, and whether every home
    screen instance triggers its own independent DB/network work instead of sharing one refresh.
  - Also check `shortcuts/ShortcutSyncer.kt` (also new/uncommitted) for the same
    "runs how often, recomputes what" questions.
- **Image loading**: Coil (`coil-compose`/`coil-network-okhttp`) usage — missing size constraints
  (loading full-resolution images into small thumbnails/list rows, which also costs frame time to
  decode/scale on demand), missing memory/disk cache configuration, repeated decode of the same
  image, images loaded without a placeholder causing layout jump/pop-in.
- **APK / startup size**: anything obviously loaded eagerly that could be lazy (Hilt modules,
  singletons doing eager initialization), missing `isMinifyEnabled`/resource shrink coverage gaps
  outside the release build type that's already configured.
- **Battery / background work**: WorkManager constraints (network type, battery-not-low, etc.) on
  `SyncWorker` and `WidgetRefreshWorker` — are they as conservative as the offline-first,
  self-hosted-instance use case warrants, or could they run more/less aggressively than needed?

## What "good" looks like for each finding

For every real issue you find, write one new `## NBC-N` entry in `TODO.md` (append at the end,
matching the exact structure of existing entries — title line, short problem-statement paragraph,
`- [ ]` checklist of concrete sub-steps, ending `Status: not started`). Each entry must give a
downstream implementing agent everything it needs without re-deriving your analysis:

- **Exact file path(s) and function/class names** where the problem lives (e.g. "`DeviceDao.kt`'s
  `observeAllRaw()`" not "the device DAO somewhere").
- **The concrete mechanism**, not a vague label — "decodes all N cached rows' JSON on every
  recomposition of `DirectoryScreen` because `filteredDevices` isn't wrapped in
  `derivedStateOf`/`remember`" beats "directory screen is slow." If the finding came from live
  testing, include what you observed (which action, which device, what the logcat evidence showed)
  alongside the code-level cause.
- **A specific proposed fix** (add an index on column X; hoist computation to sync time via a new
  precomputed column, following the `relatedObjectId` pattern from `NBC-390`; wrap in
  `remember(key) { }`; add a `LIMIT`/`WHERE`; add a skeleton/loading state; debounce input by
  Nms; etc.) — enough that the implementing agent doesn't have to make its own design decision,
  only execute yours.
- **A verification step** appropriate to the change (unit test to add/extend, what to check in a
  remote `:app:testDebugUnitTest` run, what to look for live on a physical device per
  `AGENTS.md`'s device-testing section — ideally including a concrete "no more Choreographer skip
  warnings during X" or "frame stays smooth while doing Y" check for jank fixes) — mirroring how
  existing `TODO.md` entries close out.
- Keep each entry scoped to **one coherent fix**, small enough for a less-capable agent to
  implement in one sitting. Split unrelated issues into separate `NBC-N` entries even if they're in
  the same file; don't bundle a DB-index fix and a Compose-recomposition fix into one entry just
  because they're both "performance."

## Explicitly out of scope

- **Do not edit, patch, or refactor any source file for any reason — including to verify a fix
  works, "just to be sure," or because it's a one-line change.** Your only write access to this
  repo is appending to `TODO.md`. If you catch yourself opening a source file in write mode, stop.
- Do not invent findings to pad the list. A short, high-confidence list beats a long speculative
  one — every entry you add is a task someone else will spend real time on.
- Do not re-litigate or duplicate a decision already recorded in a past `TODO.md` entry (e.g. don't
  propose "make search cache-first" if an existing entry already did that — check first).
- Don't propose adding Macrobenchmark/Perfetto profiling infrastructure itself as a fix unless you
  first confirm (via `gradle/libs.versions.toml` and `app/build.gradle.kts`) that no such tooling
  exists yet and you think it's warranted as its own scoped entry — that's a legitimate finding,
  but keep it as one entry, not a prerequisite you block everything else on.

## Output

When done, report back: how many `NBC-N` entries you added (with their numbers/titles), which ones
came from live device testing vs. static reading, and one sentence per entry on why you're
confident it's a real issue and not speculation.
