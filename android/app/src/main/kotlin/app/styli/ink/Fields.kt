package app.styli.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Styli's form controls: Material's, in the design system's colours and squircles. The data windows (table, chart) use
// these names, so they match the rest of the app without each field asking for it.

@Composable
fun OutlinedTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null, singleLine: Boolean = false, isError: Boolean = false,
) {
    val sty = LocalSty.current
    androidx.compose.material3.OutlinedTextField(
        value, onValueChange, modifier, enabled = enabled, label = label, singleLine = singleLine, isError = isError,
        shape = StyShapes.Md, textStyle = TextStyle(fontSize = 14.sp, color = sty.ink),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = sty.honeyText, unfocusedBorderColor = sty.border, disabledBorderColor = sty.border, errorBorderColor = sty.danger, errorLabelColor = sty.danger, errorContainerColor = sty.surface, errorTextColor = sty.ink,
            focusedLabelColor = sty.honeyText, unfocusedLabelColor = sty.inkMuted, cursorColor = sty.honeyText,
            focusedContainerColor = sty.surface, unfocusedContainerColor = sty.surface, disabledContainerColor = sty.sidebar,
            focusedTextColor = sty.ink, unfocusedTextColor = sty.ink, disabledTextColor = sty.inkMuted,
        ),
    )
}

/** A tick box like the ones in the menus (honey when on). */
@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    val sty = LocalSty.current
    Box(
        Modifier.size(44.dp).clip(StyShapes.Md).clickable(enabled = onCheckedChange != null) { onCheckedChange?.invoke(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).clip(StyShapes.Sm).background(if (checked) sty.honey else Color.Transparent).border(1.5.dp, if (checked) sty.honeyText else sty.borderStrong, StyShapes.Sm),
            contentAlignment = Alignment.Center,
        ) { if (checked) SvgIcon("M20 6L9 17l-5-5", sty.onHoney, 16.dp) }
    }
}

/** The title of a window, in the heading face. */
@Composable
fun DialogTitle(text: String) {
    Text(text, Modifier.padding(bottom = StySize.space2), color = LocalSty.current.ink, fontFamily = DisplayFont, fontSize = 22.sp)
}
