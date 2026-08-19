package li.songe.gkd

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.dylanc.activityresult.launcher.PickContentLauncher
import com.dylanc.activityresult.launcher.StartActivityLauncher
import com.dylanc.activityresult.launcher.launchForResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.reflect.jvm.jvmName
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.a11y.updateSystemDefaultAppId
import li.songe.gkd.a11y.updateTopActivity
import li.songe.gkd.bypass.BypassAppsPage
import li.songe.gkd.bypass.BypassHomePage
import li.songe.gkd.bypass.BypassLicensesPage
import li.songe.gkd.bypass.BypassPalette
import li.songe.gkd.bypass.BypassRecentPage
import li.songe.gkd.bypass.BypassSettingsPage
import li.songe.gkd.bypass.BypassTabBar
import li.songe.gkd.bypass.BypassTitleBar
import li.songe.gkd.bypass.GkdBypassEngine
import li.songe.gkd.permission.updatePermissionState
import li.songe.gkd.service.StatusService
import li.songe.gkd.service.fixRestartAutomatorService
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.ui.share.FixedWindowInsets
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.BarUtils
import li.songe.gkd.util.KeyboardUtils
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.componentName
import li.songe.gkd.util.fixSomeProblems
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.toast

/**
 * Bypass Ads product entry.
 *
 * The GKD engine (subscriptions, rule resolution, accessibility service) keeps
 * running underneath; this activity only renders the Bypass Ads product UI and
 * talks to the engine through [GkdBypassEngine]. No GKD product screens are
 * reachable from the normal user flow.
 */
class MainActivity : ComponentActivity() {
    val mainVm by viewModels<MainViewModel>()
    val launcher by lazy { StartActivityLauncher(this) }
    val pickContentLauncher by lazy { PickContentLauncher(this) }

    // GKD 原有 UI 组件依赖这些成员（键盘/insets/文件选择）；Bypass Ads 产品壳保留定义。
    val imeFullHiddenFlow = MutableStateFlow(true)
    val imePlayingFlow = MutableStateFlow(false)

    private val imeVisible: Boolean
        get() = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    var topBarWindowInsets by mutableStateOf(WindowInsets(top = BarUtils.getStatusBarHeight()))

    private fun watchKeyboardVisible() {
        if (AndroidTarget.R) {
            ViewCompat.setWindowInsetsAnimationCallback(
                window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onStart(
                        animation: WindowInsetsAnimationCompat,
                        bounds: WindowInsetsAnimationCompat.BoundsCompat
                    ): WindowInsetsAnimationCompat.BoundsCompat {
                        imePlayingFlow.update { imeVisible }
                        return super.onStart(animation, bounds)
                    }

                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        runningAnimations: List<WindowInsetsAnimationCompat>
                    ): WindowInsetsCompat {
                        return insets
                    }

                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        imeFullHiddenFlow.update { !imeVisible }
                        imePlayingFlow.update { false }
                        super.onEnd(animation)
                    }
                })
        } else {
            KeyboardUtils.registerSoftInputChangedListener(window) { height ->
                imeFullHiddenFlow.update { height == 0 }
            }
        }
    }

    suspend fun hideSoftInput(): Boolean {
        if (!imeFullHiddenFlow.updateAndGet { !imeVisible }) {
            KeyboardUtils.hideSoftInput(this@MainActivity)
            imeFullHiddenFlow.drop(1).first()
            return true
        }
        return false
    }

    fun justHideSoftInput(): Boolean {
        if (!imeFullHiddenFlow.updateAndGet { !imeVisible }) {
            KeyboardUtils.hideSoftInput(this@MainActivity)
            return true
        }
        return false
    }

    suspend fun pickFile(contentType: String): Uri? {
        val u = launcher.launchForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = contentType
        }).data?.data
        if (u == null) {
            toast("未选择文件")
        }
        return u
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        fixSomeProblems()
        super.onCreate(savedInstanceState)
        mainVm // keep GKD engine initialization alive
        launcher
        pickContentLauncher
        watchKeyboardVisible()
        StatusService.autoStart()
        setContent {
            BypassApp()
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisibleState++
        if (topActivityFlow.value.appId != META.appId) {
            synchronized(topActivityFlow) {
                updateTopActivity(
                    META.appId,
                    MainActivity::class.jvmName
                )
            }
        }
    }

    var isFirstResume = true
    override fun onResume() {
        super.onResume()
        if (isFirstResume && startTime - app.startTime < 2000) {
            isFirstResume = false
        } else {
            syncFixState()
        }
    }

    override fun onStop() {
        super.onStop()
        activityVisibleState--
    }

    private val startTime = System.currentTimeMillis()
}

@Volatile
private var activityVisibleState = 0
val isActivityVisible get() = activityVisibleState > 0

val activityNavSourceName by lazy { META.appId + ".activity.nav.source" }

fun Activity.navToMainActivity() {
    if (intent != null) {
        val navIntent = Intent(intent)
        navIntent.component = MainActivity::class.componentName
        navIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        navIntent.putExtra(activityNavSourceName, this::class.jvmName)
        startActivity(navIntent)
    }
    finish()
}

private val syncStateMutex = Mutex()
fun syncFixState() {
    appScope.launchTry(Dispatchers.IO) {
        if (syncStateMutex.isLocked) {
            LogUtils.d("syncFixState isLocked")
        }
        syncStateMutex.withLock {
            updateSystemDefaultAppId()
            shizukuContextFlow.value.grantSelf()
            updatePermissionState()
            fixRestartAutomatorService()
        }
    }
}

private const val TAB_HOME = 0
private const val TAB_APPS = 1
private const val TAB_RECENT = 2
private const val TAB_SETTINGS = 3

@Composable
private fun BypassApp() {
    var tab by remember { mutableIntStateOf(TAB_HOME) }
    var showLicenses by remember { mutableIntStateOf(0) }
    val engine = GkdBypassEngine

    MaterialTheme {
        Surface(color = BypassPalette.PageBackground, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                BypassTitleBar(subtitle = "离线自动跳过开屏广告")
                Spacer(Modifier.height(18.dp))
                BypassTabBar(
                    tabs = listOf(
                        "首页" to TAB_HOME,
                        "应用" to TAB_APPS,
                        "最近" to TAB_RECENT,
                        "设置" to TAB_SETTINGS,
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                )
                Spacer(Modifier.height(18.dp))

                if (showLicenses > 0) {
                    BypassLicensesPage(onBack = { showLicenses = 0 })
                } else {
                    when (tab) {
                        TAB_HOME -> BypassHomePage(
                            engine = engine,
                            onOpenApps = { tab = TAB_APPS },
                            onOpenRecent = { tab = TAB_RECENT },
                            onOpenSettings = { tab = TAB_SETTINGS },
                        )

                        TAB_APPS -> BypassAppsPage(engine = engine)

                        TAB_RECENT -> BypassRecentPage(engine = engine)

                        TAB_SETTINGS -> BypassSettingsPage(
                            engine = engine,
                            onOpenLicenses = { showLicenses = 1 },
                        )
                    }
                }
            }
        }
    }
}
