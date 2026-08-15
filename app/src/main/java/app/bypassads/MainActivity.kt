package app.bypassads

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bypassads.diagnostics.BlackBoxStore
import app.bypassads.runtime.RunMode
import app.bypassads.runtime.RunModeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @Composable
    private fun App() {
        val modeStore = remember { RunModeStore(this) }
        val blackBox = remember { BlackBoxStore(this) }
        var mode by remember { mutableStateOf(modeStore.get()) }
        var refresh by remember { mutableStateOf(0) }
        val serviceEnabled = remember(refresh) { isServiceEnabled() }
        val recent = remember(refresh) { blackBox.recentLines(4) }
        val lastConnected = remember(refresh) { modeStore.lastServiceConnected() }

        MaterialTheme {
            Surface(color = PageBackground, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                ) {
                    Text("Bypass Ads.", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(
                        "离线 · Android 15+ · 开发预览",
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 13.sp,
                        color = Muted,
                    )
                    Spacer(Modifier.height(28.dp))

                    StatusCard(serviceEnabled, mode, lastConnected)
                    Spacer(Modifier.height(14.dp))

                    SectionCard(title = "运行模式") {
                        Text(
                            "当前只开放 Shadow。它会识别、评分、写入本地黑匣子，但不会点击任何内容。",
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = Muted,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModeButton("Shadow", mode == RunMode.SHADOW) {
                                modeStore.set(RunMode.SHADOW); mode = RunMode.SHADOW; refresh++
                            }
                            ModeButton("Off", mode == RunMode.OFF) {
                                modeStore.set(RunMode.OFF); mode = RunMode.OFF; refresh++
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    SectionCard(title = "无障碍服务") {
                        Text(
                            if (serviceEnabled) "服务已启用。打开其他 App 时会进行 1 秒多帧观察。"
                            else "需要手动开启 Bypass Ads. · 开屏观察。",
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = Muted,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                refresh++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(if (serviceEnabled) "查看系统设置" else "开启无障碍") }
                    }
                    Spacer(Modifier.height(14.dp))

                    SectionCard(title = "黑匣子") {
                        Text(
                            "默认保存 7 天或 20 MB。只记录候选和评分理由，不保存完整界面文本，也不截图。",
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = Muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("最近 ${recent.size} 条记录", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
                        recent.forEach { line ->
                            Text(
                                compactLog(line),
                                modifier = Modifier.padding(top = 8.dp),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = Muted,
                            )
                        }
                        if (recent.isEmpty()) {
                            Text("还没有观察记录。", modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Faint)
                        }
                    }

                    Spacer(Modifier.height(26.dp))
                    Text(
                        "NO INTERNET PERMISSION",
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Faint,
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusCard(enabled: Boolean, mode: RunMode, lastConnected: Long) {
        val healthy = enabled && mode != RunMode.OFF
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (healthy) SoftGreen else Color.White, RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Column {
                Text("保护状态", fontSize = 13.sp, color = Muted)
                Text(
                    when {
                        mode == RunMode.OFF -> "已暂停"
                        enabled -> "Shadow 正在观察"
                        else -> "等待开启服务"
                    },
                    modifier = Modifier.padding(top = 7.dp),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Text(
                    if (lastConnected > 0L) "最近连接 ${formatTime(lastConnected)}" else "尚未记录服务连接",
                    modifier = Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(22.dp))
                .padding(20.dp),
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }

    @Composable
    private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) Ink else SoftGray,
                contentColor = if (selected) Color.White else Ink,
            ),
            shape = RoundedCornerShape(14.dp),
        ) { Text(label) }
    }

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun compactLog(line: String): String {
        fun value(key: String): String? = Regex("\\\"$key\\\":(?:\\\"([^\\\"]*)\\\"|(-?\\d+)|null)")
            .find(line)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        val pkg = value("package") ?: "unknown"
        val decision = value("decision") ?: "—"
        val score = value("score") ?: "—"
        return "$pkg  ·  $decision  ·  score $score"
    }

    private fun formatTime(epochMs: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    private companion object {
        val PageBackground = Color(0xFFF3F4F2)
        val Ink = Color(0xFF181A18)
        val Muted = Color(0xFF696D68)
        val Faint = Color(0xFF9CA09B)
        val SoftGreen = Color(0xFFE2F2E7)
        val SoftGray = Color(0xFFE9EBE8)
    }
}
