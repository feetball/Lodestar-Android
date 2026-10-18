package net.kuecker.lodestar.features.widget

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.ColorFilter
import androidx.glance.LocalSize
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import net.kuecker.lodestar.R
import net.kuecker.lodestar.theme.colorScheme

/** The widgets' layouts, by the room they have. */
internal enum class WidgetLayoutKind {
    /** The Moon and the darkness window. */
    SMALL,

    /** All four items in a grid of two by two: a label and one line each. */
    GRID,

    /** The grid with a second line under each. */
    GRID_DETAIL,

    /** All four items one under the other, narrow: a label and one line each. */
    LIST_NARROW,

    /** All four items one under the other with a title: a label and two lines each. */
    LIST,

    /** The list with a third line under each. */
    LIST_TALL,
}

/** The sizes the widgets are laid out for (Glance picks the largest that fits) and what shows at each. */
internal object WidgetSizes {
    val small = DpSize(100.dp, 90.dp)
    val grid = DpSize(220.dp, 100.dp)
    val gridDetail = DpSize(220.dp, 150.dp)
    val listNarrow = DpSize(100.dp, 160.dp)
    val list = DpSize(220.dp, 230.dp)
    val listTall = DpSize(220.dp, 300.dp)

    val all: Set<DpSize> = setOf(small, grid, gridDetail, listNarrow, list, listTall)

    /** The sizes of the two widgets of the picker, for their previews: 2 × 2 and 4 × 2 cells. */
    val previewSmall = DpSize(110.dp, 110.dp)
    val previewMedium = DpSize(250.dp, 110.dp)

    /** What a widget of [size] shows. */
    fun kind(size: DpSize): WidgetLayoutKind {
        val wide = size.width >= grid.width
        return when {
            wide && size.height >= listTall.height -> WidgetLayoutKind.LIST_TALL
            wide && size.height >= list.height -> WidgetLayoutKind.LIST
            wide && size.height >= gridDetail.height -> WidgetLayoutKind.GRID_DETAIL
            wide -> WidgetLayoutKind.GRID
            size.height >= listNarrow.height -> WidgetLayoutKind.LIST_NARROW
            else -> WidgetLayoutKind.SMALL
        }
    }
}

/** Everything a widget needs to draw, prepared off the main thread. */
internal class WidgetModel(
    val appearance: WidgetAppearance,
    /** The words, or `null` while the app has no location. */
    val copy: WidgetCopy?,
    /** The Moon's phase, drawn; `null` without [copy]. */
    val moonImage: Bitmap?,
    /** Opens the app on the Tonight tab. */
    val tapIntent: Intent,
) {
    val spoken: String get() = copy?.spoken ?: "${WidgetCopy.TITLE}. ${WidgetCopy.NEEDS_LOCATION}."
}

/** The widget, in the palette of the app (normal or night). */
@Composable
internal fun TonightWidgetContent(model: WidgetModel) {
    GlanceTheme(colors = ColorProviders(model.appearance.palette.colorScheme())) {
        val size = LocalSize.current
        var modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.surfaceVariant)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modifier = modifier.cornerRadius(android.R.dimen.system_app_widget_background_radius)
        }
        Box(
            modifier.clickable(actionStartActivity(model.tapIntent)).semantics { contentDescription = model.spoken },
            contentAlignment = Alignment.Center,
        ) {
            val copy = model.copy
            if (copy == null) {
                NeedsLocation(compact = size.height < WidgetSizes.listNarrow.height)
            } else {
                when (WidgetSizes.kind(size)) {
                    WidgetLayoutKind.SMALL -> SmallLayout(copy, model.moonImage)
                    WidgetLayoutKind.GRID -> GridLayout(copy, model.moonImage, showDetail = false)
                    WidgetLayoutKind.GRID_DETAIL -> GridLayout(copy, model.moonImage, showDetail = true)
                    WidgetLayoutKind.LIST_NARROW -> NarrowListLayout(copy, model.moonImage)
                    WidgetLayoutKind.LIST -> ListLayout(copy, model.moonImage, showExtra = false)
                    WidgetLayoutKind.LIST_TALL -> ListLayout(copy, model.moonImage, showExtra = true)
                }
            }
        }
    }
}

