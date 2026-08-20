# Bypass Ads 权限清单（Permission Matrix）

> 单一事实来源：`app/src/main/AndroidManifest.xml`（默认 gkd）与
> `app/src/fulltools/AndroidManifest.xml`。默认 gkd flavor 完全离线。

| 权限 | default (gkd) | fulltools | 用途 | 是否必要 |
|---|---|---|---|---|
| `INTERNET` | 移除（`tools:node="remove"`） | 保留（`tools:node="replace"`） | gkd：无任何网络功能；fulltools：本机 HTTP 检查工具 | gkd 否 / fulltools 是 |
| `POST_NOTIFICATIONS` | 有 | 有 | 常驻通知（状态服务） | 是 |
| `FOREGROUND_SERVICE` | 有 | 有 | 前台服务保活 | 是 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 有 | 有 | 前台服务类型 | 是 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` (≤29) | 有 | 有 | 旧系统媒体投影（截图） | 是 |
| `QUERY_ALL_PACKAGES` | 有 | 有 | 应用控制页查询已安装 App | 是（Advanced Tools 依赖） |
| `com.android.permission.GET_INSTALLED_APPS` | 有 | 有 | 同 QUERY_ALL_PACKAGES 的补充 | 是 |
| `SYSTEM_ALERT_WINDOW` | 有 | 有 | 悬浮/覆盖层能力 | 是（Advanced Tools 依赖） |
| `WRITE_EXTERNAL_STORAGE` (≤28) | 有 | 有 | 旧系统导出诊断包 | 是（兼容旧系统） |
| `WRITE_SECURE_SETTINGS` | 有 | 有 | 无障碍恢复（Shizuku/高级路径） | 是（Advanced Tools 依赖） |
| `GET_APP_OPS_STATS` | 有 | 有 | 应用操作状态（自启动诊断） | 是 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 有 | 有 | 电池优化豁免引导 | 是 |
| `REQUEST_INSTALL_PACKAGES` | **移除**（gkd manifest `tools:node="remove"`） | 无（未声明） | 默认产品无安装功能；无 flavor 需要 | 否 |

## 说明

- **Android 自动备份**：默认 gkd flavor `android:allowBackup=false`
  （`app/src/gkd/AndroidManifest.xml` 覆盖）。产品自己的备份/恢复流程通过
  本地文件继续工作，不受影响。
- **不盲删 GKD 能力**：`QUERY_ALL_PACKAGES`、`WRITE_SECURE_SETTINGS`、
  `SYSTEM_ALERT_WINDOW` 均对应 Advanced Tools / 核心路径，保留。
- **Manifest Gate**：默认 gkd 无 INTERNET、allowBackup=false；
  fulltools 有 INTERNET；两者都不带 REQUEST_INSTALL_PACKAGES。
