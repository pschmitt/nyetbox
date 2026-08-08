package dev.pschmitt.nyetbox.ui.settings

import androidx.annotation.Keep
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable
@Keep
enum class SettingsCategory(val title: String, val subtitle: String) {
    Connection("Connection", "NetBox server and credentials"),
    Backup("Backup & restore", "Portable settings backups"),
    Sync("Sync", "Cached data and refresh policy"),
    Camera("Camera", "Scanner camera and lens preferences"),
    Printing("Printing", "Printer and label defaults"),
    Gestures("Gestures", "Gesture shortcuts"),
    NavigationBar("Navigation bar", "Choose and reorder bottom bar buttons"),
    Shortcuts("App shortcuts", "Long-press the app icon to jump straight to an action"),
    Display("Display", "Theme, fields, and item types shown by default"),
    Notifications("Notifications", "NetBox change alerts"),
    About("About", "Application and build information");

    val icon: ImageVector
        get() =
            when (this) {
                Connection -> Icons.Default.Dns
                Backup -> Icons.Default.FolderSpecial
                Sync -> Icons.Default.Storage
                Camera -> Icons.Default.Cameraswitch
                Printing -> Icons.Default.Print
                Gestures -> Icons.Default.TouchApp
                NavigationBar -> Icons.Default.ViewCarousel
                Shortcuts -> Icons.Default.AppShortcut
                Display -> Icons.Default.Visibility
                Notifications -> Icons.Default.Notifications
                About -> Icons.Default.Info
            }
}
