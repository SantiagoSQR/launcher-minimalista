package com.valen.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
internal fun ManagementTitle(label: String, style: TextStyle) {
    BasicText(label, Modifier.padding(top = 12.dp, bottom = 8.dp),
        style = style.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold))
}

@Composable
internal fun ManagementGroup(label: String, style: TextStyle, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    BasicText(label, Modifier.padding(top = 18.dp, bottom = 6.dp),
        style = style.copy(color = palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    Column(Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp), content = content)
}

@Composable
internal fun ManagementAction(label: String, style: TextStyle, detail: String = "›", action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action)
        .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f).padding(end = 8.dp), style = style.copy(fontSize = 16.sp))
        BasicText(detail, style = style.copy(fontSize = 13.sp, color = LocalPalette.current.accent))
    }
}

@Composable
internal fun ManagementSwitch(label: String, checked: Boolean, style: TextStyle, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = change)
        .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f).padding(end = 8.dp), style = style.copy(fontSize = 16.sp))
        val palette = LocalPalette.current
        Box(Modifier.size(42.dp, 24.dp)
            .background(if (checked) palette.accent else palette.muted, CircleShape)
            .padding(3.dp)) {
            Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(18.dp).background(if (checked) palette.onAccent else palette.text, CircleShape))
        }
    }
}