// Text styles

@Composable
private fun primaryStyle(size: TextUnit, bold: Boolean = false, center: Boolean = false) =
    TextStyle(
        color = GlanceTheme.colors.onSurface,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = if (center) TextAlign.Center else null,
    )

@Composable
private fun secondaryStyle(size: TextUnit) = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = size)

@Composable
private fun accentStyle(size: TextUnit) =
    TextStyle(color = GlanceTheme.colors.primary, fontSize = size, fontWeight = FontWeight.Bold)

/** A rounded tile background where the system rounds corners (Android 12), else a plain one. */
@Composable
private fun tile(modifier: GlanceModifier = GlanceModifier): GlanceModifier {
    val background = modifier.background(GlanceTheme.colors.secondaryContainer)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background.cornerRadius(android.R.dimen.system_app_widget_inner_radius)
    } else {
        background
    }
}

// Pieces

private enum class WidgetGlyph(@DrawableRes val resource: Int) {
    DARK(R.drawable.widget_ic_dark),
    MOON(R.drawable.widget_ic_moon),
    MILKY_WAY(R.drawable.widget_ic_milky_way),
    EVENT(R.drawable.widget_ic_event),
    PLACE(R.drawable.widget_ic_place),
}

/** The four things a widget shows. */
private enum class Slot(val glyph: WidgetGlyph) {
    DARK(WidgetGlyph.DARK),
    MOON(WidgetGlyph.MOON),
    CORE(WidgetGlyph.MILKY_WAY),
    EVENT(WidgetGlyph.EVENT),
}

private fun WidgetCopy.item(slot: Slot): WidgetItem = when (slot) {
    Slot.DARK -> dark
    Slot.MOON -> moon
    Slot.CORE -> core
    Slot.EVENT -> event
}

@Composable
private fun Glyph(glyph: WidgetGlyph, size: Dp, tinted: Boolean = false) {
    val colors = GlanceTheme.colors
    Image(
        provider = ImageProvider(glyph.resource),
        contentDescription = null,
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(if (tinted) colors.primary else colors.onSurfaceVariant),
    )
}

/** The Moon as drawn, or its icon while there is no drawing. */
@Composable
private fun MoonPicture(bitmap: Bitmap?, size: Dp) {
    if (bitmap == null) {
        Glyph(WidgetGlyph.MOON, size)
    } else {
        Image(provider = ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.size(size))
    }
}

/** An item's label line: its picture (the Moon's phase, for the Moon), the label, a mark for what is happening now, and its figure at the end. */
@Composable
private fun LabelLine(slot: Slot, item: WidgetItem, moonImage: Bitmap?, short: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (slot == Slot.MOON) MoonPicture(moonImage, 12.dp) else Glyph(slot.glyph, 12.dp)
        Spacer(GlanceModifier.width(4.dp))
        Text(if (short) item.shortLabel else item.label, style = secondaryStyle(11.sp), maxLines = 1)
        item.badge?.let { Text(" · $it", style = accentStyle(11.sp), maxLines = 1) }
        item.trailing?.let {
            Spacer(GlanceModifier.defaultWeight())
            Text(it, style = primaryStyle(11.sp, bold = true), maxLines = 1)
        }
    }
}

/** The darkness window is the widget's headline, in the accent colour. */
@Composable
private fun valueStyle(slot: Slot, size: TextUnit): TextStyle =
    if (slot == Slot.DARK) accentStyle(size) else primaryStyle(size, bold = true)

// Layouts

