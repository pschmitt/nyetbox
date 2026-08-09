# Prompt: sync efficiency & transparency audit of Nyetbox → TODO.md backlog

Copy everything below the `---` into the task given to the inspecting agent (Fable 5).

---

The user's complaint: **sync feels heavier and more opaque than it should be.** It always seems to
do a full resync when the app is opened, rather than something visibly lighter — other apps are
"smarter" about this. It should only fetch what's actually changed, avoid burning bandwidth
needlessly (this matters a lot on cellular/roaming), and be transparent about what it's doing and
why, instead of presenting every sync as the same undifferentiated "Syncing…" spinner. Your job is
to find the real causes — both actual over-fetching and perception/UX gaps that make an already-
cheap sync *feel* heavy — and turn each into a fix another agent can implement.

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

## Before you start

1. Read `AGENTS.md` in full, especially the offline-first/cache-first guidance and the exact
   `TODO.md` entry format. Your output must follow that format exactly.
2. Read `TODO.md` end to end (it's long — search, don't just read the tail) so you don't propose
   something already fixed, already open as an `NBC-N` entry, or rejected in the past. Note
   `NBC-370` (the startup-sync grace-period delay, so a quick reopen doesn't visibly retrigger
   syncing) and `NBC-23` (background sync failure notifications / retry-count capping) — both
   sync-adjacent prior work you shouldn't re-litigate or duplicate.
3. Numbering rule: entries are `## NBC-N`, sequential, never reused/renumbered. Find the current
   highest yourself (`grep -oE '^## NBC-[0-9]+' TODO.md`) — don't hardcode a number from this
   prompt, since more entries may have landed since it was written.

## Codebase orientation — what's already good, and where I think the real problem is

**Read `sync/OfflineSyncRepository.kt` first, in full (435 lines) — it's the center of this audit.**
`SyncWorker.kt` just wraps it for WorkManager; the actual sync logic, endpoint-by-endpoint, all
lives in `OfflineSyncRepository.syncAllLocked()`.

**Important: the device/generic-object incremental machinery is already solid — don't
re-litigate it, and don't let a ticket regress it.** `DeviceRepository.syncAll()` and
`GenericObjectRepository.syncAll()` both support a `last_updated__gte` watermark
(`OfflineSyncRepository.kt:338-366`, `syncDevicesIncrementally`/`syncModelIncrementally`), and a
full unfiltered pass only runs when `forceFullSync` is explicitly requested (the Settings "Sync
now" button) or the last full pass was more than 24h ago (`FULL_SYNC_INTERVAL_MILLIS`,
`OfflineSyncRepository.kt:83`, `isFullSyncPass` at line 123) — specifically so periodic incremental
syncs can't see server-side deletions forever without eventually reconciling them. That part of the
design is deliberate and good; don't propose "make devices incremental," they already are.

**My hypothesis for the actual complaint: several *other* steps in the same sync pass have no
incremental gating at all, and run in full, unconditionally, on every single sync — including the
routine startup/periodic ones the watermark logic was specifically built to keep cheap.** So even
though the *headline* device/object sync is properly incremental, the pass as a whole still does a
meaningful chunk of full, repeated work every time, which would explain why it doesn't feel any
different from a real full sync. These are unverified but well-evidenced leads — confirm each
before writing it up, don't take my word for it:

1. **Device types re-fetched every sync, even though the codebase already has the "don't" helper
   sitting right next to it.** `OfflineSyncRepository.kt:166-177` calls
   `deviceTypeRepository.refresh(deviceTypeId)` for *every* distinct device type referenced by
   currently-cached devices, on *every* sync pass, full or incremental. But
   `DeviceTypeRepository.kt:28-30` already defines `ensureCached(id)` — "Fetches and caches [id]
   only if it isn't already cached - device-type photos rarely change" — which the sync path
   doesn't use. This looks like exactly the kind of gap the fix already exists for; check where
   else `ensureCached` is used (presumably an on-demand detail-screen path) to understand the
   intended contract before proposing the sync path switch to it (it may need an occasional
   full-refresh fallback, not just "always ensureCached forever," since a device type's images can
   change on the server even if rare).
2. **Rack elevations re-fetched in full for every rack, every sync, both faces, no staleness
   check.** `OfflineSyncRepository.kt:219-228` calls `rackElevationRepository.refresh(rack.id,
   FRONT)` and `refresh(rack.id, REAR)` for every cached rack unconditionally. `refresh()`
   (`RackElevationRepository.kt:29-40`) fetches up to `limit=1000` elevation slots per call with no
   `last_updated` filter and no comparison to anything already cached — it always clears and
   re-inserts. For an install with many racks, this alone could dwarf the actual incremental device
   delta on every single periodic sync.
