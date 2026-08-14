package dev.pschmitt.nyetbox.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.pschmitt.nyetbox.R

/**
 * OSS dependency + license list (NBC-448) - entirely generated at build time by the AboutLibraries
 * Gradle plugin (see app/build.gradle.kts) from this module's resolved dependency graph, so it
 * can't silently drift out of date the way a hand-maintained list would. Tapping a library opens
 * its project page (GitHub or otherwise, sourced from the dependency's own POM `url`/SCM metadata)
 * when one is known; libraries with no known URL fall back to the container's own default of
 * expanding the license text inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Libraries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(padding),
            onLibraryClick = { library ->
                val url = library.website ?: library.scm?.url
                if (url != null) {
                    uriHandler.openUri(url)
                    true
                } else {
                    false
                }
            },
        )
    }
}
