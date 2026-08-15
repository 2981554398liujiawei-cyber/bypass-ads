package app.bypassads.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-local connection truth supplied by the accessibility service lifecycle. */
enum class AccessibilityRuntimeState {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
}

object AccessibilityRuntimeStateStore {
    var current: AccessibilityRuntimeState by mutableStateOf(AccessibilityRuntimeState.UNKNOWN)
        private set

    fun markConnected() {
        current = AccessibilityRuntimeState.CONNECTED
    }

    fun markDisconnected() {
        current = AccessibilityRuntimeState.DISCONNECTED
    }
}

data class AccessibilityStatus(
    val headline: String,
    val description: String,
    val isObserving: Boolean,
    val shouldOpenSettings: Boolean,
)

object AccessibilityStatusReducer {
    fun reduce(
        runtimeState: AccessibilityRuntimeState,
        systemReportsEnabled: Boolean,
        mode: RunMode,
    ): AccessibilityStatus = when {
        runtimeState == AccessibilityRuntimeState.CONNECTED && mode == RunMode.SHADOW -> AccessibilityStatus(
            headline = "Shadow 正在观察",
            description = "服务已连接。打开其他 App 时会进行 0 / 60 / 140 / 300 / 600 / 1000 ms 多帧观察。",
            isObserving = true,
            shouldOpenSettings = true,
        )

        runtimeState == AccessibilityRuntimeState.CONNECTED -> AccessibilityStatus(
            headline = "服务已连接 · Shadow 已暂停",
            description = "服务保持连接，Shadow 模式已暂停。",
            isObserving = false,
            shouldOpenSettings = true,
        )

        systemReportsEnabled -> AccessibilityStatus(
            headline = "系统已启用 · 等待服务连接",
            description = "系统已启用 Bypass Ads.，正在等待服务连接。",
            isObserving = false,
            shouldOpenSettings = true,
        )

        else -> AccessibilityStatus(
            headline = "未确认服务连接",
            description = "需要在系统设置中手动启用 Bypass Ads. · 开屏观察。",
            isObserving = false,
            shouldOpenSettings = false,
        )
    }
}