3. **SVG diagrams (rack elevation renders, cable traces) re-downloaded and overwritten every sync,
   unconditionally, when "sync attachments to disk" is on.** `OfflineSyncRepository.kt:233-262`
   calls `svgDiagramRepository.refresh(...)` for front+rear of every rack and the trace of every
   cable with a resolvable target — every sync pass, not just full ones.
   `SvgDiagramRepository.refresh()` (`SvgDiagramRepository.kt:30-36`) always makes the network call
   and overwrites the persisted file; there's no dirty-check against the owning rack/cable's
   `lastUpdated`. Contrast with `FileDownloadRepository.downloadToPersistent()`
   (`FileDownloadRepository.kt:62-73`), which *does* skip work it's already done (`if (target.isFile
   && target.length() > 0L) return@runCatching target`) — but note that one has the opposite
   problem: it never re-checks staleness either, so a changed rack photo/elevation on the server
   would never be re-pulled once cached. Both ends of this are worth a look: too eager on one side
   (SVGs always refetched), too sticky on the other (attachments never refreshed once downloaded).
4. **Dashboard, custom-field definitions, model discovery, and topology all refresh unconditionally
   every sync pass**, with no incremental gating visible in `OfflineSyncRepository.kt:151-154`
   (`dashboardRepository.refresh()`, `customFieldRepository.refresh()`),
   `OfflineSyncRepository.kt:180` (`directoryRepository.refresh()` — "Discovering NetBox models"),
   and `OfflineSyncRepository.kt:264-269` (`topologyRepository.refresh()`, when the topology plugin
   is present). These may turn out to be cheap enough in practice not to matter (a small dashboard
   summary payload, a handful of model definitions) — check each repository's actual request(s) and
   typical payload size before deciding whether it's worth a ticket; don't assume "unconditional"
   automatically means "wasteful" here the way it clearly does for #2 and #3 above.

**Transparency/UX side** — even a properly incremental sync currently looks identical to a full
one from the user's seat: `ui/common/SyncStatusCard.kt` and `SyncStatusDetailsDialog.kt` (fed by
`sync/SyncStatusRepository.kt` and the `SyncProgress` step messages built in
`OfflineSyncRepository.kt:56-75`/`133-136`) show the same generic step-by-step "Syncing devices…",
"Syncing rack elevations…" messages regardless of whether this pass is incremental or a full
24h-interval pass, and give no sense of how much actually changed or transferred. If the real fix
turns out to be "most of the pass already is cheap, it just doesn't look like it," that's as valid
a set of tickets as fixing genuine over-fetching — the user explicitly said "maybe it's just a
UI/UX thing" as a live possibility, not a certainty either way.

## Live investigation

**Do real, hands-on testing on the physical devices — apps are already installed, use them.**
Prioritize the **Mi Pad 4** (SSH via `just mipad-connect`, then `mipad-*` recipes — by far the
weakest/slowest device, and also whichever network it's on is worth checking for
metered/cellular-like conditions) and the **Zenfone 10** (wired USB, `zenfone-*` recipes, no
discovery step). **Do not touch the Pixel 5 (`px5.lan`) without asking first, every time** — it's
the user's actual daily-driver phone and may be in active use; only bring it in if you have a
specific reason the other two can't cover, and ask before any `px5-*` command.

The currently-installed builds are debug builds (`dev.pschmitt.nyetbox.debug` — verify this is
still true yourself via `adb -s <serial> shell pm list packages | grep nyetbox` rather than
trusting this note). That matters here in a specifically useful way: **debug builds have OkHttp's
`HttpLoggingInterceptor` set to `Level.BASIC`** (`di/NetworkModule.kt:70-73,92-95`; release builds
set it to `NONE`), which logs one line per HTTP request/response — method, URL, response code, and
byte count — to logcat. This is your best tool for this specific audit:

- Trigger a sync (open the app fresh after a while, or use whatever manual "Sync now" affordance
  Settings exposes) while tailing logcat (`just mipad-logcat`/`zenfone-logcat`, filtered for the
  OkHttp tag) and read off the *actual* sequence of requests a single sync pass makes: which
  endpoints, how many, and roughly how many bytes each. Compare that against what
  `OfflineSyncRepository.syncAllLocked()`'s code says should happen for an incremental pass — does
  reality match, or is something making more requests / bigger requests than the code's own
  incremental logic implies it should?
- Do this at least twice back-to-back (or trigger a manual sync shortly after a real one) to see
  the *incremental* case in practice — a pass with nothing genuinely new on the server is the
  clearest way to see which requests are wasted work vs. legitimately-changed data.
- `adb -s <serial> shell dumpsys netstats` (or the device's own Settings → data-usage screen) can
  give a coarser total-bytes-for-this-app cross-check if you want a second data point beyond the
  logcat request log.
- Watch the in-app sync UI (dashboard's sync card, Settings → Sync category, the system
  notification if the app is backgrounded) at the same time and note whether what it *says* matches
  what you're actually observing on the wire — this is where a transparency/UX finding comes from,
  as opposed to a "this shouldn't have been fetched" finding.
- If neither the Mi Pad 4 nor Zenfone 10 is reachable, say so plainly and fall back to static
  reading of `OfflineSyncRepository.kt` and its dependencies only — don't fabricate request logs or
  byte counts you didn't actually observe.

Once you've root-caused something this way, **stop and write it up** — don't reach for an editor.
Diagnosing on-device is in scope; touching the code to confirm your fix works is not.

## What to audit

- **Over-fetching**: any step in `OfflineSyncRepository.syncAllLocked()` (or a repository it calls
  into) that re-fetches data unconditionally every pass when the underlying server-side data is
  unlikely to have changed, especially the four hypotheses above — confirm or rule out each one
  with real evidence (code-level, and ideally the logcat request trace) before writing it up.
- **Wrong staleness direction**: the opposite failure mode — something that's cached once and never
  re-validated even though the server-side data *can* legitimately change (see the
  `FileDownloadRepository`/attachment note in hypothesis #3). "Never re-fetches" is just as much a
  sync-smartness bug as "always re-fetches" when it means the user is silently looking at stale
  data with no way to know.
- **Concurrency/backoff behavior**: `OfflineSyncRepository.syncConcurrently()`'s bounded parallelism
  (`settingsRepository.syncConcurrency`) and `SyncScheduler`'s backoff/constraints
  (`SyncScheduler.kt`) — are they tuned sensibly for a small self-hosted NetBox instance on a
  possibly-metered connection, or could bursty concurrent requests be part of what makes a sync
  feel/cost heavier than it needs to?
- **Defaults worth reconsidering**: `SettingsRepository`'s sync-related defaults —
  `syncOnlyOnWifi` defaults to `false`, `syncWhileRoaming` defaults to `true`
  (`SettingsRepository.kt:295-299`) — mean cellular/roaming syncs happen by default. Whether that's
  the right default is a product call, not something to silently flip, but if the over-fetching
  hypotheses above turn out to be real, note that these defaults make their bandwidth cost land on
  cellular/roaming users specifically, which is worth surfacing as context even if the actual fix is
  the over-fetching itself rather than the defaults.
- **Transparency**: does the sync UI (`SyncStatusCard.kt`, `SyncStatusDetailsDialog.kt`,
  `SyncNotifier.kt`'s notification text) give the user any way to tell "this is a quick incremental
  check" from "this is the periodic full reconciliation pass," or any sense of what changed/how much
  was transferred, after the fact? A user who can't tell the difference has no reason to trust that
  the "quick" case is actually quick — that's a legitimate, fixable UX gap independent of whether
  every over-fetching hypothesis above pans out.

## What "good" looks like for each finding

Same contract as any other `TODO.md` entry in this project — write one `## NBC-N` entry per
coherent, independently-implementable fix (checklist + `Status: not started`, matching existing
entries' structure exactly):

- **Exact file/function**, not a vague area — "`OfflineSyncRepository.kt`'s rack-elevation loop at
  line ~219" beats "sync does extra work for racks."
- **The concrete mechanism and evidence** — what you read in the code, and what you saw on the wire
  (which device, what the logcat request trace showed) if this came from live testing.
- **A specific proposed fix**, not just a diagnosis — e.g. "gate `rackElevationRepository.refresh()`
  behind the rack's own `last_updated` watermark, the same pattern
  `syncModelIncrementally`/`syncDevicesIncrementally` already use, adding a per-rack synced-at
  column if one doesn't already exist" — precise enough the implementing agent makes no design
  decisions of its own, only executes yours. For a transparency/UX finding, describe the actual UI
  change (e.g. "SyncStatusCard shows 'Checking for changes…' during an incremental pass vs 'Full
  sync…' during a full one, keyed off a boolean OfflineSyncSummary already has / needs adding").
- **A verification step** — what to check remotely (`:app:testDebugUnitTest`) and/or live on a
  device (e.g. "trigger two syncs back to back on the Mi Pad 4, confirm via logcat that the second
  pass makes zero rack-elevation requests when nothing changed").
- **One coherent fix per entry** — don't bundle the device-type fix and the rack-elevation fix into
  one ticket just because both are "sync efficiency."

## Explicitly out of scope

- **Do not edit, patch, or refactor any source file for any reason — including to verify a fix
  works, "just to be sure," or because it's a one-line change.** Your only write access to this
  repo is appending to `TODO.md`. If you catch yourself opening a source file in write mode, stop.
- Do not propose changing the device/generic-object incremental-watermark design itself
  (`last_updated__gte`, the 24h full-pass interval, `forceFullSync` semantics) — that part is
  already correct; the ask is to extend the same discipline to the steps that currently lack it, not
  to redesign what's already working.
- Do not invent findings to pad the list — a short, high-confidence list beats a long speculative
  one.
- Do not silently propose flipping a user-facing default (`syncOnlyOnWifi`, `syncWhileRoaming`,
  etc.) as the fix for an over-fetching problem — the fix for "X fetches too much" is "make X fetch
  less," not "restrict when X is allowed to run."

## Output

When done, report back: how many `NBC-N` entries you added (numbers/titles), which of the four
numbered hypotheses above were confirmed vs. ruled out (and why), which findings came from live
device testing vs. static reading, and one sentence per entry on why you're confident it's real.
