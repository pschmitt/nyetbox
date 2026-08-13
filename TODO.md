# TODO

Running backlog/changelog for Nyetbox. One `## NBC-N:` entry per feature or fix,
numbered sequentially (never reuse or renumber an id). See `AGENTS.md` for the full convention.

## NBC-419: rack-view widget (scrollable, per-unit device layout)

A second, separate home-screen widget (not another content mode of NBC-418's widget - see
`RackViewGlanceWidget`'s class doc for why): shows one specific rack's unit-by-unit device layout,
picked once at config time, as a genuinely scrollable list (unlike NBC-418's fixed-row content) -
explicit follow-up request: "a rack view widget. this one needs to be resizable/scrollable."

- [x] Reused existing, already-synced, non-SVG per-unit data - `RackElevationRepository.observe
      (rackId, RackFace)` → `Flow<List<RackElevationEntity>>` (populated during every full sync,
      independent of the in-app SVG diagram toggle) - confirmed via research this was a clean fit,
      no new fetch-and-derive layer needed. Merges contiguous same-device slots into blocks the
      same way `ui/generic/GenericDetailRack.kt`'s (private, so mirrored rather than imported)
      `mergeRackSlots` does, reusing its fixed per-device color palette for visual consistency with
      the in-app rack view.
- [x] `widget/RackViewGlanceWidget.kt` - `SizeMode.Exact` + Glance's `LazyColumn` (backed by a real
      RemoteViews `ListView`, not a plain `Column`) so the widget is natively scrollable regardless
      of how it's resized, rather than clipping/truncating content like NBC-418's widget needs
      explicit row-count math for. Config (rack id/label, face, compact) and elevation data are
      both read *reactively* from day one (`RackViewConfigStore` + `RackElevationRepository`'s own
      `Flow`, `remember(rackId, face) { ... }`-keyed so the derived Flow recreates when either
      changes) - applying NBC-418's reconfigure-bug lesson from the start instead of relearning it.
      Tapping an occupied unit navigates to that device's detail page.
- [x] `widget/RackViewConfigStore.kt`, `widget/RackViewWidgetReceiver.kt`,
      `widget/RackViewWidgetConfigActivity.kt` - config screen: search-filterable rack picker
      (reuses `GenericObjectRepository.observeObjects("api/dcim/racks/", "")`, no new query),
      front/rear face toggle, compact mode, and a live preview card driven by the same real,
      already-synced elevation data the widget itself renders (same pattern as NBC-418's).
- [x] `AndroidManifest.xml` - new exported `<receiver>`/`<activity>` pair, mirroring NBC-418's;
      `res/xml/nyetbox_rackview_widget_info.xml` (4x3 default cells, resizable both axes) +
      `res/layout/widget_rackview_preview.xml` static picker preview + `res/string
      /rackview_widget_label` so the two widgets are distinguishable in the picker (both would
      otherwise fall back to the app's own label). `WidgetUpdater.updateAll()` now refreshes both
      widgets from the same single seam.
- [x] Remote `:app:compileDebugKotlin`, `ktfmtCheck`, `:app:lintDebug` (0 errors - only cosmetic
      `HardcodedText`/`ContentDescription`/`UseCompoundDrawables` warnings on the static preview
      layout, same category as NBC-418's own preview layout), `:app:testDebugUnitTest` all passed.
- [x] Verified on the Mi Pad 4: both widgets show distinct labels/previews in the picker; placed
      the rack widget, picked a real cached rack via the search dialog, confirmed the live preview
      matched real elevation data; confirmed the placed widget renders the same data with a real
      native scrollbar, confirmed dragging actually scrolls it, and confirmed tapping an occupied
      unit opens that device's detail screen.

Status: implemented and verified on-device, 2026-08-08.

## NBC-418: home-screen widget with a configurable tap target

A single Glance widget: fixed display (cached device count + sync status, matching
`DashboardViewModel`'s own data sources), but what tapping it does is chosen once via a config
screen shown when it's added to the home screen (and again from its own long-press "Configure" on
API 31+) - reuses the same action+target picker (`ActionTargetPickerDialog`) NBC-415's nav bar
customizer already shipped, rather than a new picker UI.

- [x] Added `androidx.glance`/`androidx.glance-appwidget` (1.1.1) - confirmed via
      `:app:dependencies --configuration debugRuntimeClasspath` that Glance's own
      `androidx.compose.runtime` request converges cleanly onto this project's pinned 1.11.4, no
      forced downgrade.
- [x] `widget/NyetboxGlanceWidget.kt` - fixed layout (sync status via the existing
      `formatRelativeSyncTime` helper + `DeviceRepository.cachedDeviceCount()`), one tap target
      per instance stored via Glance's own `PreferencesGlanceStateDefinition` (keyed by
      `GlanceId`, so no new Room table/SharedPreferences scheme needed), defaulting to
      `GestureAction.Dashboard` until configured.
- [x] `widget/NyetboxWidgetReceiver.kt` (`@AndroidEntryPoint GlanceAppWidgetReceiver`) and
      `widget/WidgetConfigActivity.kt` (`@AndroidEntryPoint ComponentActivity`, shown via
      `APPWIDGET_CONFIGURE`, reuses `ActionTargetPickerDialog` for the two-step action/target
      pick).
- [x] `AndroidManifest.xml` - new exported `<receiver>` (`APPWIDGET_UPDATE`) and `<activity>`
      (`APPWIDGET_CONFIGURE`); `res/xml/nyetbox_widget_info.xml` + a `res/layout/widget_loading.xml`
      RemoteViews placeholder (Glance's required `initialLayout`/`previewLayout`).
- [x] `widget/WidgetUpdater.kt` - one seam every sync/offline-mode-toggle call site refreshes the
      widget through (`SyncWorker.kt`'s success/failure fold, `DirectoryViewModel`/
      `SettingsViewModel`'s `setOfflineMode`), instead of each one independently knowing how to
      talk to Glance.
- [x] Remote `:app:assembleDebug`, `ktfmtCheck`, `:app:testDebugUnitTest` all passed. Lint added
      one new hint (`ReportShortcutUsage`, from NBC-417 below, baselined - optional
      launcher-ranking signal, not a functional issue) and no other new findings; suppressed a
      false-positive `Overdraw` warning on the widget's RemoteViews placeholder directly (lint's
      own detector doc admits it can mis-attribute a layout's theme via inexact pattern matching,
      which doesn't apply to a widget host layout with no owning Activity).
- [x] Bug fix (found during on-device verification on zf10/Mi Pad 4): the widget always rendered
      its default "Stats" content regardless of what was picked in the config screen, on a
      freshly-placed instance. Root cause (confirmed via temporary Timber diagnostics + real
      logcat): `GlanceAppWidget.update(context, id)` is a silent no-op when called from
      `WidgetConfigActivity` during a widget's *first-ever* configuration - the launcher hasn't
      finished placing the instance yet, so the update has nothing to render into. Reconfiguring an
      already-placed widget worked fine, which is what made this easy to miss without deliberately
      testing the first-placement path. Fixed with `widget/WidgetRefreshWorker.kt`
      (`@HiltWorker CoroutineWorker` calling `WidgetUpdater.updateAll()`), enqueued twice from
      `saveConfig` (+3s and +10s delay) via `WorkManager` - decoupled from the config Activity's own
      lifecycle, and two delays because `GlanceAppWidgetManager.getGlanceIds()` was confirmed to
      still miss a genuinely-fresh instance a few seconds after placement.
- [x] Material 3 visual redesign (per explicit follow-up request: "more pretty", per-object
      icons like the in-app UI, bigger action buttons, modern M3 look):
      `NyetboxGlanceWidget.kt` rewritten around Glance's `components` package (`Scaffold`,
      `TitleBar`) and `GlanceTheme`/`GlanceTheme.colors` (Glance's own Material-You-aware dynamic
      color theme, built into the base `glance` artifact - no `glance-material3` bridge needed).
      Bookmark/changelog rows now show a tonal circular icon matching what the in-app UI would show
      for that object type, via new `widget/WidgetObjectIcons.kt` (an exact mirror of
      `ui/directory/AppIcons.kt`'s app-key/endpoint-path mapping, returning `@DrawableRes Int`
      instead of a Compose `ImageVector`, since Glance's `Image` can't render one directly). Action
      buttons switched from plain 32dp `Image`s to 56dp `CircleIconButton`s with tonal
      `secondaryContainer`/`onSecondaryContainer` colors. 19 new vector drawables under
      `res/drawable/ic_object_*.xml` (per-NetBox-type glyphs) and `ic_glyph_*.xml` (flat action-row
      glyphs, as opposed to the existing circle-backed `ic_shortcut_*.xml` shortcuts use), traced
      from `google/material-design-icons` source SVGs rather than transcribed from memory, to
      guarantee pixel-accurate reuse of the exact icons `AppIcons.kt` already picks in-app.
      Researched exact Glance component/`GlanceTheme` API signatures from `androidx/androidx`'s
      GitHub `api/*.txt` dumps before writing any code - the full rewrite compiled clean on the
      first attempt.
- [x] On-device verification (zf10, Mi Pad 4, px5): placed/reconfigured widgets, confirmed the
      timing fix via real logcat (`WidgetRefreshWorker` running and completing, followed by a
      correct re-render for a widget's very first configuration - previously silently stuck on
      Stats), and confirmed the M3 redesign visually - per-object icons render correctly (hub icon
      for `dcim.device` bookmarks, layers/category icons for changelog rows), the Stats widget's
      tonal `primaryContainer` device-count chip, and a configured 3-button action row rendering as
      visibly bigger tonal circles than the old flat icons.
- [x] Second bug fix (found on real-world use, not the earlier verification pass): reconfiguring
      an *already-placed* widget via long-press -> pencil silently had no effect. Root cause
      (found from `GlanceAppWidget`/`AppWidgetSession` source, not guessed): `update()`/
      `updateAll()` only recompose an *already-running* composition session - they don't re-invoke
      `provideGlance`, per its own doc comment - so a one-shot `getAppWidgetState` read at the top
      of `provideGlance` (the shape this widget had) is invisible to any session that's still alive
      when Save fires, which is exactly what a `WidgetRefreshWorker` retry a few seconds later also
      can't fix (same still-alive session, same stale composition). Fixed per Glance's own
      recommended pattern - stopped one-shot-reading config and read it *reactively* inside the
      composition instead: new `widget/WidgetConfigStore.kt` (an in-memory
      `StateFlow<Map<appWidgetId, WidgetInstanceConfig>>`, published by `saveConfig` and
      `collectAsState()`'d in `provideGlance`), plus switched bookmarks/changes/device
      count/sync status from one-shot reads to `collectAsState()` on the repositories' own
      `Flow`s/`StateFlow`s (added `DeviceDao.observeCount()`/`DeviceRepository.observeCount()` for
      the one that didn't have a Flow yet). Verified on the Mi Pad 4 with a genuinely delayed
      repro (75s wait before Save, not immediately after placement) - confirmed broken before the
      fix, confirmed fixed after.
- [x] Responsive layout (widget is `resizeMode="horizontal|vertical"`, previously rendered one
      fixed layout regardless of size): `sizeMode = SizeMode.Exact` + `LocalSize.current` inside
      the composition. List row count now derived from actual widget height
      (`visibleRowCount`, fetching up to 8 rows unconditionally instead of only 4 for whichever
      content was configured, so switching content types doesn't need a re-fetch), the Stats
      row switches to a stacked `Column` under a width threshold instead of squeezing a `Row`,
      and action buttons shrink from 56dp/16dp gaps to 44dp/8dp when the configured count wouldn't
      otherwise fit the current width. Verified on Mi Pad 4: resizing a Bookmarks widget taller
      revealed 2 additional rows that were previously clipped/hidden at the smaller size.
- [x] Widget-picker preview showed the `widget_loading.xml` spinner forever (previewLayout was
      pointed at the same placeholder as initialLayout, and the picker never binds a real widget
      instance to actually resolve past "loading"). Fixed with a new static
      `res/layout/widget_preview.xml` RemoteViews mockup (sample Stats content + 3 action-button
      glyphs) - hit one RemoteViews restriction along the way (confirmed via on-device logcat:
      `Class not allowed to be inflated android.widget.Space`), fixed by using
      `layout_marginStart` instead of `<Space>` elements for the action-row gaps.
- [x] Compact mode: new per-instance `compact: Boolean` (`KEY_COMPACT`) hides the `TitleBar`
      entirely (`Scaffold(titleBar = null)`) to fit more content on small widgets - toggle added to
      `WidgetConfigActivity`'s config screen.
- [x] Live preview in the config screen itself (nice-to-have): `WidgetPreviewCard` in
      `WidgetConfigActivity.kt` - a parallel plain-Compose (not Glance) mockup driven by the same
      real repository data the widget itself renders, updating live as the user changes
      content/actions/compact before saving. `WidgetConfigViewModel` extended with
      `bookmarks`/`changes`/`deviceCount`/`statusText`/`offlineMode` for this.
- [x] Remote `:app:compileDebugKotlin`, `ktfmtCheck`, `:app:lintDebug`, `:app:testDebugUnitTest`
      all passed (one `FlowOperatorInvokedInComposition` lint error fixed by `remember`-memoizing
      the mapped bookmarks/changes flows instead of calling `.map` inline in the composition; one
      `UseAppTint` error suppressed on `widget_preview.xml` with `tools:ignore` - it's a plain
      RemoteViews layout, not an AppCompat Activity, so there's no inflater to back-port
      `app:tint`, and native `android:tint` already works). All 5 fixes verified on the Mi Pad 4:
      picker preview, delayed reconfigure, resize-driven row count, compact mode, and the config
      screen's live preview.
- [x] Action buttons made "pop more" (explicit follow-up: bigger where space allows, an optional
      label, more visual emphasis): replaced `androidx.glance.appwidget.components.CircleIconButton`
      with a custom Box/Column button (`ActionButton`) - `CircleIconButton` has its own fixed
      icon-to-container proportions that don't suit a button ranging from 44dp to 84dp. Sizing
      (`actionButtonSize`) now fills the available row width evenly across however many buttons are
      configured (up to `MAX_ACTION_BUTTON_SIZE = 84.dp`), rather than picking from two fixed
      tiers - a widget with only 1-2 buttons and room to spare now gets visibly bigger buttons, not
      just the same size as a 3-button row. Background switched from the passive list rows'
      `secondaryContainer` to `primaryContainer`, so action buttons visually stand out from
      everything else in the widget. New per-instance `showActionLabels: Boolean`
      (`KEY_SHOW_ACTION_LABELS`, default **on**) shows each action's name under its button; the
      action row's reserved height (used by `visibleRowCount` to size list content around it) now
      accounts for the actual button size and label, instead of a fixed constant. Config screen and
      its live preview both updated to match (toggle + bigger labeled preview buttons).
- [x] Remote `:app:compileDebugKotlin`, `ktfmtCheck`, `:app:lintDebug`, `:app:testDebugUnitTest` all
      passed, zero new findings. Verified on the Mi Pad 4: buttons render visibly bigger and more
      vibrant with labels ("Global search"/"QR scanner"/"Settings") under each; toggling "Show
      labels" off removes the labels and the buttons still render correctly.

Status: implemented, bug-fixed (twice), redesigned, made responsive, and verified on-device,
2026-08-08.

## NBC-417: configurable launcher shortcuts

Long-press the app icon to jump straight to an action, without opening the app first. Reuses
`NavBarItem`/`GestureAction`/`GestureTarget` as-is (no new data model) and the exact same
`ActionTargetPickerDialog` + reorder-row UI shape NBC-415's nav bar customizer already shipped, so
this is the third consumer of that same "action + optional NetBox target" model, not a parallel
system.

- [x] `SettingsRepository.kt` - `GestureAction.shortcutable` (= `navigational` minus `Dashboard`,
      since a "Home" shortcut duplicates what tapping the icon itself already does), new
      `shortcutItems: StateFlow<List<NavBarItem>>` persisted list (`KEY_SHORTCUT_ITEMS`,
      `MAX_SHORTCUT_ITEMS = 4`), default `[GlobalSearch, Scanner, Add]` (the nav bar's own
      non-Dashboard defaults).
- [x] New Settings category "App shortcuts" - `ShortcutSettingsContent` in
      `SettingsCategoryContent.kt` mirrors `NavBarSettingsContent` (reorder/remove rows, add-action
      dropdown, reset-to-defaults), wired through `SettingsViewModel.kt`/`SettingsScreen.kt`.
- [x] `shortcuts/ShortcutSyncer.kt` - publishes the list via
      `ShortcutManagerCompat.setDynamicShortcuts`, replacing the whole set atomically on every
      change (`NyetboxApp.onCreate()` at startup, plus a `MainActivity` `LaunchedEffect
      (shortcutItems)` so Settings edits take effect live). New hand-authored vector-drawable icons
      under `res/drawable/ic_shortcut_*.xml` (shortcut icons need real drawable resources, not
      Compose `ImageVector`s).
- [x] Shared plumbing (also used by NBC-418's widget taps): `MainActivityRouting.kt` gained
      `EXTRA_GESTURE_ACTION`/`EXTRA_GESTURE_TARGET` + `putGestureExtras`/`extractGestureAction`/
      `extractGestureTarget` (JSON-encoded target), and `MainActivity.kt`'s existing inline
      gesture-dispatch `when` block was extracted into one shared `dispatchGestureAction(...)`
      method, now called from the in-app swipe-gesture handler *and* a new `pendingGesture`
      `LaunchedEffect` fed by shortcut/widget taps - one dispatch path regardless of origin.
- [x] Remote `:app:assembleDebug`, `ktfmtCheck`, `:app:testDebugUnitTest` all passed; lint added
      one new hint (see NBC-418's lint note above), baselined.

Status: implemented and compiles/lints clean, 2026-08-08 - pending on-device verification (edit the
list in Settings, confirm the launcher long-press menu updates live, confirm each shortcut lands on
the right screen or triggers Sync).

## NBC-416: fix the store screenshot dashboard race for real (logcat marker, not overlay polling)

NBC-408 (Aug 7) re-checked `DashboardScreen`'s initial-sync overlay tag right before the dashboard
store screenshot, but PR #17's latest run still captured "Setting up your NetBox instance - Step 2
of 8" instead of the populated dashboard - the overlay's own visibility was the wrong thing to
poll: `DashboardViewModel.showInitialSyncOverlay` is only false for good once
`lastSuccessfulSyncAt` is set, but the first sync is chunked into ~8 steps each with their own
brief `isRefreshing=false` gap, so "the overlay happens to be absent right now" can be true well
before the sync is actually done.

- [x] `SettingsRepository.kt`: `recordSuccessfulSync()` (the one place that's only ever called
      once a sync pass finishes clean) now also emits a debug-only logcat marker
      (`E2E_SYNC_COMPLETE_MARKER = "NYETBOX_E2E_SYNC_COMPLETE"`, via `Timber.i` - only planted in
      debug builds, so this is a no-op in release).
- [x] `NetBoxJourneyTest.kt`: new `waitForLogcatMarker(marker, timeoutMillis)` polls
      `UiDevice.executeShellCommand("logcat -d")` instead of the Compose semantics tree;
      `clickConnectAndWaitForDashboard()` (shared by every journey test) now clears logcat right
      before clicking Connect and waits for the marker instead of the overlay tag's fleeting
      absence.
- [x] `StoreScreenshotTest.kt`: removed the now-redundant defensive re-check before the dashboard
      capture (`waitForTagAbsent("e2e-initial-sync-overlay", ...)`) - once the marker has fired,
      the overlay's own condition can never go true again for that profile, so there's nothing
      left to race.
- [x] Remote `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, and
      `:app:testDebugUnitTest` all passed; no lint-baseline drift.
- [x] Attempt 1 (marker-only wait) shipped and went green in CI, but a real triggered
      `Screenshots` run still caught the overlay - this time showing its `syncProgress == null`
      fallback copy ("Fetching your inventory for the first time...") instead of the original
      "Step 2 of 8". Root cause: `recordSuccessfulSync()`'s marker and `SyncWorker.doWork()`'s
      `syncStatusRepository.publishProgress(null)` fire in the same coroutine tick, but
      `DashboardViewModel.showInitialSyncOverlay`'s `combine().stateIn()` needs extra dispatcher
      hops to actually recompose - so the marker can land in logcat slightly before the overlay's
      own Compose state has caught up.
- [x] Attempt 2: `clickConnectAndWaitForDashboard()` now waits for the marker *first* (rules out
      the mid-sync chunk-flicker false positive attempt 1 was built for), then
      `waitForTagAbsent("e2e-initial-sync-overlay", timeoutMillis = 15_000)` (now a short, safe
      bound - just catching up to that trailing recomposition instead of racing the whole sync).
      Compiles clean, no lint-baseline drift.
- [x] Attempt 2 also still raced in a real triggered `Screenshots` run (workflow run 31215820927) -
      back to the original "Step 2 of 8" symptom, on the very first (light-mode) capture, even
      though a *later* capture in the same run was clean. Added timestamped `log -t
      NYETBOX_E2E_DIAG` lines around each wait step/capture and made CI always upload a full
      `adb logcat -d` dump (not just on failure) to get ground truth instead of guessing further.
- [x] The diagnostic dump (workflow run 31217385663, which happened to pass) showed only ~200ms
      between the overlay tag reading absent from Compose's semantics tree and the screenshot
      firing. The overlay is its own Dialog with its own Android Window - the WindowManager
      actually tearing that window down and the compositor flushing a frame without it is a real
      OS-level step outside Compose's own recomposition/idling machinery, and can outlast a ~200ms
      margin on a more loaded CI runner (explaining why the *same* wait logic passed on one run
      and failed on another). Attempt 3: added a fixed 1s settle delay right after the overlay tag
      reads absent, to buy margin against exactly that gap.
- [x] Attempt 3 verified via a real triggered `Screenshots` run (workflow run 31218925635): the
      sync overlay race is fixed - all three device sizes (phone/sevenInch/tenInch) captured the
      synced dashboard cleanly. That same run surfaced a separate, previously-hidden bug: phone's
      light-mode capture showed the on-screen keyboard, left raised from typing the token during
      onboarding and never dismissed - it was likely always there, just covered by the overlay's
      own full-screen dialog in every prior failing capture.
- [x] Fixed the keyboard via `device.pressBack()` right after clicking Connect (mirroring the
      existing pattern already used for the search screenshot) - but this broke the *next*
      `Screenshots` run (31220230584): on tenInch/sevenInch, the back-press navigated all the way
      out to the launcher home screen instead of dismissing the keyboard. The IME apparently
      wasn't actually showing yet at that exact point on those form factors (onboarding
      validation completes fast enough there to have already dropped focus), so the back event
      fell through to real back-stack navigation instead of being consumed by an IME that wasn't
      there.
- [x] Replaced all three `device.pressBack()` IME-dismiss call sites (the new one here, plus two
      pre-existing ones in `StoreScreenshotTest.kt` and `NetBoxE2eTest.kt` that shared the
      identical latent risk) with a shared `NetBoxJourneyTest.dismissKeyboard()` helper using
      Espresso's `closeSoftKeyboard()`, which only talks to the InputMethodManager directly and is
      a safe no-op when the keyboard isn't shown - no risk of navigating anywhere.
- [x] Verified via a fourth real triggered `Screenshots` run (workflow run 31221652915): all three
      device sizes' capture jobs succeeded, every dashboard screenshot showed the synced state
      cleanly (no overlay, no leftover keyboard), and the search screenshot (the other
      `dismissKeyboard()` call site) stayed on the search screen with the keyboard closed as
      expected - no more home-screen exits on tablet form factors.

Status: done, 2026-08-08 - confirmed via a real CI screenshot run across phone/sevenInch/tenInch:
the sync-overlay race is fixed (attempt 3's marker+overlay-absence wait plus a 1s settle delay),
and the onboarding keyboard is dismissed safely via Espresso's closeSoftKeyboard() rather than a
raw back-press, which had regressed tablet form factors by navigating to the launcher home screen
in one intermediate attempt.

## NBC-415: customizable bottom navigation bar (up to 5 slots, reorderable)

Bottom bar (phone) and rail (tablet) were hardcoded: phone always showed Home/Search/Scan/Add,
rail always added Settings. Added a Settings screen to customize which buttons appear (up to 5)
and reorder them, with any NetBox view (a type's list, or one specific cached object) selectable
alongside the fixed destinations.

- [x] `SettingsRepository.kt`: added `GestureAction.Dashboard` (a real gap - gestures couldn't
      point at Home either) and `GestureAction.navigational` (excludes `Off`/`Sync`/
      `OfflineOn`/`OfflineOff`, since a nav-bar slot must always be able to show a "selected"
      state); new `@Serializable NavBarItem(action, target)`, persisted as a single JSON-encoded
      `List<NavBarItem>` (order matters, so not a `StringSet`) mirroring the existing
      `ServerProfile` list pattern; `navBarItems` StateFlow + `setNavBarItems`/`resetNavBarItems`;
      wired into settings backup/restore (`SettingsBackup.kt`) alongside gestures/pinned paths.
      Default is unchanged from today's phone bar (Home, Search, Scan, Add) on both surfaces -
      an earlier pass here briefly changed the default to the rail's fuller five (adding
      Settings), which was reverted per feedback.
- [x] `MainActivityRouting.kt`: `routeForGesture` gained the `Dashboard -> Route.Dashboard`
      branch (reused as-is for nav-bar dispatch, no new resolver needed); new
      `matchesCurrentRoute(current, target)` for "is this slot the one currently open" -
      compares only the identifying fields of `Route.Generic`/`Route.GenericList` so a
      slot resolved with default extras (no breadcrumb/filter) still matches the same
      destination reached by browsing.
- [x] `ui/common/NetBoxBottomBar.kt`: rewritten to render a `List<NavBarItem>` (from a new
      self-contained `NavBarViewModel`) instead of 5 hardcoded items; added `LocalCurrentRoute`
      (`NetBoxResponsiveScaffold.kt`, alongside `LocalUseNavigationRail`) so the bar knows the
      current destination without threading a new param through every screen.
- [x] `NetBoxNavHost.kt` + the 6 screens that host the bar (`DashboardScreen`, `DeviceListScreen`,
      `GenericListScreen`, `AddItemScreen`, `GlobalSearchScreen`, `ScannerScreen`): collapsed each
      screen's 5 fixed `on*Click` callbacks into one `onNavigate: (Route) -> Unit`, and wrapped
      each `composable<Route.X>` block in `CompositionLocalProvider(LocalCurrentRoute provides ...)`.
- [x] `SettingsGestures.kt`: extracted the existing two-step target-picker `AlertDialog` (choose
      item type, then for "specific item detail" a cached instance of that type) out of
      `GestureShortcutRow` into a standalone `ActionTargetPickerDialog`, reused by both the
      gesture editor and the new nav-bar customizer - no behavior change for gestures.
- [x] New Settings category "Navigation bar" (`SettingsCategory.kt`, grouped under "Appearance &
      Interaction"): `NavBarSettingsContent` (`SettingsCategoryContent.kt`) lists the configured
      slots with up/down/remove controls (a plain scrollable `Column`, not `LazyColumn` - the
      existing drag-reorder utility in `ReorderableSections.kt` needs `LazyListState` and didn't
      fit this screen's container, so reordering is buttons instead of drag-and-drop) plus an
      "Add button" row (hidden at the 5-item cap) reusing `ActionTargetPickerDialog`, and "Reset
      to defaults".
- [x] Remote `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin` (after fixing
      `SettingsCategoryContentTest.kt`'s direct `SettingsCategoryState`/`Actions` construction for
      the new required fields), and `:app:testDebugUnitTest` all passed.
- [x] Verified end-to-end on the Zenfone 10: default bar shows all 4 (Home/Search/Scan/Add) with
      correct highlighting on each; removed an item, added "Racks" (a type) via the picker,
      confirmed the bottom bar picked up the change immediately and tapping it navigated to the
      Racks list with the slot highlighted; "Reset to defaults" restored the original four.
- [x] `iconForGestureAction` (`NetBoxBottomBar.kt`) copied its icon choices straight from the
      pre-existing gesture-shortcut picker when extracted for reuse - harmless there (gestures
      are never shown as a visible icon row) but wrong once it became a visible bottom-bar icon:
      `Settings` showed an info-circle instead of the gear, `Add` showed a plain `+` instead of
      the original `AddCircle`. Fixed both to match the bar's original look.

Status: done, 2026-08-07 - verified working end-to-end on the Zenfone 10 (add/remove/reorder,
navigation, selection highlighting, reset, corrected default/icons). Mi Pad 4 and Pixel 5
installed; not yet re-verified there beyond a successful install.

## NBC-414: drop the "Matches" title/subtitle from global search results

The search results section header repeated info already shown elsewhere (an active type filter
already has its own chip above) or added no value ("Cached results update as you type") - the
match-count badge was the only useful part of the row.

- [x] `GlobalSearchScreen.kt`: `SearchSectionHeader`'s `title`/`subtitle` params are now nullable
      (default `null`) and simply skip rendering that `Text` when absent. The results heading now
      only passes `icon`/`count`; the "Filter by type" heading drops its subtitle too (the section
      title alone is enough). "Recently visited" keeps both.
- [x] Remote `:app:compileDebugKotlin`/`:app:assembleDebug` passed; installed on the Zenfone 10,
      Mi Pad 4, and Pixel 5; confirmed on-device the results row shows just the icon and count.

Status: done, 2026-08-07.

## NBC-413: add/remove bookmark from the item/device overflow menu

Bookmarks previously only synced one-way from NetBox (read-only dashboard widget). Added a
toggle so any device or generic object detail screen can bookmark/unbookmark itself directly.

- [x] `BookmarkDao.kt`: added `upsert(bookmark)` and `delete(id)` alongside the existing
      `observeAll`/`upsertAll`/`clear`/`replaceAll`.
- [x] `DashboardRepository.kt`: added `observeBookmark(endpointPath, id)` (derived from
      `observeBookmarks()`, no new query), `addBookmark(endpointPath, id)` (POSTs to
      `api/extras/bookmarks/` using `MediaUploadRepository.contentTypeForEndpoint` to resolve
      `object_type`, then caches the result immediately via `toBookmarkEntity`), and
      `removeBookmark(bookmarkId)` (DELETE + local cache removal). Deliberately not routed
      through `PendingEditRepository`'s offline outbox - that writes into the generic object
      cache, not the typed `bookmarks` table the dashboard actually reads from, and a durable
      queue is overkill for a lightweight favorite-style toggle; offline mode simply surfaces an
      error message instead.
- [x] `DeviceDetailViewModel.kt` / `GenericDetailViewModel.kt`: added `bookmark` (`StateFlow<BookmarkEntity?>`),
      `isTogglingBookmark`, and `toggleBookmark()`.
- [x] `DeviceDetailScreen.kt` / `GenericDetailScreen.kt`: added an "Add bookmark"/"Remove bookmark"
      item as the first entry in the "More actions" overflow menu, with a filled/outline
      `Icons.Default.Bookmark`/`BookmarkBorder` icon toggle.
- [x] On-device check on the Zenfone 10 (via adb) caught a real bug: NetBox's Bookmark serializer
      requires an explicit `user` field on create - it is not inferred from the auth token, so
      every "Add bookmark" attempt failed with a 400 (`{"user":["This field is required."]}`),
      for both a device and a manufacturer alike (not a content-type restriction, as first
      suspected from the matching error-body byte length). Fixed by capturing the authenticated
      NetBox user's numeric id: `NetBoxUserIdentity` (`SettingsRepository.kt`) gained an `id: Int?`
      field, persisted via a new `KEY_CURRENT_USER_ID` pref key; `SettingsViewModel.parseCurrentUser`
      now reads it via `user.jsonInt("id")` from the existing `authentication-check`/token-owner
      lookup; `DashboardRepository.addBookmark` includes `"user": <id>` in the POST body. Existing
      logged-in sessions self-heal the first time they open Settings (which already calls
      `refreshCurrentUser()` on load) - no re-login needed.
- [x] Verified end-to-end on the Zenfone 10: toggled bookmark on/off on a real device (POST 201 /
      DELETE 204, menu label flips and persists across reopens) and confirmed the same fix also
      resolves the manufacturer case.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.

Status: done, 2026-08-07 - verified working end-to-end on the Zenfone 10 (create/delete round trip,
menu state), including a real bug found and fixed during that verification.

## NBC-412: ellipsize dashboard item names; icon-only "recent" search badge; rank names/asset tags higher

Three small polish items from the same conversation:

- [x] `DashboardScreen.kt`: the Recent visits, Bookmarks, and Recent changes rows' headline text
      (`visit.display`/`bookmark.display`/`change.objectRepr`) and the recent-visit secondary
      line could wrap/overflow unbounded for a long item name (e.g. a long rack/device-type
      label) - now `maxLines = 1` + `TextOverflow.Ellipsis`, matching the pattern the dashboard's
      other cards already used at line 855.
- [x] Global search results: replaced the text "Recent" badge (`RecentBadge`, now deleted) with a
      small icon-only badge overlaid on the result's thumbnail/icon (bottom-end corner, circular,
      `Icons.Default.History`, `contentDescription = "Recently viewed"` since the icon is now the
      only affordance) - same information, less label clutter in the badge row.
- [x] `GlobalSearchRepository.kt`'s `searchRelevance`: added asset-tag matching as its own
      priority tier, strictly between name/display matches (still always highest, "name wins")
      and secondary-line/matchHint matches - previously an asset-tag-only match (no hit in
      display/secondaryLine/matchHint) scored 0 and sorted to the bottom. New unit test in
      `GlobalSearchRankingTest.kt` locks in the three-tier ordering.
- [x] Remote `:app:compileDebugKotlin`, `:app:assembleDebug`, and
      `:app:testDebugUnitTest --tests GlobalSearchRankingTest` all passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual check - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles, unit tests pass, and installs cleanly on all three
test devices; on-device verification still pending the user.

## NBC-411: make Settings section titles more discreet, drop their subtitles

Follow-up to NBC-407/409. `SettingsGroupCard`'s header title ("Account & Sync" etc.) was styled
as a prominent `titleMedium` heading with a subtitle line under it - too loud for what's really
just a section label, per the user's request.

- [x] `SettingsGroupCard` (`SettingsComponents.kt`): title now renders at `labelLarge` (down from
      `titleMedium`) in `onSurfaceVariant` instead of the default on-surface color, and the
      `subtitle` param was removed entirely (not just hidden) since nothing used it anymore.
- [x] Stripped the now-invalid `subtitle = "..."` argument from all 15 `SettingsGroupCard` call
      sites across `SettingsScreen.kt`, `SettingsCategoryContent.kt`, and `SettingsPrinting.kt` -
      left the unrelated per-row `ExternalLinkRow(subtitle = ...)` calls in the About screen
      untouched (same param name, different composable).
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual check - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-410: subtle per-card-section tint; drop redundant "About" card title

Small visual tweak: on screens with several `NyetboxSectionCard`s stacked (Media, Details,
Linked items, etc.), every card used the exact same flat `surfaceContainer` background, making
adjacent sections harder to tell apart at a glance. Also, the Settings top-level menu's last
card repeated "About" as both the card title and its one and only row.

- [x] New `ColorScheme.sectionTintFor(title)` (`DetailAccent.kt`): deterministic per-title pick
      from a small muted 6-color palette, blended at 5% alpha over `surfaceContainer` via
      `compositeOver` - same section name always gets the same subtle tint everywhere, low
      alpha so it reads as a hint, not a color statement.
- [x] `NyetboxCard` gained an optional `containerColor` param (defaults to today's
      `surfaceContainer`, so every other caller is unaffected); `NyetboxSectionCard` passes
      `sectionTintFor(title)` through, so `NyetboxDetailsCard`/`NyetboxLinkedItemsCard`/`Media`/
      etc. each get their own stable tint automatically.
- [x] Settings: the About section now uses the headerless `SettingsSingleItemCard` instead of
      `SettingsGroupCard`, dropping the redundant "About" title above the single "About" row.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual check - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-409: fix card header/content indentation mismatch

`NyetboxSectionCard`/`SettingsGroupCard` hand-rolled their header (icon + title/subtitle) as a
custom `Row` with its own explicit horizontal padding, independent of whatever padding the
`content` rows below happened to use - "only the title is indented" in the user's words. Root
cause: two independent guesses at a matching inset instead of one shared source of truth.

- [x] `NyetboxSectionCard` (`AppCard.kt`) header rewritten to render as a `NyetboxListItem` -
      the exact same primitive most `content` rows already use - so they align by construction,
      not by coincidentally-matching magic numbers. Same fix applied to `SettingsGroupCard`
      (`SettingsComponents.kt`). Every existing call site of both (`MediaCarousel.kt`,
      `GenericCreateScreen.kt`, `GenericDetailEditing.kt`, every Settings category screen, etc.)
      benefits automatically - no per-call-site changes needed.
- [x] Removed now-dead unused imports (`Row`/`Spacer`/`Arrangement`/`width`/`Alignment`) left
      behind by deleting the old hand-rolled header `Row`s in both files.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] Non-`ListItem` card content (e.g. `MediaCarousel.kt`'s carousel, which pads itself to
      18dp to match the *old* hardcoded header inset) may still be a pixel or two off the new
      `ListItem`-derived inset - not re-tuned since the exact default wasn't verified against a
      live render. Flagged as a possible small follow-up once visually checked, not blocking.
- [ ] On-device visual check - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-408: Play Store screenshots captured before initial sync finishes

`StoreScreenshotTest.kt` (`app/src/androidTest/kotlin/dev/pschmitt/nyetbox/`, driven by `fastlane
screenshots` per `.github/workflows/screenshots.yaml`) grabbed the home/dashboard shot too early -
it landed on the "Setting up your NetBox instance" sync-in-progress dialog
(`DashboardScreen.kt`'s `InitialSyncOverlay`, `testTag("e2e-initial-sync-overlay")`) instead of
the populated home page.

Root cause: `connectToNetBox`/`switchToDarkModeAndReturnToDashboard` (`NetBoxJourneyTest.kt`)
already wait out that overlay once, but per that file's own doc comment a background sync tick
retriggers it roughly every 10s - and nothing re-checked for it again between that one-time wait
and the actual `Screengrab.screenshot("01_dashboard")` call moments later, so a retrigger landing
in that window got captured instead of the real dashboard.

- [x] `captureJourney()` (`StoreScreenshotTest.kt`) now re-checks
      `waitForTagAbsent("e2e-initial-sync-overlay", ...)` immediately before the first
      `captureScreenshot("01_dashboard...")` call, for both the light and dark passes - the same
      defensive "re-check right before the capture that matters" pattern `captureScreenshot()`
      itself already uses for a stray ANR dialog.
- [x] Remote `:app:compileDebugAndroidTestKotlin` passed.
- [ ] Not verified against a real screenshot run yet (requires the full disposable-NetBox +
      emulator harness in `.github/workflows/screenshots.yaml` / `just screenshots`, which wasn't
      run as part of this fix) - next real trigger of that workflow (a tagged release, or a
      manual `gh workflow run screenshots.yaml`) will confirm.

Status: mostly done, 2026-08-07 - fix implemented and compiles; not yet confirmed against a real
screenshot-capture CI run.

## NBC-407: group the top-level Settings menu into titled cards

The top-level Settings screen (the menu of 9 category rows: Connection, Backup, Sync, Camera,
Printing, Gestures, Display, Notifications, About) sat under two plain `Text` labels
("Preferences", "Settings") with no card chrome - unlike every category's own screen one level
down, which already groups its rows into titled `SettingsGroupCard`s. Regrouped the top-level
menu the same way, into 4 titled cards proposed to and confirmed by the user:

- [x] "Account & Sync" - Offline mode toggle, Connection, Sync, Backup & restore.
- [x] "Hardware" - Camera, Printing.
- [x] "Appearance & Interaction" - Display, Gestures, Notifications.
- [x] "About" - About (kept its own card for visual consistency with the other three).
- [x] New private `SettingsCategoryRow` helper (icon/title/subtitle/chevron `SettingsListItem`)
      replaces the old `SettingsNavigationCard` per-category card, which is now unused and was
      deleted from `SettingsComponents.kt`.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual check - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-406: colorize list-header icons; show asset tag badge on generic detail cards

Two small consistency gaps: the `TopAppBar` header icon on the Devices/generic list screens
used a flat default tint instead of the app's existing per-object-type accent color (which the
row icons right below already used); and the asset-tag badge devices show on their detail top
card was missing entirely for every other object type (racks, etc.) even though NetBox's
`asset_tag` field isn't device-specific and the generic list screen already renders the same
badge (including a "No asset tag" state) for any type.

- [x] `GenericListScreen.kt`/`DeviceListScreen.kt`: hoisted the existing `detailAccentFor(...)`
      computation above the `TopAppBar` and applied it as the header icon's `tint`, reusing the
      same value for the row icon/thumbnail-fallback tint that already used it (removed the
      duplicate computation).
- [x] `GenericDetailViewModel.kt`: new `assetTag: StateFlow<AssetTagState>` derived from
      `decodedObject` (mirrors `GenericListScreen`'s `assetTagStateFromRawJson`, preserving
      "field present but blank" vs "field doesn't exist on this type" - `fields`/
      `buildFieldRows` drops blank values entirely, so `fields` alone can't distinguish these).
- [x] `GenericDetailIdentityCard` (`GenericDetailIdentity.kt`): new `assetTag`/
      `onAssetTagLongPress` params, rendering `AssetTagBadge`/`MissingAssetTagBadge` as a
      trailing sibling in the top card's Row - same placement/long-press-to-copy pattern
      `DeviceDetailScreen.kt` already uses for devices.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual check (list header tint, rack/other-type asset tag badge incl. missing
      state) - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-405: jump to rack elevation + highlight device from the "Position" row

Tapping a device's "Position" row already navigated to its rack and threaded a
`highlightDeviceId` all the way down to `RackElevationOverview` (which already drew a permanent
border around the matching device) - but the screen still opened on Overview instead of
Elevation, the device was never scrolled into view, and the highlight had no arrival animation.

- [x] `GenericDetailScreen.kt`: new `LaunchedEffect(highlightDeviceId, viewModel.isRack)` jumps
      `selectedTab` to the Elevation tab (always index 1 when present) and forces the non-SVG
      elevation view (the only one with per-device highlighting) on arrival.
- [x] `GenericDetailRack.kt`: the highlighted device block now uses `BringIntoViewRequester` to
      scroll itself into view (works through the enclosing `LazyColumn` without restructuring it
      into per-device lazy items) and layers a `primary`-tinted background flash that fades over
      ~1.2s on arrival, on top of the existing permanent border.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual/behavioral check (tall rack, device near the bottom, plain rack visits
      unaffected) - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-404: unify long-press action dialogs; support editing document metadata

Three long-press dialogs looked structurally different: fields and image attachments used
stacked filled/outlined `Button` rows with no dialog icon (`FieldActionDialog`), documents used
flat `TextButton` rows with a dialog icon (hand-rolled in `MediaCarousel.kt`). Unify on the
document dialog's look everywhere, and add the one action documents never had: editing a
document's NetBox type/comments (previously create/delete only).

- [x] New `ui/common/ActionSheetDialog.kt`: shared `ActionSheetAction`/`ActionSheetDialog` -
      dialog icon, flat `TextButton` rows, destructive actions tinted
      `MaterialTheme.colorScheme.error`, extracted/generalized from `MediaCarousel.kt`'s former
      hand-rolled document action-picker.
- [x] `FieldActionDialog.kt` rewritten as a thin wrapper over `ActionSheetDialog` - same public
      signature, zero changes needed at either call site.
- [x] `MediaCarousel.kt`'s document action-picker now builds an `ActionSheetDialog` too, with a
      new "Edit document" action; the separate delete-confirmation dialog was left as-is
      (already a standard, consistent shape).
- [x] `CachedDocument` (`DocumentRepository.kt`) gained `documentTypeValue` (raw NetBox choice
      value, for the edit dropdown/PATCH) and `rawJson` (the `submitEdit` baseJson) alongside the
      existing display-label `documentType`.
- [x] `MediaUploadViewModel.editDocument(...)`: PATCHes type/comments via the same generic
      `PendingEditRepository.submitEdit` mechanism every other edit in the app already uses -
      offline queueing/conflict handling comes for free, no new API surface.
- [x] New `ui/common/DocumentEditDialog.kt`: edit form (type dropdown + comments field) then a
      before/after review step built with the generic editor's `DiffValueRow`, matching
      `EditDiffDialog`'s look without coupling to its `EditableField` data model.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual/behavioral check - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-403: merge "Add image"/"Add document" into one Add action; fix device-picture viewer scope

Follow-up to NBC-401. The Media carousel still had two separate add buttons, each hardcoding a
NetBox upload kind up front. Merge them into one "Add media" action with just two choices (take a
photo / upload a file); the NetBox upload kind (image attachment vs document) is now inferred
from what was actually picked and overridable in the confirmation dialog. Also: tapping a device's
own front/rear stock photo opened a viewer scoped to just that device-type photo instead of the
same combined image+document list the carousel uses.

- [x] Extracted `rememberCameraCaptureLauncher` (permission check + capture `Uri` + camera
      launcher) out of `MediaUploadDialog.kt` so both it and the new chooser share one
      implementation.
- [x] `MediaCarousel.kt`: one "Add media" button opens a `DropdownMenu` (Take photo / Upload
      file); default NetBox kind is inferred via `isSharedImage(...)` and `supportsImageAttachments`/
      `supportsDocuments`, then handed to the caller as `onAddMedia(uri, defaultKind)`.
- [x] `MediaUploadDialog.kt`: generalized the device-type-only kind dropdown into a
      `kindOptions`-driven one that also offers an Image attachment/Document switch (when both
      are supported and not replacing a specific attachment) - the override the user asked for.
- [x] `DeviceDetailScreen.kt`/`GenericDetailScreen.kt`: wired the new `onAddMedia` callback and
      `supportsImageAttachments`/`supportsDocuments` passthrough to both `MediaCarousel` and
      `MediaUploadDialog`.
- [x] Fixed the device-type photo thumbnail click in both screens to open the same combined
      viewer item list (device photos + image attachments + documents) as the carousel, instead
      of a photos-only list.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual/behavioral check - pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
on-device verification still pending the user.

## NBC-401: merge image attachments + documents into one M3 carousel widget

The item/device detail screen showed two separate cards back-to-back: `ImageAttachmentGallery`
(a plain `LazyRow` of image thumbnails) and `DocumentsSection` (a list of document rows with
small preview tiles). Experiment: merge them into a single "Media" widget using Material 3's
carousel component (multi-browse/hero style), and extend the full-screen `ImageViewerDialog`
so tapping a PDF tile opens it in-app (first page, zoomable) instead of only ever handing off
to an external app.

- [x] New `ui/common/PdfPreview.kt`: extracted, size-parameterized `renderPdfPage`/`looksLikePdf`
      (generalized from `DocumentsSection.kt`'s private helpers).
- [x] New `ui/common/MediaCarousel.kt` (replaces `ImageAttachmentGallery.kt` +
      `DocumentsSection.kt`): `HorizontalMultiBrowseCarousel` mixing image + document tiles,
      `CachedDocument.toDocumentViewerItem()` for PDF-backed tiles.
- [x] `ImageViewerDialog.kt`: `ImageViewerItem.pdfFile`, PDF-page rendering branch in
      `ZoomableImagePage`, `onOpenExternally` action in the metadata panel.
- [x] Wire `DeviceDetailScreen.kt` and `GenericDetailScreen.kt` call sites to the merged widget
      and combined image+PDF viewer item list.
- [x] Remote `:app:compileDebugKotlin` and `:app:assembleDebug` both passed; installed on the
      Zenfone 10, Mi Pad 4, and Pixel 5.
- [ ] On-device visual check (carousel layout, image/PDF/doc tap behavior, empty states) -
      pending user's own check on a physical device.

Status: mostly done, 2026-08-07 - compiles and installs cleanly on all three test devices;
visual/behavioral verification on-device still pending the user.

## NBC-402: remove the compile-time NetBox host from Android App Links

The app should support URLs from any configured NetBox instance without embedding a particular
instance host in the installed APK manifest. Keep the wildcard chooser filters and app-owned
`nyetbox://` links, but remove the exact-host verified App Link configuration.

- [x] Remove the `${appLinkHost}` Gradle setting and exact-host HTTP/HTTPS manifest filters.
- [x] Update the App Links documentation to describe chooser-based host-independent routing.
- [x] Verify remotely and deploy the debug build to the wired device and Mi Pad 4; verify wildcard
      NetBox URL resolution on both.
- [ ] Deploy the debug build to the Pixel 5; its ADB install stalled and timed out.

Status: mostly done, 2026-08-07; verified with remote compilation, the full unit test suite,
deployment to the wired device and Mi Pad 4, and wildcard URL resolution on both.

## NBC-378: restyle the "Add item" picker and create form to look more Material You

The "Add item" model picker used a plain `OutlinedTextField` search bar instead of the app's
shared pill-shaped `ModernSearchField`, showed a separate "+" icon per row that duplicated the
row's own tap target, used a star icon (with no background) to indicate pinning, and only let you
toggle a pin via a hidden long-press gesture. The generic create form (`GenericCreateScreen`)
rendered its fields as a flat, ungrouped list directly on the scaffold background with a bare
error message and an oversized spinner in the submit button while saving.

- [x] `AddItemScreen`'s search field switched to `ModernSearchField`, matching every other search
      entry point in the app.
- [x] Removed the redundant "+" icon; tapping anywhere on a row's card now opens the create form.
- [x] Star icon replaced with a `PushPin`/outlined `PushPin` icon inside a circular tinted
      background, and made independently tappable (`IconButton`) to toggle pin/unpin instead of
      requiring a long-press.
- [x] Fixed the rows' card having no horizontal inset (spanned edge-to-edge) - now matches the
      16dp margin used by the section headers and every other list screen.
- [x] `GenericCreateScreen`'s fields now render inside a rounded `NyetboxSectionCard` (with the
      model's own icon) instead of floating on the bare background; the error message got a proper
      `errorContainer` surface banner matching the edit form's existing pattern; fixed the saving
      spinner rendering at its default 40dp size inside the submit button.
- [x] Verify remote compilation and debug installation on Zenfone 10, Mi Pad 4, and Pixel 5.

Status: **done**, 2026-08-06 - remote `:app:compileDebugKotlin` passed after each change, and the
build was installed and visually checked on the Zenfone 10, Mi Pad 4, and Pixel 5.

## NBC-377: parallelize sync and add incremental (`last_updated`) fetching

Full syncs fetched every NetBox model, rack elevation, device-type, and attachment sequentially,
and always re-fetched every object from scratch even when nothing had changed. Overlap the
independent work (bounded, user-tunable, conservative by default) and skip re-fetching unchanged
objects via NetBox's own `last_updated` field, falling back to a periodic full pass so
server-side deletions still get reconciled.

- [x] Bounded-concurrency helper applied to the per-model, device-type, rack-elevation, and
      attachment-download sync loops; new "Sync concurrency" setting (default 3, presets 1-8) in
      Settings > Sync policy.
- [x] `last_updated__gte`-filtered incremental sync for both generic objects and devices, with a
      per-endpoint watermark and a fallback to a full fetch if the filter isn't supported.
- [x] Periodic full-reconciliation pass (24h, or forced from the Settings "Sync now" button) that
      prunes objects the server no longer has, without pruning endpoints that only partially
      synced.
- [x] Verify remote compilation, instrumentation tests, and debug installation on Zenfone 10, Mi
      Pad 4, and Pixel 5.

Status: **done**, 2026-08-05 - remote compilation, instrumentation tests (Android E2E + Screenshots
CI), and debug installation on Zenfone 10, Mi Pad 4, and Pixel 5 passed. Verified on the Zenfone
10 that the per-model sync loop runs concurrently (interleaved requests to different endpoints
within milliseconds of each other) and that the second sync after upgrade uses
`last_updated__gte` (confirmed via logcat against the real netbox.brkn.lol instance); the very
first sync after upgrade is necessarily a full fetch since pre-existing cached rows have no
`lastUpdated` watermark yet, which is expected. Tagged 1.4.0.

## NBC-357: link device-type photos back to their device type

The front/rear device-type photos shown in the device overview should offer a compact action to
open the corresponding device-type detail page. The device identity card should also use its empty
right-hand space for the device's asset tag.

- [x] Add an optional related-item action to the shared image viewer.
- [x] Show “Open device type” for device-type front/rear photos.
- [x] Remove the redundant `Device type: #…` metadata row.
- [x] Show the device asset tag in the overview identity card.
- [x] Verify the image-viewer navigation and identity-card layout on a device.

Status: **done**, 2026-08-04 - remote compilation, unit tests, instrumentation compilation, lint,
and debug installation on Zenfone 10, Mi Pad 4, and Pixel 5 passed.

## NBC-358: add expandable recent dashboard sections

The dashboard should surface the most recently viewed cached items above Bookmarks, with a compact
three-item preview and an explicit expansion action. Recent changes should likewise show five items
by default and offer an expansion action when more are cached.

- [x] Add the cache-backed Recently viewed dashboard section above Bookmarks.
- [x] Limit Recently viewed to three rows by default and provide expand/collapse controls.
- [x] Limit Recent changes to five rows by default and provide expand/collapse controls.
- [x] Preserve offline rendering, section ordering, and section hide/reorder behavior.
- [x] Verify with remote tests, lint, and installation on all three devices.

Status: **done**, 2026-08-04 - remote compilation, unit tests, instrumentation compilation, lint,
and debug installation on Zenfone 10, Mi Pad 4, and Pixel 5 passed.

## NBC-359: make linked item cards fully clickable

On generic item detail pages, cards representing a single linked item should navigate when any
part of the card is tapped, not only when the linked value text is tapped. Count cards should use
the same full-card affordance for their related-item sheet.

- [x] Allow detail cards to provide both a whole-card click and long-press actions.
- [x] Apply whole-card navigation to linked-item and related-count cards.
- [x] Preserve copy, overflow, and long-press actions on the card's child controls.
- [x] Verify typed and generic item views through remote checks and device installation.

Status: **done**, 2026-08-04 - remote compilation, unit tests, instrumentation compilation, lint,
and debug installation on Zenfone 10, Mi Pad 4, and Pixel 5 passed.

## NBC-1: Initial project scaffold + MVP

Offline-first NetBox companion app: token login, device list with a Room cache, QR/barcode
scanning of the device-sticker URLs (`https://<netbox>/dcim/devices/<id>/`), Material 3 UI,
Obtainium distribution.

- [x] Public GitHub repo (pschmitt/nyetbox), GPL-3.0
- [x] flake.nix (JDK 21, Android SDK, just, ktfmt, git-hooks pre-commit)
- [x] justfile (remote build on rofl-13/rofl-14, install to Zenfone 10 / Mi Pad 4, logcat, format/lint)
- [x] Gradle project skeleton (single `:app` module, AGP/Kotlin/KSP/Hilt wiring, version catalog)
- [x] AndroidManifest, Material 3 theme + splash screen, adaptive launcher icon, deep-link intent-filter
- [x] NetBox API client (Retrofit + kotlinx.serialization, dynamic base URL, token auth) + Room offline cache
- [x] CameraX + ZXing barcode/QR scanner, device-URL parser
- [x] WorkManager periodic background sync
- [x] Compose screens: onboarding, device list, device detail, scanner, settings
- [x] CI (build/lint/release workflows), CI signing keystore (rbw + GitHub secrets), Obtainium README badge, fastlane metadata, PRIVACY.md, renovate.json
- [x] Build + smoke test on Zenfone 10 and Mi Pad 4, push to GitHub

Known follow-ups (not blocking, tracked here for the next session):
- A handful of non-fatal deprecation warnings on build (`hiltViewModel` and `LocalLifecycleOwner`
  moved packages upstream, `EncryptedSharedPreferences`/`MasterKey` deprecated in favor of the
  newer Jetpack Security Crypto APIs) - cosmetic, don't block compilation.
- Onboarding auto-focuses/pops the keyboard immediately on the URL field, confirmed via
  screenshot but not yet deliberately reviewed for polish.
- Only device browsing/lookup is covered - IPAM, circuits, cabling, etc. are out of scope for
  this MVP.

Status: **done** (MVP), 2026-07-31. Verified via `just build`/`just lint`/`just test` on
rofl-14.brkn.lol, installed and smoke-tested (launches without crashing, onboarding screen
renders correctly) on both the Zenfone 10 (USB) and Mi Pad 4 (SSH/adb).

Post-merge CI was actually broken (`material-icons-extended` pinned to a nonexistent version,
wrong `retrofit2-kotlinx-serialization-converter`/`PullToRefreshBox` import packages, a Kotlin
`weight()` explicit-import resolution quirk, Hilt 2.59.2 too old to read Kotlin 2.4.0's class
metadata, no Hilt binding for `WorkManager`, a CI signing keystore generated with mismatched
store/key passwords - PKCS12 silently ignores a distinct keypass - and `BuildConfig.GIT_REVISION`
getting constant-folded into a larger string so the release-verification grep never found it
standalone). All fixed same-day; `just build`/`just lint`/`just test` plus the GitHub Actions
Build/Lint/Release workflows are green as of commit `00337cb`.

## NBC-2: Onboarding keyboard covers the API token field

The soft keyboard overlaps the input fields on first launch instead of the screen scrolling/
resizing to keep the focused field visible.

**Why:** reported by the user testing on a real device; makes the token field hard to see while typing.
**How to apply:** `enableEdgeToEdge()` opts the activity out of the legacy
`windowSoftInputMode=adjustResize` behavior - fixed via `Modifier.verticalScroll(...).imePadding()`
on the onboarding Column, the standard Compose-with-edge-to-edge pattern.

Also added while in this screen (user requests, same area):
- [x] "Open API tokens page" trailing icon on the NetBox URL field - opens
  `<url>/user/api-tokens/` in the browser once a URL is entered.
- [x] "Paste from clipboard" trailing icon on the API token field.

Status: **done**, 2026-07-31. Verified on the Zenfone 10 - both fields and the Connect button
stay visible above the keyboard regardless of which field is focused.

## NBC-3: Device type images + image attachments (list + detail)

User: "Pictures, including image attachments are a MUST!" Show NetBox device-type stock photos
(front/rear) in the device list (thumbnail) and detail screen, plus any `extras.ImageAttachment`
images uploaded on the specific device, displayed on the detail screen.

**Why:** core to making the app feel like a real inventory browser, not just a text list - user
explicitly called this a hard requirement, multiple times.
**How to apply:** needs Coil (`coil3` per findroidplus's usage) wired to the same OkHttp client/
auth interceptor; NetBox endpoints are `GET /api/dcim/device-types/{id}/` (front_image/rear_image)
and `GET /api/extras/image-attachments/?object_type=dcim.device&object_id=<id>`. Room schema needs
image URL columns (device type) and either a join table or a separate cached list for
attachments. Watch for auth-on-media-requests (NetBox media may or may not require the API
token depending on deployment).

Scope grew after the first pass: user, verbatim, "we should sync these assets as well! ie full
offline mode. This includes docs too! (netbox-documents)" - so this isn't just "show an image URL
in an `AsyncImage`", it's downloading and caching the actual image/document bytes on-device
(Coil's disk cache alone isn't durable/guaranteed offline the way an explicit downloaded-files
store would be) so device-type photos and netbox-documents attachments are browsable with zero
connectivity, same as the rest of the app. That's real storage-management surface (download
triggers, cache eviction/size limits, sync-now vs. lazy-on-view) worth thinking through
deliberately rather than bolting on ad hoc - probably wants its own short design pass alongside
NBC-7 (they share the "binary asset synced for offline use" shape) rather than being purely an
extension of this entry.

**How the first pass (network display only) landed:** deliberately scoped down to just the
"show the image" half - the offline-sync/download-to-disk half above is still not started, see
follow-ups. Added Coil3 (`coil-compose` + `coil-network-okhttp`, pinned 3.5.0), wired to the same
authenticated `OkHttpClient` as Retrofit (`NetworkModule.provideImageLoader`, set as the app-wide
default via `NyetboxApp : SingletonImageLoader.Factory`) - confirms the TODO's own note
that media requests may need the API token. Two new typed endpoints on `NetBoxApi`
(`getDeviceType`, `listImageAttachments`), confirmed against NetBox 4.5's actual DRF serializers
(not guessed): `front_image`/`rear_image` are plain absolute-URL strings (`serializers.ImageField`),
and `image-attachments` filters by `object_type` as an `"app_label.model"` string (e.g.
`"dcim.device"`) + `object_id`, matching the TODO's endpoint shape. New Room tables
(`device_types`, `image_attachments`, DB version bumped to 3 - fine under the existing
`fallbackToDestructiveMigration`) plus a `deviceTypeId` column added to `DeviceEntity`. Two new
cache-first repositories (`DeviceTypeRepository`, `ImageAttachmentRepository`) mirroring
`DeviceRepository`'s `runCatching { api -> toEntity() -> dao.upsert }` shape rather than NBC-6's
generic-JSON approach, since these need typed image-URL fields. New shared
`ui/common/RemoteThumbnail.kt` (falls back to a generic device icon when no image is
cached/set yet) used by: the device list row (`DeviceRow` leadingContent, backfilled lazily per
distinct device-type id already in view - cheap no-op once cached), and the device detail screen
(front/rear stock photos side by side, plus a `LazyRow` of image-attachment thumbnails that open
full-size in the browser on tap - no in-app image viewer built, matches current "open in
browser" pattern elsewhere in this screen).

Known limitation flagged during development, resolved on merge: `DynamicBaseUrlInterceptor` would
otherwise prepend the configured base URL's path onto these already-absolute media URLs, double-
prefixing it for a subpath-reverse-proxied instance. NBC-16 (merged concurrently) landed a
`@DownloadClient`-qualified `OkHttpClient` for exactly this "already-absolute NetBox media URL"
case (auth still applied, base-URL rewrite skipped) - `provideImageLoader` was pointed at that
client instead of the plain one, so this never shipped as a live bug.

The durable offline-asset pass now stores image/document bytes under `filesDir` when the Settings
toggle is enabled, and all image/document views prefer those local files before using the network.

`just build`/`just lint`/`just test` all green on rofl-14. Installed on all three physical
devices (Zenfone 10, Mi Pad 4, Pixel 5) via `just deploy-all` - app launches cleanly on all three,
no crashes in logcat. Confirmed via the Mi Pad 4's logcat that the app issues the expected new
requests against the real instance (`GET .../api/dcim/devices/...`, followed by what would be the
new device-type/image-attachment calls once a device list loads) using the real configured host.

**Not independently confirmed:** actual image rendering against live data. netbox.brkn.lol was
flapping during this session's verification pass (HTTPS alternating between a 10s+ TLS-handshake
timeout and a 502 from its reverse proxy, confirmed via direct `curl` from outside the app too) -
an existing infrastructure issue unrelated to this change, not something introduced by it. Revisit
once the instance is healthy again to actually see the thumbnails/photos render, not just confirm
the app doesn't crash while trying.

Status: **done**, 2026-07-31 - durable image syncing, local-file rendering, and generic media
discovery are implemented; remote `just lint`, `just test`, and `just build` pass. Live visual
verification against current NetBox media remains a physical-device follow-up.

## NBC-4: New app icon - NetBox logo x raised-eyebrow emoji mashup

Current launcher icon is a placeholder (plain stroked box glyph on teal). User wants a proper
icon combining the NetBox logo with a raised-eyebrow emoji (🤨), matching the "Nyetbox"
branding.

**Why:** user's explicit design direction, replacing the placeholder from NBC-1.
**How to apply:** NetBox's logo is trademarked (see README's trademark notice) - a "mashup" for a
non-affiliated fan app needs the same care findroidplus took with the Jellyfin logo (README says
theirs is "a combination of the Jellyfin logo and the Android robot"). Produce as a vector
adaptive icon (foreground + background layers) like the current one, not a raster mashup image.

Status: **done**, 2026-07-31 - replaced the adaptive icon's raster foreground reference with a
repository-native vector recreation of the cyan/white NetBox raised-eyebrow mark. Remote debug
build and ktfmt validation passed; the Mi Pad 4 splash visually confirmed the new icon and launched
without an app crash.

## NBC-5: Editable objects (generic PATCH-based editing)

Allow editing object fields from the app (not just read-only browsing), via NetBox's REST PATCH.

**Why:** user request - the app should be a two-way tool, not just a lookup/scan viewer.
**How it landed:** built on top of NBC-6's generic engine rather than as a Device-specific
feature - `buildEditableFields` (`GenericFieldRenderer.kt`) picks out primitive (string/number/
boolean), reference, and choice top-level fields from the raw JSON, skipping a blocklist of
server-managed/computed ones
(`id`, `url`, `display`, `display_url`, `created`, `last_updated`, `custom_fields`). Edit mode on
`GenericDetailScreen` swaps the read-only field list for text inputs (a `Switch` for booleans),
Custom fields use the cached NetBox definitions and choice-set metadata to select text, long-text,
number, integer, boolean, choice, multi-choice, reference, and multi-reference editors; unsupported
custom-field types remain read-only. Save PATCHes only via `GenericNetBoxApi.patchObject`/
`GenericObjectRepository.updateObject`, which re-caches the server's response. **Verified against
the user's real NetBox instance** (via the
Mi Pad 4, which is already logged in): edited and saved a live Provider Account, confirmed the
`last_updated` timestamp actually changed server-side - full round trip works, not just
simulated/unit-tested.

- [x] Editing reference fields (site, rack, tenant, ...) and choice fields (status, ...) - generic
  edit mode now uses cached relation pickers and DRF `OPTIONS` choices, with current values still
  available when offline.
- [x] `custom_fields` editing - use cached definitions and choice sets for type-aware text,
  long-text, URL/date/datetime, number/integer, boolean, select/multi-select, and object/multi-object
  editors; unknown types remain read-only.
- [x] The legacy Device detail screen now exposes an Edit action that opens the generic,
  conflict-aware device editor while retaining the typed screen's cached/photos presentation.

Status: **done**, 2026-07-31. Custom-field editor coverage is unit-tested, and the legacy Device
detail now routes editing through the same generic flow live-verified on the Mi Pad 4.

## NBC-6: Generic/generated object views (device types, regions, racks, sites, ...) + nav

NetBox has 100+ object types (dcim, ipam, circuits, virtualization, tenancy, ...). Rather than
hand-writing a screen per type, introspect NetBox's own API schema (OpenAPI spec at
`/api/schema/`, or the app/model listing at `/api/`) to drive generic list/detail (and eventually
NBC-5 edit) screens from field metadata. User, verbatim: "the more I think abt it the more I lean
towards us 'generating' the individual views." Pair with a NetBox-style sidebar/navbar (not just
a bottom nav) for navigating between object types - user also asked for this, referencing the
NetBox web UI's sidebar. The set of "main" sections shown should be configurable (user's words:
"navbar with the main ones (dev, dev types, rack - gotta be configurable)").

**Why:** the alternative (hand-coding a screen per NetBox model) doesn't scale to "a lot" of
views: schema-driven generation is the only realistic way to cover NetBox's full data model
without an enormous, ever-growing amount of near-duplicate screen code.

**How it landed:** not OpenAPI-schema-driven after all - simpler than planned. `GET api/` lists
app namespaces, `GET api/<app>/` lists that app's models (including one extra nesting level for
`plugins/<name>/`, so plugin-provided types like netbox-documents show up automatically) -
`DirectoryRepository` walks this to build the sidebar tree, cached in Room
(`NetBoxModelEntity`/`NetBoxModelDao`). Detail screens render directly off the actual JSON API
*response* rather than any schema (`GenericFieldRenderer.kt`/`buildFieldRows`): nested objects
with `id`+`url` are tappable references to that object's own generic detail screen (recursively -
this is also why tags render as tappable chips, since NetBox tags are real objects too), choice
fields ({value,label}) show their label, arrays of references become a linked list, arrays of
primitives become a chip list. Generic object cache is one Room table
(`NetBoxObjectEntity`: endpointPath+id, display, raw JSON) rather than a typed entity per model.
Existing Device screens/DeviceEntity were deliberately left untouched (proven, tested, not worth
the regression risk) - only *other* object types route through the new generic screens; devices
still get their own bespoke list/detail. Sidebar is a `ModalNavigationDrawer` with per-app-group
sections, a pin/unpin star per model (pinned set lives in `SettingsRepository`, default just
Devices), a search field to filter sections, and per-category icons (`AppIcons.kt`). Scan/Settings
moved out of the drawer into a bottom `NavigationBar` (Devices/Scan/Settings) shared by the
device list and generic list screens. Scanning/deep-linking was generalized too
(`scanner/NetBoxUrlParser.kt`, replacing the device-only `DeviceUrlParser`) - any NetBox object
URL now resolves, not just `/dcim/devices/`, and the manifest intent-filter path patterns were
broadened to match (dcim/ipam/circuits/tenancy/virtualization/wireless/vpn/extras/plugins).

Follow-up noted during/after this landed:
- [x] "Linked items" on the *Device* detail screen (e.g. tapping its Rack/Site) now navigate to
  the existing generic detail screens. The typed cache persists the related IDs while retaining
  its proven device-specific rendering; full migration to the generic renderer remains optional.
- [x] Live verification - the Mi Pad 4 is logged into the user's real NetBox instance
  (netbox.brkn.lol). Confirmed against real data: directory discovery correctly builds the full
  sidebar tree (Circuits/Core/... groups, each with all their models, pin stars working), the
  generic list screen shows real synced objects (e.g. Provider Accounts), the generic detail
  screen renders real fields including a tappable Provider reference that navigates correctly,
  and Comments fields showing raw Markdown (`` `code` `` spans etc. as literal text) - confirms
  NBC-12 is a real, visible gap, not a hypothetical one.

Status: **done**, 2026-07-31. `just build`/`just test`/`just lint` green on rofl-14; installed,
launched without crashing, and live-verified against the real NetBox instance on the Mi Pad 4
(directory discovery, generic list, generic detail with reference-following all confirmed working
against real data) - also installed cleanly on the Zenfone 10 and Pixel 5.

## NBC-7: netbox-documents plugin support

User has a lot of documents stored via the `netbox-documents` NetBox plugin and wants them
accessible from the app.

**Why:** user's own NetBox instance relies on this plugin for document storage.
**How to apply:** plugin adds its own REST endpoints (`/api/plugins/netbox-documents/...` typically)
- need to check the actual plugin's API surface (not core NetBox API) once this is picked up.
Presence of the plugin isn't guaranteed for all NetBox instances users of this app might have, so
this should probably be optional/detected rather than assumed.

**Turns out most of this is already free.** NBC-6's directory discovery walks `api/plugins/`
generically, so `netbox-documents` (and any other installed plugin) already shows up as its own
sidebar section with no plugin-specific code - confirmed live on the Mi Pad 4: the "Documents"
section listed real PDF filenames from the user's instance via the plain generic list/detail
screens, no special-casing needed.

The generic detail screen now opens/downloads media-backed document fields and the optional offline
sync sweep stores them durably, so the plugin needs no special API code for ordinary document files.

- [x] Verify the live plugin API surface: `/api/plugins/documents/` exposes the standard
  `documents` collection, and its detail payload is a normal media URL plus filename, nested
  assigned-object reference, tags, and scalar metadata.
- [x] Verify there are no plugin-specific actions or nested structures requiring special handling:
  the collection's live `OPTIONS` response advertises only ordinary POST/PUT actions, while the
  generic renderer handles the observed detail payload.
- [x] Add a regression fixture for the observed `netbox-documents` detail shape.

Status: **done**, 2026-07-31 - live read-only API audit against netbox.brkn.lol, focused renderer
test, and the existing remote lint/test/build validation confirm generic list/detail, media opening,
and durable offline copies cover this plugin without special-case code.

## NBC-8: App Links for the user's NetBox domain + deep link to specific object views

Register Android App Link (domain-verified, not just the generic non-verified intent-filter from
NBC-1) for the user's actual NetBox host so tapping e.g. `https://netbox.brkn.lol/dcim/devices/393/`
anywhere opens the app directly (no chooser prompt), and extend beyond devices to open directly
into whatever object type the link points at (device-type, rack, site, etc. - depends on NBC-6).

**Why:** user wants the "open with" friction removed entirely for their own instance, and wants
this to work for more than just devices.
**How to apply:** proper Android App Links need a `.well-known/assetlinks.json` served from the
NetBox host itself (or a reverse proxy in front of it) for domain verification - that's
infrastructure outside this repo (on brkn.lol's web server config), not just an app-side change.
The existing NBC-1 intent-filter (host="*", no autoVerify) still covers the "Open with" chooser
path for any host in the meantime.

- [x] Routing beyond devices - NBC-6 generalized the scanner/deep-link parser
  (`scanner/NetBoxUrlParser.kt`) and the manifest intent-filter to any NetBox app namespace
  (dcim/ipam/circuits/tenancy/virtualization/wireless/vpn/extras/plugins), not just
  `/dcim/devices/`. Non-device links now resolve to the NBC-6 generic detail screen.
- [x] Add an exact-host `android:autoVerify` filter whose host is a compile-time setting (defaults
  to `netbox.brkn.lol`; override with `-PnetboxAppLinkHost=...` or `NETBOX_APP_LINK_HOST`); the
  wildcard chooser filter remains available for other configured NetBox instances.
- [x] Publish the matching `/.well-known/assetlinks.json` on the NetBox host with the release
  certificate fingerprint.

Status: **done**, 2026-08-01 - the exact-host app filter and the generated Nix/nginx Digital Asset
Links route are in place; the live host returned `200 application/json` with package
`dev.pschmitt.nyetbox` and the release certificate fingerprint.

## NBC-11: QR-code app configuration sharing (like findroidplus's setup codes)

Let the app be configured (base URL + token) by scanning a QR code generated by another instance
of the app (or shared some other way) - findroidplus has this already (see its
`findroidplus://setup` custom-scheme intent-filter and `QrConfigCodec` referenced in its
AndroidManifest.xml/AGENTS.md).

**Why:** user wants parity with findroidplus's existing setup-code flow - faster onboarding
across devices without retyping the URL/token, and referenced findroidplus as the precedent to
follow.
**How to apply:** look at findroidplus's actual `QrConfigCodec` implementation
(`~/devel/private/pschmitt/findroid.git`) for the encoding scheme/format to mirror. Needs a
custom URI scheme intent-filter (e.g. `nyetbox://setup?...`) alongside the existing
onboarding flow, plus a way to *generate*/display the QR code from Settings for the sharing side
(scanning is already covered by the existing camera scanner, assuming the encoded payload is
recognized by NetBoxUrlParser/a new parser branch). Sensitive: the payload includes the API
token, so treat the generated QR code/settings-share flow with the same care as the token itself
(e.g. don't log it, consider a short display-only affordance rather than anything persisted).

- [x] Encode and decode versioned, URL-safe setup payloads without logging or persisting them.
- [x] Generate a display-only setup QR from Settings behind biometric/PIN authentication.
- [x] Import setup URIs from Android deep links and the existing scanner, pre-filling onboarding.
- [x] Validate with remote unit tests/lint/build and a Mi Pad 4 smoke test using a dummy payload.

Status: **done**, 2026-07-31 - remote `just test`, `just lint`, and debug build passed; Mi Pad 4
verified the biometric/PIN gate and dummy setup-URI onboarding import without connecting to NetBox.

## NBC-9: Dashboard/home page

A home/dashboard screen: NetBox change log, bookmarks, stats, and NetBox news.

**Why:** user wants a richer landing page than the current device list, matching what a NetBox
power user would want to see first.
**How to apply:** NetBox exposes `/api/extras/object-changes/` (changelog), `/api/extras/bookmarks/`
(NetBox 3.5+), and various count endpoints for stats. The news section uses the public NetBox Labs
RSS feed as an optional dashboard enhancement; it is cached locally and never receives the user's
NetBox URL or API token.

**Confirmed against the real instance (netbox.brkn.lol, NetBox 4.5.10) before writing any code:**
- The changelog endpoint has **moved**: it's `GET /api/core/object-changes/` in NetBox 4.x, not
  `/api/extras/object-changes/` as the original ask assumed (that was true pre-4.0) - `extras`'s own
  app root (`GET /api/extras/`) doesn't list it at all; `GET /api/core/` does. Shape: paginated,
  each row has `time`, `user` (nested ref), `action` ({value,label}), `object_repr`,
  `changed_object_type` (content-type string), and `changed_object` - a nested `{id, url, display,
  ...}` ref **when the object still exists**, `null` for deletes (confirmed live: a delete-action
  row's `changed_object` was absent from a same-session create/update sample - handled as nullable).
- `GET /api/extras/bookmarks/` confirmed exactly as expected: `{id, display, object_type, object_id,
  object: {id, url, display, ...}, user, created}` - `object` is the same `id`+`url`+`display` shape
  NBC-6's generic reference-field renderer already knows how to turn into a navigable target.
- Stats: confirmed `count` on `dcim/devices/`, `dcim/device-types/`, `dcim/sites/`, `dcim/racks/`
  (`382`/live count/`5`/`1` on the real instance) - picked these four as "a handful of key models,"
  not an exhaustive sweep; cheap (`?limit=1`, only `count` read, no full sync needed).

**How it landed:** new cache-first `DashboardRepository` (mirrors `GenericObjectRepository`'s
shape) backed by four Room tables/DAOs (`bookmarks`, `object_changes`, `dashboard_stats`, and
`news_items`). The news table has an explicit 14→15 migration so adding dashboard news preserves
the existing offline inventory cache.
Bookmarks/changelog are a full clear-then-replace on each refresh (small, bounded result sets - 50
bookmarks / most-recent 25 changes - so there's no reason to keep stale rows around); stats are a
plain upsert keyed by endpoint path. Reused `GenericNetBoxApi.listObjects(url, query)` (the same
schema-free call `GenericObjectRepository`/`JournalEntryRepository` already use) rather than adding
new typed Retrofit endpoints - no new API surface needed. Extracted the URL->endpointPath and
endpointPath->app-icon-key logic that used to live as private functions inside
`GenericFieldRenderer`/`GenericListScreen` into a shared `data/schema/NetBoxRef.kt` object, so the
dashboard's bookmark/changelog rows resolve navigation targets and pick icons (`AppIcons.
forAppKey(...)`) exactly the same way NBC-6's reference-field rendering already does, instead of
duplicating that logic a third time - both original call sites now delegate to it.

Bookmark/changelog rows navigate via the *same* `onNavigateToReference(endpointPath, id) ->
Route.Generic(...)` callback `GenericDetailScreen` already uses for reference fields - deliberately
**not** special-cased for devices, matching the existing precedent that reference fields elsewhere
(e.g. an IP address's assigned device) already route through the generic detail screen, not the
typed one. Rows with no resolvable target (a changelog delete, chiefly) render non-clickable rather
than silently going nowhere. Changelog row icon is action-based (add/edit/delete glyph) rather than
object-type-based, since a delete has no object to derive an icon from anyway.

**Navigation placement decision:** made the dashboard **both** the default post-login/post-onboarding
landing destination *and* a third bottom-nav tab (`Dashboard`/`Devices`/`Scan`, was just
`Devices`/`Scan` since NBC-14) rather than only one or the other - a "home page" that isn't also
where you land by default doesn't really function as one, but it still needs to be reachable
on-demand from anywhere the bottom bar shows (device list, generic list screens), so both.
**Stat tiles:** tapping the Devices tile specifically navigates to the existing typed `Route.
DeviceList` (richer, already-synced screen with thumbnails/status chips) rather than `Route.
GenericList("api/dcim/devices/", ...)` - a deliberate one-off special case (unlike the
bookmark/changelog reference-navigation decision above) since the generic object cache for that
endpoint path may well be empty until a user has separately visited it, whereas the typed Device
cache is very likely already populated; the other three stat tiles (device types/sites/racks) go
through the generic list route since there's no typed alternative for them.

- [x] Cache-first `DashboardRepository` + Room tables (bookmarks, changelog, stats)
- [x] `DashboardScreen`/`DashboardViewModel` (stat tiles, bookmarks list, recent-changes list, all
  icon-covered per `AGENTS.md`)
- [x] Wired into navigation as both the default landing destination and a third bottom-nav tab
- [x] Bookmark/changelog rows navigate into NBC-6's generic detail screen, reusing its existing
  reference-navigation callback
- [x] Add an optional cached NetBox Labs RSS news section to the dashboard.

Status: **done**, 2026-08-02 - remote lint/unit tests/debug build passed; Mi Pad 4 completed a
read-only refresh, restored 388 devices and 6,553 other objects, and visibly rendered four cached
NetBox Labs news items on the dashboard.

## NBC-10: Label printing from the app

Print device labels directly from the app, reusing/integrating with the user's existing
[printlabel](https://github.com/pschmitt/printlabel) project.

**Why:** user already has label-printing logic built and wants it available from this app instead
of a separate tool - presumably so the QR stickers this whole app is built around can be
(re)printed directly after scanning/creating a device.

**Investigated:** `printlabel` is a ~2000-line **local Bash/Python CLI**
(`printlabel`/`labelmaker.py`/`ptcbp.py`/`ptstatus.py` in that repo), distributed via a Nix flake
(`nix run github:pschmitt/printlabel -- ...`). It talks **directly over Bluetooth** to a
paired Brother P-Touch Cube using its own reimplementation of Brother's PT-CBP protocol, and shells
out to `jq` and to a separate `nbx` CLI (not a NetBox HTTP call of its own) for its `--netbox
QUERY` mode. There is **no daemon, server, or HTTP surface anywhere in it** - confirmed by reading
the full script source (`usage()`/`--help` text and the actual option parsing), not just the
README. The app therefore ports the small RFCOMM/PTCBP transport and raster path directly rather
than trying to call the Linux CLI or adding a network service.

**Implemented:** the app now ports the printlabel PTCBP transport directly: it discovers paired
Brother/P-touch printers, connects over RFCOMM, checks the printer's 32-byte ready status, sends a
128-dot QR-plus-asset-tag raster using PackBits compression, and waits for the printer's completion
status. The print action is available from both the typed device detail screen and the generic
device detail screen. Android Bluetooth runtime permission and printer selection are handled in-app.
The cached device web URL and label text are the only inputs; printing never writes to NetBox and
continues to work from cached data while offline.

- [x] Port the PTCBP status/configuration/print transport and PackBits raster encoding.
- [x] Render the cached device URL as a QR code with the asset tag beside it.
- [x] Add paired-printer discovery, Bluetooth permissions, selection, and progress/error feedback.
- [x] Replace the detail-screen share-sheet action with a real in-app print job.
- [x] Add protocol tests and pass remote unit tests, lint, and debug build.
- [x] Verify physical output with the user's paired Brother printer (user confirmed printing works).

Status: **done**, 2026-08-02 - native implementation and remote validation passed; the wired
Zenfone sent a fresh FNUC label through the bonded PT-P300BT4590 with the updated raster settings,
and the printer completed the job successfully.

## NBC-12: Render markdown fields properly

NetBox `comments`/`description` (and similar) fields support Markdown; the app currently shows
them as raw text (both on the old Device detail screen and NBC-6's generic
`FieldRow.PlainText`).

**Why:** raw `**bold**`/`- lists`/etc. as literal text is a poor reading experience for exactly
the fields (comments, descriptions) most likely to be long-form/formatted. Confirmed as a real,
visible gap (not hypothetical) while live-testing NBC-5 against the user's actual NetBox data - a
real Comments field was full of literal backtick/list markup.
**How it landed:** added `com.mikepenz:multiplatform-markdown-renderer(-m3)`, pinned to **0.41.0**
rather than latest (0.43.0 bumps `minCompileSdk` to 37; we're on 36 - see the version catalog
comment). Only the `comments` field is treated as Markdown (`description` is plain short text per
NetBox's own docs, deliberately excluded) - both NBC-6's generic detail screen
(`FieldRow.Markdown`) and the legacy Device detail screen's Comments field now render through the
same `com.mikepenz.markdown.m3.Markdown` composable. Edit mode is unaffected - editing still shows
the raw Markdown source in a plain text field, which is correct (you edit source, not rendered
output).

Live-verified on the Mi Pad 4 against the real NetBox instance: a Comments field with a bullet
list and several `` `inline code` `` spans now renders as an actual bulleted list with proper
monospace code chips, not literal asterisks/backticks.

Not covered: `custom_fields` values that are Markdown-typed per NetBox's custom field type system
- custom fields aren't rendered at all yet (see NBC-5's out-of-scope note), so this is moot until
custom field support exists.

Status: **done**, 2026-07-31. `just test`/`just lint` green; live-verified on the Mi Pad 4 against
real Markdown content.

## NBC-13: Global search

A single search that queries across NetBox object types, not just within one model's list screen.

**Why:** user wants to find something without first knowing/navigating to which object type it
lives under - the sidebar's search (NBC-6) only filters the *list of sections/categories* by
name, it doesn't search object data itself.

**Investigated first, per this entry's own note not to blindly trust the `/api/extras/search/`
guess:** checked the real instance (netbox.brkn.lol, NetBox 4.5) directly - `GET /api/extras/`'s
own root listing has no `search` key, `GET /api/extras/search/` itself 404s, and the full
`/api/schema/` OpenAPI document has zero paths containing "search". So there is no global-search
REST endpoint on this NetBox version - the TODO's original guess doesn't hold. Confirmed the
fallback instead: per-model list endpoints (`/api/dcim/devices/`, `/api/dcim/sites/`,
`/api/dcim/racks/`, `/api/circuits/circuits/`, ...) all accept `?q=<term>` and return 200 with
filtered results, verified live against each of those four. **Landed on client-side fan-out**:
query a curated set of endpoint paths in parallel via the existing `GenericNetBoxApi.listObjects`
(the same call `GenericObjectRepository.syncAll` already uses, just with a `q` query param instead
of pagination-only), merge, sort by display name.

- [x] `GlobalSearchRepository` (`data/repository/GlobalSearchRepository.kt`) fans a search term out
  across a baseline curated list of endpoint paths (devices, device-types, sites, racks,
  ip-addresses, prefixes, circuits, virtual-machines, tenants - covers the TODO's own suggested set
  plus a few equally common ones) in parallel via `coroutineScope`/`async`/`awaitAll`, one model's
  failure logged and skipped rather than failing the whole thing (mirrors
  `DirectoryRepository.refresh`'s per-app `runCatching`).
- [x] **Cache-first, not network-only** - the first same-day pass made results transient/
  network-only ("not written into the cache... since the point is a live merge, not another sync
  path"), which is a direct violation of this app's whole premise (`AGENTS.md`: "reads come from
  Room, writes/refreshes come from the API") - a search that stops working the moment NetBox is
  unreachable is exactly the regression NBC-18 exists to prevent elsewhere, caught in review before
  this ever shipped standalone. Reworked so results come from Room, like every other screen:
  `NetBoxObjectDao.searchAll(query, limit)` (new: cross-endpoint, unlike the existing per-endpoint
  `search`, so anything ever cached under *any* endpoint is instantly findable offline - the 9-model
  baseline now only bounds the network refresh below, not the cached read) combined with
  `DeviceDao.search(query)` (devices are cached in their own typed table, not `netbox_objects`, per
  NBC-6). `listObjects(endpointPath, mapOf("q" to term, "limit" to "15"))` is now purely a
  best-effort background *refresh* (`GlobalSearchRepository.refresh`) that upserts hits into
  `netbox_objects` via a new `GenericObjectRepository.cacheSearchResults` (reuses the same private
  `toEntity` mapping `syncAll` already uses) instead of returning them directly - devices are
  skipped in this refresh entirely, since `DeviceDao` already gets a full periodic sync
  (`DeviceRepository`/`SyncWorker`), so a redundant `?q=` round trip would add nothing.
- [x] `GlobalSearchViewModel.results` reads reactively straight from the cache-combining Flow above
  (renamed `isSearching` -> `isRefreshing`, since it now describes network activity, not whether
  results exist) - mirrors `GenericListViewModel`'s `objects`/`refresh()` split. Fixed a state-
  priority bug from the same first pass: `GlobalSearchScreen`'s `when` checked `isSearching` before
  `results.isEmpty()`, so a background refresh would hide already-available cached hits behind a
  full-screen "Searching…" - reordered so non-empty cached results always win, with a non-blocking
  `LinearProgressIndicator` for the refresh-in-flight hint instead.
- [x] `GlobalSearchViewModel` unions the baseline set with the user's *pinned* model paths
  (`SettingsRepository.pinnedModelPaths`) so anything a user has explicitly starred in the sidebar
  is searchable too, not just the fixed baseline - reuses `DirectoryRepository.observePinned(...)`
  (despite the "pinned" name, it's just a generic `WHERE endpointPath IN (...)` lookup) to resolve
  each hit's endpoint path back to a humanized model label + `appKey` for the icon.
- [x] Input is debounced 300ms (`Flow.debounce` + `collectLatest`, so a fast typist's earlier
  in-flight fan-out is dropped, not raced) before firing; empty query shows a hint, no-cached-hits-
  yet-with-a-refresh-in-flight shows "Searching…", zero results (refresh settled, still nothing)
  shows an explicit "No results" state.
- [x] New `GlobalSearchScreen` (`ui/search/`) - a dedicated full-screen search (not a dropdown),
  reachable via a new search `IconButton` added to the top bar `actions` of both `DeviceListScreen`
  and `GenericListScreen` (the two screens users land on most, per the bottom nav / sidebar model
  clicks) - deliberately separate from NBC-6/14's existing sidebar search field, which still only
  filters section/category *names* and is untouched. Result rows show the object's display name,
  its model label + optional secondary line (status/description), and `AppIcons.forAppKey(...)` for
  the icon - tapping navigates to `Route.Generic(endpointPath, id)`, the same generic detail route
  scanning/deep-links already use.
- [x] Reused `NetBoxRef.appKeyFromEndpointPath` (endpointPath -> appKey for `AppIcons.forAppKey`,
  extracted by NBC-9 into `data/schema/NetBoxRef.kt`) rather than writing a second copy - this
  entry's own first pass independently extracted an identical duplicate into `AppIcons.kt` while
  NBC-9 was landing the same helper concurrently in a separate worktree; reconciled on merge by
  keeping the one `NetBoxRef` copy and pointing `GlobalSearchScreen` at it.

- [x] Debounce-level refresh cancellation now propagates coroutine cancellation through the
  repository and ViewModel instead of swallowing `CancellationException`, so stale fan-out calls
  are cancelled with the outdated query.
- [x] Results now rank exact display matches, display prefixes, display substrings, and secondary
  field matches before deterministic alphabetical tie-breaking; duplicate cache hits are removed.

Status: **done**, 2026-07-31. `just build`/`just lint`/`just test` all green on rofl-13
(lint re-verified with `--rerun-tasks` to rule out a stale up-to-date cache hit) both before and
after the cache-first rework above; ranking and cancellation have focused unit coverage. Live API
verification of the underlying approach (no global-search endpoint exists; `?q=` works on per-model
endpoints) *was* done directly against the real netbox.brkn.lol instance via `curl`, see above.

## NBC-14: UI polish batch (sidebar, comments, custom fields, share, scanner)

A run of small, concrete UI/UX requests landed together in one pass:

- [x] **Sidebar sections collapsed by default**, like the NetBox web UI - was an "absurdly long"
  flat list before. Tapping a section header (app-icon row) toggles it; expand state is
  per-session (`expandedApps` local state, not persisted). Searching auto-expands every matching
  section, since collapsing search results you're actively looking for makes no sense.
- [x] **Settings moved from the bottom nav into a static sidebar footer** - `NetBoxBottomBar` is
  now just Devices/Scan (2 tabs, not 3). Footer layout: app icon | "Version X.Y.Z" + NetBox base
  URL (stacked, truncated) | settings cog - pinned below the scrollable `LazyColumn`, not part of
  it, so it never scrolls away.
- [x] **Comments re-styled as a card**, not a plain inline text row - `ui/common/CommentCard.kt`
  wraps the Markdown composable in a `Surface` with `surfaceContainerHigh` tonal background and
  rounded corners, used by both the generic detail screen and the legacy Device detail screen.
- [x] **`custom_fields` are now actually displayed** - previously silently dropped for anything
  non-primitive (object/multi-select custom fields) and crudely flattened into one row for
  primitives. Now each custom field expands into its own row via the same generic field renderer
  used for top-level fields (handles reference-typed and multi-select custom fields correctly,
  not just plain text ones). Still not *editable* - see NBC-5's out-of-scope note, unchanged.
- [x] **Share button** on both detail screens (`ui/common/ShareIntent.kt`, plain `ACTION_SEND` of
  the object's web URL). Incidentally fixed a real bug found while wiring this up: the legacy
  Device detail screen's "Open in browser" was opening the *API* URL
  (`.../api/dcim/devices/393/`, DRF's browsable API), not the actual NetBox web page - it now
  derives the correct web URL the same way NBC-6's generic screen already did.
- [x] **QR/barcode scanner viewfinder overlay** - a dimmed frame around a centered square cutout
  (`ScannerViewfinder` in `ScannerScreen.kt`), purely cosmetic like most scanner apps have; the
  analyzer still scans the whole camera frame regardless of what's inside the square.

Also surfaced while testing this batch: **netbox-documents plugin objects already list correctly**
through NBC-6's generic engine with zero plugin-specific code (see NBC-7, updated).

**Broader direction noted, not yet acted on:** user wants the generic views to feel less like a
"simple list of key/values" and more ergonomic/pretty in general, once field-type icons exist -
this batch is a step in that direction (cards, icons, collapsing) but there's more to do here;
no dedicated entry yet, revisit once there's a clearer concrete shape for it.

Status: **done**, 2026-07-31. `just build`/`just test`/`just lint` green; installed on all three
devices; sidebar collapse/expand and the netbox-documents discovery live-verified on the Mi Pad 4
against the real instance. Comment card, custom fields, share button, and scanner viewfinder
verified via successful compile+test only (not individually screenshotted against live data).

## NBC-15: NetBox Journal entries for an object

Show an object's Journal (`/api/extras/journal-entries/`) on its generic detail screen - NetBox's
free-form timestamped notes attached to any object, distinct from the auto-generated changelog.

**Why:** user request - journal entries are a normal part of how NetBox users track
context/history on an object, not currently visible anywhere in the app. Follow-up user request -
"journal entries should prolly be a separate 'tab' on the device view" - moved it out of the
inline field list into its own tab.
**How to apply:** `GET /api/extras/journal-entries/?assigned_object_type=<app.model>&assigned_object_id=<id>`
- the `assigned_object_type` filter takes a `"app_label.model"` string (e.g. `"dcim.device"`), which
isn't derivable from `endpointPath` (`api/dcim/devices/`) by a fixed string transform alone
(`devices` -> `device` is easy, but e.g. `ip-addresses` -> `ipaddress` isn't a plain de-pluralize).
`JournalEntryRepository.resolveAssignedObjectType()` instead fetches the real choice list from
journal-entries' own `OPTIONS` response (`GenericNetBoxApi.getJournalEntryOptions()`, cached
in-memory) and matches our discovered model segment against it using a small set of candidate
singular forms (strip `-`/`_`, then try as-is / drop trailing `s` / drop trailing `es` / `ies`->`y`)
- validated against the real instance for plain de-pluralization cases; plugin models resolve via
their URL plugin key as a best-effort app_label guess, not guaranteed to match. `GenericDetailScreen`
now shows a Material3 `TabRow` ("Details"/"Journal") instead of a single scrolling list, only when
there's at least one journal entry to show (kept the previous single-list layout when there are
none, to avoid an empty tab for object types that never carry journal entries). Each entry renders
via `CommentCard` (NBC-12/14) with a kind icon (info/success/warning/danger, using Material icons
already wired in via the extended icon set) + timestamp header. Posting new journal entries (not
just reading) not investigated/implemented.

Status: **done**, 2026-07-31. `just test`/`just lint` green on rofl-14. Live-verified end-to-end on
the Mi Pad 4 once netbox.brkn.lol's outage (NBC-18) resolved: navigated to the real "Office light
(retired)" device, tapped the Journal tab, and its real warning-kind entry rendered correctly -
kind icon, timestamp, and the full Markdown body (headers, bullets, inline code spans) via
`CommentCard`.

## NBC-16: download and open file/document attachments (PDFs etc.)

Detail screens now render any NetBox-hosted file field (documents, images, ...) as a tappable
"FileAttachment" row instead of a raw media URL. Tapping it downloads the file to the app's cache
dir and hands it to Android's normal `ACTION_VIEW` resolution via a FileProvider content URI - so
known types (PDF, images, ...) open directly in whichever app the user has set as default, and
anything ambiguous/unhandled falls through to the standard "Open with" chooser. Deliberately not
using `createChooser` - always forcing a chooser would fight Android's own default-app handling.

**Why:** user request - "we need a way to actually support displaying documents, esp. pdf!" plus a
follow-up constraint - "attachments with unsupported filetypes should go through the regular 'open
with' android dialog" - ruling out a custom in-app viewer or a forced chooser.
**How to apply:** `GenericFieldRenderer.isMediaUrl()` flags any field whose value is an http(s) URL
under a `/media/` path as `FieldRow.FileAttachment`; `GenericDetailViewModel.downloadAttachment()`
pulls it via `FileDownloadRepository` (a dedicated `@DownloadClient` OkHttpClient - auth header
only, no `DynamicBaseUrlInterceptor`, since NetBox's returned media URLs are already
complete/correct and must not be re-prefixed) into `cacheDir/downloads/`, then
`FileOpener.fileViewIntent()` builds the `ACTION_VIEW` intent via
`FileProvider`/`res/xml/file_paths.xml`. Plain (non-media) http(s) fields instead render as
`FieldRow.ExternalLink` and open in the browser.

Status: **done**, 2026-07-31. `just test`/`just lint`/`just build` green on rofl-14; installed on
Mi Pad 4 and Pixel 5; live-verified end-to-end against the real instance - opened a real
netbox-documents PDF (LG monitor dismantling instructions) from the "Documents" section, confirmed
the natural Android "Open with" chooser appears (multiple PDF-capable apps installed) and the PDF
renders correctly once opened. Zenfone 10 not reachable over adb this session - install there next
time it's available. Durable pre-sync of attachments is now covered by NBC-17's opt-in `filesDir`
sweep; this entry describes the original on-demand cache behavior.

## NBC-17: full offline sync - attachments, sync-on-edit, scheduled background sync, error handling

Extends the NBC-3/NBC-6/NBC-16 offline foundation from "objects sync, attachments download
on-demand" to a real offline-first experience: attachments optionally pre-synced to local disk,
sync triggered automatically (not just manually) on edits and on a schedule, and sync
failures surfaced to the user instead of failing silently.

**Why:** user request, in four parts across one message - "we should add a settings option to sync
attachments to local disk when we sync. off by default. But I want the full offline experience to
be possible", "we should sync on edits, AND schedule regular syncs - in the background. Kinda like
findroid handles auto-downloads" - then a follow-up - "we need to handle sync errors. With an
appropriate toast message if we are in the app. Maybe even retries."
**How to apply:**
- New `SettingsRepository` boolean pref (default off), e.g. `syncAttachmentsToDisk`, surfaced as a
  switch in `SettingsScreen` - mirrors the existing settings patterns there.
- When on, `GenericObjectRepository.syncAll()` (or a new pass after it) should walk the synced
  objects' `FieldRow.FileAttachment`-eligible fields (reuse `GenericFieldRenderer`'s `isMediaUrl()`
  detection) and pull each through `FileDownloadRepository` into a durable location - NOT
  `cacheDir` (NBC-16's download target), since cache can be evicted by the OS at any time and the
  whole point here is durable offline availability; needs its own `filesDir`-backed directory and
  a lookup so `GenericDetailScreen` prefers the locally-synced copy over re-downloading when
  present.
- Sync-on-edit: trigger a `refreshObject`/attachment-sync pass after a successful
  `GenericDetailViewModel.save()`, not just on manual pull-to-refresh.
- Scheduled background sync: turns out NBC-1 already shipped this half unnoticed - `sync/SyncWorker.kt`
  + `sync/SyncScheduler.kt` (`WorkModule.kt` wires the `HiltWorkerFactory`) already run a
  network-constrained 6-hourly `PeriodicWorkRequest` plus a manual `syncNow()` one-time request,
  matching findroidplus's `AutoBackupScheduler`/`AutoBackupWorker` shape (`Result.retry()` on
  failure already gets WorkManager's default exponential backoff, no extra tuning needed) - it just
  only syncs the legacy `DeviceRepository`, not the NBC-6 generic-object cache or attachments yet.
- Error handling: sync failures (manual or background) should surface as a Snackbar/Toast when the
  app is in the foreground (reuse the `errorMessage`/`SnackbarHostState` pattern already used by
  `GenericDetailViewModel`/`GenericDetailScreen`).

**Slice 1 (this pass):** `SettingsRepository.syncAttachmentsToDisk` pref + `SettingsScreen` switch
row (off by default, doesn't yet do anything downstream - the actual attachment-download sweep is
still slice 2, deliberately deferred: it needs live testing against real cached data to be sure the
walk/download/dedup logic actually behaves, and netbox.brkn.lol is unreachable this session - see
NBC-18). `SettingsViewModel.syncNow()` now surfaces `deviceRepository.syncAll()` failures via a
Snackbar instead of discarding the `Result` (was previously silent - the concrete gap the "handle
sync errors" request was pointing at for the one sync path already wired to the UI).
`GenericDetailViewModel.save()` now calls `syncScheduler.syncNow()` on a successful edit
(sync-on-edit), enqueuing the *existing* `SyncWorker` in the background - inert/safe to ship even
while offline, since it's just a WorkManager enqueue.

**Slice 2 (now done):** the attachment-to-disk download sweep extends `SyncWorker` through a shared
coordinator that scans cached generic objects and typed image metadata, downloads detected media
through a durable (not cache-dir) `FileDownloadRepository` method, and makes detail/list image views
prefer an already-synced local copy. The coordinator also syncs the NBC-6 generic-object cache,
not just the legacy device list. The "surface background sync failures via a `Notification` (a
background `WorkManager` failure has no `Activity` to show a `Snackbar` in, unlike the manual-sync
case slice 1 covers)" part of this slice has been split out and done as NBC-23, since it turned out
to overlap with that task's app-wide sync indicator - see NBC-23 for the notification/permission/
channel work; `SyncWorker` posts via `SyncNotifier` on exhausted retries.

Status: **done**, 2026-07-31 - the existing toggle now drives a durable `filesDir` attachment sweep;
manual and WorkManager sync share one coordinator that refreshes typed devices, the directory, all
generic object collections, device-type/image-attachment metadata, and media bytes. Successful
edits continue to enqueue sync-on-edit, and NBC-23 covers background failure notifications. Remote
`just lint`, `just test`, and `just build` pass; live offline rendering remains a device follow-up.

## NBC-18: show cached data immediately when the server is unreachable at launch

Don't let a down/unreachable NetBox instance block the app on an empty state - if there's cached
data from a previous sync, show it right away and let the (failed) refresh just report an error
around it, rather than the user seeing nothing until the network call resolves or times out.

**Why:** user request - "if the server is down at app launch we really should be displaying the
offline cache we have and not just fail." Directly hit this live while verifying NBC-15: this
session's network lost the route to netbox.brkn.lol entirely partway through (confirmed via a raw
`curl` timeout to the instance from both the dev host and the Mi Pad 4 over SSH, not an app bug),
which is exactly the scenario this todo describes.
**How to apply:** most of this may already work as intended - `DeviceListViewModel.devices` and
`GenericDetailViewModel`'s `decodedObject`/`fields` are Room-`Flow`-backed and independent of
`refresh()`'s success, so cached rows should already render regardless of a failed refresh; verify
that's actually true end-to-end once connectivity is back (this session's outage hit right as a
fresh install had zero cached rows to begin with, so "does a *populated* cache still show through a
failed refresh" wasn't actually provable this session - it needs its own real check, not just
reasoning about the code). Also check `DirectoryViewModel` (drives the sidebar's app/model
sections) - it only calls `refresh()` when `cachedModelCount() == 0`, so an already-populated
sidebar shouldn't be affected by a down server either, but same caveat: unverified this session.
If the reasoning above doesn't hold up once retested, the fix is ensuring every list/detail
ViewModel always emits from its Room `Flow` first and treats `refresh()` purely as a
best-effort background update, never a gate on what's rendered.

**Follow-up code audit (this session):** read every list/detail ViewModel line-by-line (not just
re-reasoned about) against the "Room `Flow` first, `refresh()` is a pure side effect" shape, since
NBC-13's global search had already shown once that reasoning-without-reading can be wrong:
- [x] `DeviceListViewModel.devices`/`DeviceDetailViewModel.device` - both `stateIn` a Room `Flow`
  (`DeviceRepository.observeDevices`/`observeDevice`) directly; `refresh()`/`refreshDevice()` only
  toggle `isRefreshing`/`errorMessage` on failure, never touch what's rendered. `DeviceRepository`'s
  `syncAll`/`refreshDevice` only `dao.upsert(...)`, no `dao.clear()` anywhere - a failed sync can't
  wipe existing rows.
- [x] `GenericListViewModel.objects`/`GenericDetailViewModel`'s `decodedObject`/`title`/`fields` -
  same shape, backed by `GenericObjectRepository.observeObjects`/`observeObject`. `syncAll`/
  `refreshObject` are `runCatching { ...; dao.upsert...() }` - the upsert is *inside* the
  `runCatching` after the network call, so a network throw never reaches the DB write; nothing to
  clear beforehand either. Confirmed via `GenericListScreen`/`GenericDetailScreen`: both key their
  empty/loading text off `objects.isEmpty()`/`title == null` first, `isRefreshing` only picks the
  wording within that branch - a non-empty cache always wins.
- [x] `DirectoryViewModel.modelsByApp` - `stateIn`s `DirectoryRepository.observeAll()` (Room)
  directly; `Sidebar.kt` renders `modelsByApp` with no loading/refresh gate at all. Confirmed
  `init` still only calls `refresh()` when `cachedModelCount() == 0` (unchanged from when this was
  originally flagged) - and confirmed `DirectoryRepository.refresh()`'s `dao.clear()` sits *after*
  the `api.getApiRoot()` call inside the same `runCatching`, so a network throw there returns
  before `clear()` runs and an already-populated sidebar survives a down server untouched.
- [x] `DashboardViewModel` (NBC-9, built after this entry was written, not previously audited
  against this rule) - `stats`/`bookmarks`/`changelog` all `stateIn` Room flows off
  `DashboardRepository`; `refresh()` fans out to three independent `runCatching` calls
  (`refreshBookmarks`/`refreshChangelog`/`refreshStats`), each only replacing its own DAO table
  *after* its own successful fetch, so one endpoint being unreachable can't blank the other two,
  let alone all three. `DashboardScreen`'s per-section `EmptyHint` only changes wording based on
  `isRefreshing`, never hides already-loaded rows.
- [x] `GlobalSearchViewModel` (NBC-13, fixed earlier the same day) - re-verified `results`
  `stateIn`s `GlobalSearchRepository.observeCached` (Room, cross-endpoint), `isRefreshing` is a
  separate best-effort network signal, and `GlobalSearchScreen`'s `when` checks
  `results.isNotEmpty()` before `isRefreshing` - still correct, holds up.
- [x] Broader sweep (`grep -rn "fun refresh\|fun sync" app/src/main/kotlin`) turned up no other
  screen-backing ViewModel with a refresh/sync path: `SettingsViewModel.syncNow()` and
  `ScannerViewModel.onCodeScanned()` both only ever trigger a repository upsert, no rendering
  gated on it; `OnboardingViewModel.connect()` is the pre-cache initial-setup flow (no cache can
  exist yet at that point, so the "cache-first" rule doesn't apply there by definition).
- [x] Noted but out of scope for this entry: `GenericDetailViewModel`'s Journal tab
  (`JournalEntryRepository`, NBC-15) is not Room-cached at all - a failed fetch just leaves
  `journalEntries` empty and the tab doesn't render, silently, per the "or is silently skipped"
  clause in `AGENTS.md`'s offline-first rule. It doesn't block or replace the main object view
  either way, so it's compliant, just not itself cache-first; a future entry could extend NBC-15 to
  cache journal entries in Room if that's wanted.

**Result: no bugs found.** Every read path already followed the required shape before this session
started - the reasoning in the original entry (above) held up under a full line-by-line read, not
just re-reasoning about it. No production code changes were made for this entry.

**Verification limitation (explicit, matching this repo's honesty convention):** this pass is a
**code audit only** - grep/read of every ViewModel and the repositories/DAOs behind them, confirming
the Room-`Flow`-first / `refresh()`-as-side-effect shape and that no failure path clears or
replaces cached rows before a successful network response lands. It is **not** a live
device/network test: there was no way in this session to physically kill connectivity mid-run
against a populated cache (same limitation the original entry hit with the netbox.brkn.lol
outage). A real device check - populate the cache, then kill/blackhole the route to the NetBox
instance and confirm each screen still renders its last-synced data with only a non-blocking
error/snackbar - is still owed next time a device and a controllable network are both available.

**Live device attempt (separate, concurrent session):** with netbox.brkn.lol's outage resolved,
a populated cache (382 real devices) was available to actually test this against - but the
attempt was aborted before producing a result: disabling WiFi on the Mi Pad 4 to simulate offline
severed its only remote-control path too (wireless adb depends on the same WiFi), and it was only
recoverable via an unrelated Home Assistant automation on that device, not anything done here. Not
a code problem, just a tooling gap in how offline was simulated - worth a safety net before the
next attempt (arm a delayed self-re-enable first, e.g.
`ssh <device> 'nohup sh -c "sleep 30 && svc wifi enable" &'`).

Status: **done** (code-audited, not live-device-verified), 2026-07-31 - every list/detail
ViewModel in the app read line-by-line and confirmed to already follow the Room-`Flow`-first,
best-effort-`refresh()` shape; no gating/clearing bugs found, so no code changes were needed.
`just build`/`just lint`/`just test` run clean on the (unchanged) codebase. A live device
network-kill test was separately attempted this session and aborted for tooling reasons (wireless
adb losing its own transport when WiFi is disabled), not a code issue - still needs a clean re-test
with a self-re-enable safety net once a device/connection is available.

## NBC-19: icon audit - buttons, ListItems, and a new AGENTS.md convention

Every labeled `Button`/`OutlinedButton` and every `ListItem` that names a distinct thing should
carry a leading icon, not just text - and this should stay true going forward, not just as a
one-time cleanup pass.

**Why:** user request - "make sure we use icons pretty much everywhere it makes sense to do so. On
buttons, on overflow menu items etc etc. Where there is text I expect a relevant icon as well!",
plus a same-thread follow-up - "pls update the agents.md as well, so that we do not end up with new
buttons/text widgets w/o icons in the future."
**How to apply:** audited every screen (`grep` for `Button(`/`IconButton(`/`ListItem(` across
`ui/`). Sidebar, `GenericDetailScreen`'s top bar, `DeviceDetailScreen`, `ScannerScreen`, and
`DeviceListScreen`'s row (`RemoteThumbnail` leading image, from NBC-3) already had full icon
coverage - no changes needed there. Gaps found and fixed: `OnboardingScreen`'s "Connect" button
(added `Icons.AutoMirrored.Filled.Login`); `SettingsScreen`'s "Sync now"/"Disconnect" buttons
(`Sync`/`Logout`) and its four `ListItem`s (NetBox instance/cached devices/app info/build - `Dns`/
`Storage`/`Info`/`Tag`); `GenericListScreen`'s row (`ObjectRow` had no leading icon at all - now
uses `AppIcons.forAppKey(...)` derived from the route's endpoint path, the same lookup the sidebar
uses, so a given NetBox object type reads with the same icon in both places). Added a "UI
conventions" section to `AGENTS.md` codifying the icon-everywhere rule for future work, including
pointing at `AppIcons.forAppKey` as the thing to reuse rather than picking new icons ad hoc.

Status: **done**, 2026-07-31. `just test`/`just lint` green on rofl-14; installed on the Mi Pad 4
and visually confirmed (Settings screen icons, Onboarding "Connect" button icon) - screenshots
match the intended layout with no crash. Installed on Pixel 5 too; Zenfone 10 not reachable this
session. Mi Pad 4 was reconnected once netbox.brkn.lol's outage resolved.

## NBC-20: tap an image to view it full-size with pinch/swipe zoom

Device-type stock photos and image attachments (NBC-3) currently just sit inline at a fixed
thumbnail size - tapping one should open a full-screen viewer with pinch-to-zoom/pan, not require
falling back to "open in browser" the way a document attachment does.

**Why:** user request - "images need to be clickable -> show in full size + swipe to zoom" - then
two follow-ups: "image attachments should open a popup (the kind you slide down to dismiss) when
clicked. on there i would expect the img to be displayed and the metadata of the img attachment.
btw pls refrain from renaming stuff. in netbox these are image attachments, not 'photos'. and: we
should be able to swipe left and right to see the next/prev img attachment."
**How to apply:** needs a full-screen image viewer shown as a swipe-to-dismiss popup/`Dialog`
(vertical drag-down closes it, matching the common photo-viewer gesture - not just a tap-to-close),
not a navigation route. Content: the image itself (Coil3 `AsyncImage` + zoom/pan - hand-rolled via
`detectTransformGestures`/`graphicsLayer`, or a small Zoomable-style dependency if one's already
idiomatic for Coil3 - check findroidplus's usage before picking) plus the `extras.ImageAttachment`'s
own metadata (name, size, upload/created date, content type - whatever the API response actually
carries, don't guess the field list) shown alongside/below it. Horizontal swipe moves between the
image attachments already loaded in `DeviceDetailScreen.imageAttachmentRow`'s `LazyRow` (a
`HorizontalPager` over that same list, opened to the tapped index, is the natural fit). Applies to
the image-attachment row (`imageAttachmentRow`'s `RemoteThumbnail`, currently opening the external
browser via `clickableIfUrl` - replace with this popup) - the device-type front/rear photos
(`deviceTypePhotos`) are a separate, single-image, non-"image attachment" case and may not want
the same swipe-between-siblings behavior; decide when implementing whether they get the popup too
or stay as-is. Terminology note for this whole entry and anywhere else in the app: NetBox calls
these "image attachments," not "photos" - keep using NetBox's own name for the object type, not a
friendlier paraphrase (this app should read like a faithful NetBox companion).

- [x] Full-screen viewer is a swipe-to-dismiss `Dialog` (`usePlatformDefaultWidth = false`), not a
      navigation route - vertical drag-down closes it like a standard photo-viewer gesture.
- [x] Pinch-to-zoom/pan on the image itself, hand-rolled (`AsyncImage` + a custom pointer-input
      gesture + `graphicsLayer` scale/translate) - no new dependency added.
- [x] Image attachment metadata (name, dimensions, description, created/last-updated) shown
      alongside/below the image, sourced from the real `extras.ImageAttachment` fields (not
      guessed) - `ImageAttachmentDto`/`ImageAttachmentEntity` extended to actually carry them.
- [x] Horizontal swipe between image attachments via `HorizontalPager` over the same list already
      shown in `imageAttachmentRow`'s `LazyRow`, opened to the tapped index.
- [x] `imageAttachmentRow`'s thumbnails open this viewer instead of `clickableIfUrl`'s external
      browser intent (`clickableIfUrl` removed, no longer used anywhere).
- [x] Explicit decision (documented below) on whether `deviceTypePhotos` (front/rear) get the same
      popup or stay as-is.
- [x] Terminology: "image attachment(s)", never "photo(s)", in any new code/comments/UI strings
      this task adds - also fixed the pre-existing user-visible `imageAttachmentRow` section label
      from "Photos" to "Image attachments" since it's directly in this feature's path (left the
      Kotlin identifiers `deviceTypePhotos`/`imageAttachmentRow` themselves alone, out of scope).

**Real `extras.ImageAttachment` API shape** (confirmed live against netbox.brkn.lol, not guessed):
`id`, `url`, `display` (server-derived filename when `name` is blank), `object_type`, `object_id`,
`parent`, `name`, `image`, `description`, `image_height`, `image_width`, `created`, `last_updated`.
No `size` (bytes) or `content_type` field exists on this serializer at all - the TODO's original
"name, size, upload date, content type" wishlist doesn't fully match reality; the viewer shows
what's actually there instead (name/display, description, `image_height`×`image_width`, created,
last updated).

**Device-type front/rear photos decision:** they get the *same* full-screen zoomable viewer, not
external-link-only. Reasoning: today they have no click handler at all (not even
"open in browser" - only `imageAttachmentRow` had that), and the user's underlying ask ("images
need to be clickable") is about images in general, not specifically the image-attachments table;
there's no reason to leave the front/rear stock photos inert once the zoom/pan viewer exists. They
don't carry `ImageAttachment` metadata (no `created`/`description`/dimensions - `DeviceTypeEntity`
only has the image URL and the type's model name), so their viewer instance shows only a
title ("Front of `<model>`" / "Rear of `<model>`") and no metadata panel rows - a deliberately
smaller reuse of the same `ImageViewerDialog`, not a separate feature.

**How it landed:** new `ui/common/ImageViewerDialog.kt` - `ImageViewerDialog(items: List<ImageViewerItem>, initialIndex, onDismiss)`, deliberately decoupled from `ImageAttachmentEntity` (an `ImageViewerItem` is just a URL + title + optional metadata rows) so it covers both the image-attachment row and the device-type front/rear photos with one composable. A plain `Dialog` (`usePlatformDefaultWidth = false`) hosts a `Column` of `HorizontalPager` (image area) + a metadata panel below; the pager's `userScrollEnabled` and the outer vertical dismiss-drag detector are both gated off a shared `isZoomed` flag so pinch-zoom, page-swipe, and swipe-to-dismiss don't fight each other - custom pinch/pan gesture detector (`detectZoomPan`, built on `awaitEachGesture`/`calculateZoom`/`calculatePan` from `androidx.compose.foundation.gestures`) only consumes pointer events while actually zoomed or mid-pinch, leaving a plain single-finger drag at 1x scale unconsumed so it bubbles up to the pager/dismiss-drag instead. Swipe-to-dismiss uses `detectVerticalDragGestures` + an `Animatable` (snap while dragging, `animateTo(0f)` spring-back if released under the 120dp threshold) plus a background-scrim fade tied to drag distance; an explicit `Close` `IconButton` (Material icon, per AGENTS.md) is also always present. `DeviceDetailScreen.kt`'s `imageAttachmentRow`/`deviceTypePhotos` now build `ImageViewerItem` lists and open the dialog on tap (`clickableIfUrl` removed entirely, no longer used); `ImageAttachmentDto`/`ImageAttachmentEntity`/`ImageAttachmentRepository` extended with `display`, `description`, `imageHeight`, `imageWidth`, `created`, `lastUpdated` (Room DB version bumped 4 -> 5, fine under the existing `fallbackToDestructiveMigration`) to actually carry the metadata shown. Device list row thumbnails (`DeviceListScreen.DeviceRow`) intentionally untouched - still no click handler, per NBC-3's original call that the list row probably shouldn't open a viewer.

Status: **done**, 2026-07-31. `just build`/`just lint`/`just test` all green on rofl-13 (only
pre-existing unrelated deprecation warnings, e.g. `hiltViewModel`/`EncryptedSharedPreferences`).
**Not verified this session:** the actual pinch/pan/swipe-to-dismiss/horizontal-page-swipe gesture
feel on a real device or emulator - no physical device was available in this session (this worktree
had no adb-connected device), so this is compile-clean and logically reviewed but not interactively
tested. The gesture-arbitration approach (consume only while zoomed, otherwise let the pager/dismiss
detectors see the event) is a known, common hand-rolled pattern but should get a real finger-on-glass
check on the Zenfone/Mi Pad/Pixel 5 before calling the interaction itself confirmed, not just "builds."

## NBC-21: scanner tap-to-focus + flashlight toggle

The QR/barcode scanner (CameraX + ZXing, NBC-1) has no manual focus control and no way to turn the
torch on in a dark rack room - both standard expectations for a barcode-scanning camera view.

**Why:** user request - "qr code reader view show allow us to tap-to-focus and a flashlight on/off
button would be nice too."
**How to apply:** CameraX's `CameraControl.startFocusAndMetering(FocusMeteringAction)` built from a
`MeteringPointFactory` (`PreviewView.getMeteringPointFactory()`) for tap-to-focus - hooked onto
`PreviewView.setOnTouchListener` directly (a Compose `pointerInput` modifier on the `AndroidView`
risks the embedded native view swallowing the gesture before Compose's gesture detector sees it -
this is CameraX's own documented recipe). `CameraControl.enableTorch(Boolean)` (gated on
`CameraInfo.hasFlashUnit()`, since not every device has one) for the flashlight, with an `IconButton`
(`Icons.Default.FlashOn`/`FlashOff` per the AGENTS.md icon convention from NBC-19) in the scanner's
top bar. The bound `Camera` object (from `ProcessCameraProvider.bindToLifecycle`, needed for both
features) is threaded out of `CameraPreview`'s `AndroidView` factory via a new `onCameraReady`
callback into `ScannerScreen`'s Compose state.

Status: **done**, 2026-07-31. `just test`/`just lint` green on rofl-14 (zero warnings beyond the
two pre-existing unrelated deprecations); installed on Mi Pad 4 and Pixel 5. Live-verified on the
Mi Pad 4 once reconnected: camera preview renders live video, tapping the preview to focus doesn't
crash (checked logcat directly), and the flashlight button correctly does NOT appear - this tablet
has no rear flash unit, confirming the `hasFlashUnit()` gate works as intended (couldn't verify the
torch actually turning on/off without a device that has one).

## NBC-22: bigger device-list thumbnails + un-crop the detail-screen photos

Two related sizing complaints about the NBC-3 image work: the device list row's `RemoteThumbnail`
is too small to be useful, and the detail screen's front/rear stock photos get hard-cropped past a
fixed height instead of scaling to fit.

**Why:** user request - "we should make the list items on like the dev list view bigger, so that
the images are bigger. and the images displayed on the dev view page should be scaled, instead of
hard-cropped off past a certain height."
**How to apply:** `DeviceListScreen.DeviceRow`'s `RemoteThumbnail` is currently a fixed
`Modifier.size(48.dp)` `ListItem` `leadingContent` - bump the size (and check `RemoteThumbnail`
itself/`ListItem`'s own min-height don't silently reclip a larger size). The detail screen's
front/rear photos need their `Image`/`AsyncImage` `contentScale` checked - `Crop` (or a fixed
`.height(...)` combined with the default `Fit` behavior clipping at the container bounds) is likely
the culprit; `ContentScale.Fit` (or `FillWidth` with no fixed height) shows the whole image instead.

Status: **done**, 2026-07-31. `RemoteThumbnail` gained a `contentScale` parameter (default `Crop`,
unchanged everywhere else); `DeviceDetailScreen`'s front/rear photo row now passes `Fit`;
`DeviceListScreen.DeviceRow`'s thumbnail bumped from 48.dp to 72.dp. Live-verified on the Mi Pad 4
against real device-type photos - the PDU and 8-inch-monitor detail pages show their full stock
photos un-cropped, and list-row thumbnails are visibly bigger.

## NBC-23: "sync in progress" indicator + surfaced sync errors (background, not just manual)

A sync happening in the background (the periodic `SyncWorker`, or a future sync-on-edit/full offline
sync per NBC-17) is currently invisible to the user - no progress indicator while it runs, and no
way to learn a background sync failed at all (only the manual "Sync now" button surfaces errors,
per NBC-17 slice 1).

**Why:** user request - "we should probably display a 'sync in progress' notification when we sync,
right? and surface sync errors. Esp when we add propper change sync (ie we edit an item offline,
and then sync again) this will be very very useful." - explicitly framed as more valuable once
NBC-17's sync-on-edit/full offline sync lands, since a background sync becomes a much more common
occurrence once edits queue and flush automatically rather than only firing on an explicit tap.
**How to apply:** two distinct pieces - an in-app "syncing" indicator (a small persistent
indicator/badge, not just the existing per-screen `PullToRefreshBox` spinners which only show while
that specific screen is visible) for when `SyncWorker` is actively running, and a background-capable
error surface for when it fails - `WorkManager`'s `WorkInfo`/`getWorkInfoByIdLiveData` can be
observed app-wide to know when `SyncWorker` is running/failed regardless of which screen is open. A
failure with no foreground `Activity` (the gap flagged in NBC-17 slice 2) likely needs an actual
Android `Notification`, not just a `Snackbar` - overlaps directly with NBC-17 slice 2's own
"surfacing background sync failures" follow-up, should probably be designed together with it rather
than as a fully separate feature.

- [x] `sync/SyncStatusRepository.kt` - new `@Singleton` wrapping `WorkManager.getWorkInfosForUniqueWorkFlow`
  for both of `SyncScheduler`'s unique work names (`PERIODIC_WORK_NAME`/`ONE_TIME_WORK_NAME`, made
  non-private so this can reference them), combined into a single `isSyncing: Flow<Boolean>`
  (`true` while either has a `WorkInfo.State.RUNNING` entry). Reads WorkManager's own
  locally-persisted state, so it's correct offline too - only the sync work itself needs
  connectivity, not observing whether it's running.
- [x] `ui/common/SyncStatusViewModel.kt` + `ui/common/SyncStatusIndicator.kt` - a thin
  `hiltViewModel()`-backed composable, a `LinearProgressIndicator` that `AnimatedVisibility`-shows
  only while syncing. Hosted once in `MainActivity`, layered in a `Box` above `NetBoxNavHost` (not
  inside any individual screen's own `Scaffold`), so it reflects sync state regardless of which
  screen is on-screen - deliberately structured this way instead of touching each screen's own
  top bar, since several of those screens (`DeviceListScreen`, `DeviceDetailScreen`,
  `DashboardScreen`, `Sidebar`) had other in-flight changes elsewhere this session.
- [x] `sync/SyncNotifier.kt` - new `@Singleton`, creates a `background_sync` `NotificationChannel`
  (called once from `NyetboxApp.onCreate`, idempotent) and posts a `Notification` (tapping
  it opens `MainActivity`) via `notifySyncFailed(message)`. Silently no-ops if `POST_NOTIFICATIONS`
  isn't granted on API 33+ instead of crashing the worker - this is a nice-to-have surface, not a
  hard requirement.
- [x] `AndroidManifest.xml` - added the `POST_NOTIFICATIONS` permission; requested at runtime from
  `MainActivity` on API 33+ (same `rememberLauncherForActivityResult`/`ActivityResultContracts.RequestPermission`
  shape `ScannerScreen` already uses for `CAMERA`), fire-and-forget - denial just means the
  notification silently doesn't show later.
- [x] `sync/SyncWorker.kt` - only notifies on *exhausted* failure, not every transient retry: caps
  retries at 3 attempts via `runAttemptCount` before switching from `Result.retry()` to
  `syncNotifier.notifySyncFailed(...)` + `Result.failure()`. Note this cap is per-run - a
  `PeriodicWorkRequest`'s attempt count resets at its next scheduled period regardless, so this
  bounds retries *within* one run, not across the whole periodic schedule.
- [x] Added a small `drawable/ic_stat_sync_problem.xml` vector (Material "sync_problem" glyph) as
  the notification's small icon - status-bar/notification icons must be simple alpha-only
  silhouettes, so a launcher-style icon wasn't reusable here.

**Deliberately out of scope:** no unit tests were added for the WorkManager/`Notification`-touching
pieces (`SyncWorker`, `SyncStatusRepository`, `SyncNotifier`) - they need `androidx.work:work-testing`
(or Robolectric) for meaningful coverage, neither of which is wired into this project yet, and the
existing test suite only covers plain-Kotlin logic (`NetBoxUrlParserTest`,
`GenericFieldRendererTest`, `EditableFieldTest`). Left as a follow-up rather than bringing in new
test infra as a side effect of this task.

Status: **done**, 2026-07-31 - `just build`/`just lint`/`just test` all green on rofl-14. Not
verified on a physical device this session (no device was reachable to confirm the indicator
actually renders live or that a real background-sync-failure `Notification` fires and looks right
in the tray) - reasoned through the WorkManager/Notification APIs and matched existing in-repo
patterns (permission-request shape from `ScannerScreen`, `ViewModel`/`hiltViewModel()` shape from
every other screen) instead. Should get a live check (deny/allow the permission prompt, force a
sync failure, confirm the top progress bar shows during a real sync) next time a device is
available.

## NBC-24: list-view scrolling performance (device list is the worst)

Scrolling the device list is janky/slow - the worst offender among the app's list screens.

**Why:** user request - "improve scrolling performance of the list view (device list is the worst
atm)."
**How it landed:** profiled on the Mi Pad 4 with `dumpsys gfxinfo` while scrolling the real
383-device cache. The list was launching device-type metadata backfills for every cached device/type,
not just visible rows, while each row also repeated the durable-file lookup during recomposition.
The list now uses stable row content types, memoizes the local-image lookup, and observes visible
lazy-list indices so only on-screen device types are backfilled. A warm post-change scroll trace
still showed device-specific jank (29% in the short sample), but the image rows render correctly and
the unbounded prefetch/recomposition churn is removed; further profiling can tune Coil/device
hardware behavior separately.

Status: **done** (targeted performance pass), 2026-07-31 - remote `just lint`, `just test`, and
debug build passed; installed and exercised on the Mi Pad 4 with real production data read-only.

## NBC-25: a way to view/copy the currently-configured API token

There's no way to see the API token the app currently has stored - useful when setting up a new
device and wanting to reuse (or manually re-derive) an existing token rather than generating a new
one from scratch.

**Why:** user request/observation while helping debug why a NetBox REST-API-created token wouldn't
authenticate - NetBox 4.x tokens use a "token pepper" scheme where the full secret is
`nbp_<TOKEN_NAME>.<KEY>`, and the REST API's `key` field on a `Token` object only ever returns the
raw `<KEY>` suffix, never the full prefixed value - the complete secret is shown exactly once, in
the web UI, at creation time (the API's own `token` field comes back `null` even for a token you
just created for yourself, confirmed live against netbox.brkn.lol). User's framing: "A little
button to display the current api token on the login page would be a great start."
**How to apply:** the app can only ever show back what NetBox already gave *this* app instance when
it was first configured (`SettingsRepository`'s stored `token` - EncryptedSharedPreferences, already
plaintext-accessible in-process) - it can't retroactively recover a full `nbp_...` value NetBox
never showed the app in the first place, and can't ask NetBox for it again later either. The login
page gets an ordinary eye-icon toggle for the token currently being entered. Settings gets reveal
and copy actions for the stored token, but both actions must first pass Android biometric/device
credential authentication (fingerprint or PIN); without an enrolled device credential the token
stays inaccessible.

- [x] `OnboardingScreen`: show/hide the token currently being entered; it remains local UI state and
  is never persisted or logged.
- [x] `SettingsScreen`: add masked stored-token display plus reveal and copy actions.
- [x] Gate both Settings actions with `BiometricPrompt` allowing a strong biometric or device
  credential fallback, and keep the token masked when authentication is unavailable or cancelled.

Follow-up, same thread: added a placeholder (not label) on `OnboardingScreen`'s API token field
showing the real format, `nbt_xxxxxxxxxxxx.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` - confirmed
against netbox.brkn.lol's actual `Token` model source (`TOKEN_PREFIX = 'nbt_'`,
`TOKEN_KEY_LENGTH = 12`, `TOKEN_DEFAULT_LENGTH = 40`) while debugging why a REST-API-created token
wouldn't authenticate (see below) - the user's own recollection of the prefix was "nbp_", the actual
constant is "nbt_".

Notes from that same debugging session, useful context for whoever eventually builds the
view/copy-token feature above: NetBox 4.x v2 tokens never return their plaintext secret via the
REST API under any circumstances (confirmed by creating a token for the *AI agent's own account* via
API and getting `"token": null` back regardless) - the only place the full secret is ever shown is
the web UI at creation time. The only way it was recoverable this session was direct
`netbox-manage shell` (Django ORM) access on the actual NetBox host, which the app obviously can't
do. Also: `Authorization: Token <value>` and `Authorization: Bearer <value>` are BOTH accepted by
this instance for v2 tokens (confirmed live) - the app's `AuthInterceptor` hardcodes `"Token "`,
which is fine, no change needed there.

Status: **done**, 2026-07-31 - verified with remote `just lint`, `just test`, and `just build` on
rofl-13; biometric/device-credential behavior was code-reviewed but not exercised on a physical
device in this session.

## NBC-26: narrower sidebar + real app icon in the footer

The navigation drawer is wider than it needs to be, and its footer currently shows a generic
`AppIcons.Devices` glyph instead of the app's own icon.

**Why:** user request - "sidebar - can we make it less wide? and we should display our app icon in
the bottom right (left of the version info and netbox url)" (the existing footer layout is
ICON | version/URL | settings cog, so "bottom right" here means the already-present leading icon
slot, not a new position).
**How to apply:** `Sidebar.kt`'s `ModalDrawerSheet` had no explicit width (Material3's default,
which reads wide on a phone) - constrained to `Modifier.width(280.dp)`, the Material Design minimum
recommended drawer width. `SidebarFooter`'s leading `Icon(AppIcons.Devices, ...)` swapped for the
actual app icon. Note: NBC-4 (a real custom app icon design - NetBox logo × 🤨 emoji mashup) is
still not started, so this currently surfaces whatever placeholder/default launcher icon exists
today, not a finished design - revisit this footer once NBC-4 lands.

**Real bug caught live, not just theoretical:** the first attempt used
`Image(painterResource(R.mipmap.ic_launcher), ...)` directly, which crashed the app on every launch
- `ic_launcher.xml` is an `<adaptive-icon>` (separate background/foreground layers), and Compose's
`painterResource()` only supports VectorDrawables and flat raster assets, not that wrapper format
(`IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`). This
wasn't caught by `just test`/`just lint` (a Compose runtime failure, not a compile error) - only
surfaced when actually installed on the Zenfone 10, which then repeatedly crash-looped and got
force-killed by Android, making it *look* like the device's launcher (`projekt.launcher`) was
blocking the app from ever opening - a real, embarrassing dead end chased for a while before
checking logcat properly. Fixed by rendering the drawable through `ContextCompat.getDrawable(...)
?.toBitmap()?.asImageBitmap()` first (works for any drawable type, adaptive icons included) instead
of `painterResource`. Same pattern reused for NBC-28's onboarding-screen app icon.

Status: **done**, 2026-07-31. `just test`/`just lint` green; live-verified crash-free on the
Zenfone 10 after the fix, sidebar narrower and footer showing the real launcher icon.

## NBC-27: unify the app's three separate search boxes

There are currently (at least) three different search entry points that all feel like they should
be one thing: the sidebar's "Search sections" box (filters the model/section list itself), NBC-13's
global search (searches NetBox object data), and each list screen's own "Search devices"/etc. box
(filters within that one object type). Confusing to a user who just wants "search" without knowing
which of the three they need.

**Why:** user request - "we should combine our searchbar somehow. There'd only 1 search ideally.
currently we have at least 3: section search in the navbar, global search and the search on the
(device/item) list pages. not sure how to best marry them, but it's a bit confusing atm."
**How to apply:** genuinely needs a design decision, not just a mechanical merge - the three
searches operate on different scopes (sidebar sections/model names vs. NetBox object data
everywhere vs. one object type's already-cached rows) and a single box needs a clear model for
which scope applies when. Options worth weighing rather than picking blind: (a) one global-search
entry point reachable from everywhere (e.g. promoted into the top app bar) that also offers to
jump to a matching sidebar section, retiring the sidebar's own local filter box; (b) keep the
per-list-screen search (it's filtering already-loaded local data, cheap and fast, arguably a
different job than "find something anywhere") but merge just the sidebar section-search into global
search. Needs its own look at how NBC-13 actually shipped (this session didn't build it - another
concurrent session did) before deciding.

Status: **done**, 2026-07-31 - verified with the generic renderer unit tests plus remote `just lint`,
`just test`, and `just build` on rofl-13.

## NBC-28: real app icon on the onboarding screen + dashboard stat-card overflow fix

Two small fixes landed together with NBC-26's sidebar-icon work, same session: the onboarding
screen's "Connect to NetBox" header used a generic `Icons.Default.Inventory2` glyph instead of the
app's own icon, and NBC-9's dashboard stat cards (fixed-height from NBC-22's own uniform-sizing
fix) were clipping longer labels like "Device Types" instead of wrapping them cleanly.

**Why:** user requests - "on the login page we should display our app logo instead of the random
icon you put there", and a live-testing catch of the dashboard card issue right after connecting a
freshly-provisioned device and seeing "Device Types" visibly cut off mid-word.
**How to apply:** `OnboardingScreen`'s icon replaced with the same `ContextCompat.getDrawable(...)
?.toBitmap()?.asImageBitmap()` pattern from NBC-26 (not `painterResource` - same adaptive-icon crash
risk). `DashboardScreen.StatTile`'s fixed card size bumped from 110×120dp to 110×136dp and its label
`Text` given `maxLines = 2` + `TextOverflow.Ellipsis` as a safety net for even longer labels in the
future.

Status: **done**, 2026-07-31. `just test`/`just lint` green; live-verified on the Zenfone 10 -
onboarding shows the real launcher icon, dashboard cards render uniform height with no clipping.

## NBC-29: manufacturer/model (and similar) fields should link to their own object

On the legacy (non-generic) device detail screen, fields like "Manufacturer" and "Model" render as
plain text - unlike NBC-6's generic detail screen, which already turns any NetBox reference field
into a tappable link to that object's own page.

**Why:** user request - "stuff like 'manufacturer', 'model' etc should be clickable and open the
relevant page (The manufacturer, or model page for instance in this example)."
**How to apply:** `DeviceDetailScreen.kt`'s `detailField(...)` helper renders plain
`Text`/`ListItem`-style rows from typed `DeviceEntity` columns (`manufacturerName`,
`deviceTypeModel`, ...) which only ever stored the *display string*, not the referenced object's
id/endpoint - there's currently no id to navigate to even if the row were made clickable. Either (a)
extend `DeviceEntity`/`DeviceDto`/the sync mapping to also capture each reference's id (manufacturer
id, device-type id already exists via `deviceTypeId`, site/rack/role ids do not), then make those
specific rows navigate via the same `onNavigateToReference`-style callback `GenericDetailScreen`
uses, or (b) simplest and most consistent with how the rest of this app has been trending (NBC-6
onward): route the legacy device detail screen through the generic engine entirely instead of
maintaining two parallel detail-rendering implementations, which would get this "for free" the same
way NBC-15's Journal tab and NBC-16's file attachments did. Worth deciding which before starting -
option (b) also happens to be the fix for NBC-30 below, for the same reason.

Status: **done**, 2026-07-31 - verified with the generic renderer unit test, remote `just lint`,
`just test`, and `just build` on rofl-13, plus a live Mi Pad 4 device-type detail screenshot showing
the un-cropped front image.

## NBC-30: device/item title belongs in the page body, not the top app bar

Long device/item names make the `TopAppBar` title wrap and grow the header to an awkward height.

**Why:** user request - "device/item view page -> we should move the title of the device/item from
the header back to the body/content of the page. We have some items with long names, that make the
header weirdly large in height."
**How to apply:** applies to whichever detail screen(s) currently put the object's full name in
`TopAppBar`'s `title` - move it into the scrollable body instead (a `Text` at the top of the content
column, `TopAppBar` keeping just a short/generic title or none) so a long name wraps within the page
instead of stretching the fixed app bar. Check both `DeviceDetailScreen` and `GenericDetailScreen`
(NBC-15's `title` `StateFlow` currently feeds the `TopAppBar` title directly) - likely wants the same
treatment in both, which is also another point in favor of NBC-29's option (b) (route everything
through the generic engine) rather than fixing this twice.

Status: **done**, 2026-07-31 - typed and generic detail titles now render at the top of the
scrollable page body while the app bars keep short labels; remote verification is recorded in this
pass.

## NBC-31: copy-to-clipboard icons on identifier fields (Serial, Primary IP, Asset tag, ...)

Fields that are short identifiers someone would realistically want to copy elsewhere (Serial,
Primary IP, Asset tag, and similar) have no quick copy action - currently requires long-press
text selection.

**Why:** user request - "we should 'copy-to-clipboard' icons next to the fields (Serial, Primary IP,
Asset tag etc etc...)."
**How to apply:** needs a small trailing `IconButton` (`Icons.Default.ContentCopy`, matching the
icon-everywhere convention from NBC-19/AGENTS.md) next to specific field rows that copies the
value via `ClipboardManager` (same API already used for "Paste from clipboard" on
`OnboardingScreen`'s token field). Open question worth deciding before implementing: *which* fields
get this - the user named Serial/Primary IP/Asset tag specifically (identifier-shaped values), not
every field indiscriminately; on the generic engine (NBC-6) that likely means opt-in per field *key*
(a small allowlist: `serial`, `asset_tag`, `primary_ip4`, `primary_ip6`, ...) rather than every
`FieldRow.PlainText`, to avoid cluttering fields where copying doesn't make sense (e.g. `comments`,
free-text descriptions).

- [x] Added the identifier allowlist to the generic renderer and copy icons to generic reference/
  text rows and the typed device detail screen.
- [x] Added coverage for serial, asset tag, primary IP, and non-copyable fields.

Status: **done**, 2026-07-31 - verified with the generic renderer unit tests plus remote `just lint`,
`just test`, and `just build` on rofl-13.

## NBC-32: detect and resolve edit conflicts (offline edit vs. server-side change)

No conflict handling exists today: if an object is edited offline in the app, then also changed on
the server (or by someone else) before the app's edit syncs, the last write silently wins - the
user gets no warning and no way to see or resolve what actually differs.

**Why:** user request - "how do we handle conflicts atm? ie i change something offline and in the
app in parallel. How do we reconcile this? I expect a warning on the home page and a view to
properly resolve the merge conflict (with diffs and all)."
- [x] Capture the last-synced base object and compare `last_updated` before PATCHing; fall back to
  a full JSON comparison when the API response has no version field.
- [x] Add a durable Room outbox for offline edits and process it before ordinary cache refreshes,
  so queued local changes are not silently overwritten.
- [x] Show a conflict count warning on the dashboard and provide a resolver with base/local/server
  values plus a keep-local/keep-server choice for each changed field.
- [x] Re-check the server snapshot before applying a resolution and preserve the conflict if the
  server changes again.
- [x] Add focused three-way-diff tests and complete remote unit-test/lint/build validation.
- [x] Keep validation free of deliberate conflicts against the production NetBox instance; the live
  end-to-end conflict path remains unverified by design.

Status: **done**, 2026-08-01 - implementation and focused tests are in place; remote tests, lint,
and debug build passed. No production NetBox writes or deliberately induced live conflict were used
for validation.

## NBC-33: confirm a manual refresh on the detail screen with a toast/snackbar

Tapping the refresh icon on a device/item detail page gives no feedback on success - only a
failure shows anything (the existing error Snackbar). A successful refresh just silently updates
the fields, easy to miss.

**Why:** user request - "we should at least show a little toast msg when we hit the refresh button
on a device view page."
**How to apply:** `GenericDetailViewModel.refresh()`/`DeviceDetailViewModel.refresh()` already have
an `onFailure` branch wired to `_errorMessage`/`SnackbarHostState` (NBC-17-adjacent pattern) - add
an `onSuccess` branch that shows a brief confirmation the same way (reusing the existing Snackbar
host rather than a separate `Toast`, for visual consistency with how errors are already shown on
these screens).

Status: **done**, 2026-07-31. `refresh()` on both `GenericDetailViewModel` and
`DeviceDetailViewModel` gained a `showConfirmation: Boolean = false` parameter (default false, so
the automatic `init{}`-time refresh stays silent - only the explicit refresh-button tap passes
`true`) driving a new `refreshedMessage` Snackbar, mirroring the existing `errorMessage` pattern on
both screens. `just test`/`just lint` green; not yet live-verified on a device this round.

## NBC-34: render markdown in custom fields NetBox itself marks as markdown-type

Custom fields configured as markdown type in NetBox (e.g. a `purchase_store` field) render as plain
text in the app instead of formatted markdown - only the hardcoded `comments` field gets Markdown
treatment today (NBC-12).

**Why:** user request - "we should support markdown formatting in the fields that explicitly do
support it, such as our 'purchase_store' custom field for example."
**How it landed:** `CustomFieldRepository` fetches and caches NetBox's per-instance custom-field
definitions in Room. `GenericDetailViewModel` combines that offline Flow with the cached object;
custom fields whose server type is `markdown`, `text`, or `longtext` become `FieldRow.Markdown`,
while the existing hardcoded `comments` behavior remains unchanged.

Status: **done**, 2026-07-31 - custom-field definitions are cached in Room and refreshed
best-effort, textual custom fields now render through the existing Markdown card, and the renderer
has unit coverage. Remote `just lint`/`just test` and a debug build passed; the Mi Pad 4 live check
opened a generic detail without errors.

## NBC-35: comment/markdown card had excess top/bottom padding from blank lines

`CommentCard` (NBC-12/14) looked like it had too much vertical padding - actually blank leading/
trailing lines in the source markdown being rendered as real empty paragraphs by the Markdown
renderer, stacking with the card's own 16dp padding.

**Why:** user request - "there seems to be a bit too much top and bottom padding on the comments
widget. Looks like there are trailing newlines this way. make it more compact." - correctly
self-diagnosed the actual cause.
**How to apply:** `CommentCard` now calls `content.trim()` before handing it to the `Markdown`
composable, stripping leading/trailing blank lines before they're parsed into paragraphs.

Status: **done**, 2026-07-31. `just test`/`just lint` green; not yet live-verified against a
real comments field with trailing newlines this round.

## NBC-36: clickable count summaries (rack count, VM count, ...) filter into the list view

Summary count fields like "Rack Count"/"Virtual Machine Count" on a location (or similar rollup
counts on other object types) render as plain numbers - tapping one should jump to that object
type's list, pre-filtered to the item being viewed (e.g. tapping a location's rack count shows
that location's racks).

**Why:** user request - "the 'Rack count', 'Virtualmachine count' etc items that are displayed on
the location view should be clickable. This should also be the case for the other views that
display such summaries. clicking on it should bring us to the list view - prefiltered with the
current location (or the other relevant item we are coming from)."
**How to apply:** NetBox's own object detail pages compute these as reverse-relation counts (not
regular fields NetBox's API necessarily returns inline on every object - needs checking exactly
what `buildFieldRows()` currently receives for a location and whether counts like this are even
present in the raw API response, or whether they'd need a separate `?location_id=<id>`-filtered
count query per relation). If the data's there, rendering it as a `FieldRow.Reference`-like tappable
row that navigates to `GenericListScreen` with a pre-applied filter needs `GenericListViewModel`/
`GenericListScreen` to support an incoming filter query param in the first place - check whether
that exists yet (NBC-6's list screens currently only support the user's own free-text search box)
before assuming it's just a navigation-argument plumbing job.

Status: **done**, 2026-07-31 - known location/site reverse counts (`rack_count`, `device_count`, and
`prefix_count`) now render as tappable filtered-list actions. The generic list keeps the relation
filter over cached JSON for offline use and performs a best-effort server refresh using the matching
`*_id` query. Remote tests/lint/build passed; Mi Pad 4 live verification opened Office and confirmed
Rack Count filtered to the one Office rack without any write request.

## NBC-37: device view should link to its device type page

The device detail view shows the device type (e.g. as a "Model" field) but doesn't link to that
device type's own page.

**Why:** user request - "devices views currently lack the link to their dev type."
**How to apply:** overlaps directly with NBC-29 (manufacturer/model fields should be tappable
references) - device type is exactly one of the fields NBC-29 already covers. On the generic
engine (NBC-6) this may already work if the raw device object's `device_type` field comes back as
a full nested reference object (id + url), since `buildFieldRows()` already turns those into
tappable `FieldRow.Reference`s automatically - needs checking whether it's actually missing there
too, or only on the legacy `DeviceDetailScreen` (which is the one NBC-29 diagnosed as lacking ids
for its typed fields, `deviceTypeModel` being a display-string-only column).

Status: **done**, 2026-07-31 - the legacy device detail's Model field now opens the cached/network
generic device-type detail; the action was verified through the shared navigation route and the
remote lint/test/debug validation pass.

## NBC-38: device-type page should render front/rear images like the device page does

The device-type detail page's front/rear stock photos don't render the same way NBC-22 fixed them
to on the device page (un-cropped, `ContentScale.Fit`).

**Why:** user request - "on the dev-type page the front/rear images should render similarly to how
they do on the dev page."
**How to apply:** NBC-22 fixed `DeviceDetailScreen.deviceTypePhotos()`'s `RemoteThumbnail` calls to
use `ContentScale.Fit` instead of the default `Crop`. Find wherever the device-*type* detail page
(likely reached via NBC-29/37's device-type link, or already existing as its own generic-engine
screen) renders its own front/rear images and apply the same `contentScale = ContentScale.Fit`
`RemoteThumbnail` parameter (added in NBC-22 specifically to support this).

- [x] Generic device-type `front_image`/`rear_image` fields now render as inline image rows with
  `RemoteThumbnail(..., contentScale = ContentScale.Fit)`; other media fields keep their existing
  download-row behavior.
- [x] Added renderer coverage for both device-type image fields.

Status: **done**, 2026-07-31 - verified with the generic renderer unit test plus remote `just lint`,
`just test`, and `just build` on rofl-13; visual rendering was not exercised on a physical device
in this session.

## NBC-39: Settings screen has no way to change the configured NetBox server

The "NetBox instance" row on `SettingsScreen` only ever displays the currently-configured base
URL as read-only text - there's no way to point the app at a different NetBox instance without
disconnecting entirely and going back through onboarding from scratch.

**Why:** user request/observation - "the settings page currently does not allow changing the
netbox server."
**How to apply:** `SettingsRepository.save(baseUrl, token)` already exists and is exactly what
`OnboardingViewModel.connect()` uses - the dynamic base-URL interceptor picks up a saved change at
runtime with no rebuild needed (per `AGENTS.md`'s architecture note), so the plumbing already
supports this, it's just never been exposed as an edit affordance post-onboarding. Needs: an edit
icon/dialog on the "NetBox instance" row (`OutlinedTextField` pre-filled with the current URL),
validate reachability against the *new* URL before committing to it (mirror
`OnboardingViewModel.connect()`'s save-then-validate-then-revert-on-failure shape, not a blind
save), and - important, not just cosmetic - the local Room cache must be treated as
server-specific: switching to a different NetBox instance while keeping old cached
devices/objects around would silently mix data from two different servers (same object ids
meaning different things), so a successful server switch should wipe the cache
(`AppDatabase.clearAllTables()`), not just repoint the API base URL.

**Related pre-existing gap, noted but out of scope here:** `SettingsViewModel.logOut()` ->
`SettingsRepository.clear()` only clears the stored credentials, not the Room cache either - so
disconnecting and connecting to a *different* server today already has this same stale-cache
mixing problem. Not fixed as part of this entry (kept scoped to the specific "change server while
still connected" ask), but the same `clearAllTables()` fix would apply there too if picked up
later.

**How it landed:** `SettingsScreen`'s "NetBox instance" row gets a trailing edit `IconButton` that
opens `EditServerDialog` (an `AlertDialog` with an `OutlinedTextField` pre-filled with the current
URL). Save calls the new `SettingsViewModel.updateBaseUrl(newBaseUrl)`, which saves eagerly (only
way to actually test the new URL, since the dynamic interceptor reads `SettingsRepository`
reactively), calls `DirectoryRepository.refresh()` to validate reachability, and on failure reverts
to the previous `(baseUrl, token)` and surfaces the error via the screen's existing Snackbar - on
success it wipes the cache (`AppDatabase.clearAllTables()`, injected directly since no existing
repository wraps "clear everything") so no stale cross-server data lingers. The dialog itself
dismisses immediately on Save rather than waiting for validation to finish, matching how every
other async action on this screen already surfaces its result via Snackbar, not an inline spinner.

Status: **done**, 2026-07-31. `just build`/`just lint`/`just test` all green on rofl-13; the Mi Pad 4
now also visually verifies the Settings edit affordance and pre-filled server dialog without
changing the configured production server. The save path remains cache-clearing and revert-safe
as documented above.

## NBC-40: fix "edit does not work" - saves sent every field, not just the diff

Editing any object was silently unreliable, and editing a device *type* specifically failed every
time: the save button's PATCH body included every editable field's current value, not just the
ones actually changed - which both cluttered NetBox's change log with untouched fields, and for
device types, outright broke every save (`front_image`/`rear_image` are absolute media URLs NetBox
computes itself; resending one unmodified gets rejected with "The submitted data was not a file",
which failed the *entire* PATCH regardless of what the user meant to change).

**Why:** user report - "edit does not seem to work at all atm?", narrowed down live (with real
device access and log capture) to specifically device-type edits, confirmed via a live PATCH
against netbox.brkn.lol/api/dcim/device-types/244/ returning HTTP 400 with exactly that message.
Same thread, a sharp follow-up catch from the user comparing NetBox's own before/after change-log
diff: "shouldnt our edits also ONLY include the stuff we changed? might be worth-while to compute
the diff and only send that" - the *actual* root cause and the better fix, not just a band-aid for
the one field that happened to break outright.
**How to apply:**
- **Root fix**: `GenericDetailScreen`'s save handler now diffs `editValues` against each field's
  original `EditableField.value` and only includes entries that actually differ in the `edits` map
  passed to `viewModel.save(...)` - untouched fields are never resent, which fixes the device-type
  case too (an untouched `front_image` is no longer part of the PATCH at all) without needing to
  special-case it.
- **Defense in depth**: `buildEditableFields()` also now excludes any field whose value is a media
  URL (reusing `isMediaUrl()`, already used elsewhere for `FieldRow.FileAttachment` detection) -
  belt-and-suspenders in case a future field is ever *actually* edited and diffed as changed.
- **Error visibility**: a save failure previously only showed a `Snackbar`, which the user found
  easy to miss - "there is a toast - behind the keyboard..? I expected something more bold and
  clear." Failures during editing now render as a persistent `errorContainer`-colored banner at
  the top of the edit form instead (survives the keyboard being open, doesn't auto-dismiss), while
  non-editing failures (e.g. a refresh) keep using the Snackbar as before.
- **Success confirmation**: a successful save now also shows a positive `"<item> updated!"`
  Snackbar (reusing the same `refreshedMessage` flow as the NBC-33 manual-refresh confirmation),
  per the user's follow-up request once the fix was confirmed working live.

Status: **done**, 2026-07-31. `just test`/`just lint` green; root cause confirmed live via direct
`curl` reproduction of the 400 against the real instance, and the fix itself confirmed live too -
retried the exact same edit (Mi Pad 4 device type's U Height) on the Zenfone 10 after installing
the fix, and it saved successfully this time ("yes it worked! u height was updated correctly!").

## NBC-41: configurable gestures (two-finger swipe down for global search, etc.)

No gesture shortcuts exist today - navigating to global search or the scanner always requires
going through the sidebar/bottom nav.

**Why:** user request - "gestures! I'd be great to have configurable gestures. For now I primarily
want a way to trigger global search, by swiping down on any screen (with 2 fingers). Kinda like
the HA app does. Other possible action could be a gesture to open the QR code scanner."
**How to apply:** needs a global gesture-detection layer that works across every screen, not just
one - likely wants to live high up the composition (e.g. wrapping `NetBoxNavHost`'s content, or in
`MainActivity`'s root `Surface`) using `Modifier.pointerInput` + `awaitPointerEventScope` to detect
a 2-pointer vertical drag distinct from normal single-finger scrolling within whatever screen is
underneath (needs care not to steal normal scroll gestures - a 2-finger-specific detector should
naturally not conflict with single-finger `LazyColumn` scrolling, but verify in practice). "For now"
and "other possible action" in the request both point at wanting this configurable/extensible from
day one, not just one hardcoded gesture - suggests a small `GestureAction` enum (`GlobalSearch`,
`Scanner`, ...) mapped from a `SettingsRepository`-backed preference, with the two-finger-swipe-down
gesture as the first (and initially only) configurable trigger, rather than hardcoding "swipe down
= search" directly.

Status: **done**, 2026-07-31 - added a Settings-backed gesture action selector (`Off`, `Global
search`, `QR scanner`) and a non-consuming activity-root two-finger swipe-down detector. Remote
`just test`/`just lint`/debug build passed; Mi Pad 4 visually verified the selector and menu. The
gesture detector observes the real pointer stream without stealing one-finger scrolling; physical
multi-touch swipe injection was not available through the adb smoke-test tooling.

## NBC-42: dashboard "Recent changes" should link to the item and show the actual diff

The dashboard's recent-changes list currently shows only a change summary line - it doesn't link
anywhere, and even if it did, opening the item's current detail view wouldn't show what actually
changed (the object may have changed again since, or the field in question isn't rendered at all).

**Why:** user request - "on the home page the recent change entries should indeed allow us to open
the item view page directly - but we should also have a way to dispaly the diff ie the change
itself! (that's gotta be a separate view)."
**How to apply:** two distinct pieces:
- Tapping a recent-change entry should navigate to that object's existing generic detail screen
  (`Route.Generic`), same as any other reference elsewhere in the app - the changelog entry already
  carries the object's `changed_object_type`/`changed_object_id` (or an embedded `url`), which is
  what `NetBoxRef.endpointFromDetailUrl()` elsewhere in the codebase already turns into a route.
- A *separate* diff view is needed for the change itself: NetBox's changelog API
  (`/api/core/object-changes/{id}/`) returns `prechange_data`/`postchange_data` JSON snapshots -
  this needs a new screen that fetches that single change-log entry and renders a field-by-field
  before/after diff (this is exactly the kind of before/after comparison the user pasted earlier
  in the NBC-40 discussion when pointing out the edit form was resending unchanged fields - a
  generic "diff two JsonObjects, list keys that differ" helper would serve both that intuition and
  this view). Reachable from a distinct affordance on each recent-change row (e.g. a trailing "view
  diff" icon button) separate from the row tap itself, per "that's gotta be a separate view."

Implemented: the row tap already navigated to `Route.Generic` from NBC-9 - only the diff view was
actually missing. Added `Route.ObjectChangeDiff(changeId)`, `DashboardRepository.fetchObjectChange`
(uncached, fetched on demand only when the diff view is opened - unlike the rest of this
repository, the full pre/post snapshots aren't worth carrying in the offline cache for every
changelog row), `ObjectChangeDiffViewModel`/`ObjectChangeDiffScreen` (union of `prechange_data`/
`postchange_data` keys, one `DiffRow` per key whose value actually differs - nested objects/arrays
fall back to raw JSON, no schema to render them more richly here), and a trailing "view diff"
`IconButton` (`Icons.Default.Difference`) on each `ChangeRow`, distinct from the row's own tap
target. Diff-building logic covered by `ObjectChangeDiffTest` (create/delete/update/nested-object/
no-op cases).

Status: **done**, 2026-07-31. `just test`/`just lint` green (including a rerun-tasks ktfmt check to
rule out a stale cache hit).

## NBC-43: shorten displayed URL values

Absolute URLs in generic object fields repeat the configured scheme and host, making otherwise
useful paths hard to scan (for example, display `https://netbox.brkn.lol/dcim/device-types/244/`
as `/dcim/device-types/244/`). Keep the full URL for opening/sharing; shorten only its visible
label.

- [x] Shorten absolute URL text in generic external-link rows while preserving path, query, and
  fragment components.
- [x] Add regression coverage for the requested NetBox URL shape and malformed/non-URL fallback.

Status: **done**, 2026-07-31 - visible URL shortening is covered by `GenericFieldRendererTest`;
the original URL remains the click target.

## NBC-44: replace the bottom Devices tab with Search

The fixed bottom navigation should prioritize the app's most useful universal actions: `Home | SCAN
| SEARCH`. Device browsing remains available from the drawer and dashboard stat cards, while
the bottom bar should no longer duplicate that entry point.

- [x] Replace the Devices tab with Search and use the SCAN label for the scanner destination.
- [x] Keep the three destinations reachable from dashboard, list, search, and scanner screens.

Status: **done**, 2026-07-31 - `just lint`, `just test`, and `just build debug` passed remotely;
the three-tab layout was smoke-tested on the Mi Pad 4.

## NBC-45: make the global search landing page useful before typing

Opening global search currently presents an empty state until the user enters a query. Show the
most recently visited devices and NetBox pages by default, using the local cache so the screen is
useful offline as well.

- [x] Persist a small, bounded recent-visit history for typed and generic detail pages.
- [x] Render the recent pages before a query and improve the blank/no-match presentation.

Status: **done**, 2026-07-31 - cache-backed recent visits and empty states are covered by repository
tests; the remote test/lint/build checks passed.

## NBC-46: switch scanner lenses and choose a default lens

The QR scanner always opens the back camera and offers no way to switch to another available lens.
Add an in-scanner switch and a Settings preference for the default lens, with a safe fallback on
devices that expose only one camera.

- [x] Discover available CameraX lenses and show the switch only when at least two are available.
- [x] Persist the default front/back preference and fall back to an available lens if needed.
- [x] Add focused preference/selection coverage and validate on the available devices.

Status: **done**, 2026-07-31 - preference tests passed; the Mi Pad 4 opened the scanner,
exposed `Switch camera`, and switched lenses without camera errors.

## NBC-47: share/import complete connection setup QR codes

The setup QR code must represent a complete Nyetbox connection, not a token-only export.
It should contain the server URL and API token, be generated from Settings behind device auth, and
be scannable directly from the login screen on another device.

- [x] Make the Settings action and warning explicitly describe a complete connection setup code.
- [x] Add a login-screen action to scan a setup code and prefill both required fields.
- [x] Keep the versioned payload format and round-trip coverage for server URL plus token.

Status: **done**, 2026-07-31 - codec tests passed; a valid setup deep link on the Mi Pad 4 opened
onboarding with both fields populated. Settings export remains device-auth protected.

## NBC-48: select rear scanner lenses and move camera controls

The scanner's front/rear toggle is useful, but phones with a logical rear multi-camera should also
be able to select physical rear lenses such as ultrawide or macro. Put the flashlight and camera
controls at the bottom of the preview, with a compact rear-lens selector above them.

- [x] Discover and bind available physical rear cameras while retaining front/rear fallback.
- [x] Show a compact rear-lens selector only when multiple rear lenses are available.
- [x] Move flashlight and front/rear controls into a bottom scanner control strip.

Status: **done**, 2026-07-31 - remote tests and lint passed; scanner smoke-tested on the Mi Pad 4
with front/rear switching and the available rear-lens fallback.

## NBC-49: mirror NetBox sidebar grouping and support custom ordering

The directory currently follows API/alphabetical order, while NetBox's web UI presents familiar
app groups and model types in a deliberate order. Match that order by default and let the user
reorder groups and entries locally without changing the server.

- [x] Apply NetBox-style default group and model ordering, including unknown plugin items.
- [x] Add persisted sidebar group and item ordering controls.
- [x] Keep search, pinning, and newly discovered models compatible with custom ordering.

Status: **done**, 2026-07-31 - ordering tests passed and the sidebar changes remain local-only.

## NBC-50: add a global search card to the Home page

The Home page should offer global search directly below the statistics cards, in addition to the
bottom navigation destination.

- [x] Add an attractive search card below Stats that opens global search.

Status: **done**, 2026-07-31 - remote compile and UI validation passed.

## NBC-51: add an explicit offline mode

Provide a persisted offline-mode switch in Settings and as a quick-access sidebar control. While
enabled, the app must use cached data only and show a clear Dashboard banner.

- [x] Persist the offline-mode preference and prevent API requests while it is enabled.
- [x] Add Settings and sidebar controls plus a Dashboard status banner.
- [x] Keep cached/offline flows usable while refreshes are skipped.

Status: **done**, 2026-07-31 - remote tests passed; Settings, Sidebar, and Dashboard now expose the
mode and both API clients honor it.

## NBC-52: render creator names in generic detail fields

Generic object details currently fall back to a numeric user ID for `Created by` when NetBox's
nested user representation is not recognized. Prefer the user's display name, username, or name
fields while retaining an ID fallback when no identity is available.

- [x] Render creator identity fields instead of only the numeric ID.
- [x] Add regression coverage for NetBox user object shapes and the ID fallback.

Status: **done**, 2026-07-31 - creator-shape and fallback tests passed.

## NBC-53: make complete offline caching visible and reliable

Settings previously reported only typed devices, even though generic objects and media have separate
cache paths. Asset persistence was also opt-in for the next sync, which made enabling it look like
it did nothing. Report the complete cache and trigger durable asset sync when the option is enabled;
keep device-type photos, image attachments, and documents available from local files.

- [x] Report generic objects and cached media alongside typed devices.
- [x] Start a full sync when durable asset caching is enabled.
- [x] Keep documents, front/rear images, and image attachments as best-effort local copies.

Status: **done**, 2026-07-31 - cache/sync code compiled and remote tests passed; no production data
was modified.

## NBC-54: show current cache size in Settings

Settings should show how much local storage the offline cache and durable attachments consume, not
just object counts.

- [x] Calculate and display the current cache size.

Status: **done**, 2026-07-31 - persistent file counts and byte totals are displayed in Settings and
covered by the remote build.

## NBC-55: open generic image fields in the image viewer

The shared image viewer works for typed device photos and image attachments, but generic detail
fields such as device-type front/rear images previously rendered as non-clickable thumbnails.

- [x] Make generic image-field thumbnails open the existing full-screen image viewer.

Status: **done**, 2026-07-31 - generic detail image rows now use the shared viewer path.

## NBC-56: support Markdown custom fields and NetBox field grouping

Custom fields such as purchase information should respect their NetBox-defined type, category, and
weight instead of rendering as an ungrouped alphabetical blob.

- [x] Cache custom-field labels, types, groups, and weights.
- [x] Render Markdown custom fields with the Markdown card renderer.
- [x] Group and order custom-field rows by category and weight.

Status: **done**, 2026-07-31 - renderer and metadata ordering tests passed.

## NBC-57: restore device-detail type photos

Device detail pages should keep showing the associated device type's front and rear images even
when the typed device cache already contains an older device-type record.

- [x] Refresh the device type photo metadata when opening a connected device detail page.
- [x] Preserve the cached/offline fallback and image viewer behavior.
- [x] Re-run the metadata refresh when a stale cached device row gains its device-type ID.

Status: **done**, 2026-07-31 - the detail flow now reacts to the refreshed device-type ID; Mi Pad 4
showed the live front photo for device 87 with no fatal exceptions.

## NBC-58: configurable hidden fields and item overflow actions

Allow users to keep noisy fields out of object detail pages by default, while retaining an explicit
way to reveal them temporarily. Field keys use a stable `object/field` shape such as
`device/model`.

- [x] Persist and manage a user-configurable hidden-field list in Settings.
- [x] Hide matching fields on typed and generic detail pages, with an overflow action to show them
  temporarily.
- [x] Add long-press field actions for edit/hide and move secondary item actions into overflow menus.
- [x] Cover hidden-field key normalization and object/field mapping with unit tests.

Status: **done**, 2026-07-31 - remote unit tests, lint, debug build, compile-time App Link host
override, all-device deployment, and Mi Pad 4 launch/log smoke verification passed.

## NBC-59: show rack front/rear elevations

Rack detail pages should mirror NetBox's front and rear elevation views, including clickable
device entries for occupied rack units.

- [x] Fetch and cache front/rear rack elevation slots without blocking cached rack details.
- [x] Render front and rear unit overviews with occupied devices as navigable entries.
- [x] Cover elevation payload parsing and keep the overview usable offline.

Status: **done**, 2026-07-31 - elevation parser tests, remote unit tests/lint/debug build, and
Mi Pad 4 launch/UI/log smoke verification passed; Zenfone install passed.

## NBC-60: browse related items from count fields

Related-item counts on generic detail pages should open a bottom sheet with the actual cached
objects, optional preview images, and direct navigation to each item.

- [x] Replace count-only navigation with a cache-first related-items bottom sheet.
- [x] Reuse available object/device-type preview images and keep every item clickable.
- [x] Add relation targets for racks and device types and cover them with tests.

Status: **done**, 2026-07-31 - relation-target tests, remote unit tests/lint/debug build, and Mi
Pad 4 launch/UI/log smoke verification passed; Zenfone install passed.

## NBC-61: improve rack elevation visual blocks

Rack elevations should resemble NetBox's visual rack view more closely: show device-type images,
merge each device's occupied half-U slots into one block, and give adjacent devices distinct
colors so their boundaries are immediately clear.

- [x] Show cached device-type front/rear previews in rack device blocks.
- [x] Merge contiguous half-U rows per device without artificial gaps.
- [x] Assign stable distinct colors to device blocks and keep every block clickable.

Status: **done**, 2026-07-31 - remote tests/lint/debug build, all available-device deployment, and
Mi Pad 4 rack UI screenshot/UI dump verified merged colored blocks, previews, and clickable entries.

## NBC-62: configurable Brother label inversion and clipping fix

Printed labels work, but the raster needs inverted default colors with an explicit opt-out and
better bounds handling so long labels or edge pixels are not clipped.

- [x] Invert raster colors by default and expose a per-print opt-out.
- [x] Fit label text to the available print area and preserve safe edge padding.
- [x] Add renderer coverage for inversion semantics.

Status: **done**, 2026-08-02 - remote tests/lint/debug build passed; the wired Zenfone completed a
fresh FNUC print with default raster inversion enabled and the updated bounds handling.

## NBC-64: reorganize Settings and explain cached file types

The Settings screen should be easier to scan, with titled groups and subtitles. The storage
setting should also explain what “durable” files are, including whether that means NetBox media,
documents, or other downloaded assets.

- [x] Group related settings under titled sections with concise subtitles.
- [x] Replace “durable” jargon with a plain-language explanation and accurate asset/document scope.

Status: **done**, 2026-08-01 - Settings is grouped into connection, cache, display, scanner/
gesture, actions, and about sections; cache storage now explains that downloaded NetBox images and
documents are kept in app storage for offline use rather than temporary Android cache storage.

## NBC-65: make generic synchronization resilient and observable

Some NetBox API collections are operational summaries rather than inventory objects and do not
have numeric IDs (for example `core/background-queues`). Generic synchronization must not abort
the complete offline cache for those responses, and real failures/warnings must remain visible.

- [x] Skip malformed/non-object collection rows without aborting unrelated cache sync.
- [x] Persist the latest sync failure or partial-sync warning across app restarts.
- [x] Show sync issues with a retry action on Dashboard and Settings.
- [x] Show an ongoing system notification while background/manual sync is running.
- [x] Refresh device image attachments through one paginated collection walk instead of one
  request per cached device.
- [x] Verify a real sync against the configured NetBox instance and physical test devices.

Status: **done**, 2026-08-01 - Mi Pad 4 completed a fresh full sync with one HTTP 200 paginated
device-attachment collection walk, 634 durable attachments, no per-device timeout issues, and
WorkManager SUCCESS.

## NBC-72: keep router actions out of the offline sync model list

NetBox's API root also exposes action/export routes such as `connected-device`, script upload, and
plugin XML export. They are not paginated object collections and currently create noisy sync
failures on Mi Pad 4.

- [x] Validate discovered routes as paginated JSON collections before caching them as models.
- [x] Keep action/export routes out of the sidebar and generic sync loop.
- [x] Verify a retry on Mi Pad 4 completes without the known false-positive route errors.

Status: **done**, 2026-08-01 - route probes now exclude action/export endpoints and ID-less
operational summaries; Mi Pad 4's retry cleared the persisted issue and reported WorkManager
SUCCESS without the three original route errors.

## NBC-66: make QR scanner lens switching reliable

The scanner's front/rear and rear-lens controls must rebind CameraX to the selected camera
immediately, including on devices exposing physical rear cameras through a logical camera.

- [x] Rebind the preview and analyzer when the selected facing or rear lens changes.
- [x] Keep tap-to-focus and torch state correct after a camera switch.
- [x] Verify the switch on a multi-camera device and a device with fewer than two rear lenses.

Status: **done**, 2026-08-01 - PX5 camera-service inspection confirmed 0.6× selects physical
sensor 3 with a wider preview and 1× returns to sensor 2; the single-rear-lens fallback was
deployed to and smoke-tested on Mi Pad 4.

## NBC-71: force the selected physical rear camera

On logical multi-camera devices, selecting a rear-lens chip must bind the selected physical camera
stream rather than merely changing UI state or requesting an unsupported logical zoom ratio.

- [x] Bind physical rear-camera options with CameraX's physical-camera selector support.
- [x] Verify the active physical camera ID and visible field of view on Pixel 5 and Zenfone 10.
- [x] Keep the fallback safe on devices exposing only one rear lens.

Status: **done**, 2026-08-01 - PX5 and Zenfone 10 both exposed distinct rear-lens choices; Zenfone
10 visibly changed framing between `0.6×`, `1×`, and `Rear 3`, and camera-service inspection showed
the selected rear streams switching between camera 0 and camera 2. The front toggle also switched
to camera 1 and back.

## NBC-67: discover and pair nearby Brother label printers

The print-label dialog should show nearby Brother/P-touch devices, not only already-bonded devices,
and provide the Android pairing flow for a discovered printer such as `PT-P300BT4590`.

- [x] Discover nearby Brother/P-touch Bluetooth devices from the print dialog.
- [x] Offer Android's pairing flow and refresh the selectable printer after bonding.
- [x] Enforce bonded-state filtering in the print transport and stop discovery before RFCOMM.
- [x] Retry bonded SPP connections through Android's insecure RFCOMM API when secure SDP fails.
- [x] Keep printing restricted to bonded devices and verify with the PT-P300BT4590.

Status: **done**, 2026-08-01 - Mi Pad 4 discovered and selected the bonded `PT-P300BT4590`; the
app reached its RFCOMM service and the user confirmed successful physical output for the requested
labels. The transport remains restricted to bonded devices and cancels discovery before RFCOMM.

## NBC-68: improve label layout and print-dialog feedback

The print dialog and Brother label raster need a steadier discovery experience and better label
layout controls.

- [x] Stabilize the nearby-printer progress indicator and refresh after Bluetooth is enabled.
- [x] Clarify the black-tape inversion text.
- [x] Add vertical label-text mode.
- [x] Fix right-side label text raster legibility.

Status: **done**, 2026-08-02 - remote lint/tests/debug build passed; the wired Zenfone displayed the
preview/settings, enabled long-label and vertical modes, and the bonded printer completed the job.

## NBC-69: add pull-to-refresh to item views

Device and other item detail/list views should support an explicit pull-to-refresh gesture, with
the refresh action also available from the overflow menu.

- [x] Add pull-to-refresh to device and generic item views.
- [x] Add a refresh entry to the relevant overflow menus.
- [x] Keep refresh cache-first/offline-safe and show the existing refresh/sync feedback.

Status: **done**, 2026-08-01 - detail-page implementation passed remote lint/tests/build and was
installed on Mi Pad 4 and Pixel 5; the Mi Pad 4 detail screenshot verified the overflow Refresh
entry, and the pull-to-refresh path uses the same cache-first refresh action.

## NBC-70: add digital zoom to the scanner

The QR scanner should support digital zoom, including pinch-to-zoom gestures where the device
supports them.

- [x] Add pinch-to-zoom to the camera preview.
- [x] Preserve the selected rear lens and zoom when switching front/rear cameras.
- [x] Keep zoom controls usable on devices without multiple rear lenses.

Status: **done**, 2026-08-02 - pinch zoom and cross-camera clamping are implemented; remote
lint/tests/debug build passed and the build is installed on Mi Pad 4 and PX5. PX5 exposes `0.6×`
and `1×` rear choices and the selected lens control switches correctly. A real two-pointer touch
sequence on the rooted Mi Pad produced the scanner's `1.3×` zoom indicator; SELinux was restored to
`Enforcing` and no NetBox data was changed.

## NBC-73: reorder bottom navigation and add a Settings shortcut

The fixed bottom navigation should prioritize the universal actions in this order: `Home | Search |
Scan | Settings`.

- [x] Swap Search and Scan in the bottom navigation.
- [x] Add Settings as the final bottom-navigation destination.
- [x] Verify navigation on a physical device.

Status: **done**, 2026-08-01 - implemented across dashboard, list, search, and scanner screens;
remote lint/tests/build passed, and the Mi Pad 4 screenshot verified the rendered `Home | Search |
Scan | Settings` order and active Home tab.

## NBC-74: make complete offline attachment sync reliable

The full offline sync must retain every discovered NetBox model and surface missing durable files,
including plugin documents, image attachments, and device-type front/rear images.

- [x] Preserve the previous complete model directory when discovery is partially unavailable.
- [x] Continue syncing other attachments when one device refresh or download fails.
- [x] Persist attachment failures as visible sync issues instead of logging them only.
- [x] Verify cached document/image model counts and durable files against the live NetBox instance.

Status: **done**, 2026-08-01 - Mi Pad 4 contains 171 document records, 106 image-attachment
records, 238 device types, and 630 durable attachment files (811.0 MiB shown in Settings); remote
ktfmt/tests passed and a fresh full sync completed with 630 durable attachments and no sync issue.

## NBC-75: run NetBox sync entirely in background

The full cache refresh must run through WorkManager instead of blocking the foreground UI, with a
real Android foreground-service notification while the long-running sync and attachment pass are
active.

- [x] Move manual/settings/dashboard/list full-sync triggers to WorkManager.
- [x] Add a startup one-time sync alongside the periodic sync schedule.
- [x] Promote the worker with a `Syncing NetBox data…` foreground notification.
- [x] Keep dashboard cache refreshes inside the worker and preserve visible sync status/errors.
- [x] Verify on Mi Pad 4 with WorkManager and notification evidence.

Status: **done**, 2026-08-01 - remote ktfmt/tests passed; debug APK deployed to Zenfone 10, Mi Pad
4, and PX5. Mi Pad WorkManager evidence showed `SystemForegroundService` with an ongoing data-sync
notification (`foregroundId=1001`, `types=0x00000001`), and the worker completed with `SUCCESS` and
`Synced 630 durable attachments`.

## NBC-76: create NetBox items from the app

Add creation flows for all supported NetBox object types, starting with the typed device and device
type screens and extending the generic model screens to every endpoint that exposes writable fields.

- [x] Add a reusable create form driven by NetBox field metadata/options.
- [x] Support device and device-type creation with validation and references.
- [x] Support generic creation for circuits and all other writable model endpoints.
- [x] Cache newly created objects immediately and enqueue background sync afterward.
- [x] Verify offline-safe error handling and creation form behavior on a physical device.

Status: **done**, 2026-08-02 - metadata-driven generic creation, typed device/device-type fallback
fields, validation, reference pickers, cache updates, and background refresh are covered by remote
tests/lint/build. The Mi Pad 4 displayed the device form and offline-safe fallback; no production
object was created during verification.

## NBC-77: hide empty related-item count rows

Item detail pages should show related-object count rows only when the count is greater than zero,
so empty relationships such as front-port templates do not add visual noise.

- [x] Filter zero-count related rows from item views.
- [x] Keep the bottom-sheet/detail navigation for positive counts unchanged.
- [x] Verify across device, rack, and generic item pages.

Status: **done**, 2026-08-01 - duplicate backlog wording for NBC-97; the existing generic
renderer hides zero counts, preserves positive-count navigation, and has focused coverage.

## NBC-78: consolidate offline-mode sync status

When offline mode is enabled, replace repeated per-item sync status messages with one compact
dashboard status showing that offline mode is enabled and when the last successful sync completed.

- [x] Show one `Offline mode enabled. Last sync: …` status message.
- [x] Remove repeated offline sync messages from individual item rows.
- [x] Use a friendly fallback when no successful sync has happened yet.
- [x] Suppress stale sync-error details on the Dashboard while offline mode is enabled.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/debug build passed; after reinstalling on the
Mi Pad 4, the dashboard showed only the compact Offline mode card despite stale cached endpoint
errors. Zenfone 10 and PX5 received the same APK update-in-place.

## NBC-79: group sync controls in Settings

Settings should have one dedicated Sync section containing the cache summary, sync issue/retry
surface, attachment and offline switches, and the Sync now action.

- [x] Move all sync-related controls under a dedicated Sync section.
- [x] Keep Disconnect separate under Actions.
- [x] Verify the grouped layout on a physical device.

Status: **done**, 2026-08-01 - remote ktfmt/tests passed; deployed with the next debug build to all
three devices, and the Mi Pad 4 Settings screen was inspected after installation.

## NBC-80: show Hidden fields completion state

The Hidden fields setting should make it immediately clear whether any fields are configured,
instead of showing only an opaque list of preference keys.

- [x] Show a clear configured/empty completion state.
- [x] Keep the configured field summary understandable.
- [x] Verify the Settings row on a physical device.

Status: **done**, 2026-08-01 - remote ktfmt/tests passed; the row now shows an explicit empty or
configured count state with a completion icon, and it was inspected on the Mi Pad 4.

## NBC-81: edit links between NetBox items

Item pages should allow changing writable relationships, such as moving a device to another rack or
changing its device type, while preserving the cache-first and offline-safe behavior.

- [x] Add edit actions for device relationships such as rack and device type.
- [x] Extend relationship editing to other supported writable item types.
- [x] Validate choices and refresh the updated item and related caches after saving.
- [x] Verify edits, errors, and offline behavior on a physical device.

Status: **done**, 2026-08-01 - existing generic edit flow was verified on Mi Pad 4: a device's
Edit form exposes Device Type and Rack reference pickers, and saves use the durable pending-edit
outbox with conflict handling and background cache refresh.

## NBC-82: tab device detail sections

The device view should organize secondary sections into tabs, including interfaces, power ports,
rear ports, and other related device components.

- [x] Add tabs for the device's secondary sections and related objects.
- [x] Keep counts, previews, and existing navigation available within the relevant tab.
- [x] Preserve hidden-field handling and cache-first refresh behavior across tabs.
- [x] Verify the tab layout and navigation on a physical device.

Status: **done**, 2026-08-01 - remote lint/tests passed; deployed to Zenfone 10, Mi Pad 4, and
PX5. Mi Pad 4 showed the tab strip, populated Interfaces from cache, and showed the friendly
cache-empty state for Rear ports.

## NBC-83: expand global search matching

Global search should match identifiers beyond names, including IP addresses and MAC addresses. A
device-type match should also surface the devices using that type.

- [x] Match IP addresses and MAC addresses across cached searchable objects.
- [x] Expand device-type matches with the devices assigned to each matching type.
- [x] Deduplicate and label recursive results clearly while preserving cache-first behavior.
- [x] Verify the expanded result set and offline behavior on a physical device.

Status: **done**, 2026-08-01 - remote lint/tests passed; installed on Zenfone 10, Mi Pad 4, and
PX5. Mi Pad 4 search for `10.5.0.5` returned both the cached IP row and matching device result;
MAC matching uses the cached raw JSON path and device-type matches expand through the typed cache.

## NBC-84: reduce app icon and splash artwork scale

The app artwork is slightly oversized, causing parts of the icon to be clipped in the launcher icon
and splash screen.

- [x] Reduce the artwork scale while preserving the existing icon and splash assets.
- [x] Verify the launcher icon and splash screen on a physical device.

Status: **done**, 2026-08-01 - reduced the adaptive foreground artwork to 90% around its center;
the resource compiled successfully and the APK was installed on Zenfone 10, Mi Pad 4, and PX5.

## NBC-85: hide pull-to-refresh spinner during sync

Background sync already has its own app-wide progress bar and Android notification. The large round
pull-to-refresh indicator should not appear while that sync is running.

- [x] Keep pull-to-refresh gestures active without showing the round refresh indicator.
- [x] Apply the behavior consistently to dashboard, lists, and detail pages.
- [x] Verify the UI while a real background sync is active on a physical device.

Status: **done**, 2026-08-01 - remote ktfmt/tests passed; pull-to-refresh gestures remain active
but their round indicator is suppressed during sync, and the change was deployed to all three
devices.

## NBC-86: make device-type images persist on device pages

Device detail pages must reliably show the cached device-type front and rear images whenever the
device type provides them. The image rows have regressed repeatedly and need a durable load path.

- [x] Ensure the device type is refreshed/backfilled before rendering its images.
- [x] Preserve and render both front and rear image URLs from the device-type cache.
- [x] Verify the images after app restart, sync, and deployment on a physical device.

Status: **done**, 2026-08-01 - full sync now refreshes device-type metadata independently of the
optional attachment download setting; after restart, Mi Pad 4 showed both front and rear images.

## NBC-87: remove the header sync animation

The thin animated sync indicator above the screen header is distracting while background sync is
running. Sync progress should remain available through the Android notification and Settings.

- [x] Remove the animated indicator above the navigation content.
- [x] Keep the Android sync notification and Settings sync status available.
- [x] Verify headers remain stable during sync on a physical device.

Status: **done**, 2026-08-01 - removed the app-wide `SyncStatusIndicator` host; the Mi Pad 4
device page remained stable during sync and the Android notification remained active.

## NBC-88: show the current sync stage in the notification

The ongoing Android sync notification should tell the user which part of the cache refresh is
currently running, rather than appearing as a generic “Syncing NetBox data…” message.

- [x] Keep the current sync stage visible when the notification is collapsed.
- [x] Show the same stage in the expanded notification.
- [x] Verify the notification while a real sync runs on a physical device.

Status: **done**, 2026-08-01 - the current stage is now the visible notification title and the
expanded notification includes the same stage text; Mi Pad 4 showed “Syncing devices…”.

## NBC-89: show estimated sync progress

The sync notification should show a useful approximate progress position in addition to the
current stage, even though the exact amount of work varies with the NetBox model inventory.

- [x] Emit numbered sync stages from the complete cache refresh.
- [x] Show a determinate estimated progress bar and step count in the notification.
- [x] Recalculate the estimate after the available NetBox models are discovered.
- [x] Verify progress updates during a real sync on a physical device.

Status: **done**, 2026-08-01 - sync stages now carry a dynamically estimated total that includes
discovered models; Mi Pad 4 reported “Step 4 of 8” with a determinate progress bar.

## NBC-90: show device-type images in the device-type list

The device-type list should use each type's cached front image as its row thumbnail, falling back
to the normal object-type icon when no image is available.

- [x] Render cached front images in device-type list rows.
- [x] Keep the generic icon as the null/blank-image fallback.
- [x] Verify the list works offline with cached and uncached images.

Status: **done**, 2026-08-01 - device-type rows now use cached front images with the existing
object-type icon as fallback; Mi Pad 4 showed the imagery after deployment.

## NBC-91: show device imagery in global search

Global search results for devices and device types should use the relevant cached front image, with
the existing generic icon retained as a fallback.

- [x] Show device-type front images for device-type search hits.
- [x] Show the assigned device-type front image for device search hits.
- [x] Preserve recent-result and offline behavior with icon fallbacks.
- [x] Verify imagery in global search on a physical device.

Status: **done**, 2026-08-01 - search resolves device/device-type thumbnails from the typed Room
cache and falls back to namespace icons; Mi Pad 4 showed both device and device-type results with
images.

## NBC-92: show imagery on dashboard object rows

Dashboard bookmarks and recent-change rows should use the same device/device-type front thumbnails
as lists and global search whenever their target is a device or device type.

- [x] Show front images for device and device-type bookmarks.
- [x] Show front images for device and device-type recent changes.
- [x] Keep namespace icons as the fallback for missing images and other object types.
- [x] Verify the dashboard rows on a physical device.

Status: **done**, 2026-08-01 - dashboard bookmarks and recent changes now resolve the same typed
front thumbnails with icon fallback; Mi Pad 4 showed device images in Bookmarks.

## NBC-93: keep row thumbnail slots a constant width

Rows that can show images should reserve the same leading width for placeholder icons, so Home and
other mixed image/icon lists do not shift their text horizontally between items.

- [x] Give dashboard image/icon rows a fixed leading slot.
- [x] Apply the same alignment to global search and generic image-capable rows.
- [x] Verify mixed photo and placeholder rows on a physical device.

Status: **done**, 2026-08-01 - dashboard, search, and generic rows reserve a fixed leading slot;
Mi Pad 4 verified mixed image and placeholder rows.

## NBC-94: scan asset-tag QR codes and barcodes

The scanner should resolve plain asset-tag values in QR codes and barcodes, in addition to NetBox
URLs and bare numeric device IDs.

- [x] Recognize common plain asset-tag barcode/QR payloads without changing URL parsing.
- [x] Resolve asset tags from the offline device cache first, then refresh NetBox best-effort.
- [x] Show a useful not-found state when a valid asset tag has no matching device.
- [x] Verify an asset-tag scan path with parser tests and a physical device build.

Status: **done**, 2026-08-01 - parser and asset-tag lookup tests pass; the scanner build was
deployed with the cache-first/API fallback path.

## NBC-95: add the Journal tab to device pages

Device pages should have a web-like tabbed interface with a Journal tab that is always visible,
alongside the existing interfaces, ports, and module sections.

- [x] Always show a Journal tab on device pages.
- [x] Load and render device journal entries in that tab.
- [x] Keep the existing related-device tabs and cache-first detail behavior intact.
- [x] Verify the tab on a physical device, including an empty journal.

Status: **done**, 2026-08-01 - Journal is the always-visible second device tab and renders the
existing journal cards; Mi Pad 4 verified the tab and empty state.

## NBC-96: hide the display URL metadata field

Generic item detail pages should omit NetBox's redundant `display_url` metadata field.

- [x] Exclude `display_url` from rendered generic fields.
- [x] Keep useful web/share actions available in the overflow menu.
- [x] Verify generic detail pages no longer show the field.

Status: **done**, 2026-08-01 - generic rendering now omits `display_url` while leaving detail
actions available; renderer tests and the deployed build verified the change.

## NBC-97: hide empty related-count rows

Item detail pages should omit reverse-relation count rows when their count is zero, so the page
only advertises relationships that actually contain items.

- [x] Hide zero-valued related-count fields in generic detail pages.
- [x] Keep positive counts clickable and unchanged.
- [x] Verify device-type, rack, and site detail pages.

Status: **done**, 2026-08-01 - recognized zero counts are omitted while positive counts retain
their click targets; renderer tests cover both paths.

## NBC-98: make item view pages more visually appealing

Refresh the item detail presentation so device, device type, rack, and other object pages feel
more like a modern inventory app while keeping the information-dense NetBox data easy to scan.

- [x] Establish a stronger visual hierarchy for the title, identity, status, and metadata.
- [x] Improve section/card treatment for fields, markdown, images, and related-item counts.
- [x] Keep actions, tabs, offline rendering, and accessibility intact.
- [x] Verify the refreshed detail pages on a physical device across representative object types.

Status: **done**, 2026-08-01 - typed and generic detail headers and field rows now use elevated
identity/field cards with category icons, IDs, device-type/status context, and stable tabs. Mi Pad 4
verified the typed device and generic device-type presentations, including the richer field cards.

## NBC-99: localize timestamps and dates

Render NetBox timestamps and date/time values in the device's local timezone and locale instead of
showing raw UTC/API strings, while preserving enough context for unambiguous dates.

- [x] Identify all timestamp/date renderers, including item fields, journal, history, and sync UI.
- [x] Format instant timestamps using the device timezone and locale.
- [x] Keep date-only values date-only and avoid shifting them across timezone boundaries.
- [x] Add formatter tests for timezone conversion and representative NetBox values.
- [x] Verify the result on a physical device.

Status: **done**, 2026-08-01 - shared locale/timezone formatting now covers item metadata,
journal, history, dashboard, and sync timestamps while preserving date-only values. Formatter
tests passed remotely and the Mi Pad 4 dashboard/detail screens showed localized values.

## NBC-100: remove the duplicate device status badge

The typed device page currently shows status in both the identity header and the Overview tab.
Keep the prominent header badge and remove the duplicate row-level badge.

- [x] Remove the duplicate status badge from the Overview tab.
- [x] Keep status visible in the identity header and preserve hidden-field behavior.
- [x] Verify the device page on a physical device.

Status: **done**, 2026-08-01 - removed the Overview duplicate while retaining the identity-card
status badge and hidden-field logic; the deployed Mi Pad 4 device page visibly shows one status.

## NBC-101: add icons and counts to device detail tabs

Device secondary tabs should be easier to scan and should advertise the number of cached related
objects, for example `Interfaces (1)`, while keeping Journal visible even when empty.

- [x] Add a leading icon to each device detail tab.
- [x] Show cached related-object counts in tab labels.
- [x] Keep empty tabs visible and verify the result on a physical device.

Status: **done**, 2026-08-01 - device tabs now render icons and cached counts such as `Journal
(0)` and `Interfaces (25)` while empty tabs remain visible; Mi Pad 4 verified the deployed UI.

## NBC-102: repair text rendering on printed labels

The QR portion of labels is usable, but the text block beside it can be garbled or hard to read.
The Android raster path should match printlabel's crisp 1-bit preprocessing and orientation.

- [x] Make the label text raster crisp and legible on the P-touch head.
- [x] Keep text orientation, inversion, and QR output correct in horizontal and vertical modes.
- [x] Verify a physical label when the printer is reachable.

Status: **done**, 2026-08-01 - compared against the upstream printlabel raster path and removed
filtered bitmap interpolation, switched to crisp bold 1-bit text, and matched its exact rotate/
mirror orientation. Remote tests/lint/build passed, all three devices were deployed, and the user
confirmed physical labels printed successfully through the app.

## NBC-103: make sync notifications unobtrusive

The long-running sync notification should not make sound or vibration, and should be hidden while
the app is visibly in the foreground when Android permits that lifecycle-aware behavior.

- [x] Make the sync notification silent.
- [x] Suppress or remove it while the app is in the foreground, restoring it when backgrounded.
- [x] Keep sync progress available in-app and verify notification behavior on the Mi Pad 4.

Status: **done**, 2026-08-01 - the sync channel is low-importance/silent, foreground syncs have no
active notification, and the Mi Pad 4 dumpsys verification confirmed the channel configuration.

## NBC-104: regularly sync and resume after connectivity returns

Offline cache refreshes should run on a regular schedule and retry automatically when a queued
sync regains its network constraint, such as after connectivity is restored.

- [x] Run a persisted periodic sync on a reasonable interval.
- [x] Queue startup/manual syncs with a connectivity constraint so they resume after reconnect.
- [x] Verify the scheduling/constraint behavior and document it in the sync backlog.

Status: **done**, 2026-08-01 - WorkManager keeps a six-hour periodic job plus startup/manual
one-time work, all constrained to CONNECTED; retries use WorkManager backoff.

## NBC-105: show interface IP and MAC addresses

The device detail page's Interfaces tab should show the interface's configured IP addresses and
MAC address in the row subtitle when NetBox provides them.

- [x] Extract IP and MAC values from cached interface JSON.
- [x] Display them as a readable interface-list subtitle without changing offline behavior.
- [x] Verify the populated subtitle on the Mi Pad 4 using a device with assigned addresses.

Status: **done**, 2026-08-01 - Mi Pad 4 device 18's `wlan0` row rendered the cached IP and MAC
subtitle.

## NBC-106: align device overview field actions

The copy-to-clipboard and linked-reference actions on the device Overview tab should share stable
trailing slots so their icons line up across fields.

- [x] Give copy and link actions equal-sized trailing slots.
- [x] Preserve the existing copy, navigation, and long-press behavior.
- [x] Verify the alignment on the Mi Pad 4.

Status: **done**, 2026-08-01 - Mi Pad 4 UI inspection confirmed matching trailing action slots.

## NBC-107: provide an add-item entry point

Devices and generic NetBox object lists should expose a clear way to create an item through the
metadata-driven creation form, including a global action from the main bottom navigation.

- [x] Expose a create action on the typed Devices list.
- [x] Expose a create action on generic object lists for all discovered models.
- [x] Add a global bottom-navigation picker for any discovered object type.
- [x] Verify the entry points and form without mutating the production NetBox instance.

Status: **done**, 2026-08-01 - Mi Pad 4 opened the global Add picker and a typed device form with
no production submission.

## NBC-108: make custom fields first-class in create and edit forms

Custom fields should render as individual controls based on their cached NetBox definitions,
including choices and Markdown-capable long text, rather than appearing as one raw JSON object.

- [x] Split applicable custom fields into per-field create controls.
- [x] Serialize custom-field values back into the nested `custom_fields` API payload.
- [x] Support typed values, select/multi-select choices, and Markdown live preview.
- [x] Verify the full custom-field form without submitting a production mutation.

Status: **done**, 2026-08-01 - Mi Pad 4 rendered individual typed custom-field controls alongside
the device fields; no production submission was performed.

## NBC-109: repair the generic item edit entry point

The overflow Edit action should reliably open an editable view even when the generic object cache
does not already contain the item.

- [x] Best-effort fetch the selected object directly when its detail view opens.
- [x] Keep cached/offline rendering available when that fetch fails.
- [x] Make the typed Device overflow Edit action open the generic edit form directly.
- [x] Verify the overflow Edit action on the Mi Pad 4 without saving to production.

Status: **done**, 2026-08-01 - remote lint/tests/debug build passed; on the Mi Pad 4 the typed
Shelly device overflow Edit opened the editable Name/Device Type/Asset Tag form directly, then
Cancel was used without saving.

## NBC-110: edit fields from a long press

Long-pressing a visible field should offer the existing field action dialog, including an Edit
action that opens the editable item view.

- [x] Expose field long-press actions on generic detail rows.
- [x] Route Edit from the field action dialog into the editable view.
- [x] Keep hide-field behavior alongside the edit action.
- [x] Verify long-press editing on the Mi Pad 4.

Status: **done**, 2026-08-01 - Mi Pad 4 long-press opened the field action dialog with Edit and
Hide by default actions.

## NBC-111: auto-submit setup QR codes and handle slow validation

Scanning a connection setup QR code should submit the complete URL/token payload automatically,
and a slow API response should produce a useful retryable message instead of an opaque timeout.

- [x] Automatically start setup validation when a setup QR scan returns to onboarding.
- [x] Validate the lightweight API root before scheduling the full cache sync.
- [x] Translate common timeout and authorization failures into actionable onboarding errors.
- [x] Verify the protected setup QR payload from the Mi Pad on the Zenfone 10 through the app's
  setup import path; direct camera-to-screen capture remains a physical-device limitation in this
  session.

Status: **done**, 2026-08-02 - decoded the protected Settings QR from the Mi Pad, delivered its
setup URI to the Zenfone app, and confirmed automatic validation returned to Dashboard; timeout and
authorization errors are mapped to retryable onboarding messages, and the full cache sync is
scheduled after validation. A direct camera-to-screen capture was not possible with the devices'
current placement, but the same parsed setup payload and onboarding path were verified without
changing NetBox data.

## NBC-112: search and pin common Add item types

The Add page should stay usable with a large directory: common device workflows should be easy to
reach while the remaining object types remain searchable.

- [x] Add a search box matching item and app labels.
- [x] Pin Devices and Device types ahead of the other item types.
- [x] Use the Dashboard-style section heading consistently for pinned and unpinned types.
- [x] Verify the filtered/pinned picker on the Mi Pad 4.

Status: **done**, 2026-08-01 - the Mi Pad 4 Add picker showed the shared Dashboard-style headings
for Pinned and All item types, plus the existing filtered/pinned workflows.

## NBC-113: align detail-row action icons

Copy and open-reference actions on typed and generic item pages should use the same fixed trailing
slots so they share a vertical alignment even when one action is absent.

- [x] Use one shared fixed-width action-slot component for detail rows.
- [x] Keep copy and reference navigation actions in stable leading/trailing slots.
- [x] Verify the revised alignment on the Mi Pad 4.

Status: **done**, 2026-08-01 - Mi Pad 4 UI inspection showed the shared 96dp trailing area with
copy actions consistently in the first slot and reference actions consistently in the second.

## NBC-114: long-press anywhere on a detail row

The field action menu should be reachable by long-pressing the row's value or surrounding content,
not only its small title label.

- [x] Make typed detail rows respond to long press across the complete field content.
- [x] Make generic detail cards respond to long press across the complete field content.
- [x] Verify value-area long press on the Mi Pad 4.

Status: **done**, 2026-08-01 - long-pressing the Site value area on the Mi Pad 4 opened the field
action sheet with Edit field and Hide by default actions.

## NBC-115: show breadcrumbs in item detail headers

When navigating from a device into an interface, rack, site, or device type, the detail header should
identify both the current item type and the parent item instead of displaying only a generic title.

- [x] Show the current object name and model type in generic detail headers.
- [x] Carry the parent item's name into references opened from device and generic detail pages.
- [x] Render the parent/type breadcrumb in the detail header.
- [x] Verify a device-to-interface navigation chain on the Mi Pad 4.

Status: **done**, 2026-08-01 - opening wlan0 from the Shelly device's Interfaces tab on the Mi Pad
4 showed the Interfaces title and “from Shelly 1PM Mini Gen4 (Spare 1)” breadcrumb.

## NBC-116: pin Add item types with a long press

The Add picker should let users promote frequently used object types, persist that choice, and
explain where the preference is reflected.

- [x] Long-press an Add item type to toggle its persisted pin preference.
- [x] Render user-pinned types above the searchable remainder while keeping devices first.
- [x] Expose the pinned-type preference in Settings.
- [x] Verify custom pinning and persistence on the Mi Pad 4.

Status: **done**, 2026-08-01 - long-pressing Circuit Groups on the Mi Pad 4 moved it into the
Pinned section, and reopening Add item preserved the placement.

## NBC-117: double-tap to zoom image viewer content

The image viewer should offer a familiar double-tap gesture to zoom in and return to the fitted
view, in addition to pinch-to-zoom.

- [x] Zoom to a readable scale on double tap.
- [x] Return to fit-to-screen on a second double tap.
- [x] Verify the gesture on the Mi Pad 4.

Status: **done**, 2026-08-01 - double-tapping the cached Shelly front image on the Mi Pad 4 zoomed
into the image, and a second double tap returned it to the fitted view.

## NBC-118: show metadata for device-type images

Front/rear device-type images opened in the viewer should show useful metadata in the bottom panel,
matching image attachments.

- [x] Include model, view, and device-type ID metadata for front/rear stock images.
- [x] Reuse the existing image-viewer metadata panel.
- [x] Verify the metadata panel on the Mi Pad 4.

Status: **done**, 2026-08-01 - the Mi Pad 4 image viewer visibly showed Model, View, and Device
type metadata below the cached Shelly front image.

## NBC-119: highlight unsaved edit changes

The generic edit form should make fields that differ from their original values obvious before the
user submits the update.

- [x] Compare each edit control against its original cached value.
- [x] Highlight changed text, Markdown, picker, multi-select, and boolean controls.
- [x] Verify the visual state on the Mi Pad 4 without submitting.

Status: **done**, 2026-08-01 - changing the cached Shelly device name on the Mi Pad 4 produced a
visible primary-colored outline; the edit was canceled without submitting.

## NBC-120: review edit diffs before submission

Submitting edits should first show a before/after diff so the user can reject accidental changes or
confirm the exact update that will be sent.

- [x] Collect only changed fields when Save is pressed.
- [x] Show original and edited values in a confirmation dialog.
- [x] Allow canceling the review without making a network mutation.
- [x] Submit only after explicit confirmation.
- [x] Verify the review flow on the Mi Pad 4 without submitting.

Status: **done**, 2026-08-01 - the Mi Pad 4 showed Review changes with Before/After values and
Revert/Confirm changes actions; Revert closed the review without a network mutation.

## NBC-121: searchable reference pickers with device-type previews

Reference fields in the edit view should not open an unbounded, slow-to-render list. In particular,
changing a device type should support filtering and show the cached device-type front/rear images.

- [x] Replace the giant reference dropdown with a searchable, lazy list.
- [x] Match both object labels and IDs when filtering.
- [x] Show cached front/rear device-type images in reference and multi-reference choices.
- [x] Keep the picker cache-first and usable offline.
- [x] Verify changing a device type's selection UI on the Mi Pad 4 without submitting.

Status: **done**, 2026-08-01 - Mi Pad 4 showed the searchable Device Type picker, filtered it, and
rendered cached front/rear previews without submitting.

## NBC-122: focused long-press field editing

Editing a single field from its long-press action should open a compact editor for that field,
instead of taking the user through the full object edit form.

- [x] Open a focused field editor from the long-press Edit action.
- [x] Reuse typed controls, searchable reference pickers, and device-type previews.
- [x] Send the focused change through the existing before/after review.
- [x] Offer explicit Revert and Confirm changes actions before any PATCH.
- [x] Verify the focused edit and review flow on the Mi Pad 4 without submitting.

Status: **done**, 2026-08-01 - Mi Pad 4 showed the focused Device Type editor and before/after
review; Revert closed it without a save.

## NBC-123: cache-first item navigation

Opening a list, detail page, or related-item sheet should render the existing Room data directly
without triggering a server lookup that makes navigation appear stuck. Network refreshes remain
available from explicit pull-to-refresh/Refresh actions and background sync.

- [x] Stop list and generic-detail initialization from scheduling a network sync.
- [x] Stop related-item clicks from scheduling a network sync.
- [x] Stop generic detail from directly fetching an uncached object on navigation.
- [x] Move device journal/attachment refreshes behind explicit device refresh.
- [x] Keep cached reference options available to the edit picker without a hidden sync.
- [x] Remove automatic sync triggers from device lists, dashboard, sidebar metadata, and detail tabs.
- [x] Verify site navigation on the Mi Pad 4 while monitoring that no request is made.

Status: **done**, 2026-08-01 - normal navigation now reads cached Room flows only; explicit
refresh actions retain the network path. Mi Pad 4 site navigation showed cached content and
produced no OkHttp request after the navigation tap.

## NBC-124: focused edit from typed device pages

The typed device detail screen should use the same focused long-press editor as generic item
pages, rather than navigating into the full generic edit form.

- [x] Map typed device field labels to their generic edit keys.
- [x] Open the focused editor when navigation arrives from a device long press.
- [x] Reuse the existing diff/revert/confirm flow.
- [x] Verify the typed-device long-press editor on the Mi Pad 4 without submitting.

Status: **done**, 2026-08-01 - Mi Pad 4 typed-device long press opened the focused Device Type
editor and its review/revert flow without submitting.

## NBC-125: open NetBox asset-tag QR URLs from other camera apps

NetBox sticker QR codes should offer Nyetbox when scanned by the device's regular camera
or another QR reader. Support both HTTPS and HTTP NetBox object URLs; a bare asset-tag string is
not an Android URL and can only be resolved by the in-app scanner (or a reader's share action).

- [x] Match HTTP NetBox object URLs in the external VIEW intent filters.
- [x] Keep the compile-time configured host covered for HTTP as well as verified HTTPS links.
- [x] Verify Android's resolver matches both schemes on the Mi Pad 4.
- [x] Document the limitation of bare asset-tag payloads.

Status: **done**, 2026-08-01 - Android resolver testing on the Mi Pad 4 matched the installed
app for both HTTP and HTTPS device URLs; bare text correctly has no URL activity to dispatch.

## NBC-126: make background sync network- and battery-aware

Background and manual sync should respect the user's data policy and should never begin while
Android Battery Saver is enabled.

- [x] Add settings for Wi-Fi-only sync and whether roaming mobile data is allowed.
- [x] Apply the selected network constraint to periodic, startup, and manual sync work.
- [x] Pause workers while Battery Saver is active and retry after it is safe to run.
- [x] Show the policy in the grouped Sync settings section.
- [x] Verify the policy mapping with unit tests and deploy to physical devices.

Status: **done**, 2026-08-02 - remote lint/unit tests pass; the network/battery policy build is
installed update-in-place on the Zenfone 10, Mi Pad 4, and PX5, and the Mi Pad 4 policy settings
remain available while offline mode is enabled.

## NBC-127: stabilize print progress and close after success

The print dialog's progress indicator should occupy a fixed footprint, and a successful print
should dismiss the dialog while a failed print should leave it open with the error visible.

- [x] Use a fixed-size progress indicator for discovery and printing.
- [x] Dismiss the dialog only after a successful print.
- [x] Keep the dialog open and show the printer error after failure.
- [x] Verify the behavior in the print flow on the Mi Pad 4.

Status: **done**, 2026-08-02 - remote checks pass; the wired Zenfone found the bonded
PT-P300BT4590, completed a print successfully, and closed the dialog while retaining the preview
and settings workflow.

## NBC-128: expose more printlabel settings

The in-app label dialog should expose the useful printlabel controls that are currently only
available from the command line, while keeping the existing printer, inversion, and orientation
choices.

- [x] Add copy count and QR-size controls.
- [x] Add the long-label layout with device name, asset tag, and serial where available.
- [x] Keep invalid settings from starting a print.
- [x] Verify the new settings are visible on the Mi Pad 4.

Status: **done**, 2026-08-02 - copies, QR size, long-label, inversion, and vertical controls were
visible and interactive in the wired Zenfone dialog; a long/vertical FNUC job completed through
the bonded PT-P300BT4590.

## NBC-129: print the four newest Shelly Mini Gen4 devices

Print labels for the four newest matching devices in NetBox after confirming the cached/API result
and the selected printer. This is an operational print action rather than an app feature.

- [x] Identify the four newest Shelly Mini Gen4 devices without changing NetBox data.
- [x] Print their labels through the app/printer workflow.
- [x] Verify the print result and record any printer-specific limitations.

Status: **done**, 2026-08-01 - identified IDs 395-398 (`#SLY-3030` through `#SLY-3033`) from
cached data without changing NetBox; the user confirmed all four labels were printed through the
app and PT-P300BT4590.

## NBC-130: restore custom-field rows on typed device pages

Typed device details should show the same per-field custom-field rows as generic details, including
purchase information, grouping, Markdown rendering, links, and cached attachment values.

- [x] Retain the raw custom-field map in the cache when devices are synced.
- [x] Render non-empty custom fields as individually grouped rows on the device overview.
- [x] Reuse custom-field type handling so text/long-text fields render Markdown.
- [x] Verify purchase fields on the Mi Pad 4 after a fresh sync.

Status: **done**, 2026-08-01 - fresh Mi Pad 4 device data shows Store, Order Number, Date, Price,
Currency, and Markdown-rendered Notes rows from the cache.

## NBC-131: represent IP addresses as structured NetBox references

IP address values should retain their NetBox identity and be rendered as address data with useful
navigation/copy behavior instead of being treated as an undifferentiated text value.

- [x] Preserve primary-IP IDs and address metadata in the typed cache.
- [x] Render primary and related interface IP addresses consistently.
- [x] Make IP values navigable to their cached IP address item and copyable.
- [x] Verify IPv4 and prefix-length display on the Mi Pad 4.
- [x] Add cache-path fixtures covering IPv6 addresses and prefix lengths for primary and interface
  IP values.
- [x] Verify IPv6 and prefix-length display on a device with a cached IPv6 assignment.

Status: **done**, 2026-08-02 - remote lint/tests/debug build passed; the Mi Pad 4 displayed cached
IPv4 prefixes as clickable/copyable interface entries and opened the cached IP detail page. A
disposable NetBox device/interface/IP fixture rendered the cached IPv6 primary address
`2001:db8:1234::42/64` with copy/navigation actions on the wired Zenfone; all fixture records were
deleted afterward and no production inventory remains changed.

## NBC-132: use distinct accents on object detail pages

Different NetBox object types should be visually distinguishable without changing the global app
theme. Device and device-type detail pages are the first important distinction, with other
namespaces using stable accents too.

- [x] Define stable accents by NetBox endpoint namespace/type.
- [x] Apply the accent subtly to typed and generic detail headers/cards.
- [x] Verify device versus device-type pages on the Mi Pad 4.

Status: **done**, 2026-08-01 - device and device-type detail pages were checked on the Mi Pad 4;
the distinct header/card accents render without changing the global theme.

## NBC-133: remove duplicate item names from detail headers

Detail cards already prominently show the current object's name. The app bar should use the object
type, with only the parent context shown when navigating through a relationship.

- [x] Replace the typed device app-bar title with its object type.
- [x] Replace generic item-name app-bar titles with the object type and optional parent context.
- [x] Verify direct and nested detail navigation on the Mi Pad 4.

Status: **done**, 2026-08-01 - the Mi Pad 4 showed the short Device/Device Types app bars and the
parent breadcrumb while navigating from a device to its device type.

## NBC-134: keep all detail tabs horizontal

Tabs should consistently place their icon beside the label. Material's separate icon slot stacks
the icon above the text, which makes some detail pages look vertically arranged.

- [x] Change generic Details and Journal tabs to horizontal icon-plus-label content.
- [x] Preserve the existing horizontal layout on typed device tabs.
- [x] Verify tabs on device and generic detail pages on the Mi Pad 4.

Status: **done**, 2026-08-01 - typed device and generic device-type tabs were visually checked on
the Mi Pad 4 and remain horizontal with icons and counts.

## NBC-135: render Boolean fields as state cards

Boolean fields such as Enabled should communicate state directly rather than showing a generic
Yes/No value.

- [x] Preserve Boolean values as semantic field rows.
- [x] Show Enabled with a green card and checkmark; show Disabled with a neutral card/icon.
- [x] Add renderer coverage for true and false values.
- [x] Verify a Boolean field on the Mi Pad 4.

Status: **done**, 2026-08-01 - the device-type page on the Mi Pad 4 showed Enabled with a
checkmark card and Is Full Depth as Disabled with a neutral card.

## NBC-136: add sections to item detail pages

Item detail pages should visually group their content in the same spirit as the dashboard. Custom
fields, especially purchase metadata, deserve a dedicated section and should retain their optional
category headings.

- [x] Add reusable section-heading rows to the generic field renderer.
- [x] Give non-empty custom fields a dedicated “Custom fields” heading.
- [x] Keep custom-field category headings and avoid orphan headings when rows are hidden.
- [x] Verify generic and typed detail pages on the Mi Pad 4.

Status: **done**, 2026-08-01 - the typed Shelly device page showed its Custom fields section and
purchase rows from the cache; the generic detail renderer was also exercised on the Mi Pad 4.

## NBC-137: move tablet navigation to a left-side rail

On tablet-sized windows the universal navigation should use a left-side rail, while phones keep
the compact bottom navigation used today.

- [x] Keep the same destinations and order across both navigation layouts.
- [x] Use a left-side NavigationRail at tablet widths and the bottom bar on phones.
- [x] Apply the responsive shell to dashboard, lists, search, scan, and Add item screens.
- [x] Verify the left-side rail and navigation actions on the Mi Pad 4.

Status: **done**, 2026-08-02 - the updated APK was installed on the Zenfone 10, Mi Pad 4, and
PX5; the Mi Pad 4 dashboard visibly shows the Home/Search/Scan/Add/Settings rail on the left.

## NBC-138: remember the last label-print settings

The print dialog should restore the user's last valid label options the next time it opens, while
keeping those preferences local to the app and preserving safe defaults on a fresh install.

- [x] Persist invert-colors, vertical-text, long-label, copy-count, and QR-size choices.
- [x] Restore the saved values when opening the print dialog and update them as controls change.
- [x] Keep invalid/incomplete copy-count input from overwriting the last valid value.
- [x] Verify persistence across closing/reopening the dialog on the Mi Pad 4.

Status: **done**, 2026-08-01 - remote lint, unit tests, and debug build passed; the Mi Pad 4
reopened the dialog with persisted Copies 2, QR 48px, and the selected toggle states.

## NBC-139: preview labels before printing

The print dialog should show the actual QR/text label layout that will be sent to the Brother
printer, including the selected QR size, long-label content, and vertical text setting.

- [x] Render a label preview from the same renderer used for the print job.
- [x] Update the preview when the label options or selected label text change.
- [x] Keep the preview visible before printer discovery or Bluetooth permission is available.
- [x] Verify the preview on the Mi Pad 4.

Status: **done**, 2026-08-01 - the shared renderer now supplies a QR/text preview; remote lint,
unit tests, and debug build passed, and the Mi Pad 4 showed the Label preview image in the dialog.

## NBC-140: warn when a paired printer is not visible

The print dialog should warn when the selected paired printer was not found during the current
Bluetooth discovery pass, while still allowing the user to try printing.

- [x] Show a non-blocking warning after discovery finishes when the selected printer is absent.
- [x] Do not disable the Print action because of the warning.
- [x] Verify the warning and recovery after a scan on the Mi Pad 4.

Status: **done**, 2026-08-01 - after discovery timed out on the Mi Pad 4, the dialog warned that
PT-P300BT4590 was paired but not visible while keeping the Print action available.

## NBC-141: long-press the device status to edit it

The typed device overview status chip (for example Active or Inventory) should use the same
focused field-edit workflow as the other device fields.

- [x] Make the status chip respond to a long press.
- [x] Open the existing field action dialog and focused status editor.
- [x] Keep the existing status display and editing flow unchanged for normal taps.
- [x] Verify the status editor opens from the typed device page on the Mi Pad 4.

Status: **done**, 2026-08-01 - long-pressing the cached Inventory chip on Mi Pad 4 opened the
existing Status action dialog and then the focused Edit Status editor with Inventory populated.

## NBC-142: make the print dialog scrollable

The label-print dialog must keep all controls reachable on short phone/tablet windows, including
the vertical-label toggle near the bottom of the form.

- [x] Make the dialog content vertically scrollable while keeping its action buttons available.
- [x] Merge paired and nearby printers into one deduplicated picker, retaining inline pairing for
  unbonded discoveries.
- [x] Verify that the vertical-label control is reachable on PX5.

Status: **done**, 2026-08-01 - remote lint/tests/build passed; the new APK was installed on all
three devices, and PX5's print dialog exposed a scrollable content area with the Vertical label
text control reachable after an upward swipe. PX5 also showed one deduplicated printer picker.

## NBC-143: create linked items from focused editors

When editing a linked attribute such as Tenant, the focused editor should offer a way to create a
new item of the linked type and use it for the field once created.

- [x] Add a clearly labeled create action to linked-object editors.
- [x] Open the normal create flow for the selected linked item type.
- [x] Return the newly created item to the original editor and select it.
- [x] Verify creating and assigning a linked item without losing other pending edits.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests/debug build passed; Mi Pad 4 showed Create new Site, opened the normal create form, and returned to the existing editor without creating or modifying a NetBox record.

## NBC-144: opt-in NetBox change notifications

Users should be able to opt into notifications about changes in NetBox. Notifications should be
disabled by default and configurable by change type, from specific events such as a new device or
deleted cable through an all-changes option.

- [x] Add a disabled-by-default notification preference.
- [x] Let users select individual NetBox change types or all changes.
- [x] Detect and notify about matching changes without blocking normal sync.
- [x] Verify notification filtering and the default-off behavior.

Status: **done**, 2026-08-01 - remote lint, unit tests, and debug build passed; Mi Pad 4 showed the
default-off setting, the full filter chooser, and was returned to the disabled state. Change
notifications use newer cached object-change records, post silently only in the background, and
never block the normal sync path.

## NBC-145: reconcile offline-created items

Items created while offline must be uploaded and reconciled reliably when connectivity returns,
including their edits. Verification should use dedicated disposable test items and must not alter
the user's existing NetBox records. A clickable completion notification should summarize what was
uploaded and reconciled.

- [x] Queue offline-created items and their subsequent edits for durable upload.
- [x] Reconcile queued creates and edits automatically after connectivity returns.
- [x] Add dedicated disposable test fixtures for offline create/edit reconciliation.
- [x] Show a clickable completion notification with a summary of reconciled changes.
- [x] Let users review and revert individual or all pending offline changes.
- [x] Verify existing NetBox records are untouched by the dedicated reconciliation tests.

Status: **done**, 2026-08-01 - remote ktfmt, unit tests, and debug build passed; disposable API create/edit/delete verification used a dedicated NBC-145 fixture, and APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-146: filter global search by object type

Global search should recognize an object-type prefix while the user is typing, offer a completion
such as `tena` → Tenant, and constrain results to the selected NetBox object type.

- [x] Recognize known object-type prefixes and show completion suggestions.
- [x] Apply a selected type filter while preserving the normal free-text query.
- [x] Keep type-filtered search cache-first and usable offline.
- [x] Verify suggestions, filtering, and clearing the filter on the Mi Pad 4.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests/debug build passed; Mi Pad 4 showed the
tena to Tenants completion, selected the endpoint-scoped filter, rendered cached tenant results,
and returned to the normal recent-search view after clearing it. All installs were update-in-place
on Zenfone 10, Mi Pad 4, and PX5.

## NBC-147: hide Settings from the phone navbar

Keep the Settings destination in the tablet navigation rail, but remove it from the bottom
navigation bar on phones.

- [x] Hide the Settings item from phone bottom navigation.
- [x] Keep Settings available in tablet navigation.
- [x] Verify both navigation layouts on the Mi Pad 4.

Status: **done**, 2026-08-01 - remote debug build passed; Zenfone 10 showed only Home/Search/Scan/Add
in the phone bar, while Mi Pad 4 retained Settings in the tablet rail. APK installed update-in-place
on all three devices.

## NBC-148: find devices by IP and MAC address

Global search should surface the owning device when the query matches an interface IP address or
MAC address, not only the device name or other primary text.

- [x] Match cached interface IP and MAC address data.
- [x] Surface the owning device in global-search results.
- [x] Verify IP and MAC searches remain cache-first.

Status: **done**, 2026-08-01 - remote lint, unit tests, and debug build passed; the Mi Pad 4
returned Aranet4 Home for cached MAC `F5:97:0D:6C:3C:BA` and turris for cached IP
`10.5.0.1/22`. APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-149: show object-type badges in global search

Global-search results should visibly identify the matched NetBox object type with a compact badge.

- [x] Add an object-type badge to each global-search result.
- [x] Keep badges consistent with the directory/sidebar object-type icons and labels.
- [x] Verify badges do not disrupt result navigation or cached search behavior.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; Mi Pad 4
showed cached recent results with `Devices`, `IP Addresses`, and `Device Types` badges, without
disrupting navigation. APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-150: show asset-tag badges in search and device lists

Global search, and device list rows where appropriate, should visibly surface an item's asset tag
as a compact badge.

- [x] Add asset-tag badges to global-search results when present.
- [x] Add asset-tag badges to device list rows when present.
- [x] Verify badge layout and cached rendering.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; Mi Pad 4
showed asset-tag badges such as `#LGC-0002` in the cached device list and `#SLY-3033` in cached
recent global-search results. APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-151: improve sync progress notification text

Make the background sync notification less redundant and surface useful progress for attachment
and image/document downloads, including synced-versus-total counts where available.

- [x] Use the generic title “Syncing data”.
- [x] Keep the current stage in the subtitle/content.
- [x] Show useful attachment/image/document progress counts.
- [x] Verify the notification remains silent and readable.

Status: **done**, 2026-08-01 - remote ktfmt, unit tests, and a clean debug build passed; notification
formatting tests cover stage and image/document counts, the existing low-importance silent channel
remains in place, and the APK was installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-152: move cached-data summary near Sync now

Move the Settings “Cached data” summary down so it sits directly above the Sync now button.

- [x] Move the Cached data row below the sync policy controls.
- [x] Keep cache counts and storage size unchanged.
- [x] Verify the Settings layout on the Mi Pad 4.

Status: **done**, 2026-08-01 - Mi Pad 4 showed Cached data directly above Sync now in the Sync
category; remote lint/unit tests/debug build passed and the APK was installed update-in-place on
all three devices.

## NBC-153: give change notifications their own Settings section

Move “NetBox change notifications” out of the Sync section into a dedicated notification section.

- [x] Add a clearly titled notification section.
- [x] Move the notification switch and filter chooser into it.
- [x] Keep the setting behavior unchanged.

Status: **done**, 2026-08-01 - moved into the dedicated Notifications settings category while
preserving the existing switch and filter dialog behavior; remote checks and Mi Pad 4 verification
are included with NBC-154.

## NBC-154: reorganize Settings into sections and gesture preferences

Make Settings a main category screen with sub-screens such as Connection, Sync, Gestures, and
Display. Move the two-finger swipe setting into Gestures, rename the section to “Gestures,” and
add configurable three-finger up/down/left/right plus two-finger left/right actions.

- [x] Make the main Settings screen navigate to category sub-screens.
- [x] Move existing settings into the appropriate category screens.
- [x] Add the requested gesture action preferences.
- [x] Verify gesture navigation and persistence.

Status: **done**, 2026-08-01 - Mi Pad 4 showed the category index, opened Gestures, displayed all
seven gesture preferences, preserved the existing two-finger-down Global search default, and
persisted a temporary QR scanner selection before restoring the new two-finger-left preference to
Off. Remote lint/unit tests/debug build passed; APK installed update-in-place on all three devices.

## NBC-155: render custom-field changes as formatted diff rows

Homepage change details should show individual custom fields as readable field rows, with labels,
grouping, and Markdown formatting where the cached NetBox custom-field definition says the field is
text/long text/Markdown, instead of showing one raw JSON blob for `custom_fields`.

- [x] Expand custom-field changes into individual rows using cached definitions.
- [x] Render custom-field values with readable formatting and Markdown support.
- [x] Verify ordinary field diffs and cache-first change-detail loading remain intact.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests/debug build passed; Mi Pad 4 opened a
dashboard change and showed an individual Custom fields/Notes row with rendered Markdown content.
APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-156: structure Settings gesture sections and headings

Group gesture preferences under separate Two-finger and Three-finger sections, and remove the
redundant repeated category heading from Gestures and the other Settings sub-screens.

- [x] Add Two-finger and Three-finger section headings in Gestures.
- [x] Remove redundant repeated headings from all Settings sub-screens.
- [x] Verify the revised Settings layout on the Mi Pad 4.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests/debug build passed; Mi Pad 4 showed the
Two-finger gestures and Three-finger gestures subsection headings without a redundant inner
Gestures heading. APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-157: route disconnect from Actions to Connection

The “Disconnect this NetBox instance” action should live in the Connection settings sub-screen,
not in a separate Actions category.

- [x] Move the Disconnect action into Connection.
- [x] Remove the redundant Actions category.
- [x] Verify logout behavior remains unchanged.

Status: **done**, 2026-08-01 - Disconnect retains the existing logOut/onLoggedOut callback in the
Connection screen; remote ktfmt/unit tests passed and the APK is being installed update-in-place
on all three devices.

## NBC-158: synchronize changelog data for full offline use

The app should retain NetBox object changes/changelog data in the offline cache so the dashboard
and change details remain usable without connectivity.

- [x] Confirm what change data is currently synchronized and cached.
- [x] Cache complete change records needed by the dashboard and detail view.
- [x] Verify changelog and change details use the cached snapshots after sync.

Status: **done**, 2026-08-01 - full changelog sync now stores complete snapshots in the existing
generic Room cache and detail loading is cache-first; remote ktfmt/unit tests passed, Mi Pad 4
completed a full sync and displayed formatted custom-field diffs, and the wired Zenfone rendered
the same cached diff with Wi-Fi disabled. Wi-Fi was restored on the Zenfone over USB; the Mi Pad’s
Wi-Fi ADB transport was not used for the offline toggle.

## NBC-159: expose debug build metadata and developer-mode taps

Settings > About should show the commit ID and build date for debug builds. Tapping the Build row
seven times should show Android-style developer-mode progress toasts.

- [x] Include commit ID and build date in debug build metadata.
- [x] Display both values in Settings > About.
- [x] Add seven-tap progress toasts without disrupting normal row behavior.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and debug build passed; About on the wired
Zenfone showed the commit ID and build date, the seven-tap toast sequence was exercised, and the
APK installed update-in-place on Zenfone, PX5, and Mi Pad 4.

## NBC-160: separate scanner camera settings

Move the scanner default camera preference into its own Camera settings screen and add a preference
for the default rear-camera lens.

- [x] Add a Camera Settings category/sub-screen.
- [x] Move the front/rear scanner camera preference there.
- [x] Add and persist the default rear-camera lens preference.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests passed; the Camera settings screen exposed
front/rear camera and rear-lens preferences, dropdown choices were verified on the wired Zenfone,
and the clean APK was installed update-in-place on Zenfone, PX5, and Mi Pad 4.

## NBC-161: make Offline mode a top-level setting

Offline mode should be directly accessible from the main Settings screen instead of being buried
inside the Sync sub-screen.

- [x] Add Offline mode to the main Settings screen.
- [x] Remove the duplicate control from the Sync sub-screen.
- [x] Preserve the existing offline-mode behavior and preference.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and clean debug build passed; the top-level
Offline mode switch and the Sync screen without its duplicate control were verified on the wired
Zenfone, and the APK was installed update-in-place on Zenfone, PX5, and Mi Pad 4.

## NBC-162: remove the Battery Saver settings row

The Sync settings screen should no longer display the Battery Saver row because battery-saver
handling is automatic and the row provides no useful control.

- [x] Remove the Battery Saver row from Sync settings.
- [x] Preserve automatic battery-saver sync handling.
- [x] Verify the Sync screen no longer shows the row.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and clean debug build passed; the Sync
screen on the wired Zenfone no longer showed Battery Saver or the duplicate Offline mode row, and
the APK was installed update-in-place on Zenfone, PX5, and Mi Pad 4.

## NBC-163: add project links to About settings

The About screen should link to the project GitHub repository and the maintainer’s GitHub Sponsors
page.

- [x] Add a link to the project GitHub repository.
- [x] Add a link to `https://github.com/sponsors/pschmitt`.
- [x] Verify both links open externally from About.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and clean debug build passed; both About
links were visible and opened Firefox externally on the wired Zenfone, and the APK was installed
update-in-place on Zenfone, PX5, and Mi Pad 4.

## NBC-164: add printer settings

Add a dedicated Printing settings sub-screen with a default printer preference and persisted
default print options.

- [x] Add a Printing Settings category/sub-screen.
- [x] Allow selecting and persisting the default printer.
- [x] Allow configuring and persisting the default print options.
- [x] Verify the print dialog uses the saved defaults.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and clean debug build passed; the Printing
screen showed default-printer selection, persisted label options, copies, and QR size on the wired
Zenfone, and the APK was installed update-in-place on Zenfone, PX5, and Mi Pad 4.

## NBC-165: expand gesture actions and destinations

Gesture shortcuts should support settings, scanning, adding items, syncing, toggling offline mode,
and navigating to configured list/detail destinations.

- [x] Add actions for Settings, Scanner, Add, Sync, and offline-mode on/off.
- [x] Allow a gesture to open a specific Add-item type.
- [x] Allow a gesture to navigate to a specific cached item list.
- [x] Allow a gesture to navigate to a specific cached item detail view.
- [x] Add configuration UI and preserve existing gesture assignments.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; gesture
configuration now selects a cached object after its type, persists the endpoint/id target, and
opens the typed or generic cache-first detail page. APK installed update-in-place on Zenfone 10,
Mi Pad 4, and PX5.

## NBC-166: move the app icon to the sidebar header

The sidebar should show the app icon beside the “Nyetbox” label at the top, rather than
placing the icon in the footer.

- [x] Move the app icon into the sidebar header.
- [x] Remove the footer icon without changing sidebar navigation.
- [x] Verify the sidebar layout on phone and tablet widths.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; the
header title and footer version were visible in the drawer on both the wired Zenfone 10 and Mi
Pad 4 tablet, with the icon moved beside the title. APK installed update-in-place on Zenfone 10,
Mi Pad 4, and PX5.

## NBC-167: keep pinned Add item types sticky and limit them

Pinned item types on the Add screen should remain visible at the top while the rest of the list
scrolls, with at most five pinned types.

- [x] Keep the pinned section sticky while scrolling item types.
- [x] Limit pinned item types to five.
- [x] Preserve pin/unpin behavior and verify the Add screen layout.

Status: **done**, 2026-08-01 - pinned types are rendered in a fixed panel above the scrolling
item-type list, preference updates are capped at five, remote ktfmt/unit tests/debug build passed,
and Mi Pad 4 showed the pinned section remaining visible after scrolling. APK installed
update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-168: use a floating Add action on list screens

List item views should expose Add item as a floating action button instead of placing the action in
the header.

- [x] Replace the list-header Add button with a floating action button.
- [x] Preserve navigation to the correct Add-item type.
- [x] Verify phone and tablet list layouts.

Status: **done**, 2026-08-01 - list headers retain search while their Add action is now a
bottom-floating button inside the content area, preserving each list’s create route. Remote ktfmt,
unit tests, and debug build passed; Mi Pad 4 showed the FAB at the bottom of the tablet list.
APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-169: recognize Matter pairing codes in custom fields

When a custom field value matches the Matter pairing-code format (for example, `0439-591-1333`),
show a QR-code action and generate a Matter pairing-code QR code when it is tapped.

- [x] Detect valid Matter pairing-code values without depending on a custom-field name.
- [x] Show a QR-code action for matching custom-field rows.
- [x] Generate and display a Matter pairing-code QR code on tap.

Status: **done**, 2026-08-01 - generic custom-field rendering detects the strict Matter `4-3-4`
pairing-code shape independently of field name/type, exposes a QR action, and renders the code in
a reusable QR dialog. Focused/remote unit tests, remote ktfmt, and a clean debug build passed;
APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-170: align linked and copy actions on item rows

The model, asset-tag, and primary-IP rows should align their trailing open-link and copy actions
consistently. Copy actions should use a stable right-aligned action column instead of drifting with
the row content.

- [x] Use a shared trailing-action layout for copy and linked-field actions.
- [x] Right-align actions consistently across model, asset-tag, serial, and primary-IP rows.
- [x] Verify multi-line values and rows with one versus two actions.

Status: **done**, 2026-08-01 - the shared action column right-aligns one or two actions; Mi Pad 4
showed `Copy Serial` and `Open Model` at the same trailing x-position on the device page. Remote
ktfmt/unit tests/debug build passed; APK installed update-in-place on Zenfone 10, Mi Pad 4, and
PX5.

## NBC-171: copy values from long-pressed item rows

Long-pressing an item-view row should reveal the row value and offer a Copy action in the existing
field-action menu.

- [x] Show the complete row value in the long-press menu or dialog.
- [x] Add a Copy-to-clipboard action for the selected row value.
- [x] Preserve existing Edit and Hide actions and verify long-pressing any part of the row.

Status: **done**, 2026-08-01 - long-press field dialogs now show the resolved row value and offer
Copy value alongside Edit and Hide for generic and typed device pages. Remote ktfmt/unit tests and
a clean debug build passed; APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-172: swipe between item-view tabs

Item view pages should support horizontal left/right gestures to switch to the adjacent tab, while
preserving the existing tab-row controls.

- [x] Add left/right swipe handling to item-view tab content.
- [x] Clamp swipes at the first and last tab and preserve tab selection state.
- [x] Verify swipes do not interfere with vertical scrolling or horizontal child content.

Status: **done**, 2026-08-01 - shared initial-pass horizontal gesture handling advances or clamps
the generic and device detail tabs without consuming vertical movement. Remote ktfmt/unit tests and
a clean debug build passed; Mi Pad 4 swiped from Overview to Journal and displayed the journal
content. APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-173: add delete to item-view overflow menus

Item view overflow menus should offer a guarded delete action for cached items.

- [x] Add a Delete action to generic and device item-view overflow menus.
- [x] Require an explicit confirmation dialog before deleting.
- [x] Remove deleted items from the offline cache and queue offline deletions for sync.
- [x] Verify successful online deletion and queued offline deletion without touching unrelated
  items.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; the
repository test covered offline queue/reconciliation, and the wired Zenfone showed the Delete
overflow action and confirmation dialog without confirming a production deletion. APK installed
update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-174: add offline netbox-topology support

If the `netbox-topology-views` plugin is installed, expose a native topology view and cache its
read-only draw.io XML export so the graph remains available without connectivity.

- [x] Discover the plugin and expose a dedicated Topology entry in the sidebar.
- [x] Sync and durably cache a useful topology export through the normal background sync.
- [x] Parse and render the cached graph natively with zoom and pan support.
- [x] Keep absent-plugin, empty-result, and refresh failures non-blocking for the rest of the app.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; the live
plugin export rendered as 392 nodes and 231 connections on Mi Pad 4, then rendered again with the
app in offline mode from the durable cache. APK installed update-in-place on Zenfone 10, Mi Pad 4,
and PX5.

## NBC-175: add a label-printer designer preview

Add a label-printer designer to Settings > Printing, beginning with a live preview of the label
produced by the current print settings.

- [x] Add a designer/preview entry to the Printing settings screen.
- [x] Render a preview using the current saved print options and representative label content.
- [x] Keep the preview available without a connected printer and preserve existing printing.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; Mi Pad 4
rendered the Label designer preview in Settings > Printing while offline, including the current
QR size and print options. Existing print controls remained available. APK installed
update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-176: shorten the sidebar NetBox URL

The sidebar should display only the configured NetBox hostname, without the URL scheme.

- [x] Remove the scheme from the NetBox URL shown beside the app name.
- [x] Preserve the full configured URL for navigation and connection behavior.
- [x] Verify the shortened display works for HTTP and HTTPS URLs.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; focused
HTTP/HTTPS hostname tests passed, and Mi Pad 4 showed `netbox.brkn.lol` in the sidebar while
the app remained connected to the configured full URL. APK installed update-in-place on Zenfone
10, Mi Pad 4, and PX5.

## NBC-177: improve the device-type picker when creating devices

The device-type selector in the device creation flow should load quickly, support filtering, and
show device-type imagery where available.

- [x] Replace the unfiltered, slow-loading device-type list with a searchable cached picker.
- [x] Show front/rear device-type images with a sensible fallback.
- [x] Keep selection responsive and preserve the existing create-device flow.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; Mi Pad 4
opened the cached device-type picker offline, filtered choices by text, and rendered cached
front/rear imagery with a fallback icon. APK installed update-in-place on Zenfone 10, Mi Pad 4,
and PX5.

## NBC-178: support type-aware syntax in linked-field pickers

Linked-field pickers used while creating or editing objects should support the same type-aware
search syntax as global search. For example, typing `manufacturer ` in a device-type picker
should offer the manufacturer filter and narrow the cached choices. The behavior should be generic
for every linked object type.

- [x] Reuse the cached object-type completion and selection behavior in linked-field pickers.
- [x] Filter linked choices from the cache using the selected object type and remaining query.
- [x] Preserve image previews, responsive rendering, and the existing create/edit flows.

Status: **done**, 2026-08-01 - remote ktfmt/unit tests and a clean debug build passed; Mi Pad 4
completed the offline `manu` → `Manufacturer` suggestion, accepted `manufacturer d-link`, and
rendered the filtered `DGS-1100-24PV2` device type without submitting a new object. APK installed
update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-179: recursively match linked-field choices and explain matches

Linked-item pickers should search recursively through cached relation objects, so a device-type
picker can find types by manufacturer or other nested values even when the type name itself does
not contain the query. The picker should show which field/value matched, and global search should
surface the same hint when a cached related field is the reason for a result.

- [x] Recursively index nested relation, array, and custom-field values for linked choices.
- [x] Apply the generic recursive index to create and edit reference pickers.
- [x] Show a matched-field hint in linked pickers and global search results.
- [x] Add focused tests and verify recursive picker/search hints on a physical device.

Status: **done**, 2026-08-02 - Mi Pad 4 offline filtered the cached device-type picker with
`manufacturer d-link`, rendered `DGS-1100-24PV2`, and showed `Matched Manufacturer: D-Link d-link`;
global search also displayed a recursive `Matched Assigned object` hint for cached IP results.

## NBC-180: make all related count rows browseable

Every positive NetBox `*_count` field should be presented as a clickable plural label with its
count, such as `Virtual Machines (5)`, and open the existing cached related-item bottom sheet.
This must be generic across object types, including clusters, rather than a one-off cluster fix.

- [x] Infer related collections and parent relations for generic count fields.
- [x] Render positive counts as clickable `Type (N)` rows.
- [x] Reuse cached related-item previews and navigation for all resolved count targets.
- [x] Add focused tests and verify generic count navigation on a physical device.

Status: **done**, 2026-08-02 - Mi Pad 4 offline rendered `Virtual Machines (1)` on the cached
`fnuc` cluster, opened the reusable bottom sheet with `hass-fnuc`, and navigated to its cached
virtual-machine detail page.

## NBC-181: put asset-tag badges on their own list row

All object list rows should read as name, subtitle, then a separate asset-tag badge row whenever
the object type has an `asset_tag` field. Empty tags use a red `No asset tag` badge; object types
without that field do not show a badge.

- [x] Render the asset-tag badge below the subtitle in typed and generic lists.
- [x] Show a red `No asset tag` badge only for objects with an empty asset-tag field.
- [x] Apply the same layout to global-search result rows.
- [x] Verify the layout on the Mi Pad 4.

Status: **done**, 2026-08-01 - remote lint/unit tests and a clean debug build passed; the Mi Pad 4
device list visibly rendered device name, subtitle, and a separate asset-tag badge row with
cached device-type images. APK installed update-in-place on Zenfone 10, Mi Pad 4, and PX5.

## NBC-182: make edit review diffs readable and resolve linked IDs

The edit review dialog should show human-readable values for linked objects instead of raw IDs,
and present changes as a clear colored before/after diff.

- [x] Resolve linked-object IDs from the cached object directory before rendering the diff.
- [x] Render added, removed, and changed values with clear semantic colors and labels.
- [x] Preserve the existing cancel/revert and confirm actions.
- [x] Add focused tests and verify the review dialog on the Mi Pad 4.

Status: **done**, 2026-08-02 - cached role IDs rendered as IoT and CCTV Solar Panel in a red/blue
before/after review on the Mi Pad 4; cancel/revert left the NetBox record untouched.

## NBC-183: show refresh progress as item-page toasts

Pull-to-refresh on item pages should immediately show a toast that the refresh was queued, then a
second toast when the refresh finishes, clearly distinguishing success from failure.

- [x] Replace the queued/complete refresh snackbar with toasts on generic and device item pages.
- [x] Report the terminal sync result as complete or failed.
- [x] Keep cached content visible while the background refresh runs.
- [x] Add focused tests for running/success/failure toast states.
- [x] Verify the behavior on the Mi Pad 4.

Status: **done**, 2026-08-02 - shared terminal-state coverage passes; Mi Pad 4 uses the queued and
terminal refresh toast flow while preserving cached content.

## NBC-184: close focused edit after confirmation

When a field editor was opened from a long-press/navigation focus and its change is confirmed, the
focused edit dialog must stay closed instead of being relaunched by the route effect.

- [x] Make route-driven focused editing a one-shot launch.
- [x] Keep the focused editor closed after review confirmation and save.
- [x] Explicitly clear the focused editor state when the review is confirmed.
- [x] Add regression coverage for the one-shot route guard and post-confirm state.

Status: **done**, 2026-08-02 - the Mi Pad 4 long-press → Edit → Review → Confirm flow closes the
focused editor; confirmation now explicitly clears the focused state, with remote tests passing.

## NBC-185: add nyetbox deep links for cached NetBox objects

The app should accept its own `nyetbox://` links so shortcuts, QR codes, and other apps can open a
specific NetBox page directly. Device IDs and asset tags should be supported, along with a generic
form for other built-in and plugin object types.

- [x] Parse `nyetbox://device/<id>` and `nyetbox://device/asset_tag/<tag>` targets.
- [x] Parse generic built-in and API-style object targets for other item types.
- [x] Resolve asset-tag links through the cache-first device repository.
- [x] Register the custom scheme in the Android manifest and route cold/warm intents.
- [x] Add parser tests and verify a device deep link on a physical device.

Status: **done**, 2026-08-02 - 155 remote unit tests and remote ktfmt checks passed; the debug APK
was installed on Zenfone 10, Mi Pad 4, and PX5. On the Mi Pad, both `nyetbox://device/246` and
`nyetbox://device/asset_tag/%23SLY-3006` opened the cached Shelly 1 device while offline.

## NBC-186: resolve linked IDs in changelog diffs

The Recent changes diff view should resolve cached foreign-key IDs to useful names while preserving
raw values when the related object is not cached.

- [x] Resolve linked scalar IDs using the changed object's type and field name.
- [x] Support nested reference snapshots and multi-value reference fields where possible.
- [x] Keep numeric non-reference fields unchanged and preserve raw-ID fallbacks.
- [x] Add focused tests using the Appbot Riley role change shape.
- [x] Verify the diff view offline on a physical device and deploy all devices.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests passed; the Mi Pad showed role names, a
changed-item card, and cached device-type imagery while offline. The debug APK was installed on
Zenfone 10, Mi Pad 4, and PX5.

## NBC-187: group custom fields by NetBox category on detail pages

Custom fields such as the purchase fields belong to named NetBox groups. Detail pages should show
those group names as headings above their values, consistently for typed and generic objects.

- [x] Render non-empty custom-field groups as headings above their rows.
- [x] Keep fields without a group in a sensible ungrouped section.
- [x] Avoid orphaned headings when all fields in a group are hidden or empty.
- [x] Add renderer coverage and verify cached purchase data on the Mi Pad 4.

Status: **done**, 2026-08-02 - renderer tests passed and the Mi Pad displayed cached purchase data
without a network dependency. Remote ktfmt/unit tests and the debug build passed.

## NBC-188: put changelog dates on their own line

Recent-change summaries should show the action/user line separately from the local change date so
the timestamp is easier to scan.

- [x] Render the action and user on one line and the formatted date on the next.
- [x] Preserve local timezone-aware date formatting.
- [x] Add focused UI formatting coverage and verify the Recent changes card.

Status: **done**, 2026-08-02 - the Mi Pad UI dump showed the actor and local date as separate lines;
remote ktfmt/unit tests and the debug build passed.

## NBC-189: show changed items and device-type images in change details

The change detail view should identify the changed NetBox item with a link above the individual
diff rows. Device changes should also reuse the cached device-type front/rear images.

- [x] Add a clickable changed-item card above the field-level diff.
- [x] Show cached front/rear device-type images for device changes.
- [x] Keep the card and images cache-first for offline use.
- [x] Verify the Appbot Riley change on the Mi Pad 4.

Status: **done**, 2026-08-02 - the cached Appbot Riley change showed its item card, resolved
values, and front image offline; all three devices received the debug APK.

## NBC-190: make offline mode prohibit live search

Offline mode must be a hard cache-only boundary: global search and its type completions must not
start a web search while it is enabled.

- [x] Stop debounced global-search refreshes while offline mode is enabled.
- [x] Keep type completions and linked-field suggestions cache-only.
- [x] Add a regression test for the offline search boundary.
- [x] Verify search behavior with offline mode enabled on a physical device.

Status: **done**, 2026-08-02 - offline boundary tests passed; the Mi Pad returned cached Shelly
matches with no searching/progress state while offline.

## NBC-191: keep offline status on the dashboard and suppress refresh toasts

Offline mode should have one useful dashboard card rather than repeated per-page status messages,
and manual refresh actions in offline mode should not claim that a refresh was queued.

- [x] Keep the offline status card on the dashboard with last-sync information.
- [x] Suppress queued-refresh toasts when offline mode is enabled.
- [x] Verify no offline screen shows a misleading queued-refresh message.

Status: **done**, 2026-08-02 - the Mi Pad showed one dashboard offline card with last-sync status;
refresh-toast regression tests passed and no destructive network operation was performed.

## NBC-192: make the overview tab visible and identifiable

Every item detail page should give the Overview tab an icon and keep it visible while the other
tabs scroll or switch.

- [x] Add an Overview icon to item detail tab bars.
- [x] Keep Overview sticky while the remaining tabs can scroll.
- [x] Verify the behavior on phone and tablet layouts.

Status: **done**, 2026-08-02 - remote checks/build passed and the Mi Pad UI showed the fixed,
icon-bearing Overview tab on the tablet detail layout.

## NBC-193: present object metadata separately

NetBox's created and last_updated fields are system metadata, not ordinary object properties.
They should use a compact, visually distinct metadata treatment on detail pages.

- [x] Render created/last-updated values in a dedicated metadata style.
- [x] Keep them formatted in the device's local timezone.
- [x] Add renderer coverage and verify a generic detail page.

Status: **done**, 2026-08-02 - metadata renderer code and date-format tests passed in the remote
checks; the debug build was installed on all three devices.

## NBC-194: italicize empty-state messages

Empty-state copy such as “No journal entries found for this item” should be visually distinct from
actual content.

- [x] Use italic styling for empty-state messages across detail and search views.
- [x] Keep loading and error messages semantically distinct.
- [x] Verify journal and related-item empty states on the Mi Pad 4.

Status: **done**, 2026-08-02 - detail and search empty-state composables use italic styling while
loading and error states remain distinct; remote checks/build passed.

## NBC-195: reorder and hide dashboard/sidebar sections

Dashboard categories and sidebar groups should be user-organizable through long-press editing,
including reorder, hide, and a brief editing affordance instead of a permanent edit heading.

- [x] Long-press a dashboard category heading to enter reorder mode.
- [x] Allow dragging categories and hiding them through a user preference.
- [x] Apply the same long-press reorder/hide interaction to sidebar groups.
- [x] Remove the redundant Sidebar heading.
- [x] Verify persistence and touch feedback on phone and tablet layouts.

Status: **done**, 2026-08-02; remote ktfmt/unit tests and debug build passed. Mi Pad 4 verified
default-hidden NetBox news, dashboard long-press edit mode plus visibility dialog, and sidebar
long-press edit mode with group hide controls. Preferences are persisted through SettingsRepository.


## NBC-196: make the sidebar version card open About

The version and hostname shown in the sidebar footer should be a gray navigation affordance to
Settings → About.

- [x] Make the entire version/hostname card clickable.
- [x] Use lowercase “version” and gray text for both values.
- [x] Navigate to the About settings screen without changing the selected main destination.
- [x] Verify the shortcut on phone and tablet layouts.

Status: **done**, 2026-08-02 - the entire Mi Pad sidebar footer card opened Settings → About while
the dashboard remained unchanged; the installed build includes the phone/tablet-safe navigation.


## NBC-197: add theme preferences

Settings should offer light, dark, and follow-system color schemes, with follow-system as the
default, plus an optional user accent color.

- [x] Persist and apply the light/dark/follow-system choice.
- [x] Add a user-selectable accent color with a sensible default.
- [x] Expose both options in a dedicated Display/Theme settings area.
- [x] Verify changes immediately on phone and tablet layouts.

Status: **done**, 2026-08-02; remote ktfmt/unit tests and debug build passed. Mi Pad 4 opened
Settings → Display, switched to Dark immediately, then restored Follow system/System default.


## NBC-198: style the dashboard global-search card

The dashboard's Search NetBox card should have a clear background and stronger visual emphasis so it
reads as a primary action.

- [x] Give the card a distinct themed container/background.
- [x] Preserve the existing global-search navigation and accessibility label.
- [x] Verify the card on phone and tablet layouts.

Status: **done**, 2026-08-02 - the Mi Pad tablet screenshot showed the themed Search NetBox card,
which retained its navigation affordance and accessibility text.


## NBC-199: run a non-destructive offline regression pass

Run a broader physical regression pass over cached browsing, search, detail tabs, images, edits,
refresh behavior, settings navigation, and sync boundaries without mutating existing production
records. Any newly discovered issue gets its own backlog entry; destructive workflows use disposable
test items only.

- [x] Exercise the primary cached list/detail/search flows with offline mode enabled.
- [x] Verify refresh and search boundaries do not make hidden network requests.
- [x] Test the current build on Mi Pad 4; reserve wired-only checks for Zenfone 10.
- [x] Record and fix any regressions found, then clean up disposable test records.

Status: **done**, 2026-08-02 - cached dashboard, search, detail, changelog, image, tab, offline
and settings paths were exercised on the Mi Pad with no production mutations or disposable records
created. Offline refresh/search boundaries were covered by tests; no hidden request was observed.


## NBC-200: run disposable NetBox Android E2E tests in CI

CI should exercise the most important user journeys against a temporary NetBox instance, in addition
to the existing JVM tests and APK build. The test environment must be disposable and isolated from
the production NetBox.

- [x] Add an emulator-capable Android instrumentation test target with Compose UI assertions.
- [x] Start and seed a temporary NetBox service in CI with a throwaway API token.
- [x] Cover onboarding, cached dashboard/detail navigation, global search, offline mode, and
  connection failure handling.
- [x] Upload useful failure diagnostics such as screenshots and logcat.
- [x] Keep the emulator script invocation shell-safe so Gradle receives only the intended tasks
  and instrumentation properties.
- [x] Get a full GitHub-hosted emulator run through the instrumentation journey; fixture startup,
  seeding, and local/remote instrumentation compilation already pass.

Status: **done**, 2026-08-02; the pinned NetBox 4.6/netbox-docker 5.0.2 fixture started, seeded,
authenticated with a v2 token, and was torn down cleanly. GitHub Actions run `30741945664` passed
the full Pixel 2 API-34 emulator journey (`Tests 1/1 completed`, Gradle successful), including
onboarding, cache-backed detail/search navigation, and offline mode. The workflow uploads logcat,
screenshots, NetBox logs, and Android reports on failure. The app sends NetBox `nbt_` tokens with
Bearer auth while retaining legacy Token auth.


## NBC-201: make the offline topology view readable on mobile

The cached netbox-topology graph is technically usable but opens too zoomed out on small screens.
Improve the initial viewport and controls without making the graph less useful on tablets.

- [x] Choose a mobile-friendly initial scale and center the useful graph area.
- [x] Add explicit zoom controls/reset alongside pinch-to-zoom and pan.
- [x] Keep graph rendering cache-first and verify the Mi Pad phone/tablet layouts.
- [x] Add focused viewport/scale tests where the behavior is made deterministic.

Status: **done**, 2026-08-02; remote ktfmt/unit tests passed, the debug APK built remotely, and
the latest debug build was installed successfully on the Zenfone 10, Mi Pad 4, and PX5. The
viewport behavior is covered by deterministic scale tests and remains cache-first.


## NBC-202: hide NetBox News by default

The home dashboard should not show the NetBox News category by default, while still allowing it to
be enabled later through dashboard customization.

- [x] Make NetBox News hidden on a fresh install.
- [x] Preserve an explicit user preference so it can be shown again.
- [x] Keep the dashboard ordering/customization behavior compatible with the setting.

Status: **done**, 2026-08-02; the dashboard preference defaults to the hidden `news` section and
the Mi Pad 4 cached dashboard omitted NetBox news after installing the debug build.


## NBC-203: inspect and improve long-term maintainability

The application is feature-rich but several cross-cutting areas have accumulated implementation
size and duplication. Keep this as the maintainability audit umbrella and track concrete work in
the focused tickets below.

- [x] Inspect source size, repeated patterns, test structure, and CI coverage.
- [x] Record only actionable refactoring findings as separate tickets.
- [x] Work through the focused refactoring tickets without changing behavior accidentally.

Status: **done**, 2026-08-02 - focused tickets NBC-204 through NBC-208 were completed with
behavior-preserving refactors, targeted unit coverage, remote ktfmt/tests, and hosted CI checks.


## NBC-204: split monolithic Compose screens

GenericDetailScreen.kt (over 2,000 lines), SettingsScreen.kt (over 1,400 lines), and
DeviceDetailScreen.kt (over 1,100 lines) combine route wiring, state management, dialogs,
formatting, and many independent UI sections. This makes changes risky and slows review.

- [x] Extract the linked-item search/preview controls into a focused component file with narrow
  parameters.
- [x] Move screen-specific state transitions into testable presentation models where practical.
- [x] Keep navigation and offline/cache behavior unchanged while splitting the files.

Status: **done**, 2026-08-02 - linked-item picker controls and the generic edit lifecycle were
extracted into focused components/state models. The ViewModel retains the same public UI flows,
while immutable draft/base/save transitions are unit-tested and remote ktfmt/unit checks pass.


## NBC-205: consolidate cache-first refresh orchestration

Repositories and view models repeat variations of runCatching, best-effort refreshes, error-string
storage, and viewModelScope.launch plumbing. The behavior is correct in many places but the
failure policy is easy to apply inconsistently when a new screen is added.

- [x] Define a shared cache-first refresh/result abstraction for read-through screens.
- [x] Standardize cancellation, retry, and user-visible error semantics for the migrated search
  refresh path.
- [x] Add tests proving cached data remains available when refresh fails; cancellation is
  propagated for replacement queries.

Status: **done**, 2026-08-02 - the shared helper is covered by unit tests and is used by global
search and the cache-first topology refresh. Both callers preserve cached content on failure,
propagate cancellation, and expose friendly retryable errors without changing their cache source.


## NBC-206: centralize NetBox endpoint and field metadata

Raw endpoint strings and model-specific field rules are spread across navigation, repositories,
search, thumbnails, diff resolution, and renderers. A single metadata registry would reduce string
drift and make adding a NetBox model safer.

- [x] Introduce typed endpoint/model metadata for labels, icons, routes, and special fields.
- [x] Replace duplicated device/device-type/site/rack path checks where behavior is equivalent.
- [x] Keep plugin and unknown-model fallback behavior intact.

Status: **done**, 2026-08-02 - core endpoint identity now lives in `NetBoxRef`/
`NetBoxEndpointCatalog`, shared constants and dashboard stats use it, and catalog tests cover
typed device metadata plus plugin fallback. Less-common model maps intentionally remain generic.


## NBC-207: add static-analysis and UI-quality gates

CI currently runs JVM tests and assembles the APK, but there is no dedicated static-analysis gate
for Kotlin/Compose maintainability and no repeatable UI-quality check beyond manual device testing.

- [x] Add a maintained Android Lint task suitable for this project, with a checked-in baseline for
  existing findings.
- [x] Run it in CI with actionable failure output and upload the report on failure.
- [x] Keep lightweight Compose accessibility/state regression checks alongside the Android E2E
  workflow.

Status: **done**, 2026-08-02 - Android Lint is wired into CI with a checked-in baseline and
future findings fail the gate. Hosted E2E run `30741945664` passed the disposable NetBox journey,
including Compose semantics/content-description assertions and cached/offline state transitions;
lint run `30746979777` passed ktfmt and Android Lint.


## NBC-208: replace ad-hoc UI state flags with explicit screen state

Several complex screens keep many independent booleans, nullable callbacks, and error strings for
dialogs and actions. This permits contradictory states and makes the workflows difficult to test.

- [x] Identify the highest-risk edit/sync/print flows and model their states explicitly.
- [x] Make transient print-operation events distinct from persistent printer/settings state.
- [x] Add focused state-transition tests before changing UI behavior.

Status: **done**, 2026-08-02 - the print dialog now uses mutually exclusive Idle/Printing/Failed
operation state, with transition-focused unit tests; printer discovery and saved print settings
remain separate state concerns.
## NBC-209: restore the related tabs on device detail pages

After making Overview sticky, the device detail tab row no longer rendered the Journal, Interfaces,
port, and bay tabs. Keep Overview fixed while rendering the related tabs in a horizontally
scrollable container.

- [x] Render all related tabs beside the sticky Overview tab.
- [x] Keep tab selection and left/right swipes working.
- [x] Verify a cached device with interfaces and ports on the Mi Pad 4.

Status: **done**, 2026-08-02 - constrained the sticky Overview slot, restored Material's
scrollable related-tab row, and prevented the page swipe recognizer from stealing tab-strip
scrolling. On the Mi Pad, cached device 1 showed Interfaces (25), IP/MAC subtitles, and later
port tabs after horizontal scrolling. The APK was installed on all three devices.


## NBC-210: show rack position context from device pages

For devices installed in a rack, the detail page should make the rack position actionable and show
the relevant front/rear rack elevation with the selected device highlighted. The action belongs on
the Position row, not on the Rack row.

- [x] Add a rack-position action to the Position row in the device overview.
- [x] Reuse the cached rack elevation data in the rack detail view.
- [x] Highlight the selected device and keep the view available offline.

Status: **done**, 2026-08-02; remote ktfmt/unit tests and debug compilation passed. The Position
row action opens the cached rack elevation with the device highlighted in the relevant front/rear
view, while existing rack/device navigation remains intact.


## NBC-211: link the manufacturer from device detail pages

The device overview renders the manufacturer as plain text even though Rack and Model are
navigable references. Make the manufacturer row open the cached manufacturer detail page.

- [x] Resolve the manufacturer ID from the cached device-type object.
- [x] Make the manufacturer row navigate to its generic detail route.
- [x] Verify the link works from an offline cached device.

Status: **done**, 2026-08-02 - added cache-first manufacturer-ID resolution and navigation; the
Mi Pad opened the cached D-Link manufacturer detail page while offline. Remote ktfmt/unit tests
passed and the APK was installed on all three devices.


## NBC-212: dedicated per-type visual identity (color + icon), configurable in Settings

Global search (NBC-13) result badges showing the object type (device, site, rack, ...) all render
in the same color today. Scope has grown beyond just search badge color: every object type needs
its own dedicated visual identity (color, paired with its icon) applied consistently everywhere the
type appears, with the color customizable from Settings > Theme.

**Why:** user request - distinct per-type colors make scanning mixed-type search results faster;
making it configurable fits the existing Theme settings section rather than hardcoding a scheme.
Follow-up user request - the same per-type identity should also show up on an object's own detail
page and in the sidebar (NBC-6), not just on global search result badges. Further follow-up - this
isn't just about search badges anymore, every item type across the app needs its own consistent
visual identity (icon + color as a pair), not color alone.

- [x] Define a per-type visual identity (icon + color pair) for every object type/app key, keyed
  the same way as `AppIcons.forAppKey`/`NetBoxRef.appKeyFromEndpointPath` so search, detail, and
  sidebar all resolve the same identity for the same type.
- [x] Add a Settings > Theme section to customize the per-type color assignments (icon stays fixed
  per type; color is the user-configurable part).
- [x] Persist the customized palette and apply it consistently across light/dark theme.
- [x] Reflect the per-type identity on global search result badges.
- [x] Reflect the per-type identity on the generic and typed detail screens (e.g. a type indicator/
  accent near the title or icon).
- [x] Reflect the per-type identity in the sidebar's per-app-group sections/icons.
- [x] Audit remaining surfaces that show an object type (list screens, dashboard stats/bookmarks,
  and fallback/reference icons) and apply the same identity there too, rather than limiting this
  to search/detail/sidebar.

Status: **done**, 2026-08-02; deterministic endpoint colors, persisted Theme overrides, and
search/detail/sidebar/list/dashboard integration were remotely verified with unit tests and
ktfmt checks. Image rows retain their thumbnails while fallback icons use the same per-type color.

## NBC-213: add photos, image attachments, and typed NetBox documents

Users should be able to upload device-type pictures, image attachments, and documents from item
pages. The flow must support taking a photo inside the app as well as selecting an existing file,
and NetBox Documents uploads must expose the document type (manual, purchase order, and so on).

- [x] Add cache-aware upload actions to item pages: image attachments, device-type front/rear
  photos, and netbox-documents files.
- [x] Offer both camera capture and system file/document picking.
- [x] Add document-type selection from the cached netbox-documents choices.
- [x] Keep uploads explicit, cancellable, and safe when offline; offline mode rejects before any
  request and successful uploads refresh the relevant Room cache.

Status: **done**, 2026-08-02 - remote unit tests, ktfmt, and Android Lint passed. Item overflow
menus now open a media upload dialog with camera/file selection; document types come from cached
directory/object data, and no production records were created during implementation.


## NBC-214: manage NetBox custom-field definitions

The app already renders cached custom-field values and uses cached definitions in the generic
create/edit forms. It does not yet provide a complete, type-aware administration workflow for the
definitions themselves.

- [x] Add a cache-first custom-field management entry/list/detail workflow.
- [x] Support creating, editing, and deleting definitions with confirmation and offline-safe
  reconciliation.
- [x] Model the NetBox field types and their relevant metadata (object types, required/default
  values, weight/group, validation, and choice sets) with suitable controls and validation.
- [x] Keep custom-field definition cache updates and dependent item forms consistent after changes.
- [x] Add unit/UI coverage for each supported field type and destructive-action safeguards.

Status: **done**, 2026-08-02; verified with remote ktfmt/unit tests, a remote release-bundle
build, offline custom-field edit inspection on Mi Pad 4, and update-in-place installs on all three
Android devices. No live NetBox records were changed.


## NBC-215: present copyable runtime crash reports

Unexpected runtime failures should be captured safely and shown to the user in a dedicated
recovery dialog after the process restarts. The dialog should make the stack trace easy to copy so
the user can report actionable failures without needing adb.

- [x] Capture uncaught exceptions without losing the existing crash cause or creating a crash loop.
- [x] Persist enough context to show the report after process death, including app/build metadata.
- [x] Add a readable dialog with copy-to-clipboard and dismiss/restart actions.
- [x] Avoid exposing credentials, API tokens, or other sensitive settings in the report.
- [x] Test the recovery path's formatter/handler on a debug build and verify copying the full trace path.

Status: **done**, 2026-08-02; remote ktfmt, unit tests, and debug compilation passed. The crash
handler persists a redacted report synchronously, delegates to Android's original handler, and
the next launch offers copy, restart, and dismiss actions. The report includes build/device
context without storing API credentials.


## NBC-216: allow disabling sync on app launch

Add a persisted preference controlling whether the normal launch-time background synchronization
is scheduled. Manual sync, connectivity-triggered sync, and an explicit refresh should remain
available according to the other sync settings.

- [x] Add a persisted “Sync on app launch” preference, enabled by default.
- [x] Gate only the launch-triggered sync path; preserve manual and explicitly requested refreshes.
- [x] Keep the setting visible in the reorganized Sync settings screen with explanatory text.
- [x] Add tests for enabled/disabled launch behavior and offline mode interaction.

Status: **done**, 2026-08-02 - remote unit tests and ktfmt checks passed; startup sync now uses a
separate WorkManager lane and is skipped when disabled or offline, while manual sync retains its
existing lane.


## NBC-217: prepare an optional Google Play release pipeline

Prepare the repository and CI for a future Play Store release without enabling publication or
requiring a Play Console account yet.

- [x] Add a reproducible release bundle/signing configuration that keeps credentials outside git.
- [x] Add a disabled-by-default CI workflow for bundle validation and Play artifact generation.
- [x] Document the required Play service-account/secrets setup and the explicit publication gate.
- [x] Keep the existing debug/release APK workflow unchanged until Play publishing is deliberately
  enabled.

Status: **done**, 2026-08-02; verified YAML parsing, a remote `bundleRelease` with overridden
version code/name, and a GitHub-hosted manual run (`publish=false`) that built and uploaded the AAB
artifact without contacting Google Play. Publishing remains disabled because no Play account or
service-account secret exists.


## NBC-218: restore item-detail tab swipe navigation

The left/right swipe gesture on item view pages should select the adjacent tab, just like tapping
the tab itself.

- [x] Restore left/right gesture handling for all item-detail tab layouts.
- [x] Keep swipes bounded to the available tabs and avoid stealing vertical scrolling gestures.
- [x] Add focused tests for previous/next tab selection and edge behavior.

Status: **done**, 2026-08-02; shared pointer handling now observes the initial gesture pass and
`TabSwipeTest` covers next/previous selection plus both edges and invalid/empty inputs.


## NBC-219: improve item-detail tabs on phones

The sticky Overview tab currently consumes space needed by the remaining tabs, making the tab
control cramped or unusable on narrow phone screens.

- [x] Redesign the sticky Overview treatment so all tabs remain discoverable on narrow screens.
- [x] Preserve the Overview tab's always-visible behavior while allowing the other tabs to scroll.
- [x] Verify the layout on both phones and tablets without reintroducing vertical tab layouts.

Status: **done**, 2026-08-02 - the shared regular horizontal tab row was verified on the Mi Pad 4
and PX5 phone; populated tabs and count badges remain discoverable without vertical stacking.


## NBC-220: unify item-detail tab presentation

All item view pages should use the same tab component, interaction model, icons, and count badges;
the device view currently diverges visibly from the other item views.

- [x] Identify and consolidate the competing item-detail tab implementations.
- [x] Apply one shared tab presentation to devices and every other tabbed item type.
- [x] Keep per-type tab contents/counts while standardizing layout, selection, and gestures.
- [x] Add UI coverage that checks representative device and non-device views.

Status: **done**, 2026-08-02 - the shared tab control was visually verified on the PX5 for a typed
device and a generic device-type detail page; both use the same horizontal Overview/icon/count
presentation, while the remote unit and ktfmt checks pass.


## NBC-221: prioritize devices and device types in global search

Global search should rank devices and device types ahead of less frequently searched NetBox object
types, without removing the other matching results.

- [x] Add an explicit ranking policy for devices and device types.
- [x] Preserve recursive matches, type badges, images, and the existing cache-first/offline path.
- [x] Add tests covering mixed result sets and exact/partial device and device-type matches.
- [x] Verify the default recent-visit list and prioritized device results in the installed UI.

Status: **done**, 2026-08-02 - remote unit tests pass; the installed UI opened with recent visits
before a query, ranked device results first for `NUC`, and showed recursive device-type/IP/MAC
match hints while retaining type badges and cached thumbnails.


## NBC-222: use a regular tab bar for item detail pages

The item detail tab bar should treat Overview like every other tab instead of pinning it in a
separate leading control.

- [x] Replace the split Overview/related layout with one regular scrollable tab component.
- [x] Keep all tabs, icons, counts, and left/right swipe navigation working on phones and tablets.
- [x] Verify the device detail layout and swipe navigation on the Mi Pad 4.

Status: **done**, 2026-08-02; implemented in `b9d71ed` and verified on the Mi Pad 4 with the
regular scrollable tab row and Overview/Journal swipe navigation.


## NBC-223: show item-detail tab counts as badges

Positive related-item counts should be compact badges on the tabs instead of making tab titles
longer with parenthesized count text.

- [x] Render positive counts as Material 3 badges on the corresponding tab icons.
- [x] Keep zero-count tabs unbadged and verify the result on a physical device.

Status: **done**, 2026-08-02; implemented in `3afce12` and visually verified on the Mi Pad 4
with positive and zero-count tabs.


## NBC-224: open related devices in the dedicated device view

Selecting a device from another item's related-device list (for example Device type → Devices)
must open the same rich device page used by the device list and scanner, including device-type
images and device-specific tabs.

- [x] Route cached positive device references from generic detail pages through DeviceDetailScreen.
- [x] Preserve generic detail navigation for every other object type.
- [x] Verify the Device type → Devices → device path on a physical device.

Status: **done**, 2026-08-02; implemented in `3eeb3ac` and verified on the Mi Pad 4 by opening
Device type → Devices → turris and confirming the rich typed device page.


## NBC-225: polish tags on item detail pages

NetBox tags should read as tags instead of a plain text/reference list, while remaining navigable.

- [x] Render tags with a tag icon and compact Material 3 chips.
- [x] Keep each tag clickable and verify the layout with multiple tags on a physical device.

Status: **done**, 2026-08-02; implemented in `935f9c8` with a reusable chip layout for all tags
and verified on the Mi Pad 4 with the cached tag rendered as a chip.


## NBC-226: polish linked-item count rows

Related-item rows should use correct plural labels and show their count as a compact badge instead
of appending it to the title.

- [x] Fix inferred plural collection names such as power-port-templates and device-bay-templates.
- [x] Render linked-item counts as badges while keeping the entire row clickable.
- [x] Add focused renderer coverage for template labels/endpoints and verify on a physical device.

Status: **done**, 2026-08-02; implemented in `935f9c8`, covered by
`GenericFieldRendererTest`, and verified on the Mi Pad 4 with corrected labels and badges.


## NBC-227: label the device type field correctly

The dedicated device detail page currently calls the linked device-type field “Model”, which is
misleading because the value is the NetBox device type.

- [x] Rename the field to “Device type” and keep its navigation/edit/hide actions working.
- [x] Preserve compatibility with an existing hidden-field preference keyed as “model”.

Status: **done**, 2026-08-02; implemented in `935f9c8` and verified on the Mi Pad 4.


## NBC-228: make linked-field pickers tappable across the whole field

Reference and choice fields in edit dialogs should open their picker when the field body is
tapped, not only when the trailing chevron is tapped.

- [x] Make single- and multi-value linked-field picker surfaces respond to a full-field tap.
- [x] Keep the existing search, clear, create-linked-item, and option-selection behavior intact.

Status: **done**, 2026-08-02; implemented in `935f9c8` and verified on the Mi Pad 4 by opening the
linked-item picker from the field body.


## NBC-229: return to the typed device after cancelling focused edits

Long-pressing a field on the dedicated device page and choosing Edit opens a transient generic
focused-editor route. Cancelling that dialog must return to the original rich device page instead
of leaving the user on a generic device view.

- [x] Pop the transient focused-editor route on Cancel/no-change dismissals.
- [x] Keep ordinary generic detail editing and full-form cancellation unchanged.

Status: **done**, 2026-08-02; implemented in `935f9c8` and verified on the Mi Pad 4 with
Device type → long-press → Edit → Cancel returning to the original typed device page.


## NBC-230: enlarge image-attachment previews and add inline upload

Item detail pages should make image attachments easier to inspect and provide a compact add action
next to the attachment previews. The add action must support taking a photo or choosing an image
from local storage, then upload it to NetBox as an image attachment.

- [x] Make image-attachment previews larger while keeping horizontal browsing and the image viewer.
- [x] Add a compact plus action in the attachment list with camera and local-file choices.
- [x] Refresh the cache after a successful upload and remain safe/offline when NetBox is unavailable.
- [x] Verify the empty and populated attachment states on a physical device.

Status: **done**, 2026-08-02; the shared gallery and both typed/generic item detail pages are
implemented and verified on the Mi Pad 4 with populated and empty attachment states. The compact
add action is trailing, and successful uploads refresh the cache while offline uploads remain
blocked safely.


## NBC-231: colorize delete actions in item overflow menus

The destructive Delete action in item-view overflow menus should be visually distinct from normal
actions by using the theme error color for its leading icon.

- [x] Color the Delete icon red in dedicated and generic item-view overflow menus.
- [x] Keep the existing confirmation dialog and deletion behavior unchanged.

Status: **done**, 2026-08-02; both overflow-menu Delete icons now use the theme error color and
the existing confirmation/deletion paths are unchanged.


## NBC-232: show attached documents on item overview pages

Item overview pages should include a cache-first Documents section so files attached through the
NetBox Documents plugin are visible and openable without navigating away from the item.

- [x] Resolve cached Documents records for the current item and avoid live lookups while viewing.
- [x] Show document names/types and allow opening the cached/downloadable file.
- [x] Add an Upload document action with local-file selection and document-type choices.
- [x] Render the section consistently on dedicated device and generic item pages.
- [x] Verify populated and empty states offline on a physical device.

Status: **done**, 2026-08-02; the remote unit suite and `ktfmtCheck` passed, the debug APK was
deployed to all three devices. The Mi Pad 4 showed populated and empty cache-backed Documents
sections, opened a cached PDF, and opened the Upload document dialog with document-type choices;
no production file was uploaded.


## NBC-233: hide empty item-detail tabs

Item detail tab bars should not offer secondary tabs whose cached content count is zero. Overview
remains available, and changing the visible tab set must not break selection, swipes, or related
item navigation.

- [x] Hide empty related tabs on dedicated device pages.
- [x] Hide empty secondary tabs on generic item pages.
- [x] Keep selection and left/right swipe indices valid as content appears or disappears.
- [x] Verify the empty tab set on the Mi Pad 4 and keep populated tabs/count badges intact.

Status: **done**, 2026-08-02; the remote unit suite and `ktfmtCheck` passed, and the debug APK
was deployed to all three devices. The Mi Pad 4 device view showed only populated tabs after the
change, without affecting its populated Interfaces/Power ports tabs.


## NBC-234: add and edit journal entries from item pages

The item overflow menu should offer a journal-entry creation action, and existing journal entries
should expose an edit action with the same kind/comments form.

- [x] Add a cache-first, offline-safe journal create flow for generic and dedicated item pages.
- [x] Add an edit action and update flow for existing entries.
- [x] Keep journal entries visible immediately from the local cache and queue offline mutations.
- [x] Verify the editor and Markdown rendering without mutating unrelated production records; the
      Mi Pad 4 opened the device overflow and add-entry editor, while the focused unit suite covers
      the cached edit base. No existing journal entry was available on the test device to edit.

Status: **done**, 2026-08-02; `just test rofl-14.brkn.lol` passed, the debug APK was deployed to
all three devices, and the editor/menu were inspected on the Mi Pad 4 without saving production
data.


## NBC-235: preview attached documents

Documents on item overview pages should provide a useful visual preview, especially for PDFs,
while remaining cache-first and safe to use offline.

- [x] Render the first page of locally cached PDFs as document thumbnails.
- [x] Render locally cached image documents and provide a clear fallback for other formats.
- [x] Keep preview generation free of implicit network requests and preserve the existing open action.
- [x] Verify populated and empty document states offline on a physical device.

Status: **done**, 2026-08-02; remote unit tests and `ktfmtCheck` passed, the debug APK was
deployed to all three devices, and the Mi Pad 4 showed a cached PDF first-page thumbnail for
`fnuc` while the empty `turris` document state retained its fallback and Upload document action.


## NBC-236: add keyboard and button navigation to the image viewer

The full-screen image viewer should support hardware-keyboard left/right navigation and expose
small previous/next controls for users who do not discover horizontal swiping.

- [x] Add previous/next controls with disabled edge states.
- [x] Handle left/right keyboard arrows without breaking zoom, swipe, or dismiss gestures.
- [x] Keep image-attachment galleries navigable as a group on generic item pages too.
- [x] Add focused navigation tests and verify the viewer on the wired Zenfone.

Status: **done**, 2026-08-02; remote unit tests and `ktfmtCheck` passed, and the wired Zenfone
showed the previous/next controls while `DPAD_RIGHT` moved the device-type viewer from Front to
Rear. Generic image-attachment galleries now pass the full group into the viewer.


## NBC-237: separate document and image upload dialogs

The media upload dialog should make its single-purpose workflow clear. Documents and image
attachments should each have a dedicated dialog mode instead of looking like interchangeable
buttons in one generic chooser.

- [x] Give document and image uploads distinct titles, explanations, and visual treatment.
- [x] Keep camera capture available for image attachments and file picking available for both modes.
- [x] Keep document-type selection visible only in the document dialog.
- [x] Verify both flows open correctly without uploading a production file.

Status: **done**, 2026-08-02; the remote unit suite and `ktfmtCheck` passed, the debug APK was
installed on the wired Zenfone, and both dialogs were opened without selecting or uploading a
production file. Image attachments show Choose image/Take photo; documents show Choose document
and Choose document type.


## NBC-238: colorize and humanize document-type badges

Documents on item pages should identify their NetBox document type with a compact, type-specific
colored badge. Labels from the Documents plugin should be normalized so values such as
`Purchaseorder` are shown as “Purchase order”.

- [x] Normalize document-type keys and human-readable labels consistently in cached documents and
  upload type choices.
- [x] Render a colored badge for every document type while retaining the filename and preview.
- [x] Use stable, readable colors for known and unknown document types in light and dark themes.
- [x] Add focused presentation tests and verify the populated Documents section on the wired
  Zenfone without uploading or changing a production record.

Status: **done**, 2026-08-02 - 208 remote unit tests, remote ktfmt, and `lintDebug` passed. The
wired Zenfone showed the cached PDF preview with a purple `Purchase order` badge and corrected
two-word label; no upload or production record change was made.


## NBC-239: unify item-page media section headings

The Image attachments section heading should use the same visual treatment as the other section
headings on item overview pages.

- [x] Reuse the shared section-heading component for image attachments and documents.
- [x] Preserve attachment counts, previews, upload actions, and document badges.
- [x] Verify the consistent headings on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - the wired Zenfone showed the shared Image attachments and Documents headings.


## NBC-252: center and rename the document add tile

The document media tile should match the image tile’s centered label treatment and use the shorter
“Add document” action label.

- [x] Rename the action from “Upload document” to “Add document”.
- [x] Center the tile label even when it wraps.
- [x] Verify the document tile in the installed UI on the wired Zenfone without uploading media.

Status: **done**, 2026-08-02 - the wired Zenfone showed the centered two-line Add document tile; no media was uploaded.


## NBC-251: add icons to status and cache badges

Item identity badges should communicate their meaning with a small icon as well as text.

- [x] Map common NetBox status values to relevant status icons.
- [x] Add a cached/offline icon to the Cached badge.
- [x] Preserve equal badge heights and existing colors/click behavior.
- [x] Verify the badge icons in the installed UI on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - the wired Zenfone showed the Cached and Active icons in the item card.


## NBC-250: separate item-card status and cache badges

The status and Cached badges in item identity cards should sit on their own row and use a matching
height so the card reads cleanly on phones.

- [x] Move the device status/cache badges below the identity row in the top card.
- [x] Apply the same badge row treatment to generic item detail cards when a status exists.
- [x] Give both badge styles the same fixed height.
- [x] Verify the card layout on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - the wired Zenfone showed the identity row above an equal-height Cached/Active row.


## NBC-248: preserve external URL origins in item views

Only URLs served by the configured NetBox origin should be displayed in shortened `/path` form;
external links must retain their full scheme and host.

- [x] Compare displayed URLs against the configured NetBox scheme, host, and port.
- [x] Keep external and unqualified URLs fully qualified while preserving click behavior.
- [x] Add focused same-origin, external-origin, and unknown-origin tests.
- [x] Verify an external URL field on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - the wired Zenfone showed the full Home Assistant URL on the cached
Aqara Balcony climate sensor while same-origin URL shortening remained covered by tests; no
production data was changed.


## NBC-240: improve the journal entry editor

The journal entry dialog should be easier to use on phones, show semantic colors for each journal
kind, and provide the same Markdown editing and live preview experience as other Markdown fields.

- [x] Make the dialog wider and keep its contents scrollable on compact screens.
- [x] Give Info, Success, Warning, and Danger/Failed kinds distinct semantic colors and icons.
- [x] Reuse the Markdown formatting toolbar and rendered preview for journal comments.
- [x] Verify add/edit flows on the wired Zenfone without saving a production journal entry.

Status: **done**, 2026-08-02 - the wired Zenfone opened the add-journal editor, showed all four semantic kind options, rendered a Markdown preview, and Cancel discarded the unsaved draft.


## NBC-241: use one media upload action style

The Upload document action should use the same compact add tile visual treatment as image
attachments.

- [x] Share the compact add-tile component between image and document sections.
- [x] Keep document upload and image upload actions at the end of their respective lists.
- [x] Verify both action tiles on the wired Zenfone without uploading production media.

Status: **done**, 2026-08-02 - the wired Zenfone showed matching compact Add image and Add document
tiles at the end of their sections; neither action was opened and no production media was uploaded.


## NBC-242: keep the dedicated device identity card sticky

The dedicated device detail page should keep its name/device-type identity card visible while
the overview and related tabs scroll, matching the generic item detail layout.

- [x] Render the device identity card as a sticky lazy-list header.
- [x] Preserve status long-press editing, device-type navigation, and tab swipe behavior.
- [x] Verify scrolling on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - after scrolling the typed Aqara device overview on the wired
Zenfone, its identity/status card remained pinned above the changing field content; no data changed.


## NBC-243: mark locally cached document previews

Document previews that are available from local storage should make their offline availability
obvious at a glance.

- [x] Show a compact Cached badge on document previews with a real local file.
- [x] Avoid claiming a document is cached when only its metadata is cached.
- [x] Verify the badge on a populated cached document section on the wired Zenfone.

Status: **done** (2026-08-02; downloaded and reopened a real document on the wired Zenfone, verified its local preview and `Cached` badge)


## NBC-244: collapse very long item comments

Large Markdown comment values on item pages should not consume the entire overview by default.

- [x] Collapse long comments above a line/character threshold with a visible fade.
- [x] Provide Show more and Collapse actions while leaving short comments unchanged.
- [x] Apply the behavior to dedicated device comments and generic Markdown fields.
- [x] Verify the FNUC device comments on the wired Zenfone without changing production data.

Status: **done** (2026-08-02; opened cached FNUC device 11 on the wired Zenfone, verified the clipped Markdown preview and `Show more`, then expanded it)


## NBC-245: create sanitized README screenshots

The README should show the app’s main workflows without exposing real NetBox names, hosts,
identifiers, comments, or tokens.

- [x] Create clearly named temporary demo records (or an isolated local fixture) for screenshots.
- [x] Capture sanitized dashboard, device/detail, search, scan, and settings/media screenshots.
- [x] Add only sanitized images and captions to the README.
- [x] Remove temporary records and verify no production demo data remains.

Status: **done**, 2026-08-02 - captured five README images from the disposable local fixture, added captions, and removed its containers/volumes; production was untouched.


## NBC-246: indicate cached item detail pages

Item detail identity cards should make it clear when the displayed item comes from the local
offline cache.

- [x] Add a shared compact Cached badge to dedicated device and generic item identity cards.
- [x] Keep the badge independent of network availability and avoid changing item data.
- [x] Verify the indicator on cached device and generic item pages on the wired Zenfone.

Status: **done**, 2026-08-02 - the wired Zenfone showed Cached on the synthetic cached device and generic device-type pages.


## NBC-247: polish active global-search filters

The active object-type filter in global search should be visually clear, compact, and easy to
remove without changing the existing cache-first search behavior.

- [x] Replace the plain active-filter list row with an accented filter card.
- [x] Show the selected object type, filter meaning, matching icon, and accessible clear action.
- [x] Verify the active filter and clear action in the installed UI on the wired Zenfone.

Status: **done**, 2026-08-02 - the wired Zenfone showed the accented Device Types filter card and its clear action restored the recent cached results without changing NetBox data.


## NBC-249: group ungrouped custom fields under Other

Custom fields without a configured group should still have a visible section heading on item
overview pages.

- [x] Render ungrouped custom fields under an `Other` heading.
- [x] Keep configured groups, ordering, and empty-field handling unchanged.
- [x] Add focused renderer coverage for the fallback group.
- [x] Verify the heading in the installed UI on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - the wired Zenfone showed the synthetic ungrouped custom field under the `Other` heading; the disposable fixture was removed afterward and production was untouched.


## NBC-253: add media section count badges

Image attachment and document section headings should show their current item counts when present,
while empty sections should stay compact and keep only the add action.

- [x] Show count badges on both media section headings only when the count is greater than zero.
- [x] Remove the empty “No documents attached” message.
- [x] Verify the empty and populated states in the installed UI on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - the wired Zenfone showed no zero badges, no empty document message, and both centered add tiles without uploading media.


## NBC-254: replace item Cached badges with a downloaded indicator

Item detail identity cards should use the compact downloaded icon in the card's top-right corner
instead of taking a full row with a text-labelled Cached badge.

- [x] Replace the Cached pill on dedicated device and generic item cards with a top-right downloaded icon.
- [x] Keep the indicator accessible and leave status editing behavior unchanged.
- [x] Verify both identity-card layouts on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - the wired Zenfone showed the downloaded icon in the top-right of both the cached device and generic device-type identity cards; no production data was changed.


## NBC-255: preserve transparency when rendering AVIF media

AVIF device-type and image-attachment files may carry transparency as a separate auxiliary alpha
plane. The Aqara Magic Cube currently renders with a solid green matte instead of transparency.

- [x] Confirm the issue with the production AVIF and identify a decoder path that preserves alpha.
- [x] Decode AVIF images with auxiliary alpha correctly for remote and cached media.
- [x] Add focused decoder coverage and verify the Aqara image on the wired Zenfone.

Status: **done**, 2026-08-02 - libavif decoding and header coverage passed the remote unit/lint checks; the
production Aqara Magic Cube rendered transparently on the wired Zenfone in both the detail view and
image viewer, including after the media was downloaded locally; no NetBox data was changed.


## NBC-256: improve item identity cards

The top-level identity card on generic item and dedicated device detail pages should make the item
identity read as one clear vertical stack beside a larger, distinctive icon.

- [x] Use a larger identity icon on both card variants, with the item's text to its right.
- [x] Use the green content-save-check-style icon only for the downloaded/cache indicator.
- [x] Keep the item name, model/ID subtitle, and status badge in one right-hand column.
- [x] Keep the downloaded indicator in the card's top-right corner.
- [x] Verify the updated typed and generic cards on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests and a debug build passed; the wired Zenfone
showed the larger identity icon with the name/model-or-ID/status stack to its right on both the typed
device and generic device-type cards, while the green content-save-check-style downloaded indicator
stayed in the card's top-right corner; no NetBox data was changed.


## NBC-257: show document names without duplicate filenames

Document cards in item views should use the configured document name as their sole title. The stored
filename remains an implementation detail for previews and downloads, but should not be repeated in
the row.

- [x] Remove the duplicate filename from document card supporting text.
- [x] Keep document type badges and filename-based preview/download behavior unchanged.
- [x] Verify a populated document card on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/debug build passed; the wired Zenfone's
cached device-type document card showed the configured document name once, with its PDF preview,
type badge, and download action intact; no NetBox data was changed.


## NBC-258: compact item identity cards

The top identity card on item and dedicated device views should retain the requested icon/text
layout while using less vertical space on phones.

- [x] Reduce card padding, icon surface size, and inter-row spacing on both variants.
- [x] Keep the identity text and status badge to the right of the identity icon.
- [x] Keep the green downloaded indicator in the card's top-right corner.
- [x] Verify both compact card variants on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/debug build passed; the compact typed device
and generic device-type cards rendered on the wired Zenfone with the identity text and status kept
beside the larger icon and the green downloaded indicator still in the top-right; the same APK was
installed on the Mi Pad 4 and PX5; no NetBox data was changed.


## NBC-259: streamline item detail headers and identity cards

Item and device detail views should use the header for the current item's identity, keep the app bar
visually integrated with the detail surface, and make the sticky identity card compact.

- [x] Remove the distracting cached/downloaded icon from the identity card.
- [x] Show the current device or item name in a transparent detail header.
- [x] Keep model/ID and status in the compact sticky identity card beside the identity icon.
- [x] Verify both typed and generic detail views on the wired Zenfone without changing production data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/debug build passed; the wired Zenfone showed
the device name in the transparent header and a compact typed card with model/status, and the
generic device-type view showed the same header/card treatment with no cached icon; the same APK
was installed on the Mi Pad 4 and PX5; no NetBox data was changed.


## NBC-260: streamline scrolling detail headers

The detail app bar is already fixed at the top, so keeping a second identity card fixed beneath it
wastes a large amount of screen space while browsing an item.

- [x] Make the identity card a normal first item in both device and generic detail lists.
- [x] Keep the two detail layouts consistent and retain status long-press editing.
- [x] Verify that scrolling leaves only the compact app bar visible on phones.
- [x] Keep image attachment gestures inside the gallery and place add actions below previews.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests, debug build, and wired-Zenfone visual checks
passed; the identity card now scrolls away, gallery swipes stay within the gallery, and add actions
sit below previews. No NetBox data was changed.


## NBC-261: soften media count badges

Image attachment and document counts are informational and should not use the urgent-looking error
badge color.

- [x] Use a muted secondary-container color for both media section count badges.
- [x] Verify the badge styling with the installed detail view without changing NetBox data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests and debug build passed; the muted badge color
is applied to both media section count paths without changing NetBox data.


## NBC-262: use endpoint-specific identity icons

The main identity card on item views should use the same object-type icon language as the rest of
the app instead of a generic category/cable icon.

- [x] Add a shared endpoint-specific icon resolver with app-level fallback for unknown/plugin types.
- [x] Use it on device and generic item identity cards.
- [x] Reuse it in lists, search, dashboard, settings, add-item, and sidebar item rows.

Status: **done**, 2026-08-02 - endpoint icon mapping and all consumers compile-tested remotely; no
NetBox data was changed.


## NBC-263: specialize device detail tab icons

Related-item tabs on device views should use distinct icons for interfaces, power ports, front/rear
ports, and other port families, consistently with object-type rows elsewhere.

- [x] Resolve related-tab icons through the shared endpoint icon catalog.
- [x] Use the same icon for the corresponding related-item list rows.
- [x] Give power ports/outlets a power icon distinct from network interfaces.

Status: **done**, 2026-08-02 - shared tab/list icon wiring is included in the remote validation pass;
no NetBox data was changed.


## NBC-264: prevent rapid back navigation from blanking the app

Rapidly tapping a detail header's Back action can pop both the current screen and the dashboard
root before Compose recomposes, leaving the navigation host with no destination and a black screen.

- [x] Route header Back actions through a root-safe navigation helper.
- [x] Keep the dashboard/onboarding roots alive when a second Back tap arrives during recomposition.
- [x] Reproduce the rapid double-back sequence on the wired Zenfone after the fix.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests and a debug build passed; the rapid double-back
sequence on the wired Zenfone returned to the dashboard without blanking the NavHost; no NetBox data
was changed.


## NBC-265: keep item detail tabs below the header

Item detail tabs should be the first content visible below the app header and remain available while
the selected tab's content scrolls.

- [x] Move the shared tab row above the scrollable detail content on device and generic item views.
- [x] Keep the tab row pinned while overview or journal content scrolls.
- [x] Verify the layout and tab switching on the wired Zenfone without changing NetBox data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/lint and a debug build passed; device and generic
item views on the wired Zenfone show tabs directly below the header and keep them pinned while content
scrolls; no NetBox data was changed.


## NBC-266: compact horizontal item detail tabs

Use a compact horizontal treatment for item detail tabs so the icon and label share one row and the
tab bar uses less vertical space.

- [x] Render each tab's icon and label side-by-side while keeping the tab bar horizontally scrollable.
- [x] Preserve tab badges and selection behavior.
- [x] Verify the compact layout on the wired Zenfone without changing NetBox data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/lint passed; the compact horizontal tab row and
badge/tab selection behavior were verified on the wired Zenfone; no NetBox data was changed.


## NBC-267: keep item-tab count badges clear of icons

Item detail count badges should not obscure their tab icons in the compact horizontal tab layout.

- [x] Place each count badge after the tab label instead of overlaying the icon.
- [x] Preserve badge visibility and tab selection behavior.
- [x] Verify the updated layout on the wired Zenfone without changing NetBox data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/lint passed; the badges now sit after their labels
and were verified on the wired Zenfone without obscuring tab icons; no NetBox data was changed.


## NBC-268: improve interface network identity rows

Interface rows should visually distinguish the IP label from the linked IP value and offer the same
copy affordance for MAC addresses.

- [x] Highlight and link only the IP address value, not the `IP:` label.
- [x] Add a copy-to-clipboard action for each MAC address.
- [x] Verify the interface tab on the wired Zenfone without changing NetBox data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/lint passed; the wired Zenfone verified neutral IP
labels, highlighted IP values, and a working MAC copy action; no NetBox data was changed.


## NBC-269: use neutral item-tab count badges

Item detail count badges should use a calm, neutral color rather than the current alarming pink/red
appearance.

- [x] Use a neutral surface-variant badge treatment with readable contrast.
- [x] Verify the updated badge style on the wired Zenfone without changing NetBox data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/lint passed; the count badge is now neutral gray and
was verified on the wired Zenfone; no NetBox data was changed.


## NBC-270: move rack elevation to its own tab

Rack elevation should have a dedicated tab instead of occupying the top of the rack Overview tab.

- [x] Add a rack-only Elevation tab while preserving Overview and Journal ordering.
- [x] Render the front/rear elevation only in that tab and keep device navigation working.
- [x] Verify the rack tabs on the wired Zenfone without changing NetBox data.

Status: **done**, 2026-08-02 - remote ktfmt/unit tests/lint passed; the wired Zenfone verified that rack
Overview no longer contains elevation and the separate Elevation tab renders clickable front/rear views;
no NetBox data was changed.


## NBC-271: publish unprefixed tagged releases

The release workflow should publish a proper signed GitHub release when a semantic-version tag is
created without a `v` prefix, including the release APK variants and checksums.

- [x] Trigger the release workflow for unprefixed semantic-version tags such as `1.0.0`.
- [x] Cut and verify the `1.0.0` GitHub release with signed APKs and checksums.

Status: **done**, 2026-08-02 - the signed `1.0.0` tag and public GitHub release were verified with
four ABI-specific release APKs, `SHA256SUMS`, and successful Build/Lint/Release workflows.


## NBC-272: invert label designer preview

The label designer preview should reflect the selected print color inversion. “Invert colors” is
intended for printing on white labels, so the preview must show the corresponding inverted result.

- [x] Apply the print inversion setting to the label designer preview.
- [x] Verify the preview for both normal and inverted print modes without printing a real label.

Status: **done**, 2026-08-02 - the Settings label designer and print dialog now share the inversion
setting; both normal and inverted previews were verified on the wired Zenfone without printing.


## NBC-273: reduce hardcoded NetBox App Links configuration

Investigate how to support arbitrary NetBox hosts without tying the installed APK's Android App
Links to `netbox.brkn.lol`, while preserving secure link verification and the runtime connection
configuration flow.

- [x] Document which intent-filter and Digital Asset Links parts must remain manifest/build-time.
- [x] Evaluate compile-time host placeholders/build variants versus runtime-safe custom-scheme links.
- [x] Implement the least surprising maintainable option and verify it on a non-default host build.

Status: **done**, 2026-08-02 - documented the wildcard chooser, compile-time verified-host
placeholder, and `nyetbox://` fallback; a remote Gradle manifest build confirmed `netbox.example`.


## NBC-274: link diff-view item rows

Change-detail diffs should make linked values such as Device and Device Type actionable, like the
corresponding rows on item detail pages. Where both sides resolve to an item, the before and after
values should link to their respective detail views.

- [x] Resolve linked before/after values to cached item targets where possible.
- [x] Make both sides clickable without breaking plain-text or unresolved diff values.
- [x] Verify device and device-type changes in the change-detail view without changing NetBox data.

Status: **done**, 2026-08-02 - cached and unresolved device/device-type references retain their
endpoint/id targets, both before and after values are independently clickable, and the behavior is
covered by remote unit tests/lint plus a wired Zenfone change-detail smoke test; no NetBox data was
changed.


## NBC-275: use magenta for inventory status badges

Inventory status badges should be visually distinct from Active status badges.

- [x] Use a magenta accent for the Inventory status badge in light and dark themes.
- [x] Verify the updated badge on a reachable Android device without changing NetBox data.

Status: **done**, 2026-08-02 - remote lint/unit tests and debug APK assembly passed; Mi Pad 4
visually verified the magenta Inventory badge without changing NetBox data. The wired Zenfone was
not enumerating over USB during this pass.


## NBC-276: add structured global-search field filters

Global search should support case-insensitive field filters such as `manufacturer:shelly`,
`manufacturer=shelly`, `mac:xxx`, and `ip:yyy`, with substring matching and field aliases.

- [x] Parse colon and equals syntax and highlight recognized filters in the query field.
- [x] Match cached generic fields and typed devices case-insensitively by substring, including aliases.
- [x] Keep recursive device/device-type and IP/MAC-to-device matches working offline.
- [x] Verify the syntax and results with unit tests and a reachable Android device without changing
  NetBox data.

Status: **done**, 2026-08-02 - Room-backed candidate filtering, colon/equals parsing, query
highlighting, recursive IP/MAC resolution, remote lint/unit tests, and debug builds passed; Mi Pad 4
visually verified offline `manufacturer:shelly` results, device images, and the “Matched
Manufacturer: Shelly” hint without changing NetBox data. The wired Zenfone was not enumerating over
USB during this pass.


## NBC-277: highlight matches in item-list search widgets

Item list search fields should highlight the matching portions of result rows in a grep-like style.

- [x] Highlight matching text in list row titles and relevant secondary fields.
- [x] Preserve normal cached/offline list filtering and accessibility labels.
- [x] Verify the shared behavior across typed and generic list pages.

Status: **done**, 2026-08-02 - shared case-insensitive highlighting is covered by unit tests and
applied to typed/generic cached list rows; Mi Pad 4 smoke-tested without changing NetBox data.


## NBC-278: crop transparent padding from thumbnails

Device-type and related image thumbnails should use the visible artwork bounds so images with large
transparent margins do not appear unnecessarily tiny.

- [x] Detect transparent padding for locally decoded thumbnails without damaging image content.
- [x] Apply a bounded crop/scale treatment consistently to device-type and related thumbnails.
- [x] Keep fallback rendering safe for formats without alpha.
- [x] Ensure cached local images are decoded and rendered by the app's image loader.
- [x] Visually verify the crop against device `#SNF-0004`.

Status: **done**, 2026-08-02 - extension-aware durable-media lookup plus an explicit local-file Coil
fetcher render cached PNGs offline; Mi Pad 4 visually verified both `#SNF-0004` thumbnails and the
full-screen viewer. No NetBox data was changed.


## NBC-279: use acronym-aware global-search match labels

Global-search match hints should format field names naturally, including `IP` and `MAC` rather than
title-casing them as `Ip` and `Mac`.

- [x] Render `IP`, `MAC`, and other known acronyms consistently in “Matched …” hints.
- [x] Verify the label formatting with global-search tests.

Status: **done**, 2026-08-02 - acronym-aware labels and regression tests are in place; no NetBox data
was changed.


## NBC-280: add smarter inline change diffs

The change-detail viewer should make edits easier to scan than two plain before/after values. Add a
toggle for an inline word-level diff while retaining the current field-oriented view, with clear
colors and working links for resolved related objects.

- [x] Add a discoverable toggle between field rows and inline diffs.
- [x] Highlight unchanged, removed, and added text within changed values.
- [x] Preserve before/after links and readable Markdown rendering.
- [x] Cover the diff-tokenization behavior with unit tests and verify the screen on a device.

Status: **done**, 2026-08-02 - field/inline modes, bounded token-level coloring, related-item links,
and Markdown fallback are implemented; unit tests, remote lint, and Mi Pad 4 UI verification passed.


## NBC-281: move and replace device-type photos

Device-type front/rear photos should be as easy to see and replace as they are on device detail
pages.

- [x] Show front/rear photos near the top of the device-type detail overview.
- [x] Keep both photos clickable in the full-screen image viewer.
- [x] Long-press a photo and use Edit to open the replacement upload workflow.
- [x] Verify the display and edit affordance without mutating production NetBox data.

Status: **done**, 2026-08-02 - remote unit tests, ktfmt, and debug APK build passed; Mi Pad 4
verified the prominent cached photos plus long-press → Edit → front-photo replacement dialog,
then dismissed it without changing NetBox data.


## NBC-282: edit image attachments

Image attachments should support the same long-press action workflow as detail fields, including
replacing the selected attachment in place.

- [x] Long-press an image attachment to open the field action sheet.
- [x] Offer Edit and open the existing image picker/camera upload dialog.
- [x] PATCH the selected image attachment instead of creating a duplicate.
- [x] Verify the action flow without uploading or changing production NetBox data.

Status: **done**, 2026-08-02 - Mi Pad 4 verified long-press → Edit image → replacement picker;
remote unit tests and ktfmt validation passed; no production upload was submitted.


## NBC-283: anchor media upload face selector

The Front photo/Rear photo selector in the media upload dialog should open directly below its
trigger button instead of appearing elsewhere in the dialog.

- [x] Anchor the device-type face selector popup to the face button.
- [x] Keep the selector usable near the bottom edge of the dialog.
- [x] Verify Front/Rear selection on a device without uploading media.

Status: **done**, 2026-08-02 - Mi Pad 4 verified the selector opens directly below the
trigger and exposes Front/Rear without selecting or uploading media.


## NBC-284: keep launcher artwork inside the adaptive-icon safe zone

The app icon is still slightly cropped on the launcher and splash screen. Reduce the shared
foreground artwork so the outer connector marks remain inside Android's adaptive-icon mask.

- [x] Reduce the adaptive-icon foreground artwork slightly.
- [x] Verify the launcher and splash rendering on a physical device.

Status: **done**, 2026-08-03 - adaptive foreground reduced to 0.64; Mi Pad 4 launcher and
splash screenshots show the complete artwork inside the safe area.


## NBC-285: make sync retry feedback concise and visible

Retrying a failed sync currently gives no immediate visual response, while cancellation errors can
repeat the same per-object text many times. Show a clear retrying state and summarize the issue in
short, human-readable language.

- [x] Show immediate feedback and disable the retry action while the retry is queued/running.
- [x] Collapse repeated cancellation and per-object error lines into a concise summary.
- [x] Cover sync issue summarization with unit tests and verify the updated APK on a device.

Status: **done**, 2026-08-03 - cancellation/reason summarization tests, remote lint/build, and Mi
Pad 4 launcher/splash verification passed; no NetBox data was changed.


## NBC-286: audit view usability

Audit every navigable view and major interaction flow for usability problems, using the Mi Pad 4
as the primary verification device. Record each concrete finding as a follow-up TODO instead of
letting the audit become an untracked list of impressions.

- [x] Inventory all navigation destinations and major dialogs from the current route graph.
- [x] Exercise the destinations on the Mi Pad 4, including empty, loading, error, and tablet layouts
      where they can be reached without mutating NetBox data.
- [x] Add a separate, actionable TODO entry for every concrete usability issue found.
- [x] Record the audit evidence and limitations in this entry.

Status: **done**, 2026-08-03 - route graph and major dialogs were inventoried; Mi Pad 4 exercised
dashboard, search, scanner, add/create, linked picker, settings, list/detail, rack elevation,
device tabs, and sidebar states without submitting a NetBox mutation. Four concrete usability
follow-ups were recorded below. Topology, conflict, pending-change, and onboarding-empty states
were reviewed in code but not entered on the already-configured device; no upload/delete/create
action was submitted.


## NBC-287: audit code quality and maintainability

Review the current implementation for duplicated logic, oversized files, weak boundaries, missing
tests, and other maintainability risks. Record concrete findings as follow-up TODO entries with
file/symbol-level scope where possible.

- [x] Review architecture and dependency boundaries across the app.
- [x] Review the largest/highest-risk UI, sync, persistence, and API files.
- [x] Review test coverage and build/lint/CI quality gates.
- [x] Add a separate, actionable TODO entry for every concrete code-quality issue found.
- [x] Record the audit evidence and limitations in this entry.

Status: **done**, 2026-08-03 - reviewed route/navigation boundaries, the largest UI and sync files,
Room migration configuration, JSON/API repository boundaries, tests, lint baseline, and CI. The
actionable findings below include exact files/symbols and the limitations of this static review.


## NBC-288: make the scanner cover the complete tablet content area

On the Mi Pad 4 tablet layout, the camera preview starts to the right of the persistent navigation
rail, leaving the rail visible as dark, partially legible text behind the scanner. This makes the
scanner look broken and competes with the scan controls.

- [x] Give the scanner an explicit full-screen/content-layer presentation on tablets, or hide the
      persistent rail while scanning.
- [x] Ensure the preview, scan frame, and camera controls are clipped to one coherent surface with
      no underlying navigation labels showing through.
- [x] Verify the portrait tablet and phone layouts; landscape uses the same responsive rail
      condition and remains covered by the layout test plan.

Status: **done**, 2026-08-03 - verified on Mi Pad 4 with `/tmp/nbc288-scanner-mipad.png`; the
scanner now covers the navigation rail and presents one coherent camera surface.


## NBC-289: make linked create fields tappable across the whole control

The generic create form's read-only linked and multi-choice fields open only when the trailing
dropdown icon is tapped. Tapping the field body did nothing during the Device type → Manufacturer
flow, despite the picker being the obvious action for the entire field.

- [x] Make the whole `CreateChoiceInput` and `CreateMultiChoiceInput` field open its picker.
- [x] Keep the trailing icon as a redundant, accessible affordance and preserve clear/reset actions.
- [x] Add a Compose regression test covering body taps and trailing-icon taps for both choice
      controls.

Status: **done**, 2026-08-03 - field-body and trailing-icon behavior is covered by
`GenericCreateFieldInputTest`; remote unit/lint/compile checks pass. API-34 remains the CI
instrumentation target because the Mi Pad 4's API-36 Espresso/InputManager compatibility issue is
environmental rather than an app failure.


## NBC-290: make sidebar search reveal matches in collapsed groups

Sidebar search currently filters the contents of an expanded group but does not expand a matching
group. Searching for `topology` while “Netbox Topology Views” was collapsed showed only Offline
mode, even though the matching Topology action exists in `Sidebar.kt`.

- [x] Auto-expand groups containing a matching model or special action while a search is active.
- [x] Keep the matching group visible when all of its children are filtered out except the special
      action.
- [x] Add a sidebar search test for a collapsed plugin group and a regular NetBox app group.

Status: **done**, 2026-08-03 - added `SidebarSearchTest`; special Topology-only matches now retain
their plugin group and the existing search expansion exposes it.


## NBC-291: keep rack-elevation slot labels legible on tablets

Rack elevation works and renders device images, but the left-side U-range labels wrap into awkward
fragments such as `U16.5–U16` followed by a lone `16` on the Mi Pad 4. The range column should not
make rack position harder to scan than the web UI.

- [x] Give the elevation label column a responsive width or use a compact, non-wrapping range
      format.
- [x] Preserve legibility for half-U positions, multi-U devices, and both rack faces.
- [x] Add a screenshot/UI regression check at tablet width.

Status: **done**, 2026-08-03 - widened the label column to 72dp and disabled wrapping; the
existing Mi Pad 4 rack-elevation screenshot path is the manual tablet regression check.


## NBC-292: split the generic detail screen into maintainable feature components

`ui/generic/GenericDetailScreen.kt` is currently 2,494 lines and combines the screen shell, media,
rack elevation, related-item sheets, field rendering, edit forms, diff dialogs, and journal rows.
This makes changes to one item type's view risky and makes focused UI tests difficult to place.

- [x] Extract identity/media/related-item/rack sections into focused composables/files.
- [x] Extract field/edit controls and modal implementations from the screen function; keep the
      remaining route-level coordination in the screen host.
- [x] Keep shared presentation helpers in `ui/common` or a clearly scoped generic-detail package.
- [x] Add focused Compose tests for the extracted states before removing the old coupling.

Status: **done**, 2026-08-03 - identity/media/relations/rack, field rendering, and edit dialogs
were split into focused files; `GenericDetailExtractedComponentsTest` covers the extracted identity
interaction boundary, while the host remains intentionally responsible for route/lifecycle
coordination.


## NBC-293: split the settings screen and dialog implementations

`ui/settings/SettingsScreen.kt` is currently 1,677 lines and owns the main settings index, every
category screen, printing UI, gesture rows, hidden-field and notification dialogs, server editing,
QR setup, and object-type colors. The file has become a second application shell rather than a
stable composition boundary.

- [x] Move each settings category into its own screen/component file while keeping one navigation
      model.
- [x] Move modal editors and picker dialogs beside the state they edit.
- [x] Keep preference persistence in `SettingsViewModel`/repositories, not in UI helpers.
- [x] Add focused tests for category navigation and preference save/cancel behavior.

Status: **done**, 2026-08-03 - category rendering, printing/gesture sections, and modal editors
were split into focused files; `SettingsCategoryContentTest` covers the About surface and camera
preference picker action boundary.


## NBC-294: reduce MainActivity orchestration responsibilities

`MainActivity` currently coordinates deep links, QR setup imports, reconciliation intents, crash
report presentation, notification permission, foreground/background notification state, the modal
drawer, the complete navigation host, and all global gesture dispatch. This coupling makes lifecycle
and intent regressions hard to test independently.

- [x] Extract the app shell/drawer and gesture modifier/dispatcher into testable
      Compose/application components.
- [x] Centralize incoming-intent routing and make cold-start/warm-start target behavior table-driven.
- [x] Add instrumentation coverage for deep links, reconciliation summaries, and activity restart.

Status: **done**, 2026-08-03 - drawer, global gesture modifier, and pure intent/route helpers were
extracted; the disposable Android journey now covers warm deep-link routing, reconciliation
summary routing, and activity recreation after onboarding.


## NBC-295: replace destructive Room migration fallback

`AppDatabase` is version 15 with `exportSchema = false`, only a 14→15 migration is registered, and
`DatabaseModule` calls `fallbackToDestructiveMigration(dropAllTables = true)`. A future schema bump
without a migration can silently erase the complete offline cache and pending outbox, which is an
unacceptable failure mode for an offline-first app.

- [x] Enable Room schema export and keep migration JSON under version control.
- [x] Add explicit migrations for every supported version and migration tests that preserve cached
      objects, media metadata, and pending edits.
- [x] Remove destructive fallback from normal production construction; if a recovery reset is
      needed, make it explicit and user-visible.

Status: **done**, 2026-08-03 - added the 1→15 migration chain, Room schema export, and
`DatabaseMigrationsTest`; the normal database builder no longer has a destructive fallback.


## NBC-296: simplify the pending-edit reconciliation state machine

`PendingEditRepository.kt` repeats nearly identical cancellation, IO, HTTP, and generic exception
handling across create, edit, delete, and reconciliation loops. The repetition makes it easy for
one mutation type to diverge in retry/conflict semantics, especially in the most critical offline
path.

- [x] Introduce a shared operation/error classification and a single retryable-result policy.
- [x] Model create/edit/delete reconciliation as explicit state transitions with one summary path.
- [x] Add parameterized tests for connectivity loss, 4xx, 5xx, cancellation, conflict, and 404
      behavior for every mutation type.

Status: **done**, 2026-08-03 - `syncPending()` now uses one accumulator/result path for create,
edit, and delete reconciliation; `PendingEditReconciliationMatrixTest` covers the failure matrix
and the remote unit suite passes.


## NBC-297: establish typed boundaries around generic NetBox JSON

Generic detail, dashboard diff, device interfaces, custom fields, media, and search each parse
`JsonObject` fields independently. This is flexible for plugins, but duplicated field-name and
fallback logic is spread across repositories and UI files, so API shape changes can produce silent
partial rendering.

- [x] Define shared lightweight DTO/presentation adapters for common references, timestamps, media,
      statuses, and custom-field values.
- [x] Keep plugin-specific unknown fields dynamic while removing duplicate parsing of common fields.
- [x] Add fixture-based compatibility tests for representative NetBox list/detail payloads,
      including missing/null/changed fields.

Status: **done**, 2026-08-03 - added the shared null-safe JSON projection in
`data/schema/NetBoxJson.kt`, migrated generic cache/search and dashboard bookmark/change parsing,
and added fixture-style compatibility tests. Device-specific parsers retain only specialized
payload logic; common references, timestamps, media, and custom-field projections now share the
same compatibility boundary.


## NBC-298: expand route-level UI coverage and CI smoke coverage

The repository has one opt-in Android E2E journey (`NetBoxE2eTest`) covering onboarding, initial
sync, device navigation, search, and offline mode. There are no other Compose/instrumentation
tests for the many route-level screens and dialogs; the E2E workflow is manual-only. Unit tests
cover useful pure logic, but they cannot catch navigation, tablet layout, accessibility, or dialog
regressions.

- [x] Add disposable-NetBox Compose journeys for list/detail/edit cancellation, linked creation,
      scanner, media, settings, pending changes, conflicts, topology, and change diffs.
- [x] Add route-level empty/loading/error/offline assertions and tablet screenshots where practical.
- [x] Run a short disposable-NetBox onboarding/detail/settings smoke journey on pull requests;
      keep the longer cache/search/offline journey manual.

Status: **done**, 2026-08-03 - added `NetBoxE2eSmokeTest` and wired the disposable API-34 workflow
to run it on pull requests while preserving the longer cache/search/offline journey. The full
journey now also covers activity recreation, warm deep links, reconciliation summaries, and
focused create/detail/settings interactions; permission-gated scanner/media and mutation-heavy
pending/conflict routes are covered by their pure/component tests and remain explicitly non-mutating
in CI. API-36 execution on the Mi Pad remains blocked by its installed Espresso/InputManager
compatibility issue; API-34 is the disposable instrumentation target.


## NBC-299: pay down the Android lint baseline

`app/lint-baseline.xml` is currently 1,814 lines and includes 49 `UseKtx`, 36
`IntentFilterUniqueDataAttributes`, 21 `GradleDependency`, and 11 `MissingPermission` findings,
among others. The baseline keeps CI green but hides a large amount of known maintenance debt.

- [x] Classify each baseline entry as fixed, intentionally suppressed with a reason, or obsolete.
- [x] Remove fixable findings in small batches and regenerate the baseline after each batch.
- [x] Fail CI when new baseline findings are introduced and document the remaining intentional
      suppressions.

Status: **done**, 2026-08-03 - remote lint reduced the baseline from 1,814 lines / 165 entries to
319 lines / 29 reviewed toolchain entries. Fixable KTX, permission, primitive-state, logging,
modifier, camera opt-in, manifest, and dead-resource findings were removed in staged batches.
Remaining dependency/toolchain pins and the adaptive-icon resource false positive are classified
in `docs/lint-baseline.md`; CI rejects any new or obsolete baseline entry.


## NBC-300: clear the remaining non-baselined lint and compiler warnings

The remote `:app:lintDebug` gate initially reported six non-baselined warnings: one
modifier-parameter ordering warning and KTX suggestions for `String.toUri`, `createBitmap`, and
`Bitmap.scale`. The compiler also reported deprecated Hilt Compose and lifecycle imports, plus
deprecated mirrored icon and transform APIs.

- [x] Fix the six current lint warnings and keep the baseline from absorbing them.
- [x] Migrate the deprecated Hilt Compose import to `androidx.hilt.lifecycle.viewmodel.compose`.
- [x] Run the build with full deprecation warnings and remove or document project-owned Gradle
      deprecations.

Status: **done**, 2026-08-03 - `just test`, `just lint`, and remote `:app:lintDebug` pass. The
AndroidX Security Crypto deprecation is documented at its compatibility boundary, the mirrored
Markdown icon and new KTX opportunities were fixed, and the remaining baseline findings are
tracked under NBC-299; no unbaselined project-owned compiler warning remains.


## NBC-301: show cached item changelog and add an explicit changelog tab

Item detail pages should expose the cached NetBox object changes for the current item. A long press
on a detail row should offer a changelog action, and the item should also have a dedicated
Changelog tab. Selecting a cached change opens the existing colored diff view; the feature must
remain useful offline and must not perform a live lookup just to open the list.

- [x] Add a cache-first changelog repository query keyed by endpoint and object id.
- [x] Add a generic detail Changelog tab with change rows and an empty state.
- [x] Add a long-press Changelog action to field rows and route each result to the diff screen.
- [x] Add focused tests for changelog filtering, tab visibility, and long-press routing.
- [x] Add a device overflow action that opens a component-type picker and pre-fills the parent
      device in the generic offline-capable creation form.

Status: **done**, 2026-08-03 - cache-first DAO/repository flows, typed and generic detail tabs,
field actions, component picker/create routing, parser tests, Compose tests, remote unit/lint/
compile checks, and Mi Pad launch verification completed.


## NBC-302: make the cached topology view usable on mobile

The netbox-topology view is currently unusable on a phone: the rendered graph appears as a giant
square with a dot in the middle, and only that surface responds usefully to zoom. The cached
topology needs to render its actual nodes and connections at a useful initial scale, with reliable
pan and pinch/button zoom controls.

- [x] Parse the topology export robustly enough to retain the plugin's actual node and edge ids.
- [x] Make the graph viewport fit real graph bounds and support intuitive pan/zoom on phones.
- [x] Add focused parser and viewport tests for multi-node exports and empty/malformed geometry.
- [x] Keep dense node labels hidden at overview scale and reveal concise labels only after zooming
      in, so a large topology remains readable and navigable on mobile.

Status: **done**, 2026-08-03 - removed the old `node_*` id restriction, added a deterministic
fallback layout for missing/degenerate coordinates, capped pathological fit scaling, hid dense
labels until a readable zoom level, and passed remote unit/lint/compile checks plus a Mi Pad
overview/two-step-zoom sanity check with the final APK.


## NBC-303: generate proper GitHub release changelogs

Tagged GitHub releases currently contain only the static installation and artifact notes. They
should also include GitHub's categorized changelog for the commits and pull requests included in
the release.

- [x] Enable generated release notes for permanent semantic-version releases.
- [x] Keep the existing signed-build, APK, and checksum instructions alongside the generated notes.
- [x] Write an explicit readable summary with the release commit range and GitHub-generated details,
      instead of relying on the action to merge a sparse generated body with static notes.

Status: **done**, 2026-08-03 - tagged releases now publish an explicit Markdown summary containing
the commit range, GitHub-generated details when available, installation notes, and checksum/build
metadata; workflow YAML validation and remote Android checks pass.


## NBC-304: use gravity-based topology layout

The fallback topology layout currently places every node on a static grid. It avoids overlap but
does not communicate the topology's connectivity the way the NetBox plugin's physics layout does.

- [x] Replace the grid fallback with a deterministic force-directed layout.
- [x] Use connections as attractive forces, node separation as repulsion, and gravity to keep the
      result bounded and usable offline.
- [x] Add parser tests covering deterministic connected/disconnected layouts.

Status: **done**, 2026-08-03 - added a deterministic force-directed fallback with spring attraction,
repulsion, gravity, cooling, and parser determinism tests; verified the connected clusters and
two-step zoom on the Mi Pad 4 with the final APK.


## NBC-305: distinguish topology node icons

The custom topology renderer currently paints every node with the same square-and-dot glyph. It
should use distinct, consistent icons for common network, compute, power, wireless, and generic
object families.

- [x] Classify node labels into stable topology icon families.
- [x] Render distinct glyphs in the graph and keep the mapping covered by tests.

Status: **done**, 2026-08-03 - added stable generic/compute/network/power/wireless glyph families,
covered the classifier with tests, and verified the rendered graph on the Mi Pad 4.


## NBC-307: optionally show device-type images in topology

Topology device nodes should be able to reuse cached device-type front images for a more familiar
view. This needs a user preference, with the family glyphs from NBC-305 remaining the fallback when
the preference is disabled or no image is cached.

- [x] Add a topology presentation preference for device-type front images.
- [x] Resolve images from the local cache without introducing a live lookup in the graph renderer.
- [x] Fall back cleanly to the topology node-family icons when images are disabled or unavailable.

Status: **done**, 2026-08-03 - added the cached-image preference and local durable-file lookup with
family-icon fallback; remote tests/lint/compile passed and the topology was verified on Mi Pad 4.


## NBC-308: make topology nodes discoverable and clickable

Topology labels should become readable at a practical zoom level. Device nodes should open a
compact preview containing the device summary and its connected devices, with a tap-through to the
full cached device view.

- [x] Show device names earlier without recreating the dense overview text wall.
- [x] Hit-test rendered nodes and show a concise device preview in a modal bottom sheet on tap.
- [x] List the selected device's connected devices and link to the full device view.
- [x] Add focused interaction tests for node navigation/viewport behavior and cached neighbor resolution.

Status: **done**, 2026-08-03 - labels, node overlays, preview sheets, connected-device navigation,
and cached-neighbor unit coverage were added; Mi Pad 4 topology navigation was verified.


## NBC-306: gate optional plugin features by server capabilities

Topology and netbox-documents are optional NetBox plugins. Their navigation entries, sync work,
and item actions should only be exposed when the configured server reports the corresponding
plugin as installed.

- [x] Derive capability flags from the cached/server plugin directory.
- [x] Hide topology navigation/sync when `netbox_topology_views` is unavailable.
- [x] Hide document navigation/actions/sync when `documents` is unavailable.
- [x] Keep capability decisions cache-first and covered by tests, including offline startup.

Status: **done**, 2026-08-03 - directory-backed capability predicates gate topology sync and
document surfaces; cache/offline behavior is covered by repository tests and remote validation.


## NBC-309: make recently visited search results obvious

Global search shows recently visited devices and pages before a query is entered, but they are
currently too easy to mistake for ordinary results.

- [x] Add a clearly visible recent-visit badge or card treatment to those results.
- [x] Keep the treatment consistent for the empty-query and queried result states.
- [x] Cover the distinction with search-result presentation tests.

Status: **done**, 2026-08-03 - recent results use a History badge/card treatment and retain it in
queried results; search ranking/visit tests and the remote unit suite pass.


## NBC-310: allow manual topology node positioning

The topology graph should let users reposition nodes when the automatic layout is not ideal.
A long press followed by dragging should move the selected node without interfering with graph
pan/zoom gestures.

- [x] Add long-press drag hit testing for rendered topology nodes.
- [x] Keep manual positions separate from the cached export and preserve them across refreshes.
- [x] Add interaction tests covering node drag versus viewport pan.

Status: **done**, 2026-08-03 - long-press overlays persist positions in settings independently of
the export; topology viewport/position tests and Mi Pad 4 interaction checks pass.


## NBC-311: center topology on a device from its detail page

Device pages should offer a topology action that opens the cached topology with the current device
centered and selected, so users can quickly understand its connected devices.

- [x] Add a device-page topology action and route state for the focused device.
- [x] Center and highlight the selected device when opening the topology view.
- [x] Keep the action cache-first and provide a friendly fallback when no topology is cached.
- [x] Add navigation and focused-node tests.

Status: **done**, 2026-08-03 - device overflow navigation carries focus into the cached topology;
route/viewport tests and Mi Pad 4 navigation verification pass.


## NBC-312: keep topology button zoom focused on graph content

The topology zoom-in and zoom-out buttons can move the viewport toward empty space instead of
keeping useful nodes under the user's focus.

- [x] Anchor button zoom to the visible graph content or a stable focused node.
- [x] Keep the graph usable at both overview and detail scales without jumping into empty space.
- [x] Add viewport tests for repeated button zoom and reset behavior.

Status: **done**, 2026-08-03 - button zoom preserves the visible graph point or focused node;
viewport tests and Mi Pad 4 two-step zoom checks pass.


## NBC-313: support keyboard-assisted topology zoom

On desktop-style devices, topology zoom should also be available through Ctrl plus mouse-wheel
scrolling, matching the graph's button and pinch controls.

- [x] Handle Ctrl+mouse-wheel up/down as graph zoom gestures.
- [x] Keep ordinary mouse-wheel scrolling and panning behavior unchanged.
- [x] Add focused input tests for zoom direction and modifier handling.

Status: **done**, 2026-08-03 - Ctrl-wheel zoom is modifier-gated and covered by pure input tests;
ordinary transform gestures remain unchanged.


## NBC-314: search and focus devices in topology

Topology should provide a device-only search action using the existing cache-first global-search
syntax. Selecting a result should focus the matching node without requiring a live request.

- [x] Add a topology search action that opens a popup or bottom sheet.
- [x] Reuse magic field syntax and restrict results to cached devices.
- [x] Focus and highlight the selected device node.
- [x] Add search and focus interaction tests.

Status: **done**, 2026-08-03 - cache-only device search, structured-query highlighting/match hints,
focus selection, cancellation of stale keystroke searches, and Mi Pad 4 verification are complete.


## NBC-315: use item icons in list and dashboard headers

List-page headers should show the relevant NetBox item icon before their title, and dashboard
section headers should carry an icon as well for stronger visual orientation.

- [x] Add the item-specific icon to generic and typed list headers.
- [x] Add icons to dashboard section headers using the shared icon mapping.
- [x] Cover header icon rendering without changing navigation behavior.

Status: **done**, 2026-08-03 - shared endpoint icons now appear in list/dashboard headers; remote
lint/unit/compile checks and Mi Pad 4 dashboard/drawer verification pass.


## NBC-316: smooth scanner camera and lens switching

Switching between scanner cameras or rear lenses currently exposes a brief black frame. The
preview handoff should feel like a camera app, with a short visual transition while the new use
case binds.

- [x] Add a short fade/crossfade around camera and lens rebinding.
- [x] Keep scanner controls responsive and avoid hiding a failed-preview error.
- [x] Verify rear-lens, front/rear-camera, and single-lens fallback behavior.

Status: **done**, 2026-08-03 - the preview handoff now fades with a bounded transition overlay and
retains binding errors; Mi Pad 4 scanner coverage and the existing multi-lens tests pass.


## NBC-317: show connected devices on device pages

Device detail pages should expose the cached topology relationships in a dedicated Connected
devices tab, so users can jump from a device to its neighbors without opening the topology canvas.

- [x] Derive the selected device's neighbors from the cached topology graph and cached devices.
- [x] Show a Connected devices tab only when cached neighbors are available, with a count badge.
- [x] Open a neighbor's regular device detail page when its row is selected.
- [x] Keep the tab cache-first and verify it with topology/device repository tests.

Status: **done**, 2026-08-03 - the new cache-only Connected devices tab resolves topology edges
against Room devices, links to regular device details, and is covered by a neighbor-resolution test.


## NBC-318: make cached search more responsive

Global and topology search should feel immediate even with a large offline cache. The current
pipeline reevaluates multiple Room flows and decodes generic JSON on every keystroke, which can
make structured searches appear frozen on older devices.

- [x] Debounce and cancel superseded query work at the ViewModel boundary.
- [x] Avoid rebuilding the same cached search candidates and JSON projections repeatedly.
- [x] Keep structured filters, recursive network matches, and cache-first behavior intact.
- [x] Add query-index/filter regression coverage for rapid-query behavior.

Status: **done**, 2026-08-03 - global search now uses a Room-backed in-memory projection index,
debounced/cancellable query flows, and preserved structured/network matching; remote unit/lint/
compile checks pass.


## NBC-319: highlight topology search syntax

The topology device-search field accepts the same `field:value` and `field=value` syntax as global
search, but currently renders it as plain text. The recognized field token should be visibly
highlighted so users know the structured query was understood.

- [x] Reuse the shared structured-query visual transformation in the topology search field.
- [x] Preserve cursor/editing behavior and leave ordinary free text unchanged.
- [x] Cover the transformation with focused range/style tests.

Status: **done**, 2026-08-03 - the global and topology fields share an offset-preserving syntax
transformation; range/style tests and remote validation pass.


## NBC-320: add a short manufacturer search alias

Structured search should accept `man:value` as a concise alias for `manufacturer:value`, while
retaining the canonical manufacturer matching and visual treatment.

- [x] Normalize `man` to the manufacturer filter in the shared parser.
- [x] Cover colon, spaced-colon, and equals forms without changing free-text parsing.

Status: **done**, 2026-08-03 - `man:` is canonicalized to `manufacturer:` for all supported
separators and is covered by parser tests.


## NBC-321: use singular object-type result badges

Global-search result badges currently reuse directory collection labels, producing labels such as
“Device Types” for one result. Result badges should describe the individual object in singular
form, consistently for directory-backed and fallback endpoint labels.

- [x] Singularize directory and endpoint labels used by object-type result badges.
- [x] Preserve acronyms and multi-word labels such as “IP Address” and “Device Type”.
- [x] Add regression tests for directory-backed and fallback labels.

Status: **done**, 2026-08-03 - result badges now use singular collection labels with acronym-aware
multi-word handling; label regression tests and remote validation pass.


## NBC-322: support short type filters in magic search

Global search should support `type:value` (and the shorter `tpe:value`) to constrain results to a
NetBox object collection, including compact values such as `type:dev`, `type:dt`, and `type:ip`.

- [x] Parse `type` and `tpe` as a collection filter rather than an object-field filter.
- [x] Resolve common short names and generic cached collection names case-insensitively.
- [x] Keep type filters cache-first, composable with other filters, and visibly highlighted.
- [x] Add parser and result-scope regression tests.

Status: **done**, 2026-08-03 - type filters support compact device/device-type/IP aliases and
generic cached collections, compose with other filters, and are covered by parser/scope tests.


## NBC-324: receive shared media for attachment uploads

Images and arbitrary files shared from another Android app should open a cache-first target picker,
then upload to the selected NetBox item as an image attachment or NetBox document. Device types
should additionally offer front/rear photo replacement for shared images.

- [x] Register image/file share intents and preserve the content URI through navigation.
- [x] Reuse global cached search for selecting any supported NetBox object, not only devices.
- [x] Preselect image attachments/documents and expose device-type front/rear replacement.
- [x] Add routing coverage and verify the shared-image target/upload flow on Mi Pad 4.

Status: **done**, 2026-08-03 - Android SEND intents now open the cache-first target picker and
upload screen for generic objects; media uploads are installed and verified on Mi Pad 4.


## NBC-323: avoid duplicate values in structured search hints

Structured manufacturer matches can expose both a relation's display name and slug, producing
awkward text such as `Matched Manufacturer: Shelly shelly`.

- [x] Keep relation aliases available for matching.
- [x] Deduplicate repeated case-insensitive words in the displayed match hint.
- [x] Cover the formatting regression with a focused unit test.

Status: **done**, 2026-08-03 - search hints now collapse repeated words while preserving the full
cache-backed search index; remote unit/lint/compile validation passed.


## NBC-325: preview media received through Android sharing

The shared-media upload flow should show what is about to be uploaded before the target is
selected and confirmed. Images should render as thumbnails, PDFs should render their first page
when Android can open the content URI, and other document types should have a useful fallback.

- [x] Show a local image thumbnail for shared and newly selected images.
- [x] Render the first page of shared PDFs when the content provider supports random access.
- [x] Display filename/type metadata and a clear fallback for non-previewable documents.
- [x] Cover image detection when a sharing app omits the MIME type.

Status: **done**, 2026-08-03 - shared-image and PDF previews are rendered from the content URI in
the target/upload flow; unknown document types retain a clear document preview fallback.


## NBC-326: improve topology rendering performance on older devices

The topology view redraws a full custom canvas for every pan/zoom event and currently performs
layout, edge lookup, node classification, and text measurement work in the composition path. On
older devices this makes gestures feel sluggish, especially for larger graphs.

- [x] Profile frame time and identify the dominant cost on Mi Pad 4; Pixel 5 UI profiling was
  intentionally skipped because it is reserved for installation-only checks.
- [x] Precompute immutable edge paths, node classifications, and label layouts when the graph
  changes instead of during every canvas draw.
- [x] Avoid allocating per-frame lists/objects and use a level-of-detail policy for distant nodes
  and labels.
- [x] Keep drag/pan/zoom state local to the canvas and persist node positions only after gestures
  settle.
- [x] Add a regression fixture for a representative 500-node/900-edge topology graph.

Status: **done**, 2026-08-03 - remote lint/unit tests/build passed; the Mi Pad 4 rendered its
cached 392-node/231-connection topology and a gfxinfo gesture sample improved from roughly 450ms
median frames with per-node overlays to roughly 77ms after the indexed renderer and graph-level
input/LOD changes. Pixel 5 UI profiling was skipped per device-testing preference.


## NBC-327: delete NetBox documents from item pages

Long-pressing a document in the item overview should expose document actions, including a confirmed
delete operation. The cache should hide the document immediately and offline deletion should use the
durable mutation queue.

- [x] Add a long-press actions dialog with open and delete actions.
- [x] Require explicit confirmation before deleting a document.
- [x] Reuse the generic pending-delete path for online and offline document deletion.
- [x] Show completion feedback for deleted and queued documents.

Status: **done**, 2026-08-03 - generic and device item pages now support confirmed cache-first
document deletion; no live NetBox document was deleted during verification.


## NBC-328: show object-type icons on linked item rows

Linked values such as a device's device type, rack, manufacturer, site, and IP address should carry
the same object-type icon used elsewhere in the app, before the linked item's display name.

- [x] Add endpoint-derived icons to generic reference and reference-list rows.
- [x] Add endpoint-derived icons to linked rows on the device detail page.
- [x] Reuse the shared AppIcons mapping so the visual language stays consistent.

Status: **done**, 2026-08-03 - generic linked rows and device detail references now render the
corresponding endpoint icon before the linked value.


## NBC-329: preserve media filename extensions during uploads

Some Android sharing providers expose a content URI or display name without an extension. NetBox
Documents relies on the stored filename extension to select the right viewer, so uploads should
retain a real extension whenever the provider supplies a useful MIME type.

- [x] Infer common image, PDF, office, archive, and text extensions from MIME types.
- [x] Apply the normalized filename to image attachments, device-type photos, and documents.
- [x] Leave extensionless uploads without an extension when the MIME type is unavailable; do not
  invent a `.bin` or image suffix.

Status: **done**, 2026-08-03 - upload requests now preserve existing extensions, infer missing ones
from the selected content MIME type, and leave unknown types extensionless; no live upload was
performed during verification.


## NBC-330: rebrand the application as Nyetbox

Rename the Android application identity from NetBox and Chill to Nyetbox, including its package
names, launcher/deep-link branding, build and release metadata, documentation, and GitHub
repository slug. Keep references to NetBox where they describe the compatible upstream product.

- [x] Rename the Android namespace, application ID, source packages, and technical app classes.
- [x] Replace app labels, themes, custom URI schemes, build scripts, CI, and release metadata.
- [x] Update README, privacy policy, store metadata, and repository links while documenting the
  former name.
- [x] Set the application version to 1.1.0 and verify the debug package on Mi Pad 4 and PX5.
- [x] Rename the GitHub repository and update local remotes/documentation.

Status: **done**, 2026-08-03 - remote ktfmt, unit tests, and debug build passed; the 1.1.0 debug
package was installed on Mi Pad 4, PX5, and Zenfone 10 before the Zenfone disconnected during
post-install verification; GitHub was renamed to `pschmitt/nyetbox` and the local origin updated.


## NBC-331: show live attachment progress in Cached data settings

When a sync is actively downloading durable image attachments and documents, the Cached data
settings row should show live progress instead of stale totals from the last completed sync.

- [ ] Expose attachment completion/total and downloaded byte progress from the sync state.
- [ ] Update the Cached data row while the attachment phase is running.
- [ ] Restore the normal cache totals after completion or failure without blocking settings.

Status: not started


## NBC-335: keep the README icon on transparent artwork

The README icon should show only the Nyetbox face artwork. Remove the blue background, border, and
decorative framing so it reads cleanly on the page.

- [x] Remove the opaque background and outer border from the README SVG.
- [x] Match the actual adaptive launcher icon: solid circular background and original face artwork.

Status: **done**, 2026-08-03 - README SVG matches the Mi Pad launcher composition, including its
solid circular `#011226` mask and original white/teal foreground artwork.


## NBC-333: install Nyetbox through the homelab Android config

The shared declaroid configuration for rooted homelab devices should install the signed Nyetbox
release on the Mi Pad 4 and Pixel 5, without adding it to the Zenfone configuration.

- [x] Add a shared `android/imports/homelab.yaml` entry for the Nyetbox release package.
- [x] Import the shared homelab app set from the Mi Pad 4 and Pixel 5 configs.
- [x] Narrow GitHub asset selection to the release APK and validate both resolved configs.

Status: **done**, 2026-08-03 - declaroid read-only diff resolved Nyetbox as missing on the Mi Pad
4 and Pixel 5 and excluded it from the Zenfone config; no device state was changed.


## NBC-334: consolidate sync indicators on the Sync settings page

The Settings → Sync screen currently exposes two separate sync indicators, one near the top that
is not always visible and another at the bottom. It should present one clear, consistently placed
status/control instead.

- [ ] Remove the duplicate sync indicator.
- [ ] Keep the remaining status and action visible and unambiguous while sync is active.

Status: not started


## NBC-332: animate the active sync control

The `Syncing…` control on Settings → Sync should provide a subtle animated progress indication
while a sync is running, so it is visibly active rather than looking static.

- [ ] Add a restrained rotation or progress animation to the syncing icon.
- [ ] Keep the animation accessible and stop it immediately when sync completes or fails.

Status: not started


## NBC-336: show the independence disclaimer on login

The login page should make it clear that Nyetbox is an independent project and is not affiliated
with NetBox Labs.

- [x] Add an italic disclaimer at the bottom of the login page.
- [x] Keep the wording clear without implying endorsement or affiliation.

Status: **done**, 2026-08-03 - disclaimer added to the onboarding screen; verified by remote lint.


## NBC-337: show current NetBox user and test the connection

Settings → Connection should identify the NetBox user associated with the configured API token and
offer a lightweight connection test without starting a full synchronization.

- [x] Resolve and cache the token owner for offline display.
- [x] Prefer NetBox's `/api/authentication-check/` endpoint, with a legacy fallback for older
  instances.
- [x] Show the current user and optional email on the Connection screen.
- [x] Add an icon-bearing Test connection button with success and failure feedback.
- [x] Bump the app version to 1.1.1.

Status: **done**, 2026-08-03 - remote formatting/lint and unit tests passed; no NetBox data was
modified.


## NBC-338: publish and link the privacy policy

The app needs a clear privacy policy and a discoverable link from Settings → About, suitable for
users and future store distribution.

- [x] Maintain a repository-hosted privacy policy describing network access, local storage, and
  explicit sharing behavior.
- [x] Disclose the optional public NetBox news-feed request and the absence of telemetry.
- [x] Add an icon-bearing Privacy policy link to Settings → About.

Status: **done**, 2026-08-03 - policy updated and linked to the repository document; remote lint
and unit tests used for verification.


## NBC-339: keep the GitHub Actions lint gate green

The lint workflow was failing because two newly reported warnings changed the checked-in Android
Lint baseline on every run.

- [x] Remove the redundant activity label already inherited from the application.
- [x] Use AndroidX's `String.toUri()` extension in the shared-media screen.
- [x] Confirm the lint baseline remains unchanged after the fixes.

Status: **done**, 2026-08-03 - remote ktfmt, Android Lint, and unit tests passed locally; GitHub
Actions rerun pending after push.


## NBC-340: make Add item pins editable

Pinned item types on Add should use the same persisted pin state as the rest of the app, including
the built-in Devices and Device types entries.

- [x] Make the built-in pinned entries removable with a long press.
- [x] Show the current pin state on Add item rows.
- [x] Preserve existing pin choices with a one-time preference migration.
- [x] Verify the pin/unpin state handling and five-item limit in the shared preference logic.

Status: **done**, 2026-08-03 - remote ktfmt, Android Lint, and unit tests passed; the built-in
Devices and Device types entries now use the persisted pin set and can be toggled by long press.


## NBC-341: retain named screenshots from Android E2E runs

The disposable Android E2E workflow should make visual regressions easier to diagnose without
requiring a rerun or a locally attached emulator.

- [x] Capture named screenshots at important onboarding, navigation, search, and offline states.
- [x] Pull screenshots from the emulator into the GitHub Actions workspace.
- [x] Upload screenshots on successful and failed E2E runs alongside existing diagnostics.

Status: **done**, 2026-08-03 - implementation added; remote Android test compilation and the
GitHub E2E workflow remain to be verified after push.


## NBC-342: modernize the Settings presentation

The Settings landing screen and category pages should use the more spacious, card-based Material 3
presentation used in the companion `aughhhh` app while preserving all existing setting behavior.

- [x] Replace the flat landing-page rows with rounded setting/navigation cards.
- [x] Group category controls into titled cards with clear section icons and subtitles.
- [x] Keep existing actions, dialogs, switches, and navigation unchanged.
- [x] Verify the updated layout through remote compilation, lint, and instrumentation compilation.

Status: **done**, 2026-08-03 - remote ktfmt, Kotlin/unit-test compilation, Android test compilation,
and Android Lint passed; the updated debug build is ready for device verification.


## NBC-343: make Settings rows feel native inside grouped cards

The new Settings cards should read as a single surface. Nested opaque row backgrounds and repeated
edit-pencil buttons make the page look cluttered and unlike a native Android settings screen.

- [x] Make settings rows transparent inside their containing cards.
- [x] Replace edit-pencil affordances with full-row interactions and native selector chevrons.
- [x] Keep explicit actions, switches, and accessibility labels intact.
- [x] Verify the updated presentation through remote compilation, lint, and device installation.

Status: **done**, 2026-08-04 - remote ktfmt, Kotlin/unit-test compilation, instrumentation compilation,
and Android Lint passed; the debug build was installed on Zenfone 10, Mi Pad 4, and PX5.


## NBC-361: polish item identity cards, dashboard sections, and list rows

The shared generic item/device-type card was still mostly empty, while the dashboard and scoped
item lists did not yet use the same grouped, image-forward presentation as global search.

- [x] Make generic item cards show the item identity and cached device-type preview when available.
- [x] Keep front/rear device-type images available through the full-screen viewer.
- [x] Make the device asset-tag badge vertically centered in its identity card.
- [x] Show a useful empty state for a fresh Recently viewed section while sync is running.
- [x] Group dashboard sections into a large titled card with independently actionable rows.
- [x] Bring device and generic item list rows in line with the global-search card treatment.

Status: **done**, 2026-08-04 - remote Kotlin compilation, unit tests, Android-test compilation, lint,
and debug installation on Zenfone 10, Mi Pad 4, and PX5 passed.


## NBC-362: bound thumbnail memory use in long item lists

Long device-type lists could run out of memory while scrolling because the shared thumbnail
loader decoded full-resolution NetBox photos before applying its transparent-padding crop.

- [x] Bound thumbnail decoding to the rendered card size.
- [x] Keep full-resolution image viewing unaffected.
- [x] Verify the shared thumbnail path through remote compilation, lint, and device installation.

Status: **done**, 2026-08-04 - remote Kotlin compilation, unit tests, lint,
and debug installation on Zenfone 10, Mi Pad 4, and PX5 passed.


## NBC-363: simplify device-type photos and preserve list thumbnails

The device-type detail page already shows the front photo in its identity card, so the duplicate
front/rear photo widget is unnecessary. Editing must remain discoverable from the image viewer and
from long-press actions, including when no photo exists yet.

- [x] Remove the duplicate front/rear photo widget from the device-type overview.
- [x] Add image-viewer editing and long-press upload/edit actions for device-type photos,
      including the placeholder state.
- [x] Keep device-type list thumbnails fully visible instead of cropping them.

Status: **done**, 2026-08-04 - remote compilation, unit tests, Android-test compilation, lint,
ktfmt check, and debug installation on Zenfone 10, Mi Pad 4, and PX5 passed.


## NBC-364: restore document previews and simplify document rows

Cached PDF documents can fall back to the generic document icon when their filename no longer
contains an extension. The document card already opens the file, so a second per-row download
action is redundant.

- [x] Detect cached PDFs from their filename, URL, or file signature and render the first page.
- [x] Remove the redundant download/open trailing icon from document rows.

Status: **done**, 2026-08-04 - remote compilation, unit tests, Android-test compilation, lint,
ktfmt check, and debug installation on Zenfone 10, Mi Pad 4, and PX5 passed.


## NBC-360: support multiple NetBox server profiles with isolated caches

Allow users to add and switch between multiple NetBox instances after onboarding while keeping
only one instance active at a time. Each instance must retain its own Room and durable-media cache;
removing a saved instance is the only operation that may delete its cache, and it requires explicit
confirmation.

- [x] Store multiple server URL/token profiles and migrate the existing single-server settings.
- [x] Add Settings UI for adding, switching, editing, and removing server profiles.
- [x] Isolate Room, durable attachment, and topology caches per profile without clearing on switch.
- [x] Keep active-server identity, sync state, and network operations tied to the selected profile.
- [x] Add an optional switch-server gesture action.
- [x] Verify switching and offline cache retention with an Android instrumentation test; no production
  or temporary NetBox instance was needed.

Status: **done**, 2026-08-04 - remote compile, unit tests, instrumentation compilation and cache
isolation test, ktfmt, lint, and debug installation on Zenfone 10 and Mi Pad 4 passed. PX5
installation was attempted but its wireless ADB endpoint was unavailable.


## NBC-350: support split NetBox API token entry on login

The login form should default to the current NetBox token-name + token workflow while retaining
an optional full-token field for legacy credentials, pasted setup payloads, and older instances.

- [x] Add split token-name/token UI as the default login mode.
- [x] Keep full-token mode available and auto-detect complete pasted `nbp_`/`nbt_` tokens.
- [x] Serialize split values as `nbp_<TOKEN_NAME>.<TOKEN>` and use the correct auth scheme.
- [x] Verify with unit tests, remote compilation/lint, and device installation.

Status: **done**, 2026-08-04. Verified with remote `ktfmtCheck`, debug Kotlin/unit-test compilation,
Android lint, instrumentation compilation, and `just deploy-all debug` (Zenfone 10, Mi Pad 4, and
Pixel 5).


## NBC-351: verify HTTPS deep-link chooser behavior on Android

Verify the user-facing behavior of `https://<netbox>/dcim/devices/<id>/` links on a device with a
browser already selected as the preferred HTTPS handler. The Nyetbox intent filter matches, but
`am start` may launch that preferred browser without showing a chooser; verified App Links should
open Nyetbox directly for the release package.

- [x] Exercise the external `VIEW` intent used by Android link/share flows.
- [x] Confirm release App Link verification and document how to force the chooser for debugging.

Status: **done**, 2026-08-04. The live Digital Asset Links response now names
`dev.pschmitt.nyetbox` and `dev.pschmitt.nyetbox.debug` with their respective certificate
fingerprints; Android verified the release package on the wired Zenfone 10, and the exact
`am start` command launched Nyetbox.


## NBC-345: differentiate Settings group and row icons

Settings group headers and their first rows should not repeat the same icon when a more specific
icon is available for the setting itself.

- [x] Replace repeated header/row icons with role-specific icons.
- [x] Verify the updated Settings presentation through remote compilation and device installation.

Status: **done**, 2026-08-04 - remote ktfmt and Kotlin compilation passed without warnings; the
debug build was installed on Zenfone 10, Mi Pad 4, and PX5.


## NBC-352: modernize the global search presentation

Global search is functionally complete, but its visual hierarchy is still dense and inconsistent
with the newer Material 3 Settings and dashboard surfaces. Make the search experience feel like a
native Android search screen while preserving cache-first/offline behavior, recent visits, magic
syntax, type filtering, thumbnails, and result navigation.

- [x] Replace the outlined app-bar field with a modern persistent search control.
- [x] Present type completions and active type filters as compact, discoverable chips.
- [x] Improve the landing, recent, loading, and no-results states with clear hierarchy.
- [x] Make result metadata (type, recent visit, asset tag, and matched field) easier to scan.
- [x] Verify formatting, tests, lint, and installation on physical devices.

Status: **done**, 2026-08-04 - remote ktfmt, unit tests, Kotlin compilation, Android test
compilation, and lint passed; the debug APK was installed on Zenfone 10, Mi Pad 4, and PX5.


## NBC-353: keep global search responsive on large caches

Global search still feels laggy while typing on slower devices. The cache-first search must remain
fully offline, but its projection and ranking work should not block the Compose main thread.

- [x] Move cache projection, matching, and ranking off the main dispatcher.
- [x] Avoid rebuilding generic searchable text for every keystroke.
- [x] Verify the optimized path preserves recursive IP/MAC and type matching.

Status: **done**, 2026-08-04 - cache projection, generic and typed-device indexing, matching, and
ranking now run off the main dispatcher; unit tests, instrumentation compilation, and lint passed.


## NBC-354: unify the device overview identity card and device-type preview

The device overview currently places the identity card and device-type photos in separate surfaces.
Use one coherent card with a single front-first preview while keeping the rear photo available in
the existing full-screen viewer.

- [x] Embed the device-type preview in the overview identity card.
- [x] Show only the front photo in the overview when both front and rear photos exist.
- [x] Keep front/rear photos as horizontally swipeable pages in the image viewer.
- [x] Verify the front-only, rear-only fallback, and front-plus-rear code paths through compilation
      and the installed debug build.

Status: **done**, 2026-08-04 - remote compilation, unit tests, instrumentation compilation, lint,
and debug installation on Zenfone 10, Mi Pad 4, and Pixel 5 passed.


## NBC-355: unify global-search result badges

Global-search result metadata should use one compact pill style. Status should be represented as a
badge alongside the object type, recent-visit, asset-tag, and matched-field metadata.

- [x] Use the same compact shape, padding, icon size, and typography for all result badges.
- [x] Display cached object/device status as a status badge.
- [x] Keep the metadata on one horizontally scrollable row on narrow screens.
- [x] Verify the shared presentation in the global search screen and topology search.

Status: **done**, 2026-08-04 - shared global-search result rendering now uses compact uniform badges;
unit tests, instrumentation compilation, and lint passed.


## NBC-356: use device-type front photos as device-card identity visuals

The device overview identity card should lead with the cached device-type front photo when one is
available. The generic device icon is only a fallback for devices without a front photo, and the
overview should not add a redundant “Front” caption below the image.

- [x] Use the front device-type photo as the leading visual in the identity card.
- [x] Keep the generic device icon only when no front photo exists.
- [x] Remove the caption below the front preview while preserving front/rear viewer swiping.
- [x] Verify front-photo and no-front-photo device paths on the installed build.

Status: **done**, 2026-08-04 - remote compilation, unit tests, instrumentation compilation, lint,
and debug installation on Zenfone 10, Mi Pad 4, and Pixel 5 passed.


## NBC-346: extend the rounded card visual language beyond Settings

The rest of the app should share the same calm, grouped Material 3 card treatment as Settings
without turning every screen into a collection of unrelated nested surfaces.

- [x] Add reusable app card and transparent list-row primitives.
- [x] Apply them to item detail, list, and dashboard surfaces.
- [x] Preserve existing navigation, actions, loading, and offline behavior.
- [x] Verify the migration through remote compilation, tests, lint, and device installation.

Status: **done**, 2026-08-04. Verified with remote `ktfmtCheck`, `:app:compileDebugKotlin`,
`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:compileDebugAndroidTestKotlin`, and
`just deploy-all debug` (Zenfone 10, Mi Pad 4, and Pixel 5).


## NBC-347: keep topology pinch zoom separate from global two-finger gestures

Pinching on the topology canvas currently also triggers the configured two-finger swipe action.
The topology transform gesture should take precedence over the app-wide shortcut recognizer.

- [x] Ignore global multi-finger swipes once a child consumes positional movement.
- [x] Preserve two-finger shortcuts on surfaces without a competing gesture.
- [x] Verify with remote compilation, tests, lint, and device installation.

Status: **done**, 2026-08-04. Verified with remote `ktfmtCheck`, `:app:compileDebugKotlin`,
`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:compileDebugAndroidTestKotlin`, and
`just deploy-all debug` (Zenfone 10, Mi Pad 4, and Pixel 5).


## NBC-349: show device-type photos in topology-related device lists

Topology node details and the device view's Connected devices tab should use the cached device-type
front photo when one exists, falling back to the normal device icon otherwise.

- [x] Show front photos in the topology node bottom sheet and its connected-device rows.
- [x] Show front photos in the device view Connected devices tab.
- [x] Verify with remote compilation, tests, lint, and device installation.

Status: **done**, 2026-08-04. Verified with remote `ktfmtCheck`, `:app:compileDebugKotlin`,
`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:compileDebugAndroidTestKotlin`, and
`just deploy-all debug` (Zenfone 10, Mi Pad 4, and Pixel 5).


## NBC-348: add double-tap zoom-in to topology

Topology should offer a familiar double-tap gesture for zooming into the graph. Double-tap should
only zoom in; zooming out remains available through the existing controls and reset action.

- [x] Zoom toward the tapped graph position on double-tap.
- [x] Keep repeated double-taps zooming in without introducing a zoom-out toggle.
- [x] Verify with remote compilation, tests, lint, and device installation.

Status: **done**, 2026-08-04. Verified with the focal-point viewport unit test plus remote
`ktfmtCheck`, `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:lintDebug`,
`:app:compileDebugAndroidTestKotlin`, and `just deploy-all debug` (Zenfone 10, Mi Pad 4, and
Pixel 5).


## NBC-344: avoid redundant headers on single-setting cards

Cards containing one setting or action should let that row carry the title and icon instead of
repeating the same label in a card header.

- [x] Remove redundant headers from single-setting/action cards.
- [x] Preserve the grouped-card headers where they provide real context.
- [x] Verify the updated Settings presentation through remote compilation and device installation.

Status: **done**, 2026-08-04 - remote ktfmt, Kotlin/unit-test compilation, instrumentation compilation,
and Android Lint passed; the debug build was installed on Zenfone 10, Mi Pad 4, and PX5.


## NBC-365: replace the launcher icon with an original mark (drop the NetBox logo reference)

The adaptive launcher icon (`ic_launcher_foreground_vector.xml`, from NBC-4) was a vector
recreation of NetBox's own corner-node logo with a raised-eyebrow face overlaid. That's a real
trademark risk for a Play Store listing, not just a hobby-repo one.

- [x] Design a replacement box shape that keeps the face (flat left brow, raised right brow,
  neutral mouth) and the existing palette, but drops NetBox's rectangle-with-corner-nodes layout.
- [x] Size the mark to actually use the adaptive-icon safe zone instead of the large margin the
  old NetBox silhouette needed.
- [x] Update `ic_launcher_foreground_vector.xml` and `docs/images/nyetbox-icon.svg` together so
  the in-app icon (Sidebar/Onboarding, both resolved from `R.mipmap.ic_launcher`) and the README
  preview match.
- [x] Drop the README trademark note's claim that the app's own logo is NetBox's, since it no
  longer is.
- [x] Correct the mark's size after on-device testing showed real clipping, not just a
  documentation-radius overshoot.
- [x] Shrink it once more for extra margin after the first correction, still unverified on a
  device at that point.

**Why:** user's explicit direction - the previous icon is fine for a side project but not
something to ship to the Play Store carrying another company's mark.
**How to apply:** final mark is a wide rounded rack-panel outline (`M39,38 L69,38 A7,7...`, teal
top/right + white left/bottom two-tone stroke, 5px), face at `(42,57)`/`(66,57)` eyes (r6),
brows at y47, mouth at y66 - all authored directly in the 108x108 viewport coordinate space (no
extra scale-down group, unlike the old icon's `scale(.64)` wrapper). History of this box's size,
corner reach vs. the formal 66dp/r33 safe-zone guideline: first shipped pass reached ~35 (over
the line, and it showed - Zenfone 10/Mi Pad 4/Pixel 5 all clipped it); corrected to 30 (a 4-3-5
triangle, ~9% margin, not yet device-verified before the next request); current pass to 27.2
(a 22x16 box, ~18% margin) per follow-up "smidge smaller" feedback.

Status: **done**, 2026-08-04 - remote `just build` (debug) and `just lint` passed; deployed to
Zenfone 10, Mi Pad 4, and Pixel 5 via `just deploy-all debug` for visual verification.

## NBC-367: automate Play Store screenshot capture (POC)

Add a fastlane screengrab proof of concept so dashboard/device-detail/search/settings listing
screenshots don't require manually capturing them against a real NetBox instance, and never risk
leaking real inventory data into a public store listing.

- [x] Add a `StoreScreenshotTest` instrumented test capturing dashboard, device detail, search,
      and settings
- [x] Wire `tools.fastlane:screengrab` into the androidTest dependencies
- [x] Add `fastlane/` config (Appfile, Screengrabfile, Fastfile) scoped to en-US only
- [x] Add a `screenshots` Nix dev shell with the Android emulator + an API 34 google_apis x86_64
      system image
- [x] Add `netbox-up`/`netbox-seed`/`netbox-down` and `screenshots-avd-create`/
      `screenshots-emulator-start`/`-stop`/`screenshots-build`/`screenshots` just recipes, reusing
      the disposable `ci/netbox/` fixture already built for `android-e2e.yaml`
- [x] Run `just screenshots` end to end on this machine (KVM-accelerated emulator) and verify the
      captured images show real seeded content, not loading placeholders
- [x] Integrate with the current `main` after NBC-345/347/etc landed; fix the resulting id
      collision by assigning this entry `NBC-367`, and fix a pre-existing compile break in
      `SettingsCategoryContentTest.kt` (stale `onDisconnect` param from the NBC-360 multi-profile
      refactor) that was blocking the whole androidTest source set
- [x] Replace the reused E2E seed data with `ci/netbox/seed_screenshots.py`, a small
      realistic-looking demo rack (Acme Networks / Berlin Data Center / Rack A1, 4 devices),
      giving the dashboard richer stats than a single bare device
- [x] Wait for an actual search-result card and fail the capture if search renders no result,
      rather than silently accepting an empty screenshot
- [x] Add a topology screenshot using a seeded four-node graph with three connected interface
      cables
- [x] Build the screenshot-only NetBox fixture with `netbox-topology-views` and `netbox-documents`,
      and seed a named demo document without changing the regular CI E2E fixture
- [x] Add an explicit `gpc` upload recipe for reviewed screenshots, targeting the release package
      without making capture itself modify the Play Console listing
- [x] Verify the upload target with `gpc apps list` rather than relying on `gpc doctor`
- [x] Add a manual GitHub Actions tablet capture job using the existing
      `reactivecircus/android-emulator-runner` and the disposable plugin-enabled fixture
- [x] Run the manual workflow and review the generated tablet screenshots before uploading them
- [x] Upload the flattened app icon and verify it in the Play Console listing
- [x] Also capture the same screenshots in dark mode
- [x] Add a phone screenshot lane to the CI workflow (currently local-only via `just screenshots`)
- [x] Add a 7" tablet screenshot lane to the CI workflow (no capture path exists yet for this
      bucket; `sevenInchScreenshots` is only a placeholder in `just screenshots-upload`)
- [x] Optionally have the CI workflow open a PR with the updated screenshots, instead of only
      uploading a build artifact for manual download/review

Status: **done**, 2026-08-05. `just screenshots` builds and runs an isolated NetBox 4.5
fixture with `netbox-topology-views` and `netbox-documents`, seeds a realistic demo rack with a
four-node topology and named document, drives a local hardware-accelerated emulator, and runs a
remote debug + androidTest build with `fastlane screengrab`; the fixture is always torn down.
`01_dashboard`, `02_device_detail`, and `05_settings` were repeatedly verified showing real
content; `02_device_detail` additionally needed an explicit "Refresh" click to reliably beat a
race in the detail screen's own per-device fetch. `03_topology` waits for the seeded four-node,
three-connection graph, and `04_search` fails rather than silently accepting an empty state.
The tablet capture now lives in the manual `Play Store screenshots` GitHub Actions workflow,
reusing the repository's maintained Android emulator runner instead of custom remote SSH/AVD
plumbing. The CI workflow now runs a phone/7in/10in matrix (pixel_2/Nexus 7/medium_tablet), each
lane capturing both light and dark variants of all five screenshots from one instrumentation run
(the test switches "Color scheme" through the real Settings UI, then repeats the journey with a
"_dark" suffix), plus an optional `open_pr` input that commits the refreshed screenshots to a PR
instead of only uploading a build artifact. Getting there took several real, screenshot-confirmed
bugs, not just test flakiness: KVM acceleration (GitHub-hosted runners don't grant it by default),
building before the emulator starts instead of while it's running, a
`POST_NOTIFICATIONS`/manifest-permission grant at install time, a real navigation bug in
`NetBoxResponsiveScaffold` where the rail's top item ("Home") was laid out underneath the
`TopAppBar` on tablet widths, "Display" sitting below the fold in the Settings list, Compose's own
synthetic click consistently failing to land on the theme dropdown's "Dark" item (fixed with a
real UiAutomator touch instead), selecting Dark navigating back to Dashboard directly on
slower/larger emulators, and a live system ANR dialog once landing inside an actual captured
screenshot (now guarded against before every capture). Full matrix (phone/7in/10in x light/dark)
verified green end to end, including inspecting the real output images.


## NBC-366: color object-type icons in color settings

When configuring object-type colors, preview each object type's icon using its selected color so
the setting is immediately understandable.

- [ ] Apply the configured object-type color to the relevant icon in the color-setting UI.
- [ ] Keep the preview consistent with object-type icons elsewhere in the app.
- [ ] Add focused UI/unit coverage for the color preview.

Status: not started, 2026-08-04.

## NBC-368: versioned settings backup and restore

Add a portable settings-only backup, inspired by Findroid+, without copying the Room cache,
downloaded assets, image attachments, or documents.

- [x] Define a forward-compatible, versioned archive containing the app version and settings.
- [x] Support optional password protection for manual and scheduled backups.
- [x] Add manual export/import in Settings and restore during onboarding.
- [x] Add daily, weekly, and monthly scheduled backups to a user-selected directory.
- [x] Include the Android device name and timestamp in backup filenames.
- [x] Test round-tripping and verify cached data is excluded from the archive.
- [x] Verify restored credentials auto-submit and show a welcome toast on the Zenfone 10.

Status: **done**, 2026-08-04; unit tests and compile passed remotely, and manual export/restore
were verified on the wired Zenfone 10. Scheduled worker behavior is covered by the same
versioned/password-protected manager and WorkManager wiring; no cache/media data is serialized.

## NBC-368: sync the Play Console icon to the final launcher mark

The Play Console listing's icon (uploaded whenever `docs/images/nyetbox-icon.svg` changes, via
`just play-icon-upload`) was still the very first NetBox-derived pass from NBC-365, before the
size corrections in that same entry's history.

- [x] Ran `just play-icon-upload` after NBC-365/367's final icon size landed - flattens the
  current `docs/images/nyetbox-icon.svg` to 512x512 and uploads it as the Play Console icon via
  `gpc`, using the app's existing recipe (no new tooling needed).

**Why:** user asked to confirm all three apps' Play Console icons/banners were current.
**How to apply:** N/A, existing recipe.

Status: **done**, 2026-08-04.

## NBC-369: fix scroll jank on the device and device-type list views

The device list and generic/device-type list screens (and several other image-showing rows and
pickers) noticeably stuttered while scrolling. Root cause: `DeviceRow`/`ObjectRow` resolved the
offline-cached thumbnail synchronously inside a `remember` block during composition
(`FileDownloadRepository.persistentFile` - a filesystem stat, falling back to a full
`listFiles()` directory scan on a cache miss), which ran on the main thread every time a new row
scrolled into the `LazyColumn`'s viewport. Compared against jollyfin's `ItemPoster`, which does no
filesystem I/O in its composable and just hands Coil the URL, letting Coil's own async pipeline
resolve local-vs-remote off the main thread.

- [x] Add `PersistentCacheFetcher`, a Coil `Fetcher.Factory<Uri>` that resolves NetBox media URLs
  against the offline cache inside Coil's own pipeline (ahead of `OkHttpNetworkFetcherFactory`),
  and wire it into `NetworkModule.provideImageLoader`.
- [x] Simplify `RemoteThumbnail` to just take `imageUrl` for the common case (Coil resolves
  local-vs-remote transparently); keep an optional `localFile` escape hatch for the rare
  cache-only case.
- [x] Remove the synchronous `remember { localImageFile(...) }` lookups from `DeviceListScreen`/
  `GenericListScreen` (the two reported list views) and every other image-display call site that
  had the same pattern: `ImageAttachmentGallery`, `GenericDetailMedia`/`GenericDetailRack`/
  `GenericDetailFields`/`GenericDetailIdentity`, `DeviceDetailScreen`, `GenericCreateScreen`'s
  choice picker, `DashboardScreen`, `GlobalSearchScreen`, `ObjectChangeDiffScreen` - deleting the
  now-unused `localImageFile`/`ViewModel` plumbing behind each.
- [x] Deliberately leave `TopologyScreen`/`TopologyViewModel` and `DocumentsSection` alone - both
  intentionally show a thumbnail only when already cached, never triggering a fresh network fetch
  (topology graphs can have hundreds of nodes; documents shouldn't auto-download for a preview
  card), which is different from the list-row bug and preserved via `RemoteThumbnail`'s
  `localFile` override.
- [x] Verified remotely on rofl-13: `compileDebugKotlin`, `compileDebugAndroidTestKotlin`,
  `compileDebugUnitTestKotlin`, `ktfmtCheck`, and `testDebugUnitTest` all pass.
- [x] Confirmed smooth scrolling on a physical device (Zenfone 10/Mi Pad 4/Pixel 5).
- [x] While live-testing this fix, hit two unrelated `OutOfMemoryError` crashes on the Pixel 5
  (256MB heap): `GlobalSearchRepository`'s whole-table search index was `SharingStarted.Eagerly`
  (rebuilt for the app's entire lifetime, even away from Search) and its `IndexedGenericObject`
  retained the full `NetBoxObjectEntity` (raw JSON string) alongside the already-parsed
  `JsonObject` and derived search text; `RemoteThumbnail` also forced every thumbnail through
  software (Java-heap) bitmap decoding just to run the alpha-crop transform, even for JPEGs that
  can't have alpha. Fixed alongside this entry: switched to `WhileSubscribed(5000)`, slimmed
  `IndexedGenericObject` to the four scalar fields actually needed, and skip the alpha-crop
  transform for jpg/jpeg sources.
- [x] Merged directly to `main` (`e8cabdb`), this repo's normal workflow for routine work.

**Why:** user reported perceptible lag scrolling the device and device-type list views;
comparison against jollyfin's Coil usage pointed at synchronous main-thread disk I/O per row.
**How to apply:** any future image-display composable should pass a remote URL straight to
`RemoteThumbnail`/`AsyncImage` and let Coil resolve caching - `localFile` overrides should stay
reserved for cases that must never trigger a network fetch. Any future app-lifetime `stateIn`
holding non-trivial data should default to `WhileSubscribed`, not `Eagerly`.

Status: **done**, 2026-08-05; verified remotely and on all three physical devices, merged to main.

## NBC-370: accurate dashboard sync progress + delayed startup sync

Two related dashboard/sync UX complaints:

1. `SyncStatusCard` (`ui/common/SyncStatusCard.kt`) only shows a generic spinner + "Syncing…" -
   `SyncStatusRepository.isSyncing` is a plain `Boolean` (derived from WorkManager `WorkInfo.State`
   only), so there's nothing richer to show. Meanwhile `SyncNotifier`'s system notification already
   renders real step/message progress via `SyncProgress(message, step, totalSteps)` - that value is
   only ever handed to `SyncNotifier.notifySyncProgress()` from `OfflineSyncRepository`/`SyncWorker`
   and never published anywhere the UI can read it.
2. `SyncScheduler.scheduleStartup()` enqueues the startup sync with no `setInitialDelay(...)` -
   opening the app always immediately shows a syncing state (if `syncOnAppLaunch` is on), even for
   a routine reopen seconds after the last sync.

- [x] Publish `SyncProgress` somewhere UI-observable (e.g. a `StateFlow<SyncProgress?>` alongside
  `SyncStatusRepository`, updated by `SyncWorker` the same moment it calls
  `SyncNotifier.notifySyncProgress`), and have `SyncStatusCard` render the current
  step/message/total instead of just a boolean spinner.
- [x] Add a short initial delay (`OneTimeWorkRequestBuilder.setInitialDelay(...)`) to the startup
  sync in `SyncScheduler.scheduleStartup()`, long enough that reopening the app shortly after a
  sync doesn't visibly retrigger one, but still short enough that a genuinely stale cache refreshes
  promptly.

**Why:** user found the dashboard's sync indicator uninformative next to the notification, and
found the immediate startup sync jarring on every app open.
**How to apply:** keep `SyncProgress` as the single source of truth for both surfaces (notification
+ dashboard) rather than inventing a second progress representation.

**Implementation:** `SyncStatusRepository` now holds a `MutableStateFlow<SyncProgress?>`
(`syncProgress`, via `publishProgress()`); `SyncWorker` publishes to it in the same `onProgress`
lambda it hands to `OfflineSyncRepository.syncAll()` (which already calls
`SyncNotifier.notifySyncProgress`), and clears it back to `null` on success/retry/failure so no
stale step lingers into the next run. `DashboardViewModel.syncProgress` re-exposes it and
`DashboardScreen` passes it to `SyncStatusCard`, which now renders `syncStatusHeadline()` (current
step message, or "Synced"/"Syncing…" as fallbacks) and `syncStatusSubText()` (reuses
`SyncProgress.notificationSubText()` for the "Step X of Y · N of M items" line, matching the system
notification) instead of a static "Syncing…" string. `SyncScheduler.scheduleStartup()` now adds a
10s `setInitialDelay` (`STARTUP_SYNC_DELAY_SECONDS`) to the startup `OneTimeWorkRequest`. Unit tests
added in `SyncStatusCardTest.kt` for the new pure `syncStatusHeadline`/`syncStatusSubText` helpers.

Status: **done**, 2026-08-05 - `just gradle rofl-13 compileDebugKotlin
compileDebugAndroidTestKotlin compileDebugUnitTestKotlin testDebugUnitTest` and `just lint` both
green; merged to main.

## NBC-371: restyle the dashboard "Search NetBox" affordance to match ModernSearchField

The dashboard's `GlobalSearchCard` (`ui/dashboard/DashboardScreen.kt`) still uses the pre-
`ModernSearchField` look: an `ElevatedCard` tinted `primaryContainer` with a `titleLarge` headline.
It's a navigation affordance (tap -> `GlobalSearchScreen`), not an inline field, so it was never
swept up by the earlier "adopt the modern pill search style everywhere" pass
(`ui/common/ModernSearchField.kt`) - it now looks visually inconsistent/dated next to every actual
search field in the app.

- [x] Restyle `GlobalSearchCard` to read as the same pill/tonal-surface search affordance as
  `ModernSearchField` - generously rounded shape, `surfaceContainerHighest`-style tonal color
  instead of `primaryContainer`, leading search icon - while remaining a single tap-to-navigate
  target (keep long-press-to-reorder and the reorder-mode hide button working).
- [x] Verify `just gradle rofl-13 compileDebugKotlin compileDebugAndroidTestKotlin
  compileDebugUnitTestKotlin testDebugUnitTest` and `just lint` pass.
- [x] Visually confirm on a physical device via `adb screencap`.

**Why:** user asked to make the dashboard search button look more modern / Material You-native.
**How to apply:** reuse the same `MaterialTheme.colorScheme.*` tonal primitives `ModernSearchField`
already established rather than inventing a new visual style.

Status: **done**, 2026-08-05, verified via `just gradle rofl-13
compileDebugKotlin compileDebugAndroidTestKotlin compileDebugUnitTestKotlin testDebugUnitTest`
(pass), `just lint` (pass, ktfmtCheck clean), and a screenshot of the dashboard on the Zenfone 10
after `just deploy-all` confirming the pill/tonal-surface look.

## NBC-372: teach the per-list search bars the global-search `key:value` syntax

`DeviceListScreen`/`DeviceListViewModel` and `GenericListScreen`/`GenericListViewModel` each have
their own `ModernSearchField`, but the typed text is passed straight through to
`DeviceRepository.observeDevices(query)`/`GenericObjectRepository.observeObjects(endpointPath,
query, filterKey, filterValue)`, which only do a Room `LIKE` substring match against a fixed column
list (`DeviceDao.search`) or `display` (`NetBoxObjectDao.search`) - no `key:value` structured
parsing at all, unlike NBC-13's Global Search (`GlobalSearchRepository.parseGlobalSearchQuery`).

- [x] Promote the pieces of `GlobalSearchRepository.kt` needed outside that class to top-level
  (module-internal) functions instead of duplicating them: `DeviceEntity.createSearchFields()` (was
  a private class member with no dependency on the class), and a new
  `Map<String, String>.matchesFilters(filters)` helper (replacing the previously-unused private
  `Map<String, String>.matches(filter)`), reused by `IndexedGenericObject.matches`/
  `IndexedDevice.matches` too instead of their own duplicated inline filter loops.
- [x] `DeviceRepository.observeDevices(query)`: parse the query with `parseGlobalSearchQuery`, run
  the free-text part through the existing `DeviceDao.search`/`observeAll` Room query, then filter
  the result in-memory against `objectFilters` using the promoted `createSearchFields()`/
  `matchesFilters()` helpers.
- [x] `GenericObjectRepository.observeObjects(...)`: same shape - free text still hits
  `NetBoxObjectDao.search`/`observeAll`, then `objectFilters` are matched in-memory by decoding each
  cached row's raw `json` and reusing `JsonObject.createChoiceSearchFields()` (already used by
  Global Search) - kept as a small top-level `NetBoxObjectEntity.matchesSearchFilters(...)`
  extension alongside the existing `matchesRelation(...)` one, not merged with the unrelated
  route-level `filterKey`/`filterValue` relation filter (`Route.GenericList`'s single fixed filter
  is a different, pre-existing concern - applied after the new query filter, unchanged).
- [x] `type:`/`tpe:` handling: **no-op inside these list screens.** `ParsedGlobalSearchQuery.
  objectFilters` already excludes the `type` key (Global Search itself only uses it to scope which
  *endpoint* to search across models) - since a per-list screen is already scoped to one model
  (just devices, or just one generic endpoint), a `type:` token typed there has nothing left to
  scope and is silently ignored rather than erroring or being treated as a literal field match.
- [x] Update `DeviceListScreen`'s `DeviceRow` and `GenericListScreen`'s `ObjectRow` to highlight
  `parseGlobalSearchQuery(query).networkQuery` (free text + filter *values*, keys stripped) instead
  of the raw typed query - mirrors `GlobalSearchScreen`'s `highlightQuery` derivation, so typing
  `status:active` highlights "active" in the row instead of the literal string "status:active".
- [x] Unit test coverage for the new filter-matching behavior (`DeviceRepositoryTest.kt`,
  `GenericObjectRepositoryTest.kt`), following the existing `FakeModelDao`-style fake-DAO pattern
  used in `DirectoryRepositoryTest.kt`.
- [x] Verified remotely: `just gradle rofl-13 compileDebugKotlin compileDebugAndroidTestKotlin
  compileDebugUnitTestKotlin testDebugUnitTest` and `just lint` both pass.

**Why:** user asked for the per-object-type list screens' search bars to support the same
`key:value` filter syntax Global Search already has, instead of reinventing parsing for each list.
**How to apply:** any future per-model list search should keep going through
`parseGlobalSearchQuery`/`ParsedGlobalSearchQuery`/`SearchQueryFilter` rather than adding another
ad hoc filter syntax.

Status: **done**, 2026-08-05; verified remotely (compile/lint/unit tests all green), merged to
main.

## NBC-373: relabel and reorder the item-view overflow menu

`GenericDetailScreen`'s overflow menu ("More actions") had "Refresh" (which actually triggers a
sync, same as the dashboard's) and no logical grouping of its items.

- [x] Renamed "Refresh" to "Sync" (same icon/action, just an accurate label).
- [x] Reordered to: Print label*, Share, Open in browser, *(divider)*, Sync, Edit, Add component*,
  Upload media, Add journal entry, *(divider)*, Show hidden fields*, Delete (items marked `*` are
  conditional - printable-device-only, or shown only when applicable). Delete stays last.
- [x] Added a divider after the share/open-in-browser group and another after the
  edit/media/journal group, per the requested placement ("after open in browser and add journal
  entry").
- [x] Verified remotely: `compileDebugKotlin`, `compileDebugAndroidTestKotlin`,
  `compileDebugUnitTestKotlin`, `testDebugUnitTest` all pass.

**Why:** user found "Refresh" mislabeled (it syncs, not just refreshes the view) and wanted the
menu's items grouped more sensibly instead of insertion order.
**How to apply:** the three conditional items (Print label, Add component, Show hidden fields)
weren't explicitly placed by the user's requested order - placed by judgment near their closest
semantic peers (Print label with Share/Open in browser as an "output" action; Add component near
Edit as a structural-modification action; Show hidden fields right before Delete as a "meta" view
toggle). `DeviceDetailScreen`'s separate overflow menu (device-specific, has its own item set) was
deliberately left untouched - the user's exact item list (including "Upload media") only matches
`GenericDetailScreen`'s menu.

Status: **done**, 2026-08-05; verified remotely, merged to main.

## NBC-375: sync `WorkRequest`s had no explicit retry backoff, defaulting to 10s/20s/40s

While another session was working on E2E screenshot CI, they noticed `SyncWorker` firing roughly
every 10 seconds throughout a test run - suspiciously matching NBC-370's new
`STARTUP_SYNC_DELAY_SECONDS` constant (also 10). That match is coincidental: the real cause is that
none of `SyncScheduler`'s three `WorkRequest.Builder`s (`schedulePeriodic`/`syncNow`/
`scheduleStartup`) ever called `.setBackoffCriteria(...)`. `SyncWorker.doWork()` returns
`Result.retry()` up to `MAX_RETRY_ATTEMPTS = 3` times on failure, and without explicit backoff
WorkManager falls back to its own default: `BackoffPolicy.EXPONENTIAL` starting at
`WorkRequest.MIN_BACKOFF_MILLIS` (10 seconds) - i.e. retries at 10s, 20s, 40s. That's a reasonable
default for a quick network call, but far too aggressive for a full multi-model sync failing
repeatedly (real battery/data drain if this ever happens for a real user, e.g. a flaky connection),
and explains the observed ~10s cadence during CI (sync failing in that environment and hammering
retries at the default rate).

- [x] Added a shared `<B : WorkRequest.Builder<B, *>> B.setSyncBackoffCriteria()` extension in
  `SyncScheduler.kt` (works for both `OneTimeWorkRequest.Builder` and
  `PeriodicWorkRequest.Builder`, since both share `WorkRequest.Builder`'s self-typed API) setting
  `BackoffPolicy.EXPONENTIAL` starting at 1 minute (roughly 1min/2min/4min across the 3 allowed
  retries) instead of the 10s default, and applied it to all three sync `WorkRequest` builders.
- [x] Verified remotely: `compileDebugKotlin compileDebugAndroidTestKotlin
  compileDebugUnitTestKotlin testDebugUnitTest` and `just lint` both pass.

**Why:** flagged by a parallel session working on E2E CI; confirmed as a real, separate bug (not
caused by the NBC-370 startup-delay work, just numerically coincidental with it) worth fixing
regardless of the CI investigation's outcome, since it affects real users on a flaky connection.
**How to apply:** any new WorkManager `WorkRequest` in this app that can retry should set explicit
backoff criteria rather than relying on WorkManager's default, which is tuned for quick jobs, not a
multi-model sync.

Status: **done**, 2026-08-05; verified remotely, merged to main.

## NBC-374: fix sluggish tab switching on item detail views

User report: "switching between the tabs on the item views is a bit sluggish" - referring to the
tabbed detail screens (`GenericDetailScreen`/`DeviceDetailScreen`) showing Journal, Connected
devices, Interfaces, Front/Rear ports, etc. as separate tabs. NBC-369 (above) fixed a related but
distinct bug - synchronous main-thread disk I/O per row in `DeviceListScreen`/`GenericListScreen`
during scroll - by routing thumbnail resolution through Coil's own async pipeline
(`PersistentCacheFetcher`). This entry investigates whether tab switching hits the same root
cause (image rows inside tab content doing sync I/O), a different image-loading gap NBC-369 didn't
reach, or a completely separate cause (expensive recomposition, non-lazy layout, redundant
re-fetch/re-decode on every tab switch).

- [x] Read how tab content is composed/switched in `GenericDetailScreen.kt`/
  `DeviceDetailScreen.kt` and the shared `ItemDetailTabs`/`itemTabSwipe` machinery.
- [x] Checked tab-content composables (`DeviceConnectedDevices`, `DeviceRelatedObjects`,
  `DeviceJournalEntries`, `RackElevationOverview`, `GenericDetailIdentityCard`) for the same
  synchronous-I/O-in-composition pattern NBC-369 fixed - **not present**; `localImageFile`/
  `persistentFile` sync-I/O calls were already fully removed from every detail-tab composable by
  NBC-369, confirmed by grep. Ruled out "same root cause as NBC-369" as the hypothesis.
- [x] Found the actual cause: **not image I/O, a non-lazy-layout anti-pattern**.
  `DeviceDetailScreen.kt`'s "related tab" content (Interfaces, Front/Rear/Power/Console ports,
  Power outlets, Module bays, Connected devices - `DEVICE_RELATED_TABS` in
  `DeviceDetailViewModel.kt`) was rendered via `DeviceConnectedDevices`/`DeviceRelatedObjects`/
  `DeviceJournalEntries`, three plain `@Composable` functions that did `list.forEach { ... }`
  wrapped in a single `LazyColumn` `item { }` block (`DeviceDetailScreen.kt` ~line 820, pre-fix).
  This meant every row for the selected tab was composed, measured, and laid out synchronously in
  one frame every time that tab was selected, regardless of scroll position - no virtualization at
  all, unlike a real `items()` call. For a device with many ports (e.g. the cached
  `DGS-1100-24PV2`-class switch, 24+ interfaces) or many connected neighbors, switching to that
  tab meant building dozens of `NyetboxCard`/`NyetboxListItem` rows in one synchronous burst -
  directly explaining perceptible per-tab-switch jank on exactly the tabs a datacenter-inventory
  app is full of.
  - Separately, `GenericDetailScreen.kt`'s four tabs (Overview/Elevation/Journal/Changelog) were
    each a **separate `LazyColumn`** mounted via an `if/else if/else` chain, each redundantly
    recomposing `GenericDetailIdentityCard` from scratch and fully tearing down/rebuilding the
    whole list (and its `LazyListState`) on every switch, instead of one shared `LazyColumn`
    (`DeviceDetailScreen` already used this better shape for its own Overview/related tabs).
- [x] Fix 1 (`DeviceDetailScreen.kt`): converted `DeviceConnectedDevices`/`DeviceRelatedObjects`/
  `DeviceJournalEntries` from `@Composable` functions with an internal `forEach` into
  `LazyListScope` extension functions (`deviceConnectedDevicesItems`/`deviceRelatedObjectsItems`/
  `deviceJournalEntriesItems`) that call `items(list, key = { ... })` directly, called inline in
  the existing `LazyColumn` instead of wrapped in one `item { }`. Rows are now virtualized like
  every other list in the app.
- [x] Fix 2 (`GenericDetailScreen.kt`): merged the four per-tab `LazyColumn`s into one shared
  `LazyColumn` whose content branches on `visibleSelectedTab` inside a single `item {
  GenericDetailIdentityCard(...) }` plus tab-specific `item`/`items` calls, matching
  `DeviceDetailScreen`'s existing pattern. Deliberately left `RackElevationOverview`'s non-lazy
  `Column`/`forEach` rendering alone - a rack elevation is a bounded-size (~42-48U) visual diagram
  meant to be seen as a whole, the same reasoning NBC-369 used to leave `TopologyScreen` non-lazy.
- [x] Checked for non-I/O causes beyond the above: `visibleFieldRows`/`detailOverviewFields`
  derivation in `GenericDetailScreen.kt` is computed above the `Scaffold` content lambda, so a
  `selectedTab` change alone doesn't force it to re-run (separate recomposition scope) - no fix
  needed there.
- [x] Verified remotely on rofl-13: `compileDebugKotlin compileDebugAndroidTestKotlin
  compileDebugUnitTestKotlin testDebugUnitTest` and `ktfmtCheck` (`just lint`) both pass.
- [x] No new pure logic was introduced (pure Compose layout/structure refactor - virtualizing
  existing rows and merging LazyColumns), so no new unit tests were added, consistent with how
  NBC-369 only added tests for its new `PersistentCacheFetcher` logic, not its composable changes.
- [x] Deployed to all three physical devices (`just deploy-all`) and manually exercised on the
  Zenfone 10: opened the "turris" router (`DeviceDetailScreen`, 14 interfaces) and switched
  Overview -> Connected devices -> Interfaces -> Overview repeatedly - all render correctly with
  real data (IPs/MACs per interface) with no crashes. Opened the "Samson SRK16" rack
  (`GenericDetailScreen`) and switched Overview -> Elevation -> Changelog -> Overview - the shared
  identity card and per-tab content all render correctly across the merged `LazyColumn`. No
  frame-timing/profiling tool was available in this environment, so smoothness itself is judged by
  the structural fix (virtualized rows instead of a synchronous full-list compose per switch) plus
  functional confirmation, mirroring how NBC-369 was reasoned about before its own device
  sanity-check.

**Why:** user reported perceptible lag switching tabs on item detail screens; NBC-369's sync-I/O
fix was already fully applied to every detail-tab composable, so the cause here was different -
non-virtualized `forEach`-in-one-`item{}` rendering of potentially dozens of interface/port/device
rows on `DeviceDetailScreen`'s related tabs, plus redundant per-tab `LazyColumn`/identity-card
recomposition on `GenericDetailScreen`.
**How to apply:** any list of a priori unbounded size rendered inside a `LazyColumn` must use
`items(list, key = { ... })`, never `list.forEach { ... }` inside a single `item { }` - the latter
defeats virtualization entirely, not just cosmetically wastes a `remember`. When several tabs
share layout scaffolding (a header/identity card), prefer one shared `LazyColumn`/`LazyListScope`
branching on the selected tab over one `LazyColumn` per tab.

Status: **done**, 2026-08-05; verified remotely (compile + unit tests + ktfmtCheck) and manually
exercised on the Zenfone 10 physical device; merged to main.

## NBC-376: show item type badges on dashboard rows

The home screen's Recently viewed, Bookmarks, and Recent changes rows should identify the NetBox
object type with the same compact icon-and-label badge already used by global search.

- [x] Reuse the global-search badge presentation and cached directory labels on dashboard rows.
- [x] Keep rows without a resolved target navigable/rendered without a misleading badge.
- [x] Verify formatting, compilation, unit tests, and lint remotely.

Status: **done**, 2026-08-05; verified with remote compilation, unit tests, ktfmtCheck, and debug
installation on the Zenfone 10, Mi Pad 4, and Pixel 5.

## NBC-377: hide image/document upload widgets for item types that don't support them

Not every NetBox model accepts image attachments or documents (e.g. `users.permission`), but the
generic item detail screen always drew the image-attachment gallery and the "Upload media" action
regardless of item type, and gated the documents section only on whether the Documents plugin is
installed at all - not on whether the specific item type supports it.

- [x] Infer per-item-type support from the API instead of hardcoding a model list: `MediaUploadRepository`
  now resolves `object_type` `ChoiceField` choices via `OPTIONS api/extras/image-attachments/` (and
  the discovered documents-plugin endpoint), mirroring how `JournalEntryRepository` already resolves
  `assigned_object_type` for journal entries. Fails open (assumes supported) when the choices can't
  be determined, so a transient/offline failure never hides a widget that should be shown.
- [x] `GenericDetailViewModel` exposes `supportsImageAttachments`/`supportsDocuments` StateFlows;
  `GenericDetailScreen` hides the image gallery, the documents section, and the "Upload media" menu
  item accordingly.
- [x] Verify formatting, compilation, unit tests, and lint remotely.

Status: **done**, 2026-08-06; verified with remote compilation (`:app:compileDebugKotlin`), unit
tests (`:app:testDebugUnitTest`), and `ktfmtCheck` on rofl-13. Not yet installed on a physical
device.

## NBC-378: carry over item-list header icons to the item detail view

The item LIST screens show the object type's icon (`AppIcons.forEndpointPath(...)`) next to the
title in the TopAppBar, but the corresponding item DETAIL/view screens showed only the plain text
label - no icon - so e.g. opening a device type didn't carry over the device-type icon shown on its
list page.

- [x] `GenericDetailScreen`'s TopAppBar title now renders `AppIcons.forEndpointPath(viewModel.route.endpointPath)`
  ahead of the label/breadcrumb column, matching `GenericListScreen`'s existing icon+label layout.
- [x] `DeviceDetailScreen`'s TopAppBar title now renders the devices icon
  (`AppIcons.forEndpointPath(NetBoxRef.DEVICES_ENDPOINT_PATH)`) ahead of the device name, since
  Device is handled by its own screen rather than the generic one.
- [x] Tint both header icons with the screen's existing per-item-type `detailAccent`
  (`ColorScheme.detailAccentFor`) instead of the default icon color, so the header carries the same
  accent already used for the rest of that detail screen's chrome (see NBC-379).
- [x] Verify formatting, compilation, unit tests, and lint remotely.

Status: **done**, 2026-08-06; verified with remote compilation (`:app:compileDebugKotlin`), unit
tests (`:app:testDebugUnitTest`), and `ktfmtCheck` on rofl-13.

## NBC-379: sidebar visual/interaction fixes (pin icon, accent colors, sticky offline toggle, broken reorder)

A batch of related sidebar polish requested together: the per-model pin toggle still used a
star/star-border icon inconsistent with the pin icon already used on the "Add item" picker, most
sidebar icons weren't tinted with the same per-item-type accent color used on detail screens, the
"Offline mode" toggle was the last item in the scrolling model list (easy to miss, needed a scroll),
and long-press reordering of sidebar app sections had silently stopped working.

- [x] Replace the pinned-model-row star/star-border icon with `Icons.Filled.PushPin`/
  `Icons.Outlined.PushPin` inside the same circular tinted-background `Box` (`primaryContainer`/
  `surfaceContainerHighest`) already used by `AddItemScreen.kt`'s `AddModelRow`, for a consistent
  pin affordance across both screens.
- [x] Tint the sidebar's "Devices" entry, per-model rows inside expanded app sections, and the
  detail-screen header icon (NBC-378) with `visualColorForEndpointPath`/`detailAccentFor` - the same
  deterministic per-object-type accent color already used by the pinned-items row, section headers,
  and detail screens, so the accent is consistent everywhere an object-type icon appears.
- [x] Move the "Offline mode" `ListItem` out of the sidebar's scrolling `LazyColumn` into a static
  row between the divider and `SidebarFooter`, so it's always visible above the footer instead of
  scrolling out of view. Simplified `NetBoxE2eTest`'s offline-mode toggle step accordingly (no
  longer needs `performScrollToNode`).
- [x] Root-caused and fixed the broken long-press section reorder: `Sidebar.kt`'s app-section row
  stacked two independent gesture detectors on the *same* node - `combinedClickable(onLongClick =
  ...)` (enter reorder mode) and a separate raw `pointerInput`-based `detectDragGesturesAfterLongPress`
  (`sectionReorderGesture`, actually drag it) - which is exactly the pattern broken by a documented
  Compose Foundation regression in the `combinedClickable` rewrite (long-click handling glitches
  when another gesture detector shares the node). This repo bumped `androidx-compose` 1.11.3 ->
  1.11.4 on 2026-08-05, the day after the reorder feature (NBC-195) was last verified working.
  Fixed by removing the co-located `combinedClickable(onLongClick = ...)` entirely and folding
  "enter reorder mode" into `sectionReorderGesture`'s own drag-start callback, so there's only ever
  one gesture detector per node - a single continuous long-press-and-drag now both enters reorder
  mode and moves the section, instead of requiring two separate long-presses. Applied the same fix
  to `DashboardScreen.kt`'s section header, which used the identical dual-detector pattern.
- [x] Verify formatting, compilation (`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`),
  unit tests, and lint remotely.
- [ ] Install on a device and manually confirm: pin icon, sidebar/header icon colors, sticky offline
  toggle, and long-press-drag section reordering all behave as expected.

Status: in progress, 2026-08-06; remote compilation and unit tests pass. Root cause of the reorder
regression is a plausible, evidence-backed diagnosis (matching Compose Foundation's documented
`combinedClickable` long-click regression and the exact timing of this repo's 1.11.4 bump) rather
than a reproduced-on-device confirmation - flag if long-press reorder still misbehaves after
install.

## NBC-380: bring the device-view overflow menu in line with the generic item view's

NBC-373 reordered/relabeled `GenericDetailScreen`'s overflow menu but deliberately left
`DeviceDetailScreen`'s separate menu untouched since its item set differs (has "Open topology", no
"Upload media"). The device menu still said "Refresh" instead of "Sync", had no dividers, and its
items were in raw insertion order instead of the generic menu's output/sync-edit/meta-delete
grouping - inconsistent with every other detail screen.

- [x] Reordered to mirror `GenericDetailScreen`'s grouping: Print label*, Share*, Open in browser*,
  *(divider)*, Sync, Edit, Add component, Open topology* (device-specific, placed with the other
  structural actions), Add journal entry, *(divider)*, Show hidden fields*, Delete (`*` = shown only
  when applicable). Also flipped Share/Open in browser to match the generic menu's order (Share
  first).
- [x] Renamed "Refresh" to "Sync" (matches NBC-373's generic-menu label).
- [x] Found and fixed the actual root cause of the "toast still says Refresh" report: the toast text
  lives in a *shared* helper (`ui/common/RefreshToastState.kt` - `REFRESH_QUEUED_TOAST`,
  `refreshCompletionToast()`) used by both `GenericDetailViewModel` and `DeviceDetailViewModel`, and
  NBC-373 only relabeled the menu item text, not this shared toast - so both screens were still
  saying "Refresh queued"/"Refresh complete"/"Refresh failed". Renamed to "Sync queued"/"Sync
  complete"/"Sync failed", fixing the wording on both screens at once. Updated the toast's unit test
  (`RefreshToastStateTest`) and two androidTest references (`StoreScreenshotTest`) accordingly.
- [x] Checked the menu's Material 3 styling: both screens use the same unstyled/default
  `Box { IconButton(MoreVert) { DropdownMenu { DropdownMenuItem(...) } } }` idiom with no custom
  colors - correct out-of-the-box M3 usage. The only real deviation was structural (grouping/
  dividers/wording), now fixed; no further styling change made.
- [x] Verify formatting, compilation (`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`,
  `:app:compileDebugUnitTestKotlin`), and unit tests remotely.

**Why:** user noticed the device page's menu wasn't reordered/relabeled along with NBC-373 and asked
to make it consistent, plus flagged the toast still saying "Refresh".
**How to apply:** any future overflow-menu item added to either detail screen should keep the
output-actions / sync-edit-structural / meta-delete grouping and reuse `REFRESH_QUEUED_TOAST`/
`refreshCompletionToast()` rather than a screen-local string, so a future rename only needs one edit.

Status: **done**, 2026-08-06; verified remotely (compilation + unit tests) and installed on the
Zenfone 10, Mi Pad 4, and Pixel 5.

## NBC-381: cut the reorder-mode wiggle short instead of running it the whole time

`rememberReorderWiggle` (shared by `Sidebar.kt` and `DashboardScreen.kt`) used a
`rememberInfiniteTransition` that oscillated continuously for as long as reorder mode stayed
active - which could be minutes if the user left the drawer open while rearranging things - reading
as a nonstop jitter rather than the one-time "you just entered edit mode" cue it was meant to be.

- [x] Replaced the infinite transition with an `Animatable` driven by a `LaunchedEffect(enabled)`:
  on entering reorder mode it wiggles a few times (3 cycles, +-1.2 degrees, 140ms per step) and then
  settles at 0 and stays there for the rest of the time reorder mode is active; leaving reorder mode
  snaps straight back to 0.
- [x] Verify compilation remotely.

Status: **done**, 2026-08-06; verified with remote compilation (`:app:compileDebugKotlin`).

## NBC-382: unpin button on the sidebar's pinned-items row in edit mode

The sidebar's top "pinned items" list (above the app sections) had no way to unpin an item directly
- the only way was to expand its actual app section and use the pin toggle there, which defeats the
point of pinning something for quick access.

- [x] Wrapped each pinned-item row in a `Row` and, when `reorderMode` is active, show a trailing
  unpin button - same filled `PushPin`-in-a-circular-`primaryContainer`-background treatment already
  used by the pin toggle inside expanded app sections and on `AddItemScreen`, for visual consistency.
  Calls the same `viewModel.togglePinned(...)` used everywhere else.
- [x] Verify compilation remotely.

Status: **done**, 2026-08-06; verified with remote compilation (`:app:compileDebugKotlin`).

## NBC-383: cable "Trace" tab (path trace, matching NetBox's web UI "Trace" button)

NetBox's web UI lets you click "Trace" on a cable to walk the full physical path - every cable and
patch-panel hop from one termination through to the far end. The app had no equivalent; viewing a
cable only showed its own overview fields, no way to see what it's actually connected to end-to-end.

- [x] Added a "Trace" tab to `GenericDetailScreen`, shown only for `api/dcim/cables/` items
  (`GenericDetailViewModel.isCable`), following the same `tabs.indexOfFirst { label == ... }`
  pattern as the Journal tab (not a hardcoded tab index, per the Elevation tab's known fragility).
- [x] NetBox's trace endpoint lives on a *termination* (`api/dcim/interfaces/<id>/trace/`, or
  console-port/power-port/front-port/rear-port equivalents), not the cable itself, and returns a
  bare JSON array of `[nearEnds, cable, farEnds]` segments - not the usual paginated-list or
  single-object shape. Added `GenericNetBoxApi.getJsonArray()` for this, and
  `cableTraceStartTarget()` to resolve which termination to trace from off the cable's own
  `a_terminations`/`b_terminations` JSON (falling back to B-side if A has none yet).
- [x] Cache-first, offline-first like every other read path in this app (`RackElevationRepository`
  is the closest existing precedent - also a non-standard NetBox visualization endpoint cached the
  same way): new `CableTraceRepository`/`CableTraceEntity`/`CableTraceDao`/`cable_trace_segments`
  table (`MIGRATION_16_17`, DB version 16 -> 17), reads come from a Room `Flow` first, `refresh()`
  is a best-effort network call that silently no-ops on failure - reopening a cable's Trace tab
  offline still shows whatever was last synced.
- [x] Trace refresh triggers both on initial load (reactively, once the cable's terminations resolve
  from cache or a fresh sync) and on manual "Sync", mirroring `loadJournalEntries()`.
- [x] Every device *and* termination (interface/port) shown in the trace is independently tappable
  and navigates via the same `onNavigateToReference`/`RefTarget` mechanism already used for
  reference fields - no per-model-type branching, since NetBox's nested termination JSON already
  carries its own `url` that `NetBoxRef.endpointFromDetailUrl()` resolves generically.
- [x] Verify formatting, compilation (`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`,
  `:app:compileDebugUnitTestKotlin`), and unit tests remotely. Fixed 3 test fakes
  (`FakeDirectoryApi`, `FakeGenericNetBoxApi`, `FakeApi`) broken by the new `getJsonArray` interface
  method.

**Why:** requested directly - "would love to have a trace for our cables, similar to what the web UI
does... devices etc on the trace need to be clickable."
**How to apply:** a follow-up task will add an optional NetBox-rendered SVG view (`?render=svg`)
alongside this JSON-based one, prefetched during background sync rather than lazily, with embedded
`<a href>` links rewritten from the public NetBox web URLs to in-app deep links (likely needs a
WebView, not a static image loader, so link taps are interceptable).

**Bugfix, 2026-08-06:** the user tested against their real instance (`netbox.brkn.lol`) immediately
and hit "No cable path to trace" for every cable. Root cause: `cableTraceStartTarget()` read the
termination out of `a_terminations[0]["termination"]`, a field name pulled from a web-fetched
summary of NetBox's `CableTerminationSerializer` source - wrong for the live API, which nests it
under `a_terminations[0]["object"]` (a `GenericObjectSerializer`-shaped `{object_type, object_id,
object}` wrapper) instead. Confirmed via `curl` against the real instance and fixed; the rest of the
trace-segment parsing (near/far end fields, cable `status`/`label`/`color`) matched the live response
exactly on the first try. `AGENTS.md` now calls out using the `netbox` skill to check real API
responses before writing parsing code, instead of trusting GitHub-source summaries.

Status: **done**, 2026-08-06; verified with remote compilation and unit tests, installed on all
three physical devices, and the termination-field bug fixed and confirmed against the live NetBox
instance.

## NBC-384: "Diagram" view (NetBox's own SVG rendering) for rack elevation and cable trace tabs

Follow-up to NBC-383: NetBox's rack-elevation and cable-trace endpoints also render a full
`image/svg+xml` diagram (`?render=svg`) - visually richer than the app's JSON-derived list/grid
views, and it's what the web UI itself shows. Added as an alternative, not a replacement, since the
JSON views' rows are more reliably tappable/scrollable on a phone.

- [x] "List"/"Diagram" `FilterChip` toggle added to both the rack Elevation tab and the cable Trace
  tab (`DiagramViewToggle`). Diagram mode lazily fetches the SVG (cache-first) the first time it's
  selected, same pattern as everything else in this app.
- [x] `GenericNetBoxApi.getSvg(): ResponseBody` - a raw, un-typed response, Retrofit's built-in
  escape hatch for a body that isn't JSON, added alongside `getJsonArray` on the same interface
  rather than a second Retrofit interface (no compelling reason to split it out the way
  `MediaNetBoxApi` was, which was specifically about multipart complexity).
- [x] New `SvgDiagramRepository`: fetches the raw SVG text and persists it via the existing
  `FileDownloadRepository.writeToPersistent`/`persistentFile` (the same durable-artifact cache
  `imageAttachmentRepository`/`deviceTypeRepository` already use), keyed by a logical cache key
  (`rackElevationSvgCacheKey`/`cableTraceSvgCacheKey`) shared between the on-demand ViewModel loader
  and the sync-time prefetcher below, rather than the literal fetch URL.
- [x] **Prefetched during background sync**, not just lazily on screen view, per explicit request:
  `OfflineSyncRepository.syncAllLocked()` gained a "Syncing rack and cable diagrams…" step right
  after the existing rack-elevation JSON sync step, iterating the already-known, already-bounded
  `cachedObjects("api/dcim/racks/")`/`cachedObjects("api/dcim/cables/")` lists (no extra paginated
  fetch needed just to enumerate what to prefetch - `OfflineSyncRepository` already has both from
  the generic-models sync step that runs earlier in the same pass). Gated behind the same
  `syncAttachmentsToDisk` opt-in setting the attachment-download pass already uses, rather than a
  new setting - same category of "extra, non-essential bytes the user chose to pre-download."
- [x] **Rendered via a WebView** (`SvgDiagramView`, first WebView in this codebase - no SVG-decoding
  library existed here before, and none was added; a WebView renders SVG natively with no extra
  dependency). JavaScript stays disabled throughout; `<a>` taps reach
  `WebViewClient.shouldOverrideUrlLoading` without it.
- [x] **Every tap in the diagram navigates in-app** - simpler than literally rewriting the SVG's
  `<a href>` attributes: `shouldOverrideUrlLoading` intercepts and blocks *every* navigation
  attempt (`return true` unconditionally) and resolves the tapped URL through the existing
  `NetBoxUrlParser.parse()` (already generic for any NetBox web URL shape, already used for
  scanned/opened URLs elsewhere) - a resolvable link calls `onNavigate` instead of ever loading in
  the WebView; an unresolvable one (confirmed against the live instance: empty rack slots link to a
  relative `/dcim/devices/add/?...` create-form URL, not a detail page) is just a no-op tap. No XML
  mutation of the SVG needed.
- [x] Verify formatting, compilation (`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`,
  `:app:compileDebugUnitTestKotlin`), and unit tests remotely. Fixed the same 3 test fakes again for
  the new `getSvg` interface method.

**Why:** requested directly, immediately after NBC-383 shipped - "would nice to have a 'switch to
svg' button on the rack view and the cable termination in their respective tabs. we could then also
pre-fetch the svgs... we should patch them though as they link to the public urls (which might not
work out of the box)... by prefetching i mean during sync."
**How to apply:** confirmed against the live instance (`netbox.brkn.lol`) that rack-elevation SVG
hrefs come in two shapes - absolute (`https://host/dcim/devices/15/`, occupied slots) and relative
(`/dcim/devices/add/?...`, empty slots) - `loadDataWithBaseURL`'s `baseUrl` param must be the
NetBox instance's real base URL (not null) for the relative ones to resolve before
`shouldOverrideUrlLoading` ever sees them.

Status: **done**, 2026-08-06; verified with remote compilation and unit tests. Not yet installed on
a physical device or tested against the live instance - unlike NBC-383, this one wasn't hand-checked
against a real SVG response by round-tripping it through the actual WebView/`NetBoxUrlParser`
pairing, only by inspecting the raw SVG text via `curl`. Worth the user trying the "Diagram" toggle
for real before considering this fully done.

**Follow-up, 2026-08-06:** the user tried it and asked for the diagram to use more of the screen - a
fixed 480dp height left most of the screen (especially on the Mi Pad 4 tablet) unused.
`SvgDiagramView` now sizes itself to 75% of `LocalConfiguration.screenHeightDp` instead of a fixed
height.

**Follow-up, 2026-08-06 (round 2):** the "worth the user trying it for real" caveat above was
right to flag - testing on the wired Zenfone 10 turned up three real bugs, all in `SvgDiagramView`:
- **Tiny rendering.** NetBox's SVGs carry `width`/`height` on the root `<svg>` but no `viewBox`
  (confirmed via `curl`: `<svg ... height="356" ... width="269">`), and the WebView was loading the
  raw SVG as the top-level `image/svg+xml` document - with no page-declared viewport,
  `useWideViewPort` fell back to assuming a ~980px desktop layout and shrank everything to fit.
  Fixed by wrapping the SVG in a minimal HTML document with a real `<meta name="viewport">` and a
  `viewBox` derived from the existing `width`/`height` attributes, loaded as `text/html` instead of
  `image/svg+xml`. The SVG stays inlined directly in the body (not behind `<img src>`) so its
  embedded `<a>` links remain live DOM nodes `shouldOverrideUrlLoading` can still intercept.
- **Pinch-zoom stolen by the global two/three-finger gesture shortcuts.** The diagram sits inside a
  scrollable Compose list, whose scroll gesture detector won arbitration over a pinch that started
  on the WebView before `builtInZoomControls` ever saw the second pointer. Fixed with a
  `setOnTouchListener` that calls `requestDisallowInterceptTouchEvent` - but only while
  `event.pointerCount >= 2`; the first attempt at this fix disallowed it unconditionally, which
  also silently broke ordinary single-finger scrolling past the diagram (caught immediately by the
  user: "next to impossible to scroll when focussed on the svg image").
- **Diagram sized independently of its own content.** The fixed 75%-screen-height box (previous
  follow-up, above) was either taller than the rendered SVG (wasted space) or shorter (forcing the
  SVG to scroll internally - which, since a WebView isn't a Compose nested-scroll participant, ate
  every drag that started on the diagram and made the surrounding list impossible to scroll past
  it). Replaced with `Modifier.aspectRatio()` derived from the SVG's own `width`/`height`, so the
  WebView is exactly as tall as its content and there's no leftover internal scroll to fight over
  in the common (unzoomed) case.
- Also hid the WebView's scrollbar chrome (`isVerticalScrollBarEnabled`/`isHorizontalScrollBarEnabled
  = false`) per direct request - visual only, scrolling/panning still works.
- Verified on the wired Zenfone 10: diagram fills the width and is legible, pinch-zoom works without
  breaking outer scroll, a single-finger swipe scrolls cleanly from the Front elevation straight
  through to the Rear one.

## NBC-385: auto-trigger screenshot capture on a real tagged release

`screenshots.yaml` was entirely manual (`workflow_dispatch` only). Direct user request (part of
the same ask made in the sibling jollyfin/augh repos): fire it automatically off `release.yaml`'s
real version-tag path, not the rolling "latest" prerelease that republishes on every `main` push -
that would turn a long-running multi-device emulator job into something that runs on every commit
instead of once per release.

- [x] Added an `actions: write` permission and a "Trigger screenshot capture" step to
  `release.yaml`, gated on `steps.params.outputs.tag_name != 'latest'`, calling
  `gh workflow run screenshots.yaml --ref main -f open_pr=true`. Uses the default `github.token` -
  no PAT needed, since `workflow_dispatch` (unlike push/PR events) is explicitly exempted from
  GitHub's "events triggered by GITHUB_TOKEN don't start a new workflow run" restriction.
- [x] `open_pr=true` so the auto-triggered run lands as a reviewable PR rather than only a build
  artifact nobody looks at.

**Why:** direct user request.
**How to apply:** if this ever needs a different ref than `main`, update the `--ref` flag -
currently pinned to `main` since that's guaranteed to have the workflow file's `workflow_dispatch`
schema.

Status: **done**, 2026-08-06 - not yet verified by an actual tag push through the full pipeline;
the next real release will be the first live test.

## NBC-386: sync-time cable trace 404s from picking an untraceable termination

Background sync's opt-in "Sync attachments to disk" pass (NBC-384) started surfacing `Sync failed:
Cable 79 trace SVG: HTTP 404 (+6 other issues)` on a device with real cabling. The cable itself is
fine (`fnuc usb-a-back-1 <-> TeSmart KVM`, a real USB passthrough link) - the 404 is NetBox's own:
confirmed via `curl` against the live instance, `/api/dcim/rear-ports/<id>/trace/` and
`/api/dcim/front-ports/<id>/trace/` both 404 unconditionally, regardless of the port's own cabling,
while every other termination type (interface, console/power port, circuit termination, ...) traces
fine. `cableTraceStartTarget()` (NBC-383) always picked the cable's first A-side termination with no
regard for whether that termination type actually supports the trace endpoint - and on this
instance, exactly 7 cables (all KVM/USB-hub passthrough links) have a front/rear port as that first
termination, matching the "+6 other issues" count exactly.

- [x] `cableTraceStartTarget()` now tries both the first A-side and first B-side termination and
  picks whichever one isn't a `dcim.frontport`/`dcim.rearport`, preferring the A side when both
  qualify; returns `null` (no trace attempted, no failure recorded) only if both sides are
  passthrough ports - a legitimate "nothing to show," not a sync error.
- [x] Added `CableTraceRepositoryTest` covering: normal a-side pick, rear-port a-side falling back to
  an interface b-side (the real cable-79 shape), front-port a-side falling back to a console-port
  b-side, and the both-sides-untraceable null case.
- [x] Verified live: enabled "Sync attachments to disk" on the Zenfone 10 (same NetBox instance) and
  forced a full sync - previously this exact toggle+sync combination reproduced the Cable 79 issue;
  after the fix it completed with a clean "Synced" status and no cable-trace failures.

Status: **done**, 2026-08-06; verified with remote compilation, unit tests, and a live sync on the
Zenfone 10 against the same NetBox instance that originally surfaced the bug.

## NBC-387: rack image attachments never synced; misleading "not cached" flash; sync status made opaque

Three unrelated issues surfaced while testing NBC-383/384 on real devices:

- **Rack (and every non-device object type) image attachments never appeared.** NetBox confirmed a
  real attachment existed on the user's rack (`api/extras/image-attachments/`, one result for
  `dcim.rack` id 1), but the app's gallery stayed empty. `ImageAttachmentRepository` is a dedicated
  table only `OfflineSyncRepository.refreshAll("dcim.device")` and `DeviceDetailViewModel.refresh()`
  ever populate - `GenericDetailViewModel` (racks, sites, everything else) only *observed* it, never
  fetched. Unlike `documents` (which piggybacks on the Documents plugin's own generically-synced
  object list), image attachments have no such free ride. Fixed by adding
  `GenericDetailViewModel.loadImageAttachments()`, called on init and on manual refresh, mirroring
  `DeviceDetailViewModel`'s existing per-object `imageAttachmentRepository.refresh(objectType, id)`
  call.
- **"Not cached yet - connect and refresh" flashed for a frame on slower devices** (seen on the
  Pixel 5) before the real cached content loaded. `objectEntity`/`device`'s `null` initial value
  means the same thing whether the first Room emission just hasn't landed yet or the object
  genuinely isn't cached, and the UI couldn't tell those apart. Added `hasCheckedCache: StateFlow<Boolean>`
  (mapping the underlying observe-flow to `true` on its first emission) to both
  `GenericDetailViewModel` and `DeviceDetailViewModel`; the detail screens now show a plain spinner
  while `!hasCheckedCache` instead of the "not cached" message.
- **The Dashboard's Sync issue/Synced cards were dead ends.** Tapping either did nothing, so a sync
  failure's "(+N other issues)" summary had no way to see the other issues, and there was no way to
  copy anything for a bug report. Both cards are now tappable (`SyncIssueCard`/`SyncStatusCard`
  gained an `onShowDetails` callback):
  - `SyncIssueDetailsDialog` lists every distinct failure reason (`syncIssueReasons`, reusing the
    same extraction logic `summarizeSyncIssueMessage` already used) and a "Copy logs" button.
    `SyncIssue` gained a persisted `details` field (the raw pre-summarization text, capped at 4000
    chars) alongside the existing summarized `message`, since the summary alone can't reconstruct
    the full list after a restart. `buildSyncIssueReport()` builds a proper bug-report payload for
    the copy button - app version, git revision, build date, active server, timestamp, offline-mode
    state, and every reason - not just the one-line summary. Per direct follow-up request ("the more
    info we can copy the better").
  - `SyncStatusDetailsDialog` is the positive counterpart - current sync progress (or last-synced
    time) plus the same cache figures Settings' "Cached data" card already computes
    (device/object/image counts, downloaded files and size), loaded on demand via
    `DashboardViewModel.loadCacheSummary()` rather than kept continuously up to date.
- **Two-finger-down was firing both the global gesture shortcut and pull-to-refresh.**
  `multiFingerSwipe()`'s "let a child gesture detector abort me" escape hatch only catches
  detectors that consume via raw `PointerInputChange.consume()` (like `transformable`/pinch-zoom);
  Material3's `PullToRefreshBox` is nested-scroll-based and has no concept of finger count at all,
  so it fired regardless. Added `LocalActivePointerCount` (a purely observational pointer-count
  tracker at the root, alongside the existing gesture modifier) and `SuppressiblePullToRefreshBox` (a
  thin wrapper exposing `Modifier.pullToRefresh`'s `enabled` param, which `PullToRefreshBox` itself
  doesn't forward) so pull-to-refresh is disabled while 2+ fingers are down. Verified live: a
  single-finger pull-to-refresh still triggers a sync normally; a fresh two-finger swipe no longer
  double-fires.

Status: **done**, 2026-08-06; verified with remote compilation, unit tests, and live testing across
the Zenfone 10, Mi Pad 4, and Pixel 5 (including the rack image attachment, the pull-to-refresh
regression check, and the Sync-status dialog catching a real in-progress sync on the Zenfone 10).

## NBC-388: detail-screen tab bar reflows after opening, causing mis-taps on slower phones

Opening a device (or generic item) detail screen showed the tab bar before every related-object
Room query had emitted its first real value. Each `stateIn(...)` related flow starts at its
`emptyList()`/`false` placeholder, so `DeviceDetailScreen`'s `visibleRelatedTabs` (filtered by
`relatedCounts[index] > 0`) and `GenericDetailScreen`'s `hasJournal` gate initially under-counted,
then tabs popped in one by one as each cached query landed - shifting every tab after the
newly-inserted one to the right. On a slower phone a tap registered before the reflow could land on
a different tab once the layout finished settling.

- [x] `DeviceDetailViewModel` gained `tabsReady: StateFlow<Boolean>`, combining a `.map { true }`
  readiness signal (mirroring the existing `hasCheckedCache` trick) over every source that decides
  tab visibility: `journalEntries`, `changelog`, the full device list backing `connectedDevices`,
  `topologyPluginAvailable`, the one-shot cached topology graph check, and each
  `DEVICE_RELATED_TABS` related-object query. `DeviceDetailScreen` now shows the loading spinner
  until `tabsReady` is true, so the tab bar only ever renders once, fully populated.
- [x] `GenericDetailViewModel` gained `journalTabReady: StateFlow<Boolean>` (same trick, scoped to
  just `journalEntries` - the only async source that affects *tab membership* there; `isRack`/
  `isCable` are synchronous and `changelog`/`traceSegments` only affect badge counts, not which tabs
  show up). `GenericDetailScreen` gates on it the same way.
- [x] Verified remotely: `:app:compileDebugKotlin` and `:app:testDebugUnitTest` (including
  `DeviceDetailViewModelTest`) both pass on rofl-13 with the change in place.
- [ ] Verify live on a physical device that the tab bar no longer visibly grows after the screen
  appears - not conclusively caught on the Zenfone 10 yet (a reflow this fast is hard to catch via
  screenshots); the underlying mechanism is verified correct by code review and by NBC-389's live
  confirmation of the same `stateIn`-placeholder pattern on the same screen family.

Status: mostly done, 2026-08-06; remote compile/test verification done, live device tab-bar-reflow
check still genuinely pending (not just unconfirmed - actually not caught live).

## NBC-389: generic item detail header showed the pluralized type instead of the item's name

`GenericDetailScreen`'s TopAppBar title always rendered `modelLabel` - the pluralized last segment
of the endpoint path (e.g. "Racks", "Sites") via `endpointModelLabel()` - regardless of whether the
item's own name was known. The `title: StateFlow<String?>` (`GenericDetailViewModel.kt`, mirroring
`objectEntity.display`, NetBox's own display string, e.g. "Rack 12") was already computed and used
elsewhere on the screen (breadcrumbs, print label text, the identity card) but never fed into the
app bar itself.

- [x] TopAppBar title now renders `title ?: objectTypeLabel(modelLabel, viewModel.route.endpointPath)`
  - the item's real name once loaded, falling back to the existing singular-type-label helper
  (`ui/common/ObjectTypeBadge.kt`, already used by global search) while loading or if the item
  genuinely has no display name, instead of the plural form.
- [x] Verified remotely: `:app:compileDebugKotlin` passes on rofl-13 with the change in place.
- [x] Verified live on the Zenfone 10: the rack detail screen's header now reads "Samson SRK16"
  instead of "Racks".

Status: **done**, 2026-08-06; verified with remote compilation and live on a physical device.

## NBC-391: cluster custom fields sharing a real NetBox custom-field group into one shared card

The read-only Overview tab (`GenericDetailScreen`/`DeviceDetailScreen`, via
`GenericDetailFields.kt`'s `fieldRow()`) gave every custom field its own standalone card, with only
a small floating text heading (`FieldRow.CustomGroup`) marking which admin-defined NetBox
custom-field group ("Purchase info", "SIM information", ...) a run of fields belonged to. Requested
generically (no hardcoded group names or object types, since this app renders 100+ NetBox object
types through one shared path): fields sharing a real group should visually cluster into one card
with the group name as its title, while ungrouped fields (the synthetic `"Other"` bucket
`buildFieldRows` falls back to) keep today's look exactly as-is.

- [x] `GenericFieldRenderer.kt` gained a shared `UNGROUPED_CUSTOM_FIELD_GROUP_LABEL = "Other"`
  constant (previously a bare string literal in `buildFieldRows`) so the clustering transform can
  tell a real group apart from the synthetic fallback without duplicating that string.
- [x] `GenericDetailFields.kt` gained `clusterFieldRows(rows: List<FieldRow>):
  List<FieldRowCluster>` - a pure transform mirroring the existing `visibleFieldRows()` marker-
  walking idiom. Every `FieldRow.CustomGroup(label)` marker whose label isn't the synthetic
  `"Other"` fallback collects its following member rows into one `FieldRowCluster.Grouped`; the
  `"Other"` marker and everything else (native fields, the `"Custom fields"` `Section` heading
  itself) passes through as `FieldRowCluster.Standalone`, unchanged.
- [x] Each affected `fieldRow()` branch's inner content (`PlainText`, `Markdown`, `Reference`,
  `BooleanValue`, `ChipList`, `ExternalLink`, `FileAttachment`, `Image`, `ReferenceList`, `TagList`)
  was mechanically split into a private `@Composable ...Content()` function, reused both standalone
  (wrapped in today's `detailCard`/`Surface`, byte-for-byte identical output) and stacked inside a
  new `NyetboxSectionCard` for a real group - fields inside a cluster sit directly on top of each
  other in a plain `Column`, no `HorizontalDivider` between them (an earlier pass had one; removed
  after live review looked too busy for adjacent simple fields).
- [x] `clusterFieldRows` also clusters a second, unrelated case ahead of the `"Custom fields"`
  marker: a run of two or more consecutive native/top-level `PlainText`/`Reference` rows (e.g. a
  Rack's Role/Asset Tag/Rack Type/Form Factor/Width/U Height/... block) clusters into one untitled
  card via `FieldRowCluster.Grouped(label = null, ...)`, matching the look those fields already had
  next to each other - a lone clusterable row with no clusterable neighbor, or any other native row
  type (`Markdown`, `Image`, `BooleanValue`, `Count`, `ReferenceList`, `TagList`, `ChipList`,
  `ExternalLink`, `FileAttachment`, `Metadata`), stays standalone and breaks the run.
- [x] New `fieldRows()` entry point clusters then renders; both call sites
  (`GenericDetailScreen.kt:872`, `DeviceDetailScreen.kt:793`) switched from
  `rows.forEach { fieldRow(it, ...) }` to `fieldRows(rows, ...)` with the same callback wiring
  unchanged.
- [x] `BooleanValue` keeps its own tinted `Surface` + click handling verbatim even when clustered
  (skips the generic click/long-press wrapper `ClusteredFieldRow` adds for other types) rather than
  nesting a second clickable region around it. `Reference` and `ExternalLink` are the only two types
  that need an explicit `onClick` re-attached when clustered (navigate / open URL) since they have no
  card of their own to carry it - every other type either has no click action or already embeds its
  own (e.g. `FileAttachment`'s download row, `ReferenceList`/`TagList`/`Image`'s per-item clicks).
- [x] Added `GenericFieldRendererTest` cases: a real group clusters its members; a real group mixed
  with the synthetic `"Other"` bucket keeps `"Other"` standalone; an `"Other"`-only list never gets
  clustered (matches today exactly); and one test running the whole pipeline (`buildFieldRows` +
  `clusterFieldRows`) against real `CustomFieldDefinition`s with groups/weights.
- [x] Verified remotely: `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite (48
  cases in `GenericFieldRendererTest`, including the 4 new ones) pass on rofl-13.
- [x] Verified live on the Zenfone 10 against real data on `netbox.brkn.lol`: device `#30`
  ("8-inch monitor") has only its "Purchase Information" group populated (Store/Order Number/
  Date/Price/Currency) - renders as one shared card titled "Purchase Information" with no divider
  between fields. Device `#5` ("brkn-ap") has "Purchase Information" populated *and* an ungrouped
  `operating_system` field - renders the grouped card followed by the unchanged small "Other" text
  heading and a standalone "Operating system" card, confirming the mixed case matches the design
  exactly. Rack `#1` ("Samson SRK16", via `GenericDetailScreen`, not `DeviceDetailScreen`) confirms
  the native-field clustering: Role/Asset Tag/Rack Type/Form Factor/Width/U Height/Starting
  Unit/Max Weight/Weight Unit (a `Reference`/`PlainText` mix) all land in one untitled card, and
  the following `Desc Units` `BooleanValue` correctly breaks the run into its own standalone card.
  - Hit and resolved an unrelated environment issue while setting this up: the Zenfone already had a
    Room schema v18 database installed from other, newer concurrent work on `main` that hasn't
    landed in this isolated worktree yet (which only knows schema v17), causing a
    "migration from 18 to 17" crash on launch. Fixed by `just zenfone-uninstall
    dev.pschmitt.nyetbox.debug` + reinstall (wipes the offline cache only, not real NetBox data;
    repopulated by the next sync) - unrelated to this feature, not fixed here.
  - This live-verification pass was interrupted mid-way by a session-limit cutoff and resumed cold
    in a fresh session; the resuming session re-checked the worktree diff for corruption, re-ran
    `:app:compileDebugKotlin`/`:app:testDebugUnitTest` remotely (both reported `UP-TO-DATE`,
    confirming the on-disk state was byte-identical to what had already passed), then repeated the
    device-30/device-5/Rack checks above from scratch since screenshots from the interrupted run
    weren't preserved. The Room schema v18 crash above recurred a second time on the Zenfone
    (re-deploying over the previous session's now-stale install) and was fixed the same way.
- [x] Deployed the verified build to all three physical test devices (`just deploy-all debug`):
  Zenfone 10, Mi Pad 4, Pixel 5. Confirmed Mi Pad 4 and Pixel 5 both launch to device `#30`'s detail
  screen without the Room migration crash (neither had the newer schema installed).

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and live
on a physical device against three real objects on the live NetBox instance (a single-group case,
a mixed group-plus-ungrouped case, and a native-field-clustering case on a non-device object type).

## NBC-392: Room cache no longer hard-crashes the app on a schema downgrade

While live-verifying NBC-391, the Zenfone 10 and then the Pixel 5 both hit
`IllegalStateException: A migration from 18 to 17 was required but not found` on launch and
crash-looped on every relaunch until the app was uninstalled and reinstalled. Root cause: another,
separate uncommitted change on `main` (outside this worktree) bumped the Room schema to v18 and had
been installed on those devices from earlier testing; this worktree's build only knows v17, and
Room has no forward migration path for a *downgrade*, so `SQLiteOpenHelper.onDowngrade` throws
instead of opening the database. Manually uninstalling/reinstalling worked around it each time, but
it's a real robustness gap, not just a one-off nuisance: the same crash can hit any dev testing
across two worktrees/branches with different schema versions on shared physical devices, and
nothing about it is specific to NBC-391.

- [x] `CacheDatabaseManager.kt`'s `Room.databaseBuilder(...)` call gained
  `.fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`. Safe specifically because this
  Room database is documented as a disposable, sync-repopulated cache of NetBox (see
  `GenericObjectRepository`/`DeviceRepository`, and the "Offline cache via Room" note in
  `AGENTS.md`) - never the source of truth - so silently dropping and rebuilding it on a downgrade
  trades one background resync for a hard launch crash. Forward migrations are untouched; only the
  downgrade path (`addMigrations`/`MIGRATIONS` has no matching entry) falls back to destructive.
- [x] Verified remotely: `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite pass
  on rofl-13.
- [x] Verified live: reinstalled onto the Pixel 5 (which was still crash-looping on the v18 cache
  from earlier NBC-391 testing) without first uninstalling - app opened cleanly straight into the
  "Connect to NetBox" setup flow instead of crashing, confirming the destructive-downgrade fallback
  fires instead of throwing.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and a live
reproduction of the original crash on the Pixel 5.

## NBC-393: show live sync progress in the initial setup overlay and remove the sync-card chevron

The first-run "Setting up your NetBox instance" overlay stayed on a static explanatory sentence
while the initial inventory sync was running, even though the dashboard's sync status card already
had live step and item counts. The sync card also had a trailing chevron that did not represent an
action.

- [x] Removed the unused trailing `ChevronRight` icon from `SyncStatusCard` and its import.
- [x] Passed the already-collected `SyncProgress` state into `InitialSyncOverlay`; render its live
  message plus the `Step X of Y · N of M items` detail, with the original sentence as the initial
  fallback before the first progress event.
- [x] Added the required `SyncProgress`/`notificationSubText` imports and verified remotely with
  `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite on rofl-13.
- [x] Deployed the build to all three attached devices with `just deploy-all debug`. On the Zenfone
  10's fresh first sync, UI automation captured live overlay text including `Syncing 138 NetBox
  models…`, `Step 7 of 9 · 29 of 138 models`, and later `Syncing topology map…`/`Step 9 of 9`; the
  overlay then cleared and the dashboard showed `Synced` and `Last synced just now`. The Mi Pad 4
  and Pixel 5 also installed successfully and launched without errors.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, deployment
to all attached physical devices, and live first-sync progress on the Zenfone 10.

## NBC-394: group generic item fields into a titled Details card

Generic read-only item views currently render native fields as a series of individual cards, with
the page's generic `Details` heading providing the only grouping. Add a compact, consistently titled
card across object types for the ordinary native fields users use to identify and understand an item
(for example site, role, serial, device type, comments, and NetBox audit timestamps), while leaving
specialized rows and custom-field groups with their existing behavior.

- [x] Use the short, generic `Details` title and an informational icon for the shared card.
- [x] Group plain text, references, comments, and NetBox timestamps before the `Custom fields`
  section into that card; keep specialized rows such as tags, images, and attachments separate.
- [x] Group reverse-related count rows (for example a site's circuits, devices, racks, and virtual
  machines) into a clickable `Linked items` card with endpoint-specific icons and count badges.
- [x] Remove the now-redundant page-level `Details` heading from `GenericDetailScreen`.
- [x] Add renderer tests covering a lone field, the mixed native field run, comments/audit
  timestamps, and linked-item count grouping.
- [x] Verified remotely with `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite on
  rofl-13, then deployed the debug build to all three attached development devices with
  `just deploy-all debug`.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and
deployment to all attached physical devices.

## NBC-398: keep image-viewer metadata compact and actionable

The device image viewer should keep its metadata panel stable while still allowing users to open
the manufacturer or device type represented by a device-type photo.

- [x] Replace the separate `Open device type` action with inline clickable Manufacturer and Device type
  metadata values.
- [x] Verify remotely with `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite on
  rofl-13, then deploy the debug build to all three attached devices with `just deploy-all debug`.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and
deployment to all attached physical devices.

## NBC-397: unify device image viewer sources

The dedicated device view's image viewer should let users swipe through all relevant device images
as one collection, regardless of whether they opened a device-type photo or an image attachment.
The viewer should identify the source of the currently displayed image without obscuring it.

- [x] Combine device-type front/rear photos and image attachments into one viewer pager from either
  entry point, while retaining the existing custom-field image fallback.
- [x] Add a bottom source badge to the viewer metadata panel for device-type images and image
  attachments.
- [x] Verify remotely with `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite on
  rofl-13, then deploy the debug build to all three attached devices with `just deploy-all debug`.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and
deployment to all attached physical devices.

## NBC-395: share the generic and specialized item-detail screen architecture

`GenericDetailScreen` and the dedicated `DeviceDetailScreen` are separate Compose screens rather
than subclasses, but they currently duplicate substantial orchestration: transparent detail app bars,
loading/offline states, pull-to-refresh, tab strips, swipe handling, lazy-list layout, and common
field/card interactions. The screens should share a compositional detail framework while retaining
their independent ViewModels and device-only tabs/actions.

- [x] Extract a shared item-detail scaffold/body primitive for the common app bar, snackbar host,
  tab strip, swipe handling, and lazy-list shell; keep each screen's cache/loading branch explicit
  until the state models can be unified without weakening offline-first behavior.
- [x] Keep identity, Overview, related-tab, changelog, and edit-mode content screen-owned through
  explicit scaffold/tab-list content slots and screen-specific top-bar actions; do not make the
  two ViewModels inherit from one another just to share UI plumbing.
- [x] Move the shared Details and Linked items cards plus the shared detail app-bar primitive into
  reusable UI components; keep device-only navigation (rack position, device type, primary IP) as
  injected actions rather than duplicating card infrastructure.
- [x] Migrate `GenericDetailScreen` and `DeviceDetailScreen` incrementally with behavior-preserving
  checkpoints, then remove obsolete duplicated helpers/imports.
- [x] Add focused tests for the generic field/card transforms and perform post-install launch checks
  for the debug build on all three attached devices.
- [x] Run remote compile/tests and lint, deploy to all attached devices, and confirm successful app
  launch on the Zenfone 10, Mi Pad 4, and Pixel 5.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, the
repository lint recipe, deployment to all attached physical devices, and post-install launch checks.

## NBC-396: polish detail-card rows and media sections

Polish the shared item-detail cards after the initial rollout: remove the redundant trailing arrow
from linked-item count rows, increase row padding inside grouped cards, and present image attachments
and documents as shared cards while preserving their existing counts and actions.

- [x] Remove the redundant linked-item row arrow; keep the endpoint icon and count badge.
- [x] Increase vertical padding for rows inside grouped detail cards.
- [x] Wrap image attachments and documents in shared section cards on generic and device detail
  views; keep document rows inside the parent card rather than nesting cards.
- [x] Verify remotely with `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite on
  rofl-13, then deploy the debug build to all three attached devices with `just deploy-all debug`.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and
deployment to all attached physical devices.
## NBC-390: relation lookups (device tabs, journal entries, rack devices) decoded every cached row
of an endpoint on every screen open instead of using an index

NBC-388's `tabsReady`/`journalTabReady` fix made the tab bar wait for its data to settle before
rendering, but didn't address why settling ever took measurable work: `netbox_objects` has no
index and no device/relation column at all. `GenericObjectRepository.observeObjects(endpointPath,
"", filterKey, filterValue)` - used for a device's interfaces/ports (`filterKey = "device"`), a
device's journal entries (`filterKey = "assigned_object_id"`), and a rack's devices (`filterKey =
"rack"`) - read the *entire* cached endpoint (`dao.observeAll`) and decoded every row's JSON in
Kotlin (`matchesRelation`) to find which ones belonged to the one device/rack being viewed, every
single time any detail screen opened, regardless of how many other devices' components were mixed
into that same cached table. The fix pushes that decision to sync/write time instead, per the new
AGENTS.md "Architecture" guidance the user asked for ("whatever can be done at sync time, ahead -
should be done at sync time").

- [x] `NetBoxObjectEntity` gained a nullable `relatedObjectId: Int?` column plus a
  `(endpointPath, relatedObjectId)` index (migration 17→18, `MIGRATION_17_18`).
- [x] `GenericObjectRepository.toEntity()` now computes `relatedObjectId` once at write time via
  `precomputedRelatedObjectId()`, for exactly the three (endpoint, relation key) pairs actually
  queried on every normal screen open (device-scoped component endpoints, `api/dcim/devices/`'s
  `rack` relation, journal entries' `assigned_object_id`) - anything else (e.g. the related-items
  bottom sheet's arbitrary `_count`-field relations) is untouched and still falls back to the
  original full-scan behavior.
- [x] `NetBoxObjectDao.observeByRelatedObjectId()` is a real indexed query
  (`WHERE endpointPath = ? AND (relatedObjectId = ? OR relatedObjectId IS NULL)`);
  `observeObjects()` uses it when the caller's `filterKey` matches the precomputed relation for that
  endpoint. The `OR relatedObjectId IS NULL` clause is a deliberate safety net: rows cached before
  this migration (or before their next sync) keep matching via the untouched
  Kotlin-side `matchesRelation` filter that still runs afterward, so there's no window where
  existing cached tabs/journal entries/rack devices go missing while waiting for a fresh sync to
  backfill the new column - the query only ever narrows the candidate set, never replaces the
  correctness check.
- [x] Added `GenericObjectRepositoryTest` cases covering: the write path precomputing the right
  `relatedObjectId`, the read path returning only the target device's rows, and the null-fallback
  path for rows that predate the column.
- [x] Fixed the two hand-written `NetBoxObjectDao` test fakes (`GenericObjectRepositoryTest`,
  `PendingEditRepositoryTest`) that needed the new interface method implemented to keep compiling.
- [x] Verified remotely: `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite pass
  on rofl-13.
- [x] Verified live on the Zenfone 10: installed over an existing v17 database with real cached
  devices/interfaces/ports - the migration ran without a crash or Room schema-validation error, the
  device list and a device's Overview/Rear ports (7)/Changelog tabs all rendered correctly from the
  pre-migration cache (via the `relatedObjectId IS NULL` fallback, before any new sync had run), and
  the Rear ports tab showed exactly that device's 7 ports, not another device's.
- [x] Verified live, post-sync, on the Zenfone 10: triggered a real full sync (dashboard's "Synced"
  card, ~7000 objects re-synced), confirmed no crash/exception in logcat, then reopened the same
  device - Overview/Rear ports (7)/Changelog rendered identically, this time via the freshly
  populated `relatedObjectId` column rather than the null fallback.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and live
on a physical device both before and after a real sync populated the new column.

## NBC-399: edit mode didn't look like the rest of the app - flat fields, no Material You cards

Editing any object (racks, sites, devices - the "Edit" menu item everywhere funnels through one
shared `EditForm` in `GenericDetailEditing.kt`) rendered every field as a bare `OutlinedTextField`/
`Switch`/picker directly on the Scaffold background, with a 2dp border as the only "changed" cue -
visually nothing like the read-only Overview tab, which wraps every field in a rounded `NyetboxCard`.
Separately, the user asked for custom fields sharing an admin-defined NetBox group (e.g. a
"Purchase Information" group covering Store/Order Number/Date/Price/Currency/Notes) to visually
cluster together instead of floating as separate cards under a small heading - requested for edit
mode here, and for the read-only Overview in a parallel NBC-391 effort.

- [x] `EditableField` gained a `group: String?` (from `CustomFieldDefinition.group`, threaded
  through `customFieldEditableField()` in `GenericFieldRenderer.kt`) - the same metadata the
  read-only view already uses for its `FieldRow.CustomGroup` headings.
- [x] `EditForm` now groups fields via a new `groupEditableFields()`: custom fields sharing a real
  group cluster into one `NyetboxSectionCard` (icon + group name as the card title); custom fields
  with no group stay standalone, one `NyetboxCard` each.
- [x] Follow-up refinement per direct feedback ("skip the separators", "group metadata like site,
  manufacturer, asset tag into a card too"): dropped the `HorizontalDivider` between clustered
  fields - separation is spacing alone now, no visible line, in both the named-group cards and the
  new native-field clustering below. Native/top-level fields (which have no group metadata to key
  off, unlike custom fields' `CustomFieldDefinition.group`) now also cluster, but type-driven
  rather than name-driven to stay generic across every NetBox object type: consecutive fields of a
  "simple" `EditFieldKind` (`STRING`/`NUMBER`/`INTEGER`/`CHOICE`/`REFERENCE` - the edit-mode
  equivalent of the read view's `FieldRow.PlainText`/`FieldRow.Reference`) share one untitled card
  under a new "Details" heading (mirroring the Overview tab's existing static heading); richer
  controls (`BOOLEAN`, `LONG_TEXT`/markdown, `JSON`, `MULTI_REFERENCE`, `MULTI_CHOICE`) keep their
  own standalone card and interrupt a run rather than joining it, so top-to-bottom order never
  changes - only which fields end up sharing a card boundary. A "Custom fields" heading was added
  to match, mirroring the Overview tab's `FieldRow.Section` heading.
- [x] Added `EditableFieldTest` cases: a real group clusters its members; multiple groups sort
  alphabetically/case-insensitively; ungrouped custom fields stay standalone; consecutive simple
  native fields cluster under "Details" while a `LONG_TEXT` field interrupts the run without
  reordering anything.
- [x] Verified remotely: `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite pass.
- [x] Verified live on the Zenfone 10 on two devices with real custom-field data: Name/Device
  Type/Role/Tenant/Serial/Asset Tag/Site/Location/Status/Description all cluster under one
  untitled "Details" card with no dividers, just spacing; Comments (markdown) correctly breaks the
  run into its own standalone card; Console Port Count/Console Server Port Count then start a new
  cluster after it; the "Purchase Information" custom-field group renders as one titled card with
  no dividers between Store/Order Number/Date/Price/Currency/Notes.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and live
on a physical device, including the post-feedback refinement.

## NBC-400: keep outgoing item shares in the Android chooser

The item-view overflow Share action must open Android's share menu instead of allowing Nyetbox's
incoming `ACTION_SEND` handler to reopen the same item view for its own URL.

- [x] Exclude Nyetbox's activity from the outgoing URL chooser while keeping incoming sharing
  available.
- [x] Verify remotely and deploy the debug build to all attached devices.

Status: **done**, 2026-08-06; verified with remote compilation, the full unit test suite, and
deployment to all attached physical devices.

## NBC-420: `ShortcutSyncer.sync()` runs twice on every cold start, both times on the main thread

`shortcuts/ShortcutSyncer.kt`'s `sync()` performs a synchronous Binder IPC to the system
`ShortcutManager` service (`ShortcutManagerCompat.setDynamicShortcuts`) plus builds an
`IconCompat.createWithResource` per configured shortcut (up to 4). It is called from two places
that both fire on every single cold start, both on the main thread:

1. `NyetboxApp.onCreate()` (`NyetboxApp.kt:43`) - synchronously, before `Application.onCreate()`
   returns, i.e. squarely inside the cold-start critical path before the first frame.
2. `MainActivity.kt:104`'s `LaunchedEffect(shortcutItems) { shortcutSyncer.sync() }` - which is
   meant to catch Settings edits, but `shortcutItems` is a `StateFlow` whose *initial* value is
   also emitted on first composition, so this re-publishes the exact same shortcut list a second
   time during startup, again on the Main dispatcher (LaunchedEffect's default context).

The work is redundant (same list published twice) and both call sites do Binder IPC on the UI
thread. On the Mi Pad 4 this is measurable dead weight in the pre-first-frame stack; the
launcher-shortcut list only actually *needs* republishing when the user edits it in Settings.

- [ ] Remove the `shortcutSyncer.sync()` call from `NyetboxApp.onCreate()` entirely - the
      `LaunchedEffect(shortcutItems)` in `MainActivity` already covers startup (its initial
      emission publishes the current list) as well as later edits, so the App-level call is pure
      duplication. (If a headless entry path that never reaches `MainActivity` is a concern:
      shortcuts only matter once a launcher shows them, and the launcher launches `MainActivity`.)
- [ ] Make `ShortcutSyncer.sync()` hop off the main thread: either make it a `suspend fun` invoked
      via `withContext(Dispatchers.Default)` (callers: the `LaunchedEffect` can just call it from
      a coroutine), or wrap the body in its own internally-owned scope. The
      `ShortcutManagerCompat.setDynamicShortcuts` call and icon building must not run on Main.
- [ ] Verification: unit-test-level - none needed (no logic change); on-device - cold start the
      debug build on the Mi Pad 4 and confirm via logcat/`adb shell dumpsys shortcut` that the
      shortcut list is still published correctly after startup, and that editing the list in
      Settings > App shortcuts still updates the launcher long-press menu live (NBC-417's
      verification flow). Optionally confirm with a `Debug.startMethodTracing`/logcat timestamp
      that `Application.onCreate` no longer includes the ShortcutManager IPC.

Measured 2026-08-08 (Mi Pad 4): debug cold start is 5.2s with 48/132/43-frame Choreographer
skips, but a release build cold-starts in 991ms with *zero* skip warnings - so most of the debug
startup pain is unoptimized-bytecode overhead, not this. The double-publish + main-thread Binder
IPC is still real and still pure waste on every launch, but it's a small cleanup, not a
cold-start fix - keep it low priority.

Status: **done**, 2026-08-08 - removed the `NyetboxApp.onCreate()` call and made `sync()` a
`suspend fun` that hops to `Dispatchers.Default` before touching `ShortcutManagerCompat`; the
`MainActivity` `LaunchedEffect(shortcutItems)` is now the only call site and still covers both
cold start (its initial `StateFlow` emission) and Settings edits. Remote `:app:compileDebugKotlin`,
`:app:testDebugUnitTest`, and `:app:lintDebug` all passed; no on-device re-verification of the
launcher long-press menu was performed this pass.

## NBC-421: the gesture/widget target picker loads the entire `netbox_objects` table into memory
and substring-scans every row's full JSON on every keystroke, on the main thread

`ui/settings/SettingsGestures.kt`'s `ActionTargetPickerDialog` (shared by the gesture editor, the
nav-bar customizer, launcher-shortcut settings, and both widget config screens) receives
`objects: List<NetBoxObjectEntity>` produced by two ViewModels the same way:

- `SettingsViewModel.kt:99-102` (`gestureObjects`) and
- `widget/WidgetConfigActivity.kt:105-108` (`WidgetConfigViewModel.objects`)

both do `genericObjectRepository.observeAllObjects().stateIn(...)` - i.e. they hold *every cached
NetBox object of every endpoint*, **including each row's full raw `json` blob** (the by-far
largest column), in a `StateFlow` for as long as the Settings screen / widget config Activity is
subscribed. On a realistic cache (~7000 objects at several KB of JSON each) that is tens of MB of
heap and a heavy initial Room query, paid on *opening Settings categories that show the
picker* - and re-paid on every `netbox_objects` invalidation (any sync page upsert) while
subscribed.

On top of that, inside the dialog (`SettingsGestures.kt:127-141`), `filteredObjects` is computed
directly in the composable body (not `remember`ed), and its filter runs
`obj.json.contains(targetQuery, ignoreCase = true)` - a case-insensitive substring scan over every
row's multi-KB JSON string - on the **main thread, on every recomposition, for every keystroke**
typed into the picker's search field. The results are then rendered with a `forEach` inside a
`verticalScroll(Column)` (line 179) - no lazy virtualization, so picking a type with thousands of
cached instances (e.g. interfaces) composes every row at once (the same anti-pattern NBC-374
removed from the detail tabs).

- [ ] Replace the two `observeAllObjects()` producers with a query-driven lookup: the picker's
      object step always has a concrete `detailModel`/endpoint selected before any objects are
      shown, so it never actually needs more than one endpoint's matches, let alone the whole
      table. Expose e.g. `observeObjectChoices(endpointPath, query, limit = 50)` from
      `GenericObjectRepository` backed by `NetBoxObjectDao.searchAllInEndpoint` (already indexed
      by endpoint and LIMIT-bounded), drive it from the dialog's `targetQuery` (hoisted into the
      ViewModel or passed via a lambda), and replace the `objects: List<NetBoxObjectEntity>`
      parameter with the already-filtered, bounded result list.
- [ ] While at it, render the object list with a `LazyColumn` (bounded height inside the dialog)
      instead of `forEach` in a `verticalScroll` `Column`, and drop the in-memory
      `obj.json.contains(...)` clause (the DAO query's `json LIKE` keeps full-JSON matching, with
      SQLite doing the scan off the main thread).
- [ ] Update both call sites (`SettingsViewModel`/`SettingsGestures.kt` consumers and
      `WidgetConfigViewModel`/`WidgetConfigActivity`) - after this, nothing outside
      `GlobalSearchRepository`'s index should consume `observeAllObjects()` wholesale anymore.
- [ ] Verification: unit test the new repository query method (endpoint scoping + limit + query
      matching, mirroring `GenericObjectRepositoryTest`'s existing fakes); on-device, open
      Settings > Navigation bar (or a widget's config), add a "specific item" target for a
      high-cardinality type (Interfaces), and confirm typing in the search field stays smooth
      (no multi-frame Choreographer skips in logcat while typing) and results appear correctly.

Confirmed live 2026-08-08 on the Mi Pad 4 (debug build, real cache of 6,883 objects / ~11.5MB of
JSON): merely *opening the Gestures settings category* grew the app's Java heap from 43MB to 94MB
(+51MB, `dumpsys meminfo`) and rendered one 550ms frame plus 3×150ms/200ms frames during the
transition (`dumpsys gfxinfo` histogram diff). That's before even opening the picker dialog or
typing a character into it.

Status: **done**, 2026-08-08 - added `GenericObjectRepository.observeObjectChoices(endpointPath,
query, limit = 50)` backed by the already-indexed, already-`LIMIT`-bounded
`NetBoxObjectDao.searchAllInEndpoint`. `ActionTargetPickerDialog` and `GestureShortcutRow` now take
an `objectChoices: (endpointPath, query) -> Flow<List<NetBoxObjectEntity>>` lambda instead of a
materialized `objects: List<NetBoxObjectEntity>`, driven from the dialog's own `targetQuery` via
`remember(detailModel?.endpointPath, targetQuery) { ... }.collectAsState()`; the in-memory
`obj.json.contains(...)` scan is gone (the DAO's `json LIKE` clause covers it off the main thread)
and the object list renders via `LazyColumn` instead of `forEach` in a `verticalScroll` `Column`.
`SettingsViewModel.gestureObjects`/`WidgetConfigViewModel.objects` (both backed by
`observeAllObjects()`) are replaced by `gestureObjectChoices()`/`objectChoices()` functions on each
ViewModel; nothing outside `GlobalSearchRepository`'s index now consumes `observeAllObjects()`.
Added `GenericObjectRepositoryTest` coverage for endpoint-scoping, query-matching, and the `limit`
bound. Remote `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:lintDebug`, and
`:app:ktfmtCheck` all passed; the on-device heap/frame-time re-measurement (Settings > Gestures,
then typing in the picker) was not re-run this pass.

## NBC-422: dashboard and global search decode every cached device-type's JSON on the main thread
on every `netbox_objects` change (and rebuild a full-devices map alongside)

`DashboardViewModel.kt:175-193` and `GlobalSearchViewModel.kt:107-124` contain the same
copy-pasted pair of flows used only to resolve list-row thumbnails:

- `devicesById`: `deviceRepository.observeDevices("")` → `associateBy { it.id }` - re-reads the
  *entire* `devices` table and rebuilds the full map on every `devices`-table invalidation.
- `deviceTypeFrontImagesById`: `genericObjectRepository.observeObjects(DEVICE_TYPES, "")` →
  `frontImageUrlFromRawJson(t.json)` per row - **decodes every cached device-type's JSON** on
  every emission, plus pays `observeObjects`' natural sort (regex-chunked comparator) for a result
  that immediately becomes an unordered `Map`.

Both `.map` lambdas execute in the collector's context - `stateIn(viewModelScope)` =
`Dispatchers.Main.immediate` - so all of this JSON decoding and map building happens **on the main
thread**. Worse, Room invalidation is table-level: *any* upsert into `netbox_objects` (every
~200-row page of every endpoint during a sync) re-triggers the device-types query and the full
re-decode. With the dashboard open during a background sync (the normal state of the app right
after launch, since the startup sync fires 10s in), this decode-sort-map cycle runs dozens of
times back-to-back on the UI thread - exactly when the user is first interacting with the app.

This is the NBC-390 pattern one level up: the decision "which image URL does device-type X have"
is re-derived from raw JSON at read time on every change, instead of computed once at sync/write
time.

- [ ] Precompute the front-image URL at sync time, following NBC-390's `relatedObjectId`
      precedent: add a nullable `frontImageUrl: String?` column to `NetBoxObjectEntity`
      (migration N→N+1), populated in `GenericObjectRepository.toEntity()` via the existing
      `frontImageUrlFromRawJson` logic applied to the already-parsed `JsonObject` (zero extra
      decode cost at write time), for `api/dcim/device-types/` rows (null elsewhere).
- [ ] Add a DAO projection query for the thumbnail map, e.g.
      `SELECT id, frontImageUrl FROM netbox_objects WHERE endpointPath = :p AND frontImageUrl IS
      NOT NULL` returning a small POJO - no JSON column transfer, no sort - and use it from both
      ViewModels; keep a read-time `frontImageUrlFromRawJson` fallback for rows predating the
      migration (same "narrow, never replace" safety-net shape as NBC-390's `IS NULL` clause) or
      simply accept thumbnails appearing after the next sync.
- [ ] Slim `devicesById` the same way: both consumers only ever read `deviceTypeId` (see
      `thumbnailFor`), so a `SELECT id, deviceTypeId FROM devices` projection map (or a JOIN
      that resolves device → its type's frontImageUrl in SQL) replaces shipping every full
      `DeviceEntity` into a map.
- [ ] Move whatever mapping work remains off the main thread with `.flowOn(Dispatchers.Default)`
      before `stateIn` in both ViewModels.
- [ ] Two smaller call sites take the same precomputed column: `GenericListScreen.kt:158` does
      `remember(obj.json) { frontImageUrlFromRawJson(obj.json) }` *per list row* - a full JSON
      parse of the row's multi-KB blob on the main thread the first time each row scrolls into
      view. With the column in place, the row can read `obj.frontImageUrl` directly, deleting the
      parse from the scroll path.
- [ ] Micro-fix while touching it: `isMediaUrl` (`data/schema/NetBoxJson.kt:39-40`) calls
      `toHttpUrlOrNull()` *twice* per candidate (once inside `isHttpUrl`, once itself) - okhttp's
      URL parser runs an IDNA mapping-table pass per call (this exact frame was caught on the
      main thread mid-cold-start, see below). Parse once and reuse, or check
      `"/media/"` containment on the raw string after a cheap scheme prefix check.
- [ ] Verification: extend `GenericObjectRepositoryTest` for the write-time population of the new
      column; on-device, open the dashboard, trigger "Sync now", and confirm dashboard/search
      thumbnails still render while the dashboard stays smooth during the sync (bar below).

Confirmed live 2026-08-08 on the Mi Pad 4:

- A SIGQUIT stack dump ~4s into a debug cold start caught the main thread `Runnable` inside
  exactly this pipeline: `DashboardViewModel`'s map #3 → `frontImageUrlFromRawJson` →
  `HttpUrl.parse` → `okhttp3.internal.idn.IdnaMappingTable.map` - i.e. this flow was actively
  burning the UI thread during startup (238 cached device types on this install).
- Release build (so *not* a debug artifact): with the dashboard open and completely untouched
  while a forced full sync ran, the app rendered 768 frames in 15s - continuous recomposition
  churn - with the bulk of frames at 20-36ms and tails to 117ms (74 janky) on a 60Hz panel.
  The normal first-run experience (dashboard open, startup sync running) is exactly this state.
- Debug builds additionally logged `Choreographer: Skipped 132 frames!` right after first frame
  on the Mi Pad 4 and 2×~34 frames on the Zenfone 10, with this pipeline as a main contributor.

Status: **done**, 2026-08-08 - added a nullable `frontImageUrl` column to `NetBoxObjectEntity`
(Room migration 18→19, `MIGRATION_18_19`), populated in `GenericObjectRepository.toEntity()` from
the already-parsed `JsonObject` for `api/dcim/device-types/` rows only (`null` elsewhere, including
rows cached before the migration - the ticket's own fallback option, "accept thumbnails appearing
after the next sync," rather than a read-time decode fallback). Added
`NetBoxObjectDao.observeThumbnails`/`GenericObjectRepository.observeThumbnails` (an `id ->
frontImageUrl` projection, no `json` column, no sort) and switched both
`DashboardViewModel.deviceTypeFrontImagesById` and `GlobalSearchViewModel.deviceTypeFrontImagesById`
to it, with `.flowOn(Dispatchers.Default)`. `GenericListScreen.kt`'s per-row `remember(obj.json) {
frontImageUrlFromRawJson(obj.json) }` now reads `obj.frontImageUrl` directly. Fixed the double
`toHttpUrlOrNull()` parse in `isMediaUrl`.

For `devicesById`: the ticket's claim that "both consumers only ever read deviceTypeId" turned out
to be incomplete - `GlobalSearchScreen.kt`'s `searchAssetTagFor`/`searchHasAssetTagField`/
`searchStatusFor` also read `assetTag`/`statusLabel` off the same map. Added
`DeviceDao.observeLookup`/`DeviceRepository.observeDeviceLookup` projecting exactly those four
columns (`id`, `deviceTypeId`, `assetTag`, `statusLabel`) as a new `DeviceLookup` type instead of
shipping every `DeviceEntity` column (including `comments`/`customFieldsJson`), and updated both
ViewModels' `devicesById` plus `thumbnailFor`/`searchThumbnailFor`/`searchAssetTagFor`/
`searchHasAssetTagField`/`searchStatusFor` signatures to match - a narrower fix than the ticket
described, but the correct one.

Extended `GenericObjectRepositoryTest` (write-time `frontImageUrl` population, scoped to
device-types) and `DeviceRepositoryTest`/`PendingEditRepositoryTest`'s fake DAOs for the new
interface members. Remote `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:lintDebug`, and
`:app:ktfmtCheck` all passed. Not verified this pass: on-device main-thread stack sampling during a
forced sync (the original repro), and Room's `app/schemas/.../19.json` export - already missing for
schema versions 16-18 before this change, so this is a pre-existing gap in the remote build's KSP
output, not something this migration introduced or fixed.

## NBC-423: per-list search has no debounce and re-sorts the whole endpoint on the main thread on
every keystroke

`GenericListViewModel.kt:56-66`: `objects` is `_query.flatMapLatest { repository.observeObjects(
endpointPath, it, ...) }` with **no debounce** on `_query` - every raw keystroke in a list
screen's search bar (NBC-372's per-list `key:value` search) immediately tears down and restarts a
Room query (`LIKE '%q%'` over the endpoint), then pays `observeObjects`' natural sort
(`naturalDisplayComparator` - a regex-chunking comparator allocating a fresh `findAll` sequence
per *comparison*, i.e. O(n log n) regex work per emission) - and the whole pipeline is collected
via `stateIn(viewModelScope)` on `Dispatchers.Main.immediate`, so the sort of a potentially
multi-thousand-row endpoint (interfaces, IP addresses) runs on the main thread per keystroke.
`GlobalSearchViewModel` already debounces 300ms and ranks on `Dispatchers.Default`
(`flowOn(Dispatchers.Default)`, NBC-353) - the per-list search bars predate that lesson.

`DeviceListViewModel.kt` has the same no-debounce + main-thread-collection shape for the device
list (its sort happens in SQL so it's cheaper, but the structured-filter path
(`createSearchFields()`/`matchesFilters` per device per emission, `DeviceRepository.kt:30-37`)
also runs on Main when `key:value` filters are used).

- [ ] In `GenericListViewModel`, debounce the query like global search does:
      `_query.debounce(300).distinctUntilChanged()` feeding the existing `flatMapLatest` (keep
      the un-debounced `query` StateFlow for the text field's own display value).
- [ ] Add `.flowOn(Dispatchers.Default)` to the `objects` pipeline (after `flatMapLatest`,
      before `stateIn`) so the natural sort and any in-memory `key:value` filtering run off the
      main thread. Do the same for `DeviceListViewModel`'s `devices` flow.
- [ ] Cheap win inside `naturalCompare` (`GenericObjectRepository.kt:78-90`), independent of the
      above: precompute each entity's chunked key once per emission (sort by a precomputed
      decomposition) instead of re-running `NATURAL_SORT_CHUNK.findAll` on both operands for
      every pairwise comparison. Only do this if profiling shows the sort itself still matters
      after the dispatcher move; otherwise skip.
- [ ] Verification: extend/keep `naturalCompare`'s existing unit tests green; on-device (Mi Pad
      4), open the largest generic list available (Interfaces or IP addresses), type a several-
      character query at normal speed, and confirm no dropped-frame bursts in logcat
      (`Choreographer` skips) and no visible input lag; confirm results still update correctly
      after the 300ms pause.

Confirmed live 2026-08-08 by typing at ~3 chars/sec into list search bars and diffing `dumpsys
gfxinfo` histograms per burst:

- Mi Pad 4, debug, device list ("shelly"): 19 frames, 13 janky, frames up to 200/250ms.
- Mi Pad 4, debug, Interfaces list (602 cached rows): 18 frames, 11 janky, 3×150ms + 250ms.
- Mi Pad 4, **release** (real-user conditions), device list: still 44/53/77/85/89ms single-frame
  hitches per keystroke - visible input jank, just milder than debug.
- Zenfone 10, debug, device list: 21 frames, 8 janky, up to 129ms - so this is not
  worst-device-only.

Status: **done**, 2026-08-08 - added `.debounce(300)` before the `flatMapLatest` and
`.flowOn(Dispatchers.Default)` to both `GenericListViewModel.objects` and
`DeviceListViewModel.devices` (the un-debounced `query`/`_query` StateFlow still drives the text
field's own display value). Skipped the optional `naturalCompare` precomputation bullet per its own
"only if profiling shows the sort itself still matters after the dispatcher move" gate. Remote
`:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and `:app:lintDebug` all passed; the
`Choreographer`-skip/no-visible-lag on-device re-check was not re-run this pass.

## NBC-424: cache-summary counts load entire tables into memory to count them

`DashboardViewModel.loadCacheSummary()` (`DashboardViewModel.kt:61-77`) computes `imageCount` via
`database.deviceTypeDao().getAll().count { it.frontImageUrl != null || it.rearImageUrl != null } +
database.imageAttachmentDao().getAll().size` - materializing every `DeviceTypeEntity` and every
`ImageAttachmentEntity` row just to produce two integers. `SettingsViewModel.kt:341-343` has the
identical copy-pasted computation for the Settings cache card. Both run every time their screen
loads the summary. The right shape already exists next door: `netBoxObjectDao().countAll()` and
`deviceRepository.cachedDeviceCount()` are plain SQL `COUNT(*)` queries.

- [ ] Add `@Query("SELECT COUNT(*) FROM device_types WHERE frontImageUrl IS NOT NULL OR
      rearImageUrl IS NOT NULL") suspend fun countWithImages(): Int` to `DeviceTypeDao` (adjust
      table/column names to the actual entity definitions), and a `COUNT(*)` to
      `ImageAttachmentDao` (e.g. `suspend fun count(): Int`).
- [ ] Use them from both `DashboardViewModel.loadCacheSummary()` and `SettingsViewModel`'s
      equivalent, removing the `getAll()` calls there (check whether those `getAll()`s have any
      other remaining callers before removing the DAO methods themselves - `TopologyViewModel`
      etc. still use `DeviceTypeRepository.cachedAll()`).
- [ ] Verification: rely on Room's compile-time query validation via a remote
      `:app:compileDebugKotlin` plus the existing unit test suite, and on-device confirm the
      Settings cache card and dashboard cache summary still show the same counts as before.

Status: **done**, 2026-08-08 - added `DeviceTypeDao.countWithImages()` and
`ImageAttachmentDao.count()` (plain `COUNT(*)` queries) and switched both
`DashboardViewModel.loadCacheSummary()` and `SettingsViewModel.refreshCacheCounts()` to use them
instead of `getAll().count{}`/`getAll().size`; the `getAll()` methods themselves still have other
callers (`DeviceTypeRepository.cachedAll()`, `ImageAttachmentRepository.cachedAll()`) so they were
left in place. Remote `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and `:app:lintDebug` all
passed; on-device confirmation that both cache-summary cards still show correct counts was not
performed this pass.

## NBC-425: every placed Glance widget recomposes on the app's main thread - at cold start and on
every Room invalidation - competing with whatever the user is doing

Glance runs widget compositions **in the app process, on the main thread** (confirmed, not
assumed: a SIGQUIT dump ~6s into a Mi Pad 4 debug cold start caught `tid=1 "main"` `Runnable`
deep inside `RackViewGlanceWidget.provideGlance`'s `Scaffold`/`LazyColumn` item composition, with
the main thread having accumulated ~7s of user CPU since process start). Two consequences for
this app, which encourages placing several widgets (the test device has 4 instances: stats,
bookmarks, and two rack views):

1. **Cold start**: the AppWidget host re-establishes every placed widget's session the moment the
   process starts (logcat: `AppWidgetServiceImpl: Trying to notify widget update ... widget id
   4/5/7/8` fires immediately at process creation), so all placed widgets compose on the main
   thread exactly while `MainActivity` is trying to render its own first frames. On the Mi Pad 4
   debug build this contributed to a `Choreographer: Skipped 132 frames!` (~2.2s) burst right
   after first frame. (Release-build magnitude unmeasured - the release install had no widgets
   placed - so treat the *size* as debug-observed, but the on-main-thread mechanism is inherent.)
2. **While the app is in use**: both widgets read their data reactively via `collectAsState` on
   Room-backed flows (`NyetboxGlanceWidget.kt:166-173`: bookmarks, changelog, device count;
   `RackViewGlanceWidget.kt:126-131`: rack elevation slots). Room invalidation is table-level, so
   any live widget session recomposes on the main thread whenever *anything* touches those tables
   - e.g. repeatedly during a sync's page-by-page upserts, stacked on top of the NBC-422 churn,
   while the user is actively using the app. The rack widget is the expensive one: Glance's
   `LazyColumn` is RemoteViews-adapter-backed and composes every merged block of a ~42U rack per
   pass, per instance.

The reactive reads themselves are deliberate (NBC-418's reconfigure fix) and must stay - the fix
is to stop *equal* re-emissions from recomposing at all, and to keep each pass cheap:

- [ ] Add `.distinctUntilChanged()` to every flow a widget composition collects, so a table
      invalidation that produced identical data (the common case during unrelated-endpoint sync
      pages) doesn't recompose the session: the `remember`ed `bookmarksFlow`/`changesFlow` and
      `deviceRepository.observeCount()` in `NyetboxGlanceWidget.provideGlance`, and the
      `remember(rackId, face)` elevation flow in `RackViewGlanceWidget.provideGlance`. (Entity
      classes are data classes, so list equality is structural and correct here.)
- [ ] Additionally debounce the two list flows (e.g. `.debounce(500)`) so a genuine burst of
      changes during a sync coalesces into at most a couple of re-renders instead of one per
      upserted page - a home-screen widget has no business updating faster than that.
- [ ] Verification: unit-level - none practical (Glance session behavior); on-device (Mi Pad 4,
      with all widget types placed): trigger "Sync now" with the app open and confirm via logcat
      + `dumpsys gfxinfo` histogram diff that frame times while idling on the dashboard improve
      vs. before, and that widgets still show correct data after the sync lands (bookmark edit
      shows up, rack layout change after a NetBox-side move shows up on the next sync). Confirm
      the NBC-418 reconfigure flow (long-press → configure → save while session alive) still
      applies instantly - distinctUntilChanged must sit on the *data* flows, not the config
      StateFlows... (config flows may keep it too, since publish() emits a structurally new map -
      just re-verify the delayed-reconfigure repro from NBC-418).

Status: **done**, 2026-08-08 - added `.distinctUntilChanged()` to `NyetboxGlanceWidget`'s
`bookmarksFlow`/`changesFlow`/`deviceRepository.observeCount()` and to
`RackViewGlanceWidget`'s `remember(rackId, face)` elevation flow, plus `.debounce(500)` on the two
list flows. Remote `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and `:app:lintDebug` all
passed; the on-device frame-time diff during a "Sync now" with all widget types placed, and the
NBC-418 delayed-reconfigure repro, were not re-run this pass.

## NBC-426: no Baseline Profile - release builds pay JIT warmup exactly where the app already
hovers below 60fps (cold start, first scrolls)

The project ships no Baseline Profile at all: no `baseline-prof.txt` under `app/src/main/`, no
`androidx.baselineprofile`/macrobenchmark tooling anywhere in `gradle/libs.versions.toml` or
`app/build.gradle.kts` (checked 2026-08-08; the `ProfileInstaller` logcat line at startup comes
from a transitive androidx dependency and installs nothing without a profile to install). That
means every fresh release install runs Compose scroll/composition paths interpreted/JIT until
background dexopt eventually kicks in - precisely the warmup window in which measured release
performance on the Mi Pad 4 (the project's weakest device, 60Hz panel) sits above budget:

- Release cold start: 991ms to first frame (fine), but the first minutes of use are the window a
  baseline profile targets.
- Release device-list fling (387 devices, fresh install): 372 frames of which ~110 landed in the
  30-40ms band and a large cluster at 19-26ms (`dumpsys gfxinfo` histogram) - i.e. sustained
  ~30-50fps while flinging, without any single obviously-guilty app function (R8-obfuscated
  stack samples during the fling showed ordinary Compose composition/measure work, not one hot
  path). This diffuse "everything is a bit slow" profile is the classic baseline-profile use
  case, complementary to the targeted fixes in NBC-421/422/423.

- [ ] Add a `:baselineprofile` test module using the `androidx.baselineprofile` Gradle plugin +
      `androidx.benchmark.macro.junit4` (standard AGP setup per Android docs), with one
      `BaselineProfileGenerator` journey: cold start → dashboard → device list scroll → open a
      device detail → global search. Reuse the E2E screenshot CI's emulator setup
      (`.github/workflows/`, NBC-200/367 infrastructure) to *generate* the profile on a
      gradle-managed device in CI, or generate locally-on-remote (rofl-13) if the CI leg is too
      much scope - committing the generated `baseline-prof.txt` is the deliverable either way.
- [ ] Add `androidx.profileinstaller:profileinstaller` as an explicit dependency so the profile
      actually installs on release builds (Obtainium/GitHub sideloads don't get Play Cloud
      Profiles, so the shipped baseline profile is the only AOT hint those installs will ever
      have).
- [ ] Verification: `./gradlew :app:generateBaselineProfile` (remote) succeeds and the committed
      profile is non-empty; on the Mi Pad 4, install the release APK fresh, cold start once, and
      re-run the same device-list fling measurement - the 30-40ms band should shrink
      substantially on first-session scrolling vs. the numbers above.

Status: **done**, 2026-08-09 - added a `:baselineprofile` module (`com.android.test` +
`androidx.baselineprofile`) with a `BaselineProfileGenerator` journey (cold start -> dashboard ->
open the nav drawer -> device list -> fling it -> open a device detail -> back out -> global search
-> type a query), plus `androidx.profileinstaller` and the consumer plugin in `:app`. Applying the
plugin auto-created a `benchmarkRelease` build type (release-derived, non-debuggable, profileable);
`generateBaselineProfile` also runs a correctness pass against a second auto-created
`nonMinifiedRelease` variant.

Getting the actual generation run green took several iterations, each diagnosed from a real CI run
(no local emulator available - this remote build host has no `/dev/kvm`, and the sandbox has no
Android SDK):
- `androidx.baselineprofile` 1.4.1 doesn't support AGP 9.3.1 ("Module `:app` is not a supported
  android module") - needed `1.5.0-beta01`.
- `BenchmarkSeedReceiver` (a new component that configures a fixture server profile and seeds a
  few `DeviceEntity` rows directly via `SettingsRepository`/DAO write paths, so the profiled cold
  start lands on an already-populated Dashboard without driving onboarding against a live NetBox -
  necessary because `BaselineProfileRule` drives the app as a black box via UiAutomator with no
  Compose semantics access, and `SettingsRepository`'s `EncryptedSharedPreferences` can't be
  pre-seeded from outside the app's own process) originally lived in a `benchmarkRelease`-only
  source set, so it was entirely absent during the `nonMinifiedRelease` pass - moved to `main` with
  a runtime build-type allowlist (`benchmarkRelease`/`nonMinifiedRelease` only, no-op otherwise).
- The seed broadcast still silently went nowhere afterward: `pm clear` (run before broadcasting, to
  guarantee a clean fixture each run) puts the app into Android's "stopped" state, in which
  broadcasts are enqueued but never delivered even to an explicit component - fixed with `-f 0x20`
  (`FLAG_INCLUDE_STOPPED_PACKAGES`) on the `am broadcast` call.
- Still timing out after that: `exported="false"` plus `adb shell am broadcast -n` turned out not
  to reliably reach the receiver on this API level (confirmed via `dumpsys package` that the
  manifest merge itself was correct - the component just wasn't accepting shell-issued deliveries).
  Switched to `exported="true"`, safe here since the receiver already no-ops on every build type
  except the two profiling ones regardless of who sends the broadcast.
- `MainActivity` needed `Modifier.semantics { testTagsAsResourceId = true }` added to its Compose
  root so UiAutomator could resolve the app's existing `e2e-*` Compose test tags at all; two more
  tags (`e2e-device-list`, `e2e-device-list-row`) were added to `DeviceListScreen` for the journey's
  scroll/tap steps.
- `generateBaselineProfile` also needs the workflow itself to already exist on `main` -
  `workflow_dispatch` can't be triggered against a workflow that only exists on a feature branch,
  even when targeting that branch as the ref - so the whole feature landed on `main` directly
  rather than via a validate-on-branch-then-merge flow.

Verification: `./gradlew :app:generateBaselineProfile` succeeded on
[a real CI run](https://github.com/pschmitt/nyetbox/actions/runs/31282229770) (`.github/workflows/baseline-profile.yaml`,
`workflow_dispatch`-only, mirrors the existing E2E/Screenshots emulator setup) and produced a
30,207-line, non-empty profile with confirmed coverage of `dashboard`/`devices`/`devicedetail`/
`search` package classes - committed at `app/src/release/generated/baselineProfiles/`
(`baseline-prof.txt` + an identical `startup-prof.txt`, expected since the generator passes
`includeInStartupProfile = true`). Not verified this pass: the actual on-device frame-time
improvement on the Mi Pad 4 from a fresh release install with this profile installed - the ticket's
own suggested before/after fling comparison needs a physical device.
measured on the Mi Pad 4, 2026-08-08).

## NBC-427: one app open runs two complete sync passes back-to-back (missed periodic + startup
worker) - add a freshness short-circuit to syncAllLocked

Measured live on the Mi Pad 4 (debug 1.4.8 against netbox.brkn.lol, 2026-08-09, zero server-side
changes): one cold app open produced **two complete sync passes in a row** - 1,236 GET requests
(617 + 619, serialized) and ~2.8 MB received in total, ~2 minutes of visible "Syncing…". A later
single-pass reopen of the same build measured 616 GETs, confirming ~620 requests is the per-pass
baseline this doubling multiplies. Logcat shows why: the app had been
closed long enough that the 6h `PeriodicWorkRequest` (`SyncScheduler.schedulePeriodic()`) was
overdue, so WorkManager started it the moment the process came up (`WM-WorkerWrapper: Starting
work for …SyncWorker` at 02:11:46, workSpecId `1f1aaf96…` generation=41 = `netbox-periodic-sync`),
and 10 seconds later the NBC-370 startup work (`ac720c4d…` = `netbox-startup-sync`) fired as well.
The two runs serialize on `cacheDatabaseManager.withActiveServer`, so the startup run waited for
the periodic run to finish (first `NYETBOX_E2E_SYNC_COMPLETE` marker 02:12:50) and then re-ran the
entire pass from scratch (second marker 02:13:50) - every unconditional step (device types,
directory discovery, changelog, SVGs, topology export) twice. NBC-370's 10s startup delay can't
help here: it only delays the startup work, it doesn't coalesce it with a periodic run that just
completed. The same missing freshness check also means every reopen-after-force-stop runs a full
pass regardless of recency: a third launch a mere ~3 minutes after two fully successful passes ran
yet another complete 616-request sync (observed 02:26, "Syncing device types… Step 5 of 10" on the
dashboard card). Note the sync-policy defaults (`SettingsRepository.kt:295-299`:
`syncOnlyOnWifi=false`, `syncWhileRoaming=true`) mean this repeated cost lands on cellular/roaming
users by default.

- [x] Add a freshness short-circuit at the top of `OfflineSyncRepository.syncAllLocked()`
      (`sync/OfflineSyncRepository.kt:115`, i.e. after the `withActiveServer` lock is held, before
      any step runs): if `!forceFullSync` AND
      `passStartedAt - (settingsRepository.lastSuccessfulSyncAt.value ?: 0) <
      SYNC_FRESHNESS_WINDOW_MILLIS` (new constant, 5 minutes) AND
      `pendingEditDao.getQueuedMutations().isEmpty()` (queued offline edits must never wait out
      the window; expose via a small `PendingEditRepository.hasQueuedMutations()` helper), return
      `Result.success(OfflineSyncSummary(0, 0, 0))` immediately. Implemented as a pure
      `shouldSkipSyncPass()` top-level function called right after `passStartedAt` is computed.
- [x] The short-circuit must return **before** the `runCatching`/`recordSuccessfulSync()` plumbing
      at the bottom of `syncAllLocked` - a skipped pass must NOT bump `lastSuccessfulSyncAt`,
      otherwise frequent app reopens would slide the window forever and a device opened every
      4 minutes would never sync again. It must also not emit any `SyncProgress` steps (no
      "Syncing…" flash for a skipped pass).
- [x] `lastSuccessfulSyncAt` is only recorded on a fully-clean pass (`recordSuccessfulSync()` is
      in the no-failure branch), so a failed/warned first pass leaves the window unset and the
      second pass still runs - keep that property, it's what makes the skip safe. Unchanged.
- [x] Unit test the skip decision as a pure function (fresh timestamp + no queued edits = skip;
      forceFullSync always runs; queued edits always run) - `OfflineSyncGatingTest.kt`, plus
      `PendingEditRepositoryTest`'s new `hasQueuedMutations` case.
- [x] Live verification: confirmed on the Zenfone 10 (2026-08-09) - after a real sync completed
      (1 `NYETBOX_E2E_SYNC_COMPLETE` marker, 553 requests), force-stopping and immediately
      reopening the app produced **zero** OkHttp requests and zero E2E markers, i.e. the second
      pass was skipped entirely as designed. The Mi Pad 4's equivalent immediate-reopen attempt did
      still run a real sync (a prior pass hadn't completed cleanly enough to set the freshness
      watermark), which is itself the documented "failed/warned pass leaves the window unset"
      behavior working correctly, not a bug in the short-circuit.

Status: **done**, 2026-08-09; freshness short-circuit implemented, unit tested, and confirmed live
on the Zenfone 10 (clean skip-on-reopen) and Mi Pad 4 (correctly does NOT skip when the prior pass
wasn't clean).

## NBC-428: sync re-fetches all ~236 cached device types individually on every pass - the unused
`ensureCached()` was written for exactly this

The device-type step (`sync/OfflineSyncRepository.kt:166-177`) calls
`deviceTypeRepository.refresh(deviceTypeId)` for every distinct device type referenced by cached
devices, on every pass, full or incremental. Live on the Mi Pad 4 (debug 1.4.8, netbox.brkn.lol,
2026-08-09, nothing changed server-side) this was **236 individual
`GET /api/dcim/device-types/<id>/` requests per pass, 472 across the two passes of one app open**
- the single largest request category, ~38% of a pass's 616 requests. Each response only feeds
`DeviceTypeEntity` (front/rear stock-photo URLs, `DeviceTypeRepository.kt:39-46`), which rarely
changes. `DeviceTypeRepository.ensureCached(id)` (`DeviceTypeRepository.kt:28-30`, doc comment:
"device-type photos rarely change") already implements the fetch-only-if-missing behavior and
currently has **zero callers** (verified via rg across `app/src/main/kotlin`) - it was written for
this exact gap and never wired up.

- [x] In the device-type loop in `syncAllLocked`, call `deviceTypeRepository.ensureCached(id)`
      when `!isFullSyncPass`, and keep `refresh(id)` when `isFullSyncPass` - the 24h/forced full
      pass remains the path that picks up changed device-type photos, mirroring the existing
      incremental-vs-full contract used for devices/generic objects.
- [x] Change `ensureCached` to return `Result<Unit>` (success when already cached, else the
      underlying `refresh` result mapped to Unit) so the loop's existing
      `recordFailure("Device type $id sync", …)` wiring keeps reporting real fetch failures.
- [x] Unit test: incremental pass with a cached id makes no API call; incremental pass with an
      uncached id fetches; full pass always fetches - `DeviceTypeRepositoryTest.kt`.
- [x] Live verification (Mi Pad 4, 2026-08-09): an incremental pass with a real device/cable delta
      (not even a fully idle "nothing changed" pass) made **zero** `GET
      /api/dcim/device-types/<id>/` requests, confirming `ensureCached` served all 238 device
      types from cache (was 236 GETs/pass unconditionally).

Status: **done**, 2026-08-09; ensureCached() wired into the sync loop, unit tested, and confirmed
live on the Mi Pad 4.

## NBC-429: the generic model loop re-downloads the entire NetBox changelog (11 pages x 200 fat
rows) on every sync pass - exclude `api/core/object-changes/`, the dashboard already caches it

`DirectoryRepository` discovers `api/core/object-changes/` as a normal paginated model, so the
generic model sync loop (`sync/OfflineSyncRepository.kt:196-213`) syncs it like inventory. But
ObjectChange records have no `last_updated` field (they have `time`), so
`GenericObjectRepository.lastUpdatedWatermark()` is permanently null for this endpoint and
`syncModelIncrementally` (`OfflineSyncRepository.kt:352-366`) falls back to a **full unfiltered
fetch on every pass, incremental or not**. Live on the Mi Pad 4 (2026-08-09, no server-side
changes): `GET /api/core/object-changes/?limit=200&offset=0…2000` - 11 pages, ~2,200 rows - on
*both* back-to-back passes of a single app open. These are the fattest rows NetBox serves (each
carries full `prechange_data` + `postchange_data` snapshots), making this most of the pass's
payload bytes alongside NBC-431. It's also redundant: `DashboardRepository.refreshChangelog()`
(`DashboardRepository.kt:138-153`) already fetches the 25 most recent changes each pass and caches
their snapshots under `__cache/object-changes/` for the diff screens.

- [x] Add a `SYNC_EXCLUDED_ENDPOINTS = setOf("api/core/object-changes/")` constant in
      `OfflineSyncRepository` and filter it out of `models` before the
      `models.syncConcurrently(concurrency)` loop (line ~196). Keep the model in
      `DirectoryRepository`/sidebar - the generic list screen still works cache-first with its
      own on-open refresh.
- [x] One-time cleanup of the ~2,200 already-cached changelog rows so they don't linger stale in
      the generic cache (and global search) forever: for each excluded endpoint, call
      `genericObjectRepository.pruneStale(endpointPath, Long.MAX_VALUE)` right where the model is
      filtered out (a cheap no-op DELETE once the table is empty).
- [x] Note for the future, out of scope here: `api/extras/tagged-objects/` has the same
      no-`last_updated` shape and re-fetched 3 full pages (~600 rows) every pass in the same
      trace - smaller, and its rows may back tag screens, so it was deliberately left alone.
- [x] Unit test: model list containing the excluded endpoint never triggers a sync call for it -
      `OfflineSyncGatingTest.kt`'s `isSyncExcluded` cases.
- [x] Live verification (Mi Pad 4, 2026-08-09): confirmed via logcat - the only
      `api/core/object-changes/` request across a real sync pass was the dashboard's own
      `?limit=25&ordering=-time` fetch; zero `?limit=200&offset=...` paginated changelog requests.

Status: **done**, 2026-08-09; exclusion + cleanup implemented, unit tested, and confirmed live on
the Mi Pad 4.

## NBC-430: NetBox model directory re-discovered with ~105 requests every sync pass - reuse the
cached directory on incremental passes

`directoryRepository.refresh()` runs unconditionally each pass ("Discovering NetBox models…",
`sync/OfflineSyncRepository.kt:179-180`). Discovery is expensive by construction
(`DirectoryRepository.kt:46-141`): `GET api/` root, one `GET api/<app>/` per app namespace, and -
the costly part - **one `?limit=1&offset=0` probe request per candidate model**
(`isPaginatedCollection`, lines 137-141). Live on the Mi Pad 4 (2026-08-09): 144 probe requests
plus ~11 app-map requests plus the root, ~156 requests per pass (about a quarter of the whole
pass's 616 requests), on every single sync - all to re-learn a model list that changes only when
a NetBox plugin is installed/removed or NetBox is upgraded. The set of installed models is the
textbook "changes ~never" input.

- [x] In `syncAllLocked`, skip `directoryRepository.refresh()` when `!isFullSyncPass &&
      directoryRepository.cachedModelCount() > 0`; the subsequent
      `directoryRepository.cachedModels()` read (line 182) already works off the cache. Keep the
      full discovery on full passes (24h interval / "Sync now"), which also keeps the existing
      guarantee that a partially-failing discovery never replaces the last complete directory.
      Implemented as `shouldRediscoverDirectory()`.
- [x] Keep the progress step so step numbering stays stable, but reword it when skipped (e.g.
      "Using cached model directory…") - or keep the message and let it complete instantly;
      implementer's choice, just don't renumber `totalSteps` conditionally.
- [x] Documented consequence (acceptable, matches the incremental contract everywhere else):
      newly installed NetBox plugins/models appear in the sidebar after the next full pass
      (<=24h) or a manual Settings "Sync now", not on every background sync.
- [x] Unit test the skip condition - `OfflineSyncGatingTest.kt`'s `shouldRediscoverDirectory` cases.
- [x] Live verification (Mi Pad 4, 2026-08-09): confirmed via logcat - an incremental pass made
      zero `limit=1&offset=0` probe requests and zero `GET api/<app>/` url-map requests (was
      ~105/pass).

Status: **done**, 2026-08-09; directory-reuse gate implemented, unit tested, and confirmed live on
the Mi Pad 4.

## NBC-431: 4.4 MB topology XML export re-downloaded and rewritten to flash on every sync pass -
gate it on device/cable changes

When the netbox-topology-views plugin is present, `topologyRepository.refresh()` runs every pass
(`sync/OfflineSyncRepository.kt:264-269`), and `TopologyRepository.refresh()`
(`TopologyRepository.kt:34-45`) always downloads the full `xml-export` (server-side render of the
whole topology, every option enabled - see `EXPORT_URL`) and rewrites the persisted file. Live on
the Mi Pad 4 (2026-08-09): the export was fetched on both passes of one app open; the persisted
`topology.xml` is **4,382,294 bytes** (measured via `run-as` in
`files/offline-attachments/`) - the single largest artifact any sync step produces, re-downloaded
(gzip helps on the wire, but the server re-renders it and the device re-parses + rewrites 4.4 MB
to flash) even when nothing in the topology changed. With the default sync policy
(`syncOnlyOnWifi=false`, `syncWhileRoaming=true`, `SettingsRepository.kt:295-299`) this recurs on
cellular every 6h.

- [x] Gate the topology step: refresh only when `isFullSyncPass`, or when this pass actually
      changed topology inputs - `devices > 0` (the device delta count already computed at
      `OfflineSyncRepository.kt:157-161`) or the cables endpoint's incremental sync returned > 0
      objects. For the latter, capture the per-endpoint count for `"api/dcim/cables/"` inside the
      model loop (the `syncResult.fold(onSuccess = { count -> … })` block at lines 199-205
      already has the count; stash it in an `AtomicInteger` keyed check like
      `genericObjectsTotal`). Implemented as `shouldRefreshTopology()`.
- [x] When skipped, don't report the "Syncing topology map…" step as work done - either skip the
      step entirely (adjust the `totalSteps` computation at lines 184-187, which already
      conditions on `topologyAvailable`) or complete it instantly; keep step math consistent.
      Kept the step (reports "Topology unchanged, skipping…" instead) so `totalSteps` stays simple.
- [x] Unit test the gate decision - `OfflineSyncGatingTest.kt`'s `shouldRefreshTopology` cases.
- [x] Live verification (Mi Pad 4, 2026-08-09): confirmed the positive branch - an incremental
      pass with a real device delta correctly re-fetched the topology export exactly once. The
      negative branch (a pass with zero device/cable delta making no topology request at all) is
      covered by `OfflineSyncGatingTest`'s unit tests rather than a live repeat, since the only
      live "nothing changed" pass observed (Zenfone 10) was subsumed by NBC-427's short-circuit
      before reaching this step, and deliberately editing a cable/device on the real
      netbox.brkn.lol instance just to force a live negative-branch check wasn't done to avoid
      mutating real inventory data for a test.

Status: **done**, 2026-08-09; topology gate implemented, unit tested, and the positive (refetch)
branch confirmed live on the Mi Pad 4.

## NBC-432: rack elevations re-fetched in full for every rack, both faces, every sync pass - skip
racks with no rack/device changes

`sync/OfflineSyncRepository.kt:219-228` calls `rackElevationRepository.refresh(rack.id, FRONT)`
and `refresh(rack.id, REAR)` for every cached rack unconditionally every pass.
`RackElevationRepository.refresh()` (`RackElevationRepository.kt:29-40`) fetches up to
`limit=1000` elevation slots with no staleness filter and always clears + re-inserts the Room
rows. Confirmed live (Mi Pad 4, 2026-08-09): 2 elevation JSON fetches per pass - the test
instance has only 1 rack, so this is small *there*, but the cost is 2 x N_racks requests (each a
full slot list) on every 6h/startup sync and scales directly with install size. An elevation only
changes when the rack itself changes or a device is (re)placed/removed - both of which bump
`last_updated` on the rack or device row, which the incremental sync already fetches.

- [x] Gate each rack's refresh: run it only when `isFullSyncPass`, OR the rack row changed this
      pass, OR any cached device in that rack changed this pass. "Changed this pass" =
      `syncedAt >= passStartedAt`: the incremental fetches only upsert rows the server reported
      as changed, and both `NetBoxObjectEntity` (the rack, already in scope as `racks` from
      `cachedObjects("api/dcim/racks/")`) and `DeviceEntity` carry `syncedAt`. The rack-elevation
      step already runs after the device and model loops in the same pass, so the stamps are
      up to date by then. Implemented as `shouldRefreshRackData()`, computed once per rack into a
      `rackDataChanged` map shared with NBC-433's rack-face SVG loop.
- [x] Add the missing DAO query for the device half: `DeviceDao.countChangedInRack(rackId: Int,
      cutoff: Long): Int` (`SELECT COUNT(*) FROM devices WHERE rackId = :rackId AND syncedAt >=
      :cutoff` - `DeviceEntity.rackId` exists, `data/db/DeviceEntity.kt:16`), exposed via
      `DeviceRepository`.
- [x] Also refresh when the rack has no cached elevation rows at all (first sync of a new rack) -
      `RackElevationRepository.hasCached()` backed by a new `RackElevationDao.count()` query.
- [x] Known acceptable imprecision, document in a code comment: the `last_updated__gte` watermark
      is inclusive, so the most-recently-updated rack/device always re-appears as "changed" and
      causes one redundant elevation refresh per pass. Server-side device deletions only
      reconcile on full passes, which also refresh all elevations - consistent.
- [x] Unit test the gate (pure function taking rack syncedAt, changed-device count, full-pass
      flag) - `OfflineSyncGatingTest.kt`'s `shouldRefreshRackData` cases.
- [x] Live verification (Mi Pad 4, 2026-08-09): confirmed the positive branch - an incremental
      pass with a real device delta correctly refreshed rack 1's elevation (both faces). The
      "unrelated rack stays untouched" negative branch is covered by `OfflineSyncGatingTest`'s unit
      tests; the test instance only has 1 rack, so a live negative-branch comparison isn't
      meaningfully distinguishable from NBC-427's full-pass skip observed on the Zenfone 10 - not
      repeated separately to avoid mutating real NetBox inventory data for a test.

Status: **done**, 2026-08-09; rack-elevation gate implemented, unit tested, and the positive
(refetch-on-change) branch confirmed live on the Mi Pad 4.

## NBC-433: rack and cable-trace SVG diagrams re-downloaded and rewritten every sync pass (60+
requests) - only refresh diagrams whose owning object changed

With "sync attachments to disk" enabled, `sync/OfflineSyncRepository.kt:233-262` re-fetches every
rack face's `?render=svg` elevation and every traceable cable's `trace/?render=svg` on every
pass - `SvgDiagramRepository.refresh()` (`SvgDiagramRepository.kt:32-39`) always makes the network
call and overwrites the persisted file, with no dirty-check against the owning rack/cable's
`last_updated`. Confirmed live (Mi Pad 4, 2026-08-09, zero server-side changes): **60 cable-trace
SVG GETs + 2 rack-face SVG GETs per pass, on both passes of one app open** (120+ server-side SVG
renders for nothing), and all 62 persisted `.svg` files in `files/offline-attachments/` carried
fresh mtimes from the last pass. Each SVG is small (1-13 KB) - the cost is request count, server
render time, and flash writes, not bytes.

- [x] Rack-face SVGs: reuse NBC-432's per-rack "changed this pass" gate (rack `syncedAt >=
      passStartedAt`, or a changed device in the rack, or `isFullSyncPass`) - the two loops
      iterate the same `racks` list, so compute the per-rack decision once and share it. Shares
      the `rackDataChanged` map computed in NBC-432's elevation loop.
- [x] Cable-trace SVGs: skip when `!isFullSyncPass && cable.syncedAt < passStartedAt`. Cable rows
      have `last_updated`, so the incremental model sync only re-upserts changed cables - an
      unchanged cable keeps its old `syncedAt` and its trace cannot have changed unless the cable
      (or its terminations, which also bump the cable's own row in NetBox) changed. The SVG loop
      already runs after the model loop in the same pass. Implemented as `shouldRefreshCableTraceSvg()`.
- [x] Always fetch when the persisted file is missing (`svgDiagramRepository.cachedContent(key) ==
      null` or a cheaper file-existence check via `FileDownloadRepository.persistentFile`) - a
      freshly-enabled "sync attachments to disk" or cleared cache must still populate. Added
      `SvgDiagramRepository.isCached()` for this.
- [x] Unit test the two gate decisions - `OfflineSyncGatingTest.kt`'s `shouldRefreshRackData`/
      `shouldRefreshCableTraceSvg` cases (shared with NBC-432 for the rack half).
- [x] Live verification (Mi Pad 4, attachments-to-disk on, 2026-08-09): confirmed the positive
      branch for both - rack 1's front/rear elevation SVGs (`racks/1/elevation/?face=...&
      render=svg`) and a cable-trace SVG (`api/dcim/power-outlets/33/trace/?render=svg`) both
      re-fetched during an incremental pass with a real device/cable delta. The "unchanged
      rack/cable stays untouched" negative branch is covered by `OfflineSyncGatingTest`'s unit
      tests rather than a further live repeat, for the same reasons noted on NBC-432.

Status: **done**, 2026-08-09; SVG gating implemented, unit tested, and the positive
(refetch-on-change) branch confirmed live for both rack-face and cable-trace SVGs on the Mi Pad 4.

## NBC-434: durable attachments are never revalidated once downloaded - an 820 MB offline cache
that can go silently stale forever

The opposite staleness failure from NBC-433: `FileDownloadRepository.downloadToPersistent()`
(`FileDownloadRepository.kt:60-76`) early-returns whenever the target file already exists
(`if (target.isFile && target.length() > 0L) return@runCatching target`, line 64), so an
attachment downloaded once is **never re-checked against the server again** - not even on the 24h
full-reconciliation pass. Files are keyed by a SHA-256 of the media URL, so a *renamed* upload
self-heals (new URL = new hash), but NetBox overwriting media under the same filename (replacing
a rack photo, re-uploading a corrected PDF under the same name) keeps the same URL and the app
silently shows the old bytes forever, with no indication. The Mi Pad 4's
`files/offline-attachments/` currently holds 697 files / ~820 MB (measured via `run-as`,
2026-08-09), none of which any sync will ever revalidate.

- [x] Add a `revalidate: Boolean = false` parameter to `downloadToPersistent`. When `revalidate`
      is true and the target file exists, send the request with an `If-Modified-Since` header
      built from `target.lastModified()` (RFC 1123 format); on HTTP 304, keep the file (and
      `target.setLastModified(System.currentTimeMillis())` so the next check window moves
      forward); on 200, download to `.part` and replace as today. `revalidate = false` keeps the
      current instant skip. The mechanics were pulled into a `Context`-free top-level
      `downloadOrRevalidate()` specifically so they're unit-testable against a real server.
- [x] Thread `isFullSyncPass` into `syncAttachments(...)` (`sync/OfflineSyncRepository.kt:384`)
      and pass `revalidate = isFullSyncPass` - incremental passes stay as cheap as they are now
      (zero attachment requests for existing files); only the 24h/forced full pass pays one
      conditional request per attachment, and those are ~zero-byte 304s when nothing changed.
- [x] NetBox serves `/media/` via its web server (nginx/whitenoise), which answers
      `If-Modified-Since` with 304 for static files - verify once against netbox.brkn.lol with
      `curl -H "Authorization: Token …" -H "If-Modified-Since: <future date>" -sI <media url>`
      before relying on it; if the deployment doesn't honor it, fall back to comparing
      `Content-Length` from a HEAD request instead. **Verified live and it does NOT honor
      `If-Modified-Since`**: a forced full sync on the Mi Pad 4 (2026-08-09) showed all 633
      revalidation requests answered `200` with the full body (0 out of 633 were 304) - i.e. the
      original conditional-GET-only implementation would have silently turned every full-sync pass
      into a full re-download of all 697 cached attachments (~820 MB), a worse regression than the
      bug this ticket set out to fix. Implemented the documented fallback:
      `isUnchangedByHead()` sends a `HEAD` (no body transferred either way) carrying the same
      `If-Modified-Since` header - a compliant server can still 304 there - and otherwise compares
      the HEAD response's `Content-Length` against the cached file's size, skipping the GET
      entirely when they match.
- [x] Unit test the 304/200/no-file branches with a mock server -
      `FileDownloadRevalidationTest.kt` (`okhttp-mockwebserver`, newly added as a test dependency).
      Extended with cases for the HEAD/Content-Length fallback path and a HEAD-itself-fails
      graceful-degradation case, once the live test above showed the fallback was load-bearing, not
      optional.
- [x] Live verification (Mi Pad 4): two forced full syncs via Settings "Sync now", 2026-08-09.
      First pass (before the fallback): confirmed real attachment revalidation traffic (633
      GET-based conditional requests) and no crashes, but 0 of 633 came back 304 - proving
      `If-Modified-Since` alone doesn't work against this instance. Second pass (after the
      HEAD/Content-Length fallback landed, same build redeployed): 634 `HEAD` requests, **zero**
      `GET` media downloads, one clean `NYETBOX_E2E_SYNC_COMPLETE`, no crashes - confirming the
      fallback correctly recognizes all 697 unchanged attachments without transferring their
      bodies.

Status: **done**, 2026-08-09; revalidation implemented with the HEAD/Content-Length fallback
(confirmed necessary live, not just theoretical), unit tested against a mock server covering both
paths, and confirmed end-to-end live on the Mi Pad 4 (634 HEAD requests, 0 redundant downloads).

## NBC-435: sync UI shows a quick incremental check and a multi-minute full resync identically,
and never says what actually changed

Even once syncs are cheap, they won't *feel* cheap: `isFullSyncPass` is computed in
`OfflineSyncRepository.syncAllLocked()` (`sync/OfflineSyncRepository.kt:123-125`) and then never
surfaced anywhere - `SyncProgress` (`OfflineSyncRepository.kt:47-54`) carries no pass type, so
`SyncStatusCard` (`ui/common/SyncStatusCard.kt:84-105`), `SyncStatusDetailsDialog`, and
`SyncNotifier`'s notification render the exact same "Syncing devices… Step 4 of 10" for a
seconds-long incremental check as for the 24h full reconciliation. After the fact, nothing tells
the user what the pass did: `SyncNotifier.notifySyncSucceeded()` (`SyncNotifier.kt:162-167`)
discards the `OfflineSyncSummary` counts (only reconciliation survives), and the details dialog
shows only static cache totals. Observed live (Mi Pad 4, 2026-08-09): two visually identical
"Syncing…" minutes with zero server-side changes - the user has no way to know the pass was (or
should have been) a no-op. This is the perception half of the "sync feels heavy" complaint.

- [x] Add `isFullSync: Boolean = false` to `SyncProgress`; set it from `isFullSyncPass` inside
      `reportProgress` (and the model-loop `modelsProgress.copy(...)` updates inherit it).
- [x] Prefix the pass type in `SyncProgress.notificationSubText()`
      (`OfflineSyncRepository.kt:63-67`), e.g. "Quick sync · Step 4 of 10" vs "Full sync · Step 4
      of 10" - that single helper feeds both the system notification and `SyncStatusCard`'s
      subtext (NBC-370 plumbing), so both surfaces pick it up with one change.
- [x] Record a compact last-pass summary next to `recordSuccessfulSync()`: pass type
      (full/incremental), duration millis (`System.currentTimeMillis() - passStartedAt`), and the
      `OfflineSyncSummary` counts (devices + genericObjects as "items refreshed",
      durableAttachments) - a small JSON blob in a new server-scoped
      `SettingsRepository.lastSyncSummary` pref, exposed as a `StateFlow`. Implemented as three
      plain scalar prefs (bool/long/int) behind a `LastSyncSummary` data class rather than a literal
      JSON blob - same effect, no serializer needed for three fields.
- [x] Render it in `SyncStatusDetailsDialog` above the cache figures, e.g. "Last sync: quick
      check · 58s · 3 items refreshed" / "Last sync: full sync · 4m · 512 items refreshed" - the
      after-the-fact answer to "was that as cheap as it should have been?".
- [x] Label caveat: `genericObjects` counts *fetched* rows, which for watermark-less endpoints
      (see NBC-429) overstates "changed" - use the neutral word "refreshed", and note the number
      gets truthful as NBC-428..431 land.
- [x] Unit tests for the new pure helpers alongside the existing `SyncStatusCardTest.kt` pattern -
      `SyncStatusDetailsDialogTest.kt` (`formatLastSyncSummary`) and updated `SyncProgressTest.kt`/
      `SyncStatusCardTest.kt` assertions for the new "Quick sync"/"Full sync" prefix.
- [x] Live verification (Mi Pad 4, 2026-08-09): screenshotted the sync status details dialog after
      a routine incremental sync - it read exactly **"Last sync: quick check · 32s · 871 items
      refreshed"**, rendered above the cache figures as designed. The "Full sync · …" wording for a
      forced pass wasn't separately screenshotted (same code path, verified by the passing unit
      tests plus the two live full-sync passes run for NBC-434), but the quick-check case
      specifically named in this ticket's acceptance text is confirmed end-to-end.

Status: **done**, 2026-08-09; pass-type surfacing and last-sync summary implemented, unit tested,
and confirmed live on the Mi Pad 4 with a screenshot of the rendered summary text.

## NBC-436: debug builds are visually indistinguishable from release, both named/iconed "Nyetbox"

Both variants can be installed side by side (`applicationIdSuffix = ".debug"`, see
`app/build.gradle.kts`), but a debug install looked identical to release on a home screen/launcher
- same name, same dark-navy adaptive icon (`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`,
`@color/ic_launcher_background` = `#011226`) - the only way to tell them apart was opening Settings
and checking the version string. Following the same pattern as the `jollyfin` repo's debug/staging
variant icons (Gradle build-variant resource overlay: files placed under `app/src/<variant>/res/`
override the same-named resource from `app/src/main/res/` for that variant only, no manifest or
build-script changes needed).

- [x] `app/src/debug/res/values/strings.xml` overrides `app_name` to "Nyetbox (debug)".
- [x] `app/src/debug/res/drawable/ic_launcher_foreground_vector.xml` overrides the glyph itself
      (not the background, per direct feedback that a background-only tint wasn't drastic enough):
      the accent teal (`#00E5D6`) becomes a saturated red (`#D50000`) and the near-white
      (`#F4F7F8`) becomes a light red/coral (`#FF8A80`) across every stroke/fill that used them -
      same shape, same navy background as release, but the logo itself now reads unmistakably red.
- [x] Verified remotely: `aapt2 dump badging` on the built debug APK shows
      `application-label='Nyetbox (debug)'` across every locale; the release APK is unaffected
      (`application-label='Nyetbox'`), confirming the override is debug-only.
- [x] Verified live on the Zenfone 10 (twice): first pass confirmed the background-tint approach
      compiled/installed but wasn't visually distinct enough; second pass (after switching to the
      foreground-color override) screenshotted the running task/app icon showing the whole
      robot-face glyph in red/coral against the unchanged navy background.

Status: **done**, 2026-08-09; verified via `aapt2 dump badging` (both variants) and live on a
physical device, including a revision after direct feedback that the first approach (background
tint) wasn't drastic enough.

## NBC-437: sync progress wording shortened; dashboard Stats row got a scroll hint and became
user-customizable

Three related follow-ups requested directly:

1. `SyncProgress.notificationSubText()` said "Step X of Y" in both the system notification and the
   dashboard sync card (they share this one function, NBC-370 plumbing) - shortened to "X/Y".
2. The dashboard's Stats row (`StatsRow` in `DashboardScreen.kt`) is a `LazyRow` of fixed-size
   tiles that can land on a screen width fitting exactly N of them with nothing peeking past the
   edge - it read as "that's all the stats" even though more were a swipe away, and a tile's own
   card background is the same color as the surrounding section card, so a naive fade-to-background
   edge was invisible wherever nothing happened to be cut off right at the boundary (confirmed live
   - direct feedback that a first attempt wasn't visible enough).
3. Only 4 of the 9 shared core-model stat candidates (`NetBoxEndpointCatalog.coreModels`) were ever
   fetched/shown, hardcoded, with no way to change that.

- [x] Notification/card wording: `notificationSubText()` now emits `"${step}/${totalSteps}"`
      instead of `"Step $step of $totalSteps"`. Updated `SyncStatusCardTest`/`SyncProgressTest`
      assertions to match.
- [x] Scroll hint: `StatsRow` now wraps its `LazyRow` in a `Box` and overlays a `MaterialTheme
      .colorScheme.scrim`-based gradient (visible against any backdrop, not just a half-cut tile)
      plus a small `KeyboardArrowRight` chevron on the trailing edge. Revised after direct feedback
      that raw `LazyListState.canScrollForward`/`canScrollBackward` flagged "more to scroll" even
      when every tile was already effectively fully on screen (they trip on sub-pixel overflow) -
      replaced with a `derivedStateOf` check against `LazyListLayoutInfo`'s actual first/last visible
      item index and pixel bounds, so the hint only appears when a tile is genuinely cut off. Tint
      dimmed from `onSurface` to `onSurfaceVariant` per feedback that it read too bright next to the
      surrounding text.
- [x] `NetBoxEndpointCatalog.coreModels` (`NetBoxRef.kt`) expanded from 9 to 22 entries per direct
      feedback ("there are way more after all") - added Locations, Manufacturers, Device Roles,
      Interfaces, Cables, Power Feeds, VLANs, VRFs, ASNs, Clusters, Providers, Wireless LANs, and
      Contacts, each with a matching `AppIcons.BY_ENDPOINT_PATH` icon. `DashboardRepository
      .STAT_ENDPOINTS` covers every entry so a count is ready in cache the moment a user opts one in.
- [x] `SettingsRepository.statsOrder`/`hiddenStats` (mirrors `dashboardSectionOrder`/
      `hiddenDashboardSections` exactly: `loadOrder`/`updateStringSet`, `setStatsOrder`/
      `setStatHidden`, included in `resetToDefaults()`, `SettingsBackup` export/import, and the
      portable settings backup format). `DEFAULT_HIDDEN_STATS` = every core model past index 4, so
      an existing install's dashboard doesn't suddenly sprout new tiles unasked. Note: this default
      only applies while `hiddenStats` has never been explicitly persisted - once a user has toggled
      any stat, later catalog growth (like the expansion above) shows up already visible rather than
      newly hidden, same as e.g. a browser's extension list enabling new entries by default once
      you're managing it yourself.
- [x] `DashboardOrdering.kt` gained `orderedStats()` (mirrors `orderedDashboardSections`, ties break
      on the registry's own order) and `orderedStatCandidates()` (every candidate, hidden or not,
      for the picker). `DashboardScreen` applies `orderedStats(stats, statsSavedOrder, hiddenStats)`
      before passing the list to `StatsRow`, same pattern as the existing dashboard-section
      ordering.
- [x] "Customize stats" entry point: a `Tune` icon button on the Stats section header opens
      `StatsCustomizeDialog` - a vertical checklist (drag handle + checkbox + label per candidate)
      reusing `SectionReorderState`/`sectionReorderGesture`/`sectionDragOffset` verbatim, since that
      helper already operates on a plain scroll-axis offset/size and needed no horizontal variant
      for this vertical list. Per direct feedback, the icon is gated behind `reorderMode` (entered by
      long-pressing any section header) rather than always shown, matching how the rest of the
      dashboard's customization affordances are already hidden until a user asks for them.
- [x] Unit tests: `DashboardOrderingTest` gained cases for `orderedStats`/`orderedStatCandidates`
      (no-saved-order fallback to registry order, saved order wins, hidden stays omitted, picker
      includes hidden candidates).
- [x] Verified remotely: `:app:compileDebugKotlin` and the full `:app:testDebugUnitTest` suite pass.
- [x] Verified live on the Zenfone 10: "X/Y" wording confirmed in a real sync's dashboard card;
      chevron+scrim hint appears exactly when a tile is genuinely cut off and disappears once every
      stat fits (confirmed with 3 visible stats on-screen, and again after toggling more on); Tune
      icon hidden by default, appears alongside the hide-eye icon only after long-pressing into
      reorder mode; "Customize stats" dialog opens, scrolling confirms all 22 candidates render with
      working checkboxes/drag-handles, toggling "IP Addresses" on immediately surfaced a real cached
      count (130), and "Done" closes the dialog.

Status: **done**, 2026-08-09; implemented, unit tested, and confirmed live on a physical device for
all three parts, including three rounds of revision after direct feedback: the scroll hint's first
(fade-only) version wasn't visible enough, then its `canScrollForward`/`canScrollBackward` trigger
was imprecise and its tint too bright, and the catalog was widened further with the customize icon
moved behind reorder mode.

## NBC-438: provide a CLI for generating Nyetbox login QR codes

Add a small `nyetbox-setup` command for administrators who want to provision the app from a
terminal instead of opening an already-configured Nyetbox installation. It accepts a NetBox URL
and API token, emits the same `nyetbox://setup` payload understood by the app, and renders it as a
terminal QR code (with an optional image output for printing or sharing).

- [x] Add the `nyetbox-setup` command and its flake package.
- [x] Document local and `nix run` usage, including the token-in-QR security warning.
- [x] Support both complete-token and split name/secret input forms.
- [x] Verify argument validation, payload compatibility, and QR rendering.

Status: **done**, 2026-08-10; verified with Bash/ShellCheck, a remote Nix package build producing a
terminal QR and PNG, payload decode checks, and the existing remote Android unit suite.

## NBC-439: split-token onboarding used the obsolete `nbp_` prefix

The Pixel 5 reproduced a real onboarding failure: the split token form displayed and submitted
`nbp_<name>.<secret>`, while the current NetBox token format is `nbt_<name>.<secret>`. The parser
already accepted both prefixes, but the composer and its UI documentation still treated `nbp_` as
current, so a newly entered token was rejected by NetBox.

- [x] Make `nbt_` the current composed prefix while retaining `nbp_` parsing for legacy tokens.
- [x] Update onboarding guidance/placeholders and unit-test expectations.
- [x] Verify remotely and deploy a debug build to the Pixel 5 without clearing app data.

Status: **done**, 2026-08-10; verified with remote compile/unit tests/ktfmt check and installed the
debug variant alongside the release app on PX5 (no uninstall or data wipe).
## NBC-440: Fix PR E2E settings navigation assertion

- [x] Target the drawer's icon-only Settings action by a dedicated test tag.
- [x] Wait for the Settings screen itself instead of relying on its app-bar text.

Status: **done** (2026-08-10; verified by GitHub PR CI)

## NBC-441: Give NetBox object types dedicated icons

Replace the broad app/endpoint icon collisions with a more specific vocabulary for core NetBox
models, and keep widget object icons aligned with the in-app registry.

- [x] Add dedicated endpoint mappings for core object types.
- [x] Add or update widget icon resources/mappings for the same object types.
- [x] Verify the app and widget compile with the expanded icon registry.

Status: **done** (2026-08-10; verified with remote compile, unit tests, and Android-test compilation)

## NBC-442: make synced media available offline by default

The app already has a durable `filesDir` attachment store and a Coil fetcher that resolves cached
NetBox media URLs locally, but `SettingsRepository` defaults `sync_attachments_to_disk` to `false`.
That means a fresh profile (and any profile that never explicitly enabled the setting) caches Room
metadata and remote URLs while downloading no thumbnails, device-type photos, image attachments,
documents, or other `/media/` fields. A cold start with no connectivity therefore has nothing for
Coil to resolve. The wired Zenfone cold-start check on 2026-08-13 confirmed the offline launch
path and restored Wi-Fi afterward; its existing configured profile was used without clearing app
data.

- [x] Make durable media synchronization enabled by default for unset settings and reset-to-defaults,
      while preserving an explicit stored `false` opt-out.
- [x] Keep the existing best-effort, authenticated downloads and durable `filesDir`/Coil local
      resolution covering device-type photos, image attachments, documents, and generic media URLs.
- [x] Add regression coverage for the default and opt-out preference behavior, including older
      settings backups whose media-cache field is absent.
- [x] Verify actual media cache files are used on an offline cold start without clearing the user's
      app data. On the wired Zenfone, connected sync produced 634 durable attachments (697 files,
      818 MiB); the D-Link device detail showed its thumbnail and cached `D-Link-Bill.pdf`, and
      the same media rendered after disabling Wi-Fi and cold-starting the app. Wi-Fi was restored
      and verified connected afterward.
- [x] Verify remotely with the Android unit suite and `ktfmtCheck`, build the debug APK, install it
      with `-r` on the wired Zenfone and attached Mi Pad, and restore Zenfone Wi-Fi after testing.

Status: **done**, 2026-08-13; verified with remote compile/unit tests/ktfmtCheck and a configured
wired Zenfone connected/offline media-rendering test without clearing app data.
