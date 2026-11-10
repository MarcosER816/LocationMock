package com.mockroute.app.ui

import androidx.compose.foundation.layout.RowScope
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