@Composable
private fun NeedsLocation(compact: Boolean) {
    Column(
        GlanceModifier.fillMaxSize().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(WidgetGlyph.PLACE, if (compact) 20.dp else 28.dp, tinted = true)
        Spacer(GlanceModifier.height(6.dp))
        Text(WidgetCopy.NEEDS_LOCATION, style = primaryStyle(if (compact) 12.sp else 14.sp, bold = true, center = true))
    }
}

/** 2×2: the Moon, and the darkness window under it. */
@Composable
private fun SmallLayout(copy: WidgetCopy, moonImage: Bitmap?) {
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonPicture(moonImage, 36.dp)
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(copy.moon.value, style = primaryStyle(12.sp, bold = true), maxLines = 2)
                Text("${copy.moon.trailing.orEmpty()} lit", style = secondaryStyle(11.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        LabelLine(Slot.DARK, copy.dark, null, short = true)
        Text(copy.dark.value, style = accentStyle(15.sp), maxLines = 2)
    }
}

/** 4×2 and larger: the four items as tiles, two by two. */
@Composable
private fun GridLayout(copy: WidgetCopy, moonImage: Bitmap?, showDetail: Boolean) {
    Column(GlanceModifier.fillMaxSize().padding(6.dp)) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            Tile(Slot.DARK, copy, moonImage, showDetail)
            Spacer(GlanceModifier.width(6.dp))
            Tile(Slot.MOON, copy, moonImage, showDetail)
        }
        Spacer(GlanceModifier.height(6.dp))
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            Tile(Slot.CORE, copy, moonImage, showDetail)
            Spacer(GlanceModifier.width(6.dp))
            Tile(Slot.EVENT, copy, moonImage, showDetail)
        }
    }
}

@Composable
private fun RowScope.Tile(slot: Slot, copy: WidgetCopy, moonImage: Bitmap?, showDetail: Boolean) {
    val item = copy.item(slot)
    Column(
        tile(GlanceModifier.defaultWeight().fillMaxHeight()).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelLine(slot, item, moonImage, short = true)
        Text(item.value, style = valueStyle(slot, 13.sp), maxLines = 1)
        if (showDetail) item.detail?.let { Text(it, style = secondaryStyle(11.sp), maxLines = 1) }
    }
}

/** 2×3 and taller but narrow: the four items one under the other, a label and one line each. */
@Composable
private fun NarrowListLayout(copy: WidgetCopy, moonImage: Bitmap?) {
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        for ((index, slot) in Slot.entries.withIndex()) {
            if (index > 0) Spacer(GlanceModifier.defaultWeight())
            Entry(slot, copy, moonImage, short = true, detail = false, extra = false)
        }
    }
}

/** 4×4 and larger: the title and the four items one under the other. */
@Composable
private fun ListLayout(copy: WidgetCopy, moonImage: Bitmap?, showExtra: Boolean) {
    Column(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(WidgetCopy.TITLE, style = accentStyle(14.sp), maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            Text(copy.place, style = secondaryStyle(11.sp), maxLines = 1)
        }
        for (slot in Slot.entries) {
            Spacer(GlanceModifier.defaultWeight())
            Entry(slot, copy, moonImage, short = false, detail = true, extra = showExtra)
        }
    }
}

/** One item of a list: its label line, its main line and the lines under it that fit. */
@Composable
private fun Entry(slot: Slot, copy: WidgetCopy, moonImage: Bitmap?, short: Boolean, detail: Boolean, extra: Boolean) {
    val item = copy.item(slot)
    Column(GlanceModifier.fillMaxWidth()) {
        LabelLine(slot, item, moonImage, short)
        Text(item.value, style = valueStyle(slot, 14.sp), maxLines = 1)
        if (detail) item.detail?.let { Text(it, style = secondaryStyle(11.sp), maxLines = 1) }
        if (extra) item.extra?.let { Text(it, style = secondaryStyle(11.sp), maxLines = 1) }
    }
}
