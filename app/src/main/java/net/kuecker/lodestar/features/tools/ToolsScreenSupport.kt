package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground

/**
 * The frame of a Tools screen that is pushed onto another tab's navigation stack: the screen background and a top
 * bar with a back button (on iOS the navigation bar provides both). The system back gesture is handled by the
 * navigation stack that hosts the screen.
 */
@Composable
internal fun ToolsScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        ToolsTopBar(title, onBack)
        content()
    }
}

/** The top bar: a back arrow in the accent colour and the screen's title. */
@Composable
private fun ToolsTopBar(title: String, onBack: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = palette.accent)
        }
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 12.dp).semantics { heading() },
        )
    }
}

/** A titled section of a form whose rows are declared with [FormRows]; hairlines are drawn between them. */
@Composable
internal fun FormSection(title: String, footer: String? = null, build: FormRows.() -> Unit) {
    val rows = FormRows().apply(build)
    SettingsSection(title = title, footer = footer) { rows.Content() }
}

/**
 * The rows of a [FormSection], collected so that hairlines can go between them. Each call adds one row, like each
 * child view of an iOS `Section`.
 */
internal class FormRows {
    private val rows = ArrayList<@Composable () -> Unit>()

    /** A row with the standard insets around [content]. */
    fun row(content: @Composable ColumnScope.() -> Unit) {
        rows += { SettingsRow(content = content) }
    }

    /** A shared settings control that is a row by itself (number field, menu picker, slider). */
    fun control(content: @Composable () -> Unit) {
        rows += content
    }

    /** A label with its value on the right. */
    fun info(label: String, value: String) = row { InfoRow(label, value) }

    /** Explanatory text in a row of its own. */
    fun caption(text: String) = row { SettingsCaption(text) }

    /** A tappable row in the accent colour, with an optional leading [icon]. */
    fun action(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
        rows += { ActionRow(text, icon, onClick) }
    }

    /** Draws the rows with hairlines between them. Call it inside the section. */
    @Composable
    fun Content() {
        rows.forEachIndexed { index, row ->
            if (index > 0) SettingsDivider()
            row()
        }
    }
}

/** A tappable row: [text] in the accent colour, with an optional [icon] before it. */
@Composable
private fun ActionRow(text: String, icon: ImageVector?, onClick: () -> Unit) {
    val palette = LocalPalette.current
    SettingsRow(onClick = onClick, modifier = Modifier.semantics { role = Role.Button }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
            }
            Text(text, color = palette.accent, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** What [rememberWhilePushed] keeps between visits of the tab. */
private class PushedState<T : Any> {
    var value: T? = null
}

/**
 * Screen state that behaves like the SwiftUI `@State` of a view on a navigation stack: it survives switching to
 * another tab and back (the Exposure Calculator and Polar Alignment point you to other tabs), but it starts afresh
 * when the screen is pushed again after it was popped. [key] names the state; each tab that can host the screen
 * has its own copy.
 */
@Composable
internal fun <T : Any> rememberWhilePushed(key: String, create: () -> T): T {
    val app = LocalAppModel.current
    val hostTab = remember { Snapshot.withoutReadObservation { app.selectedTab } }
    val pushed = rememberRetained("$key.${hostTab.name}") { PushedState<T>() }
    val value = pushed.value ?: create().also { pushed.value = it }
    DisposableEffect(pushed) {
        // Leaving the composition while the host tab is still selected means the screen was popped; leaving it
        // because another tab was selected keeps the state for the way back.
        onDispose { if (app.selectedTab == hostTab) pushed.value = null }
    }
    return value
}
