package com.mockroute.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.mockroute.app.ui.theme.AppShapes

/*
 * App-wide widgets. They shadow the Material 3 ones inside this package (files here don't import
 * material3.Button/Text…), so every button is a smooth square and every static label goes through [I18n].
 */

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = AppShapes.Button, content = content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = AppShapes.Button, content = content)

@Composable
fun FilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = AppShapes.Button, content = content)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = AppShapes.Button, content = content)

/** Text that is translated when the app language is Spanish and the exact English text is in the dictionary. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) = androidx.compose.material3.Text(
    text = I18n.tr(text), modifier = modifier, color = color, style = style, fontWeight = fontWeight, textAlign = textAlign, maxLines = maxLines,
)

/** Cards: smooth squares on a raised tone with a hairline edge (no heavy shadow). */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.CardColors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
    elevation: androidx.compose.material3.CardElevation = androidx.compose.material3.CardDefaults.cardElevation(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(
    modifier = modifier,
    shape = androidx.compose.material3.MaterialTheme.shapes.large,
    colors = colors,
    elevation = elevation,
    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant),
    content = content,
)

/**
 * Floating panel over the map (sheets, banners, the simulation card): rounded, near-opaque surface, hairline
 * edge and a soft shadow so it lifts off the 3-D buildings. [accent] tints the edge for the kind of thing shown.
 */
@Composable
fun MapPanel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    color: Color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
    content: @Composable () -> Unit,
) = androidx.compose.material3.Surface(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    shape = AppShapes.Panel,
    color = color,
    shadowElevation = 10.dp,
    border = androidx.compose.foundation.BorderStroke(
        1.dp, accent?.copy(alpha = 0.45f) ?: androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
    ),
    content = content,
)

/** Small grab handle shown at the top of a bottom panel. */
@Composable
fun PanelHandle(modifier: Modifier = Modifier) = androidx.compose.foundation.layout.Box(
    modifier.size(width = 36.dp, height = 4.dp).background(
        androidx.compose.material3.MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
    ),
)

/** Vertical group of square map controls (like the map apps' stacked buttons), one surface with dividers. */
@Composable
fun MapControlStack(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = AppShapes.shapes.medium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant),
    ) { androidx.compose.foundation.layout.Column(content = content) }

/** One 48-dp square button inside a [MapControlStack]. */
@Composable
fun MapControl(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(
        modifier
            .size(48.dp)
            .clickable(onClickLabel = description, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { content() }

/** Hairline between controls of a [MapControlStack]. */
@Composable
fun MapControlDivider() = androidx.compose.material3.HorizontalDivider(
    Modifier.width(48.dp).padding(horizontal = 10.dp), thickness = 1.dp,
    color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
)

/** Small coloured tag ("Route", "Paused", "Feasible"…): tinted fill, vivid text. */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) = androidx.compose.material3.Surface(
    modifier = modifier,
    shape = AppShapes.shapes.extraSmall,
    color = color.copy(alpha = 0.16f),
    contentColor = color,
) {
    Text(
        text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium, maxLines = 1,
    )
}
