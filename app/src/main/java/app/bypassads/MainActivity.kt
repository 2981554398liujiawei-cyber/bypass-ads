package app.bypassads

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.bypassads.diagnostics.BlackBoxStats
import app.bypassads.diagnostics.BlackBoxIoHealth
import app.bypassads.diagnostics.DiagnosticRecord
import app.bypassads.runtime.RunMode
import app.bypassads.runtime.RunModeStore
import app.bypassads.runtime.AccessibilityRuntimeStateStore
import app.bypassads.runtime.AccessibilityStatus
import app.bypassads.runtime.AccessibilityStatusReducer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var refreshSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    override fun onResume() {
        super.onResume()
        refreshSignal++
    }

    @Composable
    private fun App() {
        val modeStore = remember { RunModeStore(this) }
        val blackBox = remember { BlackBoxStore(this) }
        var mode by remember { mutableStateOf(modeStore.get()) }
        var destination by remember { mutableStateOf(Destination.HOME) }
        var selectedRecord by remember { mutableStateOf<DiagnosticRecord?>(null) }
        var clearConfirmationVisible by remember { mutableStateOf(false) }
        val refresh = refreshSignal
        val systemReportsEnabled = remember(refresh) { isServiceEnabled() }
        val accessibilityStatus = AccessibilityStatusReducer.reduce(
            runtimeState = AccessibilityRuntimeStateStore.current,
            systemReportsEnabled = systemReportsEnabled,
            mode = mode,
        )
        var stats by remember { mutableStateOf(BlackBoxStats(0, 0, 0, null)) }
        var recent by remember { mutableStateOf(emptyList<DiagnosticRecord>()) }
        var ioHealth by remember { mutableStateOf<BlackBoxIoHealth?>(null) }
        val lastConnected = remember(refresh) { modeStore.lastServiceConnected() }

        DisposableEffect(blackBox) {
            onDispose { blackBox.close() }
        }

        LaunchedEffect(refresh) {
            blackBox.todayStats { value -> runOnUiThread { stats = value } }
            blackBox.recentRecords { value -> runOnUiThread { recent = value } }
            blackBox.health { value -> runOnUiThread { ioHealth = value } }
        }

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
                        "离线 · Android 15+ · Shadow 观察",
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 13.sp,
                        color = Muted,
                    )
                    Spacer(Modifier.height(22.dp))

                    DestinationTabs(destination) { destination = it }
                    Spacer(Modifier.height(18.dp))

                    when (destination) {
                        Destination.HOME -> Home(
                            accessibilityStatus = accessibilityStatus,
                            lastConnected = lastConnected,
                            recordCount = stats.recordCount,
                            wouldClickCount = stats.wouldClickCount,
                            rejectedCount = stats.rejectedCount,
                            latestDecision = stats.latestDecision,
                            onOpenAccessibilitySettings = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                        )

                        Destination.DIAGNOSTICS -> Diagnostics(
                            records = recent,
                            ioHealth = ioHealth,
                            onRefresh = { refreshSignal++ },
                            onSelectRecord = { selectedRecord = it },
                            onClear = { clearConfirmationVisible = true },
                        )

                        Destination.SETTINGS -> Settings(
                            mode = mode,
                            onModeChange = { selectedMode ->
                                modeStore.set(selectedMode)
                                mode = selectedMode
                                refreshSignal++
                            },
                        )
                    }
                }
            }
        }

        selectedRecord?.let { record ->
            RecordDetailsDialog(record = record, onDismiss = { selectedRecord = null })
        }
        if (clearConfirmationVisible) {
            AlertDialog(
                onDismissRequest = { clearConfirmationVisible = false },
                title = { Text("清空本地记录？") },
                text = { Text("此操作只删除本机黑匣子记录，无法恢复，不会影响无障碍服务状态。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            blackBox.clear {
                                runOnUiThread {
                                    clearConfirmationVisible = false
                                    refreshSignal++
                                }
                            }
                        },
                    ) { Text("清空", color = Danger) }
                },
                dismissButton = {
                    TextButton(onClick = { clearConfirmationVisible = false }) { Text("取消") }
                },
            )
        }
    }

    @Composable
    private fun DestinationTabs(selected: Destination, onSelect: (Destination) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Destination.entries.forEach { destination ->
                ModeButton(
                    label = destination.label,
                    selected = selected == destination,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @Composable
    private fun Home(
        accessibilityStatus: AccessibilityStatus,
        lastConnected: Long,
        recordCount: Int,
        wouldClickCount: Int,
        rejectedCount: Int,
        latestDecision: DiagnosticRecord?,
        onOpenAccessibilitySettings: () -> Unit,
    ) {
        StatusCard(accessibilityStatus, lastConnected)
        Spacer(Modifier.height(14.dp))

        SectionCard(title = "今日观察") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("记录", recordCount.toString(), Modifier.weight(1f))
                Metric("WOULD_CLICK", wouldClickCount.toString(), Modifier.weight(1f))
                Metric("安全放弃", rejectedCount.toString(), Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionCard(title = "最近一次 Decision") {
            if (latestDecision == null) {
                Text("尚未完成扫描。启用无障碍服务后，打开其他 App 会触发一秒多帧观察。", fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
            } else {
                DecisionSummary(latestDecision)
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionCard(title = "无障碍服务") {
            Text(
                accessibilityStatus.description,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = Muted,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenAccessibilitySettings,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (accessibilityStatus.shouldOpenSettings) "查看系统设置" else "开启无障碍") }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            "SHADOW ONLY · NO INTERNET PERMISSION",
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.SemiBold,
            color = Faint,
        )
    }

    @Composable
    private fun Diagnostics(
        records: List<DiagnosticRecord>,
        ioHealth: BlackBoxIoHealth?,
        onRefresh: () -> Unit,
        onSelectRecord: (DiagnosticRecord) -> Unit,
        onClear: () -> Unit,
    ) {
        SectionCard(title = "本地黑匣子") {
            Text("记录只保存在本机，达到 7 天或 20 MB 时滚动清理。不会保存完整界面文本、截图或用户输入。", fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeButton("刷新", selected = false, onClick = onRefresh, modifier = Modifier.weight(1f))
                Button(
                    onClick = onClear,
                    colors = ButtonDefaults.buttonColors(containerColor = SoftRed, contentColor = Danger),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("清空日志") }
            }
            ioHealth?.let { health ->
                Text(
                    "I/O queue ${health.pendingIoJobs} pending, peak ${health.maxPendingIoJobs}, writes ${health.recordsWritten}, failures ${health.writeFailures}",
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Faint,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (records.isEmpty()) {
            SectionCard(title = "尚无记录") {
                Text("启用服务并打开其他 App 后，这里会显示事件、扫描、评分、风险与最终决策。", fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
            }
        } else {
            records.forEach { record ->
                DiagnosticRow(record = record, onClick = { onSelectRecord(record) })
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    @Composable
    private fun Settings(mode: RunMode, onModeChange: (RunMode) -> Unit) {
        SectionCard(title = "运行模式") {
            Text("M1 仅提供 Shadow：会识别、评分并写入黑匣子，但永远不执行节点点击或手势。", fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeButton("Shadow", mode == RunMode.SHADOW, { onModeChange(RunMode.SHADOW) }, Modifier.weight(1f))
                ModeButton("Off", mode == RunMode.OFF, { onModeChange(RunMode.OFF) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionCard(title = "数据与隐私") {
            Text("Retention：最多 7 天或 20 MB，先达到的限制优先。应用不申请 INTERNET 权限，不包含 Analytics、崩溃上报、远程规则或广告 SDK。", fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
        }
        Spacer(Modifier.height(14.dp))
        SectionCard(title = "关于") {
            Text("Bypass Ads. 是 Android 15+ 的离线开屏广告观察工具。核心判断逻辑保持在可测试、可回放的纯 Kotlin 模块中。", fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
        }
    }

    @Composable
    private fun StatusCard(status: AccessibilityStatus, lastConnected: Long) {
        val healthy = status.isObserving
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (healthy) SoftGreen else Color.White, RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Column {
                Text("运行状态", fontSize = 13.sp, color = Muted)
                Text(
                    status.headline,
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
    private fun Metric(label: String, value: String, modifier: Modifier) {
        Column(
            modifier = modifier
                .background(SoftGray, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(label, modifier = Modifier.padding(top = 3.dp), fontSize = 11.sp, color = Muted)
        }
    }

    @Composable
    private fun DecisionSummary(record: DiagnosticRecord) {
        Text(record.packageName ?: "unknown package", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
        Text(
            listOfNotNull(
                record.decision ?: record.trigger.name,
                record.score?.let { "score $it" },
                record.latencyMs?.let { "${it}ms" },
            ).joinToString("  ·  "),
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 13.sp,
            color = Muted,
        )
        record.rejectionReason?.let {
            Text("拒绝原因：${it.replace('_', ' ')}", modifier = Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Danger)
        }
    }

    @Composable
    private fun DiagnosticRow(record: DiagnosticRecord, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
        ) {
            Text(record.packageName ?: "系统事件", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(
                listOfNotNull(
                    formatTime(record.epochMs),
                    record.trigger.name,
                    record.decision,
                    record.score?.let { "score $it" },
                ).joinToString("  ·  "),
                modifier = Modifier.padding(top = 5.dp),
                fontSize = 12.sp,
                color = Muted,
            )
        }
    }

    @Composable
    private fun RecordDetailsDialog(record: DiagnosticRecord, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("诊断详情") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("${record.trigger.name} · ${formatTime(record.epochMs)}", fontSize = 13.sp, color = Muted)
                    record.packageName?.let { Text(it, modifier = Modifier.padding(top = 8.dp), fontSize = 14.sp, color = Ink) }
                    Text("窗口 ${record.windowCount} · 节点 ${record.nodeCount} · 候选 ${record.candidateCount}", modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Muted)
                    record.decision?.let { Text("Decision：$it", modifier = Modifier.padding(top = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink) }
                    record.score?.let { Text("Score：$it", modifier = Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Muted) }
                    record.latencyMs?.let { Text("Latency：${it}ms", modifier = Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Muted) }
                    record.scanWorkMs?.let { Text("Scan work：${it}ms", modifier = Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Muted) }
                    record.rejectionReason?.let { Text("拒绝原因：${it.replace('_', ' ')}", modifier = Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Danger) }
                    ReasonList("Evidence", record.evidence)
                    ReasonList("Risk flags", record.risks)
                    if (record.candidates.isNotEmpty()) {
                        Text("候选特征", modifier = Modifier.padding(top = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                        record.candidates.forEach { candidate ->
                            Text(
                                "${candidate.label} · ${candidate.resourceId ?: "no resource id"} · bounds ${candidate.bounds.joinToString()}",
                                modifier = Modifier.padding(top = 5.dp),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = Muted,
                            )
                            Text(
                                "clickable=${candidate.clickable}, hits=${candidate.stabilityHits}, countdown=${candidate.countdownValue}, drift=${candidate.positionDriftDetected}, ctaSibling=${candidate.ctaSiblingDetected}, identityAmbiguous=${candidate.identityAmbiguous}",
                                modifier = Modifier.padding(top = 2.dp),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = Faint,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
    }

    @Composable
    private fun ReasonList(title: String, reasons: List<app.bypassads.diagnostics.DiagnosticReason>) {
        if (reasons.isEmpty()) return
        Text(title, modifier = Modifier.padding(top = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
        reasons.forEach { reason ->
            Text("${reason.code}  ${if (reason.points >= 0) "+" else ""}${reason.points}", modifier = Modifier.padding(top = 3.dp), fontSize = 12.sp, color = Muted)
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
    private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) Ink else SoftGray,
                contentColor = if (selected) Color.White else Ink,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = modifier,
        ) { Text(label, maxLines = 1) }
    }

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info -> info.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun formatTime(epochMs: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    private enum class Destination(val label: String) {
        HOME("首页"),
        DIAGNOSTICS("诊断"),
        SETTINGS("设置"),
    }

    private companion object {
        val PageBackground = Color(0xFFF3F4F2)
        val Ink = Color(0xFF181A18)
        val Muted = Color(0xFF696D68)
        val Faint = Color(0xFF9CA09B)
        val SoftGreen = Color(0xFFE2F2E7)
        val SoftGray = Color(0xFFE9EBE8)
        val SoftRed = Color(0xFFFCE8E7)
        val Danger = Color(0xFFA8352B)
    }
}
