package dev.pschmitt.nyetbox.shortcuts

import androidx.annotation.DrawableRes
import dev.pschmitt.nyetbox.R
import dev.pschmitt.nyetbox.data.repository.GestureAction

/**
 * A real drawable resource per [GestureAction], for surfaces that can't use a Compose `ImageVector`
 * the way in-app UI does (`iconForGestureAction`) - launcher shortcuts need a self-contained icon
 * (their own circular background baked in, since the launcher provides no chrome of its own). See
 * [drawableResForGestureActionGlyph] for the flat variant the widget's `CircleIconButton`s use
 * instead (which supplies its own background).
 */
@DrawableRes
fun drawableResForGestureAction(action: GestureAction): Int =
    when (action) {
        GestureAction.GlobalSearch -> R.drawable.ic_shortcut_search
        GestureAction.Scanner -> R.drawable.ic_shortcut_scanner
        GestureAction.Settings -> R.drawable.ic_shortcut_settings
        GestureAction.Add -> R.drawable.ic_shortcut_add
        GestureAction.SwitchServer -> R.drawable.ic_shortcut_switch_server
        // No more specific icon for these - all point at some NetBox list/object, so the
        // generic "list" glyph fits every case reasonably.
        GestureAction.DeviceList,
        GestureAction.AddSpecific,
        GestureAction.ListSpecific,
        GestureAction.DetailSpecific -> R.drawable.ic_shortcut_device_list
        // Not offered by GestureAction.shortcutable (the catalog both shortcuts and widget action
        // buttons pick from) - unreachable in practice, but exhaustive for the compiler.
        GestureAction.Off,
        GestureAction.Dashboard,
        GestureAction.Sync,
        GestureAction.OfflineOn,
        GestureAction.OfflineOff -> R.drawable.ic_shortcut_device_list
    }

/**
 * Flat (no background) counterpart of [drawableResForGestureAction], for the widget's
 * `CircleIconButton` action row - that component already draws its own tonal circle behind the
 * glyph, so a self-contained icon would double up backgrounds.
 */
@DrawableRes
fun drawableResForGestureActionGlyph(action: GestureAction): Int =
    when (action) {
        GestureAction.GlobalSearch -> R.drawable.ic_glyph_search
        GestureAction.Scanner -> R.drawable.ic_glyph_scanner
        GestureAction.Settings -> R.drawable.ic_glyph_settings
        GestureAction.Add -> R.drawable.ic_glyph_add
        GestureAction.SwitchServer -> R.drawable.ic_glyph_switch_server
        GestureAction.DeviceList -> R.drawable.ic_object_hub
        GestureAction.AddSpecific,
        GestureAction.ListSpecific,
        GestureAction.DetailSpecific -> R.drawable.ic_object_category
        GestureAction.Off,
        GestureAction.Dashboard,
        GestureAction.Sync,
        GestureAction.OfflineOn,
        GestureAction.OfflineOff -> R.drawable.ic_object_category
    }
