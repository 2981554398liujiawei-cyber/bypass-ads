package li.songe.gkd.bypass

import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import li.songe.gkd.app
import li.songe.gkd.util.appIconMapFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBypassTime(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
fun BypassHomePage(
    engine: BypassEngine,
    onOpenApps: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val serviceState by engine.serviceState.collectAsState()
    val masterEnabled by engine.masterEnabled.collectAsState()
    val stats by engine.stats.collectAsState()
    val latestAction by engine.latestAction.collectAsState()
    val genericFallbackEnabled by engine.genericFallbackEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BypassSectionCard(title = "自动跳过开屏广告") {
            BypassMutedText(
                "开启后，Bypass Ads 会在 App 启动时自动跳过开屏广告。完全离线运行，不收集任何数据。",
                size = 13,
            )
            Spacer(Modifier.height(8.dp))
            BypassSwitchRow(
                title = "开屏保护",
                subtitle = if (masterEnabled) "已开启" else "已暂停",
                checked = masterEnabled,
                onCheckedChange = { engine.setMasterEnabled(it) },
            )
        }
        Spacer(Modifier.height(14.dp))

        BypassSectionCard(title = "已保护") {
            Row {
                BypassMetric(
                    label = "专用规则",
                    value = "${stats.appCount} 个应用",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(10.dp))
                BypassMetric(
                    label = "规则组",
                    value = "${stats.groupCount}",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            BypassSwitchRow(
                title = "通用开屏保护",
                subtitle = if (genericFallbackEnabled) {
                    "未适配的应用也尝试跳过标准的「跳过」按钮"
                } else {
                    "仅使用专用规则"
                },
                checked = genericFallbackEnabled,
                onCheckedChange = { engine.setGenericFallbackEnabled(it) },
            )
        }
        Spacer(Modifier.height(14.dp))

        BypassSectionCard(title = "最近跳过") {
            if (latestAction == null) {
                BypassMutedText("打开带开屏广告的 App 后，这里会显示自动跳过记录。")
            } else {
                val action = latestAction!!
                Text(
                    action.appName ?: action.appId,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BypassPalette.Ink,
                )
                Text(
                    "已跳过开屏广告 · ${formatBypassTime(action.time)}",
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = BypassPalette.Muted,
                )
                Spacer(Modifier.height(10.dp))
                BypassModeButton("查看全部", selected = false, onClick = onOpenRecent)
            }
        }
        Spacer(Modifier.height(14.dp))

        BypassSectionCard(title = "") {
            BypassNavRow("应用管理", onOpenApps)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("设置", onOpenSettings)
        }
        Spacer(Modifier.height(16.dp))

        Text(
            "离线运行 · 无网络权限 · GPL-3.0",
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            color = BypassPalette.Faint,
        )
    }
}

@Composable
fun BypassAppsPage(engine: BypassEngine) {
    var apps by remember { mutableStateOf(emptyList<BypassAppInfo>()) }
    var refreshing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        apps = engine.getProtectedApps().sortedBy { it.appName }
        refreshing = false
    }
    val iconMap by appIconMapFlow.collectAsState()
    val scope = rememberCoroutineScope()

    if (refreshing) {
        BypassMutedText("正在加载应用列表…")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BypassMutedText(
                "关闭某个应用的开关后，Bypass Ads 将不再对该应用执行规则。",
                size = 12,
            )
            Spacer(Modifier.height(8.dp))
        }
        items(apps, key = { it.packageName }) { appInfo ->
            BypassAppRow(
                appName = appInfo.appName,
                packageName = appInfo.packageName,
                icon = iconMap[appInfo.packageName]?.toBypassPainter(),
                enabled = appInfo.enabled,
                onToggle = { enabled ->
                    scope.launch { engine.setAppEnabled(appInfo.packageName, enabled) }
                    apps = apps.map { if (it.packageName == appInfo.packageName) it.copy(enabled = enabled) else it }
                },
            )
        }
    }
}

@Composable
private fun Drawable.toBypassPainter() = remember(this) { toBitmap().asImageBitmap() }

@Composable
fun BypassRecentPage(engine: BypassEngine) {
    val recent by engine.recentActions.collectAsState()
    if (recent.isEmpty()) {
        BypassMutedText("还没有跳过记录。打开带开屏广告的 App，Bypass Ads 会自动跳过并记录在这里。")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(recent, key = { it.time }) { action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        action.appName ?: action.appId,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BypassPalette.Ink,
                    )
                    Text(
                        action.groupName?.let { "已跳过 · $it" } ?: "已跳过开屏广告",
                        modifier = Modifier.padding(top = 3.dp),
                        fontSize = 12.sp,
                        color = BypassPalette.Muted,
                    )
                }
                Text(
                    formatBypassTime(action.time),
                    fontSize = 12.sp,
                    color = BypassPalette.Faint,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun BypassSettingsPage(
    engine: BypassEngine,
    onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    val serviceState by engine.serviceState.collectAsState()
    val masterEnabled by engine.masterEnabled.collectAsState()
    val genericFallbackEnabled by engine.genericFallbackEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BypassStatusCard(
            headline = serviceState.label,
            description = serviceState.description,
            healthy = serviceState.status == BypassServiceStatus.NORMAL,
            actionLabel = if (serviceState.status == BypassServiceStatus.NORMAL) null else "去开启",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )
        Spacer(Modifier.height(14.dp))

        BypassSectionCard(title = "保护") {
            BypassSwitchRow(
                title = "自动跳过开屏广告",
                subtitle = "",
                checked = masterEnabled,
                onCheckedChange = { engine.setMasterEnabled(it) },
            )
            Spacer(Modifier.height(6.dp))
            BypassSwitchRow(
                title = "通用开屏保护",
                subtitle = "对没有专用规则的应用，尝试跳过标准的「跳过」按钮。",
                checked = genericFallbackEnabled,
                onCheckedChange = { engine.setGenericFallbackEnabled(it) },
            )
        }
        Spacer(Modifier.height(14.dp))

        BypassSectionCard(title = "关于") {
            BypassMutedText(
                "Bypass Ads ${li.songe.gkd.META.versionName} · 规则引擎 GKD 1.12.1 · GPL-3.0",
                size = 13,
            )
            Spacer(Modifier.height(4.dp))
            BypassMutedText(
                "完全离线的开屏广告自动跳过工具。不申请网络权限，不收集任何数据。",
                size = 12,
            )
            Spacer(Modifier.height(10.dp))
            BypassModeButton("开源许可", selected = false, onClick = onOpenLicenses)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun BypassLicensesPage(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BypassModeButton("返回", selected = false, onClick = onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard(title = "GPL-3.0 开源许可") {
            BypassMutedText(
                "本应用基于 GKD（gkd-kit）构建，遵循 GPL-3.0 许可。\n\n" +
                    "源码与许可信息见项目仓库 LICENSE 文件。\n\n" +
                    "第三方开屏规则仅用于本地个人自用构建，未获授权公开再分发。",
                size = 12,
                lineHeight = 20,
            )
        }
    }
}
