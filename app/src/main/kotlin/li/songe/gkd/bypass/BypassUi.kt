package li.songe.gkd.bypass

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bypass Ads product UI components (style follows the original Bypass Ads shell:
 * warm off-white page, ink text, white rounded cards).
 */
object BypassPalette {
    val PageBackground = Color(0xFFF3F4F2)
    val Ink = Color(0xFF181A18)
    val Muted = Color(0xFF696D68)
    val Faint = Color(0xFF9CA09B)
    val SoftGreen = Color(0xFFE2F2E7)
    val SoftGray = Color(0xFFE9EBE8)
    val SoftRed = Color(0xFFFCE8E7)
    val Danger = Color(0xFFA8352B)
    val Accent = Color(0xFF2E7D4F)
}

@Composable
fun BypassSectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(20.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun BypassMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(BypassPalette.SoftGray, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Text(label, modifier = Modifier.padding(top = 3.dp), fontSize = 11.sp, color = BypassPalette.Muted)
    }
}

@Composable
fun BypassModeButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) BypassPalette.Ink else BypassPalette.SoftGray,
            contentColor = if (selected) Color.White else BypassPalette.Ink,
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) { Text(label, maxLines = 1) }
}

@Composable
fun BypassSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A settings row is a single affordance. The full row target also
            // avoids a tiny hit area on large-screen and accessibility setups.
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = BypassPalette.Muted,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BypassPalette.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BypassPalette.Faint,
            ),
        )
    }
}

@Composable
fun BypassStatusCard(
    headline: String,
    description: String,
    healthy: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (healthy) BypassPalette.SoftGreen else Color.White, RoundedCornerShape(8.dp))
            .padding(22.dp),
    ) {
        Column {
            Text("运行状态", fontSize = 13.sp, color = BypassPalette.Muted)
            Text(
                headline,
                modifier = Modifier.padding(top = 7.dp),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = BypassPalette.Ink,
            )
            Text(
                description,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = BypassPalette.Muted,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BypassPalette.Ink,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun BypassNavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = BypassPalette.Ink,
        )
        Text("›", fontSize = 22.sp, color = BypassPalette.Faint)
    }
}

@Composable
fun BypassAppRow(
    appName: String,
    packageName: String,
    icon: ImageBitmap?,
    enabled: Boolean,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .run { if (onClick != null) clickable(onClick = onClick) else this }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(BypassPalette.SoftGray, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Text(
                    appName.take(1).ifEmpty { "?" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BypassPalette.Muted,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(appName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
            Text(subtitle, modifier = Modifier.padding(top = 1.dp), fontSize = 11.sp, color = BypassPalette.Faint)
            Text(packageName, modifier = Modifier.padding(top = 1.dp), fontSize = 10.sp, color = BypassPalette.Faint)
        }
        Spacer(Modifier.size(10.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BypassPalette.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BypassPalette.Faint,
            ),
        )
    }
}

@Composable
fun BypassMutedText(text: String, size: Int = 12, lineHeight: Int = 18, color: Color = BypassPalette.Muted) {
    Text(
        text,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        color = color,
    )
}

@Composable
fun BypassTitleBar(subtitle: String) {
    Column {
        Text("Bypass Ads", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Text(
            subtitle,
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 13.sp,
            color = BypassPalette.Muted,
        )
    }
}

@Composable
fun BypassTabBar(
    tabs: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { (label, index) ->
            BypassModeButton(
                label = label,
                selected = selected == index,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
